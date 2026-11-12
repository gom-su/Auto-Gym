"""Auto-Gym MCP server (INT-01): read an authorized training context, create week proposals, never approve them.

The phone app remains the source of truth. This server only transports:
  * it reads exactly one file, a v1 TrainingContext package the user exported and reviewed in the app
    (Programas > Intercambio JSON > Preparar contexto local). Nothing else on disk is readable through it.
  * it writes new v1 WeekProposal packages, one file per proposal, into an outbox directory. The user imports
    them in the app, where they stay pending until explicitly approved. There is no approval tool.
  * it writes v1 PlanOutline packages: the general multi-week (typically 12-week) structure the user activates in the
    app. Once active, the exported context carries, per plan, the next week to plan and its targets
    (get_next_week_brief), and week proposals for that program must follow it.

With --sign-key (a pairing key generated in the app: Intercambio JSON > Envíos firmados), each proposal is wrapped in
an HMAC-SHA256 signed envelope (INT-02). The app accepts it only if the key is paired and not revoked, the signature,
type, age and nonce verify; it is then still pending until the user approves it.

Transport: MCP over stdio, newline-delimited JSON-RPC 2.0. Standard library only.

Usage:
  python mcp/auto_gym_mcp.py --context path/to/auto-gym-context.json --outbox path/to/outbox [--sign-key agk1.<id>.<secret>]
"""
import argparse
import base64
import datetime
import hashlib
import hmac
import secrets
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
MAX_NON_WORK_SETS = 4


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
    _check(data.get("schemaVersion") in (1, 2) and data.get("type") == "TrainingContext" and isinstance(data.get("context"), dict),
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



def _load_key_label(key):
    return f"{key[0]} {key[1]}"


def _num(value):
    """Formats a load like the app: whole numbers without a decimal point."""
    return str(int(value)) if float(value).is_integer() else str(value)


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
        _check(isinstance(d, dict) and set(d) <= {"id", "name", "exercises", "cardio"} and {"id", "name", "exercises"} <= set(d),
               f"{p}: campos id, name, exercises y cardio opcional.")
        ident(d["id"], p); name(d["name"], p)
        _check(isinstance(d["exercises"], list), f"{p}: exercises debe ser una lista.")
        if "cardio" in d:
            c = d["cardio"]
            _check(isinstance(c, dict) and set(c) == {"modality", "durationSeconds"}, f"{p}: cardio con modality y durationSeconds.")
            _check(isinstance(c.get("modality"), str) and c["modality"].strip() and len(c["modality"]) <= 200, f"{p}: modalidad de cardio no válida.")
            _check(isinstance(c.get("durationSeconds"), int) and not isinstance(c.get("durationSeconds"), bool)
                   and 1 <= c["durationSeconds"] <= 86400, f"{p}: duración de cardio fuera de rango (1–86400 s).")
        _check(d["exercises"] or "cardio" in d, f"{p}: falta un ejercicio o un bloque de cardio.")
        for ei, e in enumerate(d["exercises"], 1):
            ep = f"{p} / ejercicio {ei}"
            _check(isinstance(e, dict) and set(e) == {"id", "exerciseId", "name", "sets"}, f"{ep}: campos id, exerciseId, name, sets.")
            ident(e["id"], ep); ident(e["exerciseId"], f"{ep} exerciseId"); name(e["name"], ep)
            _check(isinstance(e["sets"], list) and e["sets"], f"{ep}: falta una serie.")
            for si, s in enumerate(e["sets"], 1):
                sp = f"{ep} / serie {si}"
                _check(isinstance(s, dict) and set(s) <= {"id", "reps", "rir", "load", "restSeconds", "kind"} and "id" in s, f"{sp}: campos no válidos.")
                _check(s.get("kind", "WORK") in {"WORK", "WARMUP", "APPROACH"}, f"{sp}: kind debe ser WORK, WARMUP o APPROACH.")
                _check(s.get("load") is not None, f"{sp}: falta la carga prescrita (0 solo si es la carga prescrita).")
                ident(s["id"], sp)
                for key, lo, hi in (("reps", 1, 1000), ("rir", 0, 10)):
                    v = s.get(key)
                    _check(v is None or (isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi), f"{sp}: {key} fuera de rango ({lo}–{hi}).")
                rest = s.get("restSeconds")
                if rest is not None:
                    _check(isinstance(rest, int) and not isinstance(rest, bool), f"{sp}: restSeconds debe ser un entero.")
                    _check(rest == 0 or 45 <= rest <= 120,
                           f"{d['name']} · {e['name']}, serie {si}: descanso {rest} s fuera de 45–120 s, "
                           "el rango que la persona puede ajustar durante la sesión.")
                load = s.get("load")
                if load is not None:
                    _check(isinstance(load, dict) and set(load) == {"value", "unit", "basis"}, f"{sp}: carga con value, unit y basis.")
                    _check(isinstance(load["value"], (int, float)) and not isinstance(load["value"], bool) and 0 <= load["value"] <= 100000,
                           f"{sp}: carga fuera de rango.")
                    _check(load["basis"] in UNIT_BASES.get(load["unit"], set()), f"{sp}: unidad y base de carga incompatibles.")
            non_work_count = sum(s.get("kind", "WORK") != "WORK" for s in e["sets"])
            exercise_label = f"{d['name']} · {e['name']}"
            _check(non_work_count < len(e["sets"]),
                   f"{exercise_label}: no tiene series de trabajo; solo calentamientos o aproximaciones no se pueden revisar.")
            _check(non_work_count <= MAX_NON_WORK_SETS,
                   f"{exercise_label}: {non_work_count} series de calentamiento o aproximación; el máximo es {MAX_NON_WORK_SETS}.")
            # Mirrors the app's WARMUP_ORDER: warm-up and approach sets come before the work sets of their exercise.
            first_work = next((i for i, s in enumerate(e["sets"]) if s.get("kind", "WORK") == "WORK"), None)
            _check(first_work is None or all(s.get("kind", "WORK") == "WORK" for s in e["sets"][first_work:]),
                   f"{exercise_label}: las series de calentamiento o aproximación deben ir antes de las de trabajo.")
            # Mirrors the app's NON_WORK_LOAD: a warm-up or approach cannot be heavier than the heaviest work set of the
            # exercise in the same unit and basis; a different unit or basis cannot be compared, so it is blocked.
            work_loads = {}
            for s in e["sets"]:
                if s.get("kind", "WORK") == "WORK" and s.get("load"):
                    key = (s["load"]["unit"], s["load"]["basis"])
                    work_loads[key] = max(work_loads.get(key, float("-inf")), s["load"]["value"])
            for s in e["sets"]:
                if s.get("kind", "WORK") == "WORK" or not s.get("load") or not work_loads:
                    continue
                load = s["load"]
                key = (load["unit"], load["basis"])
                kind_label = "calentamiento" if s["kind"] == "WARMUP" else "aproximación"
                if key not in work_loads:
                    _check(False, f"{exercise_label}: una serie de {kind_label} usa {_load_key_label(key)}, distinta de la carga de trabajo ("
                                  f"{', '.join(_load_key_label(k) for k in work_loads)}); no se puede comparar.")
                _check(load["value"] <= work_loads[key],
                       f"{exercise_label}: una serie de {kind_label} pesa {_num(load['value'])} {_load_key_label(key)}, "
                       f"más que la carga de trabajo máxima ({_num(work_loads[key])}).")
            seen_ids = [e["id"]] + [s["id"] for s in e["sets"]]
            _check(not (set(seen_ids) & seen) and len(set(seen_ids)) == len(seen_ids), f"{ep}: ID duplicado.")
            seen.update(seen_ids)
        _check(d["id"] not in seen, f"{p}: ID duplicado.")
        seen.add(d["id"])


def validate_outline(o):
    """Mirrors the app's PlanOutline v1 rules (PlanOutlines.validate)."""
    def text(v, path, limit=10000):
        _check(isinstance(v, str) and v.strip() and len(v) <= limit, f"{path}: texto vacío o demasiado largo.")

    def ident(v, path):
        _check(isinstance(v, str) and ID.match(v) is not None, f"{path}: ID no válido.")

    def integer(v, lo, hi, path):
        _check(v is None or (isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi), f"{path}: fuera de rango ({lo}–{hi}).")

    def span(lo, hi, a, b, what, path):
        integer(lo, a, b, f"{path}: {what} mínimo"); integer(hi, a, b, f"{path}: {what} máximo")
        _check(lo is None or hi is None or lo <= hi, f"{path}: {what} mínimo mayor que el máximo.")

    def fields(obj, required, optional, path):
        _check(isinstance(obj, dict) and required <= set(obj) <= required | optional,
               f"{path}: campos esperados {', '.join(sorted(required))}" + (f" (opcionales {', '.join(sorted(optional))})" if optional else "") + ".")

    fields(o, {"id", "version", "programId", "programName", "name", "goals", "rules", "days", "blocks", "weeks"}, set(), "Plan")
    ident(o["id"], "Plan"); ident(o["programId"], "Plan programId"); text(o["programName"], "Plan programName", 200); text(o["name"], "Plan", 200)
    _check(isinstance(o["version"], int) and not isinstance(o["version"], bool) and o["version"] > 0, "Plan: versión positiva.")
    for key in ("goals", "rules"):
        _check(isinstance(o[key], list), f"Plan: {key} debe ser una lista de textos.")
        for t in o[key]:
            text(t, f"Plan {key}")
    _check(isinstance(o["days"], list) and o["days"], "Plan: falta la distribución de días.")
    template = set()
    day_ids = []
    for d in o["days"]:
        fields(d, {"id", "name", "exercises"}, {"focus"}, "Día")
        ident(d["id"], "Día"); text(d["name"], f"Día {d['id']}", 200); day_ids.append(d["id"])
        if d.get("focus") is not None:
            text(d["focus"], f"Día {d['id']}")
        _check(isinstance(d["exercises"], list) and d["exercises"], f"Día {d['id']}: falta un ejercicio.")
        seen = set()
        for e in d["exercises"]:
            fields(e, {"exerciseId", "name"}, {"sets", "notes"}, f"Día {d['id']} / ejercicio")
            ident(e["exerciseId"], f"Día {d['id']} / {e.get('name')}"); text(e["name"], f"Día {d['id']} / ejercicio", 200)
            integer(e.get("sets"), 1, 50, f"Día {d['id']} / {e['name']} series")
            if e.get("notes") is not None:
                text(e["notes"], f"Día {d['id']} / {e['name']}")
            _check(e["exerciseId"] not in seen, f"Día {d['id']}: ejercicio repetido.")
            seen.add(e["exerciseId"]); template.add(e["exerciseId"])
    _check(len(set(day_ids)) == len(day_ids), "Plan: días duplicados.")
    _check(isinstance(o["blocks"], list) and o["blocks"], "Plan: falta un bloque.")
    block_ids = []
    for b in o["blocks"]:
        fields(b, {"id", "name"}, {"focus"}, "Bloque")
        ident(b["id"], "Bloque"); text(b["name"], f"Bloque {b['id']}", 200); block_ids.append(b["id"])
        if b.get("focus") is not None:
            text(b["focus"], f"Bloque {b['id']}")
    _check(len(set(block_ids)) == len(block_ids), "Plan: bloques duplicados.")
    _check(isinstance(o["weeks"], list) and 1 <= len(o["weeks"]) <= 52, "Plan: entre 1 y 52 semanas.")
    for i, w in enumerate(o["weeks"], 1):
        p = f"Semana {i}"
        fields(w, {"number", "blockId", "phase", "microcycle", "deload", "targets"}, {"cardio", "notes"}, p)
        _check(w["number"] == i, f"{p}: las semanas se numeran 1..N, en orden y sin huecos.")
        _check(w["blockId"] in block_ids, f"{p}: bloque {w['blockId']} no definido.")
        text(w["phase"], f"{p}: fase", 200); text(w["microcycle"], f"{p}: microciclo", 200)
        _check(isinstance(w["deload"], bool), f"{p}: deload debe ser true o false.")
        if w.get("notes") is not None:
            text(w["notes"], p)
        _check(isinstance(w["targets"], list), f"{p}: targets debe ser una lista.")
        scopes = []
        for t in w["targets"]:
            fields(t, {"label"}, {"exerciseIds", "sets", "repsMin", "repsMax", "rirMin", "rirMax"}, f"{p} / objetivo")
            tp = f"{p} / {t['label']}"
            text(t["label"], f"{p}: objetivo", 200)
            ids = t.get("exerciseIds") or []
            _check(isinstance(ids, list) and len(set(ids)) == len(ids), f"{tp}: exerciseIds sin repetir.")
            for e in ids:
                _check(e in template, f"{tp}: el ejercicio {e} no está en la distribución de días.")
            integer(t.get("sets"), 1, 50, f"{tp}: series")
            span(t.get("repsMin"), t.get("repsMax"), 1, 1000, "repeticiones", tp)
            span(t.get("rirMin"), t.get("rirMax"), 0, 10, "RIR", tp)
            scopes += ids or ["*"]
        _check(len(set(scopes)) == len(scopes), f"{p}: dos objetivos aplican al mismo ejercicio.")
        c = w.get("cardio")
        if c is not None:
            fields(c, {"modality"}, {"minutes", "sessions", "rpeMin", "rpeMax", "notes"}, f"{p}: cardio")
            text(c["modality"], f"{p}: modalidad de cardio", 200)
            integer(c.get("minutes"), 1, 600, f"{p}: minutos de cardio"); integer(c.get("sessions"), 1, 14, f"{p}: sesiones de cardio")
            span(c.get("rpeMin"), c.get("rpeMax"), 1, 10, "RPE", f"{p}: cardio")
            if c.get("notes") is not None:
                text(c["notes"], f"{p}: cardio")
    order = []
    for w in o["weeks"]:
        if not order or order[-1] != w["blockId"]:
            order.append(w["blockId"])
    _check(len(set(order)) == len(order), "Plan: las semanas de un bloque deben ser consecutivas.")
    _check(order == block_ids, "Plan: cada bloque necesita semanas y se lista en el orden en que ocurre.")


def parse_key(text):
    """'agk1.<keyId>.<base64url secret>' as shown by the app."""
    parts = (text or "").strip().split(".")
    _check(len(parts) == 3 and parts[0] == "agk1" and ID.match(parts[1]) is not None, "Clave de emparejamiento no válida.")
    secret = base64.urlsafe_b64decode(parts[2] + "=" * (-len(parts[2]) % 4))
    _check(len(secret) >= 32, "Clave de emparejamiento demasiado corta.")
    return parts[1], secret


def sign(key_id, secret, payload_type, payload, issued_at=None, nonce=None):
    """Signed envelope over every field; the payload is the exact v1 JSON text the app will decode."""
    issued_at = issued_at or datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    nonce = nonce or secrets.token_hex(16)
    message = "\n".join(["auto-gym-sync-v1", key_id, nonce, issued_at, payload_type, payload]).encode("utf-8")
    mac = hmac.new(secret, message, hashlib.sha256).hexdigest()
    return {"syncVersion": 1, "keyId": key_id, "nonce": nonce, "issuedAt": issued_at, "payloadType": payload_type,
            "payload": payload, "mac": mac}


class Server:
    def __init__(self, context_path, outbox, sign_key=None):
        self.context_path = os.path.abspath(context_path)
        self.outbox = os.path.abspath(outbox)
        self.sign_key = parse_key(sign_key) if sign_key else None
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
        {"name": "create_plan_outline",
         "description": "Creates a v1 PlanOutline: the general structure of a multi-week plan (typically 12 weeks) for one program: goals, rules (progression, effort, pain, cardio, deloads), the day distribution (A–D) with its exercises, blocks, and one entry per week with block, phase, microcycle, deload flag, set/rep/RIR targets and cardio. Written to the outbox; the user activates it in the app. It contains no prescriptions: each week is then proposed with create_week_proposal, following get_next_week_brief.",
         "inputSchema": {"type": "object", "additionalProperties": False, "required": ["outline"],
                         "properties": {"outline": {"type": "object", "description": "PlanOutline v1: id, version, programId, programName, name, goals[], rules[], days[ id, name, focus?, exercises[ exerciseId, name, sets?, notes? ] ], blocks[ id, name, focus? ], weeks[ number (1..N), blockId, phase, microcycle, deload, targets[ label, exerciseIds? (empty = all others), sets?, repsMin?, repsMax?, rirMin?, rirMax? ], cardio?{ modality, minutes?, sessions?, rpeMin?, rpeMax?, notes? }, notes? ]. To revise an active plan, reuse its id with a higher version."}}}},
        {"name": "get_next_week_brief",
         "description": "For a program with an activated plan: which week to plan next (computed by the app from the saved weeks and real records), its block, week in block, phase, microcycle, deload flag, targets and cardio, the day templates and rules, and the reference week (highest week with real data) with its recorded sessions plus the week before it for trends. status READY means plan weekNumber now; ALREADY_PLANNED means that week exists and needs records first; CYCLE_COMPLETE means the plan is over (do not create another week).",
         "inputSchema": {"type": "object", "additionalProperties": False, "required": ["programId"],
                         "properties": {"programId": {"type": "string"}}}},
        {"name": "list_created_proposals",
         "description": "Lists proposal files this server wrote to the outbox. Whether the user approved them is only known in the app.",
         "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}},
    ]

    def call(self, name, args):
        if name == "get_training_context":
            return load_context(self.context_path)
        if name == "create_week_proposal":
            return self.create_proposal(args or {})
        if name == "create_plan_outline":
            return self.create_outline(args or {})
        if name == "get_next_week_brief":
            return self.next_week_brief(args or {})
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
        outline = next((o for o in context.get("outlines", []) if o.get("programId") == a["programId"]), None)
        if outline is not None:
            # With an activated plan, the app decides which week comes next; the agent only fills it in.
            brief = next((b for b in context.get("nextWeeks", []) if b.get("programId") == a["programId"]), None)
            _check(brief is not None, "El contexto no incluye la próxima semana del plan; vuelve a exportarlo desde la app.")
            _check(brief["status"] == "READY", f"No toca planificar una semana nueva: {brief['message']}")
            _check(a["blockId"] == brief["blockId"],
                   f"La semana {brief['weekNumber']} del plan pertenece al bloque {brief['blockId']}, no a {a['blockId']}.")
        else:
            _check(program is not None, f"El programa {a['programId']} no está en el contexto autorizado.")
            _check(any(b.get("id") == a["blockId"] for b in program["blocks"]), f"El bloque {a['blockId']} no existe en ese programa.")
        week_ids = [d["id"] for d in a["week"]["days"]] + [a["week"]["id"]] + \
            [x for d in a["week"]["days"] for e in d["exercises"] for x in [e["id"]] + [s["id"] for s in e["sets"]]]
        reused = set(week_ids) & set(_all_ids(program) if program else [])
        _check(not reused, "La semana reutiliza IDs guardados (" + ", ".join(sorted(reused)[:5]) + "); usa IDs nuevos.")
        result = self._write("WeekProposal", "proposal",
                             {"id": a["proposalId"], "version": a["version"], "programId": a["programId"], "blockId": a["blockId"],
                              "source": "Agente vía MCP", "week": a["week"], "differences": a["differences"], "reasons": a["reasons"]})
        result["next"] = "Importa el archivo en Auto-Gym > Programas > Intercambio JSON y revísalo en Propuestas de semana. Solo la persona usuaria puede aprobarlo."
        return result

    def create_outline(self, a):
        _check(set(a) == {"outline"}, "Campo esperado: outline.")
        validate_outline(a["outline"])
        context = load_context(self.context_path)["context"]
        active = next((o for o in context.get("outlines", []) if o.get("programId") == a["outline"]["programId"]), None)
        if active is not None:
            _check(active["id"] == a["outline"]["id"],
                   f"El programa ya tiene el plan activo {active['id']}; publica una versión nueva de ese plan.")
            _check(a["outline"]["version"] > active["version"],
                   f"La versión debe ser posterior a la activa ({active['version']}).")
        result = self._write("PlanOutline", "outline", a["outline"])
        result["next"] = "Importa el archivo en Auto-Gym > Programas > Intercambio JSON y actívalo en Planes de periodización. Después, exporta un contexto nuevo para planificar la semana 1."
        return result

    def next_week_brief(self, a):
        _check(set(a) == {"programId"} and isinstance(a["programId"], str), "Campo esperado: programId.")
        context = load_context(self.context_path)["context"]
        outline = next((o for o in context.get("outlines", []) if o.get("programId") == a["programId"]), None)
        _check(outline is not None, f"El programa {a['programId']} no tiene un plan activo en el contexto autorizado.")
        brief = next((b for b in context.get("nextWeeks", []) if b.get("programId") == a["programId"]), None)
        _check(brief is not None, "El contexto no incluye la próxima semana del plan; vuelve a exportarlo desde la app.")
        program = next((p for p in context.get("programs", []) if p.get("id") == a["programId"]), None)
        weeks = [w for b in (program or {}).get("blocks", []) for w in b["weeks"]]
        sessions = [s for s in context.get("history", {}).get("sessions", [])
                    if s.get("programId") in (None, a["programId"])]

        def recorded(week):
            if week is None:
                return []
            days = {d["id"] for d in week["days"]}
            return [s for s in sessions if s.get("sessionPlanId") in days]

        ref = brief.get("referenceWeekNumber")
        reference = weeks[ref - 1] if ref else None
        previous = weeks[ref - 2] if ref and ref >= 2 else None
        return {"brief": brief,
                "plan": {k: outline[k] for k in ("id", "version", "name", "programName", "goals", "rules", "days", "blocks")},
                "referenceWeek": {"number": ref, "plan": reference, "sessions": recorded(reference)} if reference else None,
                "previousWeek": {"number": ref - 1, "plan": previous, "sessions": recorded(previous)} if previous else None,
                "proposal": {"programId": a["programId"], "blockId": brief.get("blockId"),
                             "weekName": f"Semana {brief['weekNumber']}" if brief.get("weekNumber") else None,
                             "rule": "Usa IDs nuevos para la semana, días, ejercicios y series. Solo con status READY."}}

    def _write(self, payload_type, key, payload):
        import_id = "mcp-" + uuid.uuid4().hex
        package = {"schemaVersion": 1, "type": payload_type, "importId": import_id,
                   "createdAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
                   "context": None, "proposal": None, "results": None}
        package[key] = payload
        text = json.dumps(package, ensure_ascii=False, indent=2)
        _check(len(text.encode("utf-8")) <= MAX_BYTES, "El paquete supera 1 MiB.")
        if self.sign_key:
            text = json.dumps(sign(self.sign_key[0], self.sign_key[1], payload_type, text), ensure_ascii=False, indent=2)
            _check(len(text.encode("utf-8")) <= MAX_BYTES, "El envío firmado supera 1 MiB.")
        path = os.path.join(self.outbox, import_id + ".json")
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(text)
        os.replace(tmp, path)  # atomic: the app never sees a partial file
        return {"file": path, "importId": import_id, "status": "pendiente", "signedWith": self.sign_key[0] if self.sign_key else None}

    # --- JSON-RPC ----------------------------------------------------------------------------------------------
    def handle(self, msg):
        method, mid = msg.get("method"), msg.get("id")
        if mid is None:  # notification
            return None
        try:
            if method == "initialize":
                result = {"protocolVersion": PROTOCOL_VERSION, "serverInfo": SERVER_INFO, "capabilities": {"tools": {}},
                          "instructions": "Read the authorized Auto-Gym context, create a multi-week plan outline once, then one week proposal at a time following get_next_week_brief. You cannot approve or activate anything."}
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
    parser.add_argument("--sign-key", default=os.environ.get("AUTO_GYM_SIGN_KEY"),
                        help="Pairing key from the app (agk1.<id>.<secret>); or set AUTO_GYM_SIGN_KEY")
    args = parser.parse_args(argv)
    load_context(args.context)  # fail fast on an unauthorized or invalid file
    if args.sign_key:
        parse_key(args.sign_key)
    if hasattr(sys.stdin, "reconfigure"):
        sys.stdin.reconfigure(encoding="utf-8"); sys.stdout.reconfigure(encoding="utf-8")
    Server(args.context, args.outbox, args.sign_key).serve()


if __name__ == "__main__":
    main()
