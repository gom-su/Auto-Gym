"""Auto-Gym MCP server (INT-01): read an authorized training context, create week proposals, never approve them.

The phone app remains the source of truth. This server only transports:
  * it reads exactly one file, a v1 TrainingContext package the user exported and reviewed in the app
    (Programas > Intercambio JSON > Preparar contexto local). Nothing else on disk is readable through it.
  * it writes new v1 WeekProposal packages, one file per proposal, into an outbox directory. The user imports
    them in the app, where they stay pending until explicitly approved. There is no approval tool.

Transport: MCP over stdio, newline-delimited JSON-RPC 2.0. Standard library only.

Usage:
  python mcp/auto_gym_mcp.py --context path/to/auto-gym-context.json --outbox path/to/outbox
"""
import argparse
import datetime
import json
import os
import re
import sys
import uuid

PROTOCOL_VERSION = "2025-06-18"
SERVER_INFO = {"name": "auto-gym", "version": "1.0.0"}
ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
UNIT_BASES = {"KG": {"TOTAL", "PER_SIDE", "PER_HAND"}, "LB": {"TOTAL", "PER_SIDE", "PER_HAND"},
              "MACHINE_SETTING": {"MACHINE_SETTING"}, "BODYWEIGHT": {"BODYWEIGHT"}}
MAX_BYTES = 1_048_576


class ToolError(Exception):
    """A validation or permission problem reported to the agent as a tool error, never as a crash."""


def _check(ok, message):
    if not ok:
        raise ToolError(message)


def load_context(path):
    """Reads and checks the single authorized context file. It is never modified."""
    with open(path, "rb") as f:
        raw = f.read(MAX_BYTES + 1)
    _check(len(raw) <= MAX_BYTES, "El contexto supera 1 MiB.")
    data = json.loads(raw.decode("utf-8"))
    _check(data.get("schemaVersion") == 1 and data.get("type") == "TrainingContext" and isinstance(data.get("context"), dict),
           "El archivo autorizado no es un contexto de entrenamiento v1.")
    return data


def _all_ids(program):
    ids = [program["id"]]
    for b in program["blocks"]:
        ids.append(b["id"])
        for w in b["weeks"]:
            ids.append(w["id"])
            for d in w["days"]:
                ids.append(d["id"])
                for e in d["exercises"]:
                    ids.append(e["id"])
                    ids.extend(s["id"] for s in e["sets"])
    return ids


def validate_week(week):
    """Mirrors the app's ProgramPackage v1 rules for one week (types, ranges, IDs, unit/basis pairs)."""
    def name(v, path):
        _check(isinstance(v, str) and 0 < len(v.strip()) and len(v) <= 200, f"{path}: nombre de 1 a 200 caracteres.")

    def ident(v, path):
        _check(isinstance(v, str) and ID.match(v) is not None, f"{path}: ID no válido.")

    _check(isinstance(week, dict), "La semana debe ser un objeto.")
    allowed = {"id", "name", "version", "days"}
    _check(set(week) <= allowed and {"id", "name", "version", "days"} <= set(week), "Semana: campos esperados id, name, version, days.")
    ident(week["id"], "Semana"); name(week["name"], "Semana")
    _check(isinstance(week["version"], int) and week["version"] > 0, "Semana: versión positiva.")
    _check(isinstance(week["days"], list) and week["days"], "Semana: falta un día.")
    seen = {week["id"]}
    for di, d in enumerate(week["days"], 1):
        p = f"Día {di}"
        _check(isinstance(d, dict) and set(d) == {"id", "name", "exercises"}, f"{p}: campos id, name, exercises.")
        ident(d["id"], p); name(d["name"], p)
        _check(isinstance(d["exercises"], list) and d["exercises"], f"{p}: falta un ejercicio.")
        for ei, e in enumerate(d["exercises"], 1):
            ep = f"{p} / ejercicio {ei}"
            _check(isinstance(e, dict) and set(e) == {"id", "exerciseId", "name", "sets"}, f"{ep}: campos id, exerciseId, name, sets.")
            ident(e["id"], ep); ident(e["exerciseId"], f"{ep} exerciseId"); name(e["name"], ep)
            _check(isinstance(e["sets"], list) and e["sets"], f"{ep}: falta una serie.")
            for si, s in enumerate(e["sets"], 1):
                sp = f"{ep} / serie {si}"
                _check(isinstance(s, dict) and set(s) <= {"id", "reps", "rir", "load", "restSeconds"} and "id" in s, f"{sp}: campos no válidos.")
                ident(s["id"], sp)
                for key, lo, hi in (("reps", 1, 1000), ("rir", 0, 10), ("restSeconds", 0, 86400)):
                    v = s.get(key)
                    _check(v is None or (isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi), f"{sp}: {key} fuera de rango ({lo}–{hi}).")
                load = s.get("load")
                if load is not None:
                    _check(isinstance(load, dict) and set(load) == {"value", "unit", "basis"}, f"{sp}: carga con value, unit y basis.")
                    _check(isinstance(load["value"], (int, float)) and not isinstance(load["value"], bool) and 0 <= load["value"] <= 100000,
                           f"{sp}: carga fuera de rango.")
                    _check(load["basis"] in UNIT_BASES.get(load["unit"], set()), f"{sp}: unidad y base de carga incompatibles.")
            seen_ids = [e["id"]] + [s["id"] for s in e["sets"]]
            _check(not (set(seen_ids) & seen) and len(set(seen_ids)) == len(seen_ids), f"{ep}: ID duplicado.")
            seen.update(seen_ids)
        _check(d["id"] not in seen, f"{p}: ID duplicado.")
        seen.add(d["id"])


class Server:
    def __init__(self, context_path, outbox):
        self.context_path = os.path.abspath(context_path)
        self.outbox = os.path.abspath(outbox)
        os.makedirs(self.outbox, exist_ok=True)

    # --- tools -------------------------------------------------------------------------------------------------
    TOOLS = [
        {"name": "get_training_context",
         "description": "Returns the training context the user exported and authorized in the Auto-Gym app (programs, typed session results, phase, goals, rules; body measurements only if the user chose to include them). Read-only.",
         "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}},
        {"name": "create_week_proposal",
         "description": "Creates a v1 WeekProposal for a program and block in the authorized context. The proposal is written to the outbox for the user to import; it stays pending in the app until the user explicitly approves it. This server cannot approve or activate anything.",
         "inputSchema": {"type": "object", "additionalProperties": False,
                         "required": ["proposalId", "version", "programId", "blockId", "week", "differences", "reasons"],
                         "properties": {"proposalId": {"type": "string"}, "version": {"type": "integer", "minimum": 1},
                                        "programId": {"type": "string"}, "blockId": {"type": "string"},
                                        "week": {"type": "object", "description": "WeekPlan v1: id, name, version, days[ id, name, exercises[ id, exerciseId, name, sets[ id, reps?, rir?, load?{value,unit,basis}, restSeconds? ] ] ]"},
                                        "differences": {"type": "array", "items": {"type": "string"}},
                                        "reasons": {"type": "array", "items": {"type": "string"},
                                                    "description": "Mention each exercise you change by name so the app can attach the reason to that change."}}}},
        {"name": "list_created_proposals",
         "description": "Lists proposal files this server wrote to the outbox. Whether the user approved them is only known in the app.",
         "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}},
    ]

    def call(self, name, args):
        if name == "get_training_context":
            return load_context(self.context_path)
        if name == "create_week_proposal":
            return self.create_proposal(args or {})
        if name == "list_created_proposals":
            return sorted(f for f in os.listdir(self.outbox) if f.endswith(".json"))
        raise ToolError(f"Herramienta desconocida: {name}. Este servidor no puede aprobar ni activar propuestas.")

    def create_proposal(self, a):
        required = {"proposalId", "version", "programId", "blockId", "week", "differences", "reasons"}
        _check(set(a) == required, "Campos esperados: " + ", ".join(sorted(required)) + ".")
        for key in ("proposalId", "programId", "blockId"):
            _check(isinstance(a[key], str) and ID.match(a[key]) is not None, f"{key} no válido.")
        _check(isinstance(a["version"], int) and not isinstance(a["version"], bool) and a["version"] > 0, "version debe ser positiva.")
        for key in ("differences", "reasons"):
            _check(isinstance(a[key], list) and all(isinstance(t, str) and t.strip() and len(t) <= 10000 for t in a[key]),
                   f"{key}: lista de textos no vacíos.")
        validate_week(a["week"])
        context = load_context(self.context_path)["context"]
        program = next((p for p in context.get("programs", []) if p.get("id") == a["programId"]), None)
        _check(program is not None, f"El programa {a['programId']} no está en el contexto autorizado.")
        _check(any(b.get("id") == a["blockId"] for b in program["blocks"]), f"El bloque {a['blockId']} no existe en ese programa.")
        week_ids = [d["id"] for d in a["week"]["days"]] + [a["week"]["id"]] + \
            [x for d in a["week"]["days"] for e in d["exercises"] for x in [e["id"]] + [s["id"] for s in e["sets"]]]
        reused = set(week_ids) & set(_all_ids(program))
        _check(not reused, "La semana reutiliza IDs guardados (" + ", ".join(sorted(reused)[:5]) + "); usa IDs nuevos.")
        import_id = "mcp-" + uuid.uuid4().hex
        package = {"schemaVersion": 1, "type": "WeekProposal", "importId": import_id,
                   "createdAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
                   "context": None,
                   "proposal": {"id": a["proposalId"], "version": a["version"], "programId": a["programId"], "blockId": a["blockId"],
                                "source": "Agente vía MCP", "week": a["week"], "differences": a["differences"], "reasons": a["reasons"]},
                   "results": None}
        text = json.dumps(package, ensure_ascii=False, indent=2)
        _check(len(text.encode("utf-8")) <= MAX_BYTES, "La propuesta supera 1 MiB.")
        path = os.path.join(self.outbox, import_id + ".json")
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(text)
        os.replace(tmp, path)  # atomic: the app never sees a partial file
        return {"file": path, "importId": import_id, "status": "pendiente",
                "next": "Importa el archivo en Auto-Gym > Programas > Intercambio JSON y revísalo en Propuestas de semana. Solo la persona usuaria puede aprobarlo."}

    # --- JSON-RPC ----------------------------------------------------------------------------------------------
    def handle(self, msg):
        method, mid = msg.get("method"), msg.get("id")
        if mid is None:  # notification
            return None
        try:
            if method == "initialize":
                result = {"protocolVersion": PROTOCOL_VERSION, "serverInfo": SERVER_INFO, "capabilities": {"tools": {}},
                          "instructions": "Read the authorized Auto-Gym context and create week proposals. You cannot approve them."}
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": self.TOOLS}
            elif method == "tools/call":
                params = msg.get("params") or {}
                try:
                    value = self.call(params.get("name"), params.get("arguments"))
                    result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False, indent=2)}], "isError": False}
                except (ToolError, OSError, ValueError, KeyError, TypeError) as e:
                    result = {"content": [{"type": "text", "text": f"Error: {e}"}], "isError": True}
            else:
                return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"Method not found: {method}"}}
            return {"jsonrpc": "2.0", "id": mid, "result": result}
        except Exception as e:  # never crash the transport
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32603, "message": str(e)}}

    def serve(self, stdin=sys.stdin, stdout=sys.stdout):
        for line in stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
            else:
                response = self.handle(msg)
            if response is not None:
                stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
                stdout.flush()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--context", required=True, help="TrainingContext v1 file exported from the app (the only readable file)")
    parser.add_argument("--outbox", required=True, help="Directory where proposal packages are written")
    args = parser.parse_args(argv)
    load_context(args.context)  # fail fast on an unauthorized or invalid file
    if hasattr(sys.stdin, "reconfigure"):
        sys.stdin.reconfigure(encoding="utf-8"); sys.stdout.reconfigure(encoding="utf-8")
    Server(args.context, args.outbox).serve()


if __name__ == "__main__":
    main()
