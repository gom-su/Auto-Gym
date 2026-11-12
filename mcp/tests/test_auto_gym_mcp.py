"""Exercises the MCP server over its real stdio transport. Run: python -m unittest discover -s mcp/tests"""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SERVER = os.path.join(ROOT, "mcp", "auto_gym_mcp.py")
CONTEXT = os.path.join(ROOT, "app", "src", "main", "assets", "examples", "training-context-v1.json")


def digest(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def week(prefix="mcp-week"):
    return {"id": prefix, "name": "Semana MCP", "version": 1, "days": [{"id": f"{prefix}-day", "name": "Día MCP", "exercises": [
        {"id": f"{prefix}-squat", "exerciseId": "squat", "name": "Sentadilla ficticia", "sets": [
            {"id": f"{prefix}-s1", "reps": 6, "rir": 2, "load": {"value": 0.0, "unit": "KG", "basis": "PER_SIDE"}, "restSeconds": 90},
            {"id": f"{prefix}-s2", "reps": 6, "rir": 2, "load": {"value": 0.0, "unit": "KG", "basis": "PER_SIDE"}, "restSeconds": 90}]}]}]}


class McpServerTest(unittest.TestCase):
    def setUp(self):
        self.outbox = tempfile.mkdtemp()
        self.proc = subprocess.Popen([sys.executable, "-I", SERVER, "--context", CONTEXT, "--outbox", self.outbox],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8")
        self.next_id = 0
        init = self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}})
        self.assertEqual("auto-gym", init["result"]["serverInfo"]["name"])
        self.notify("notifications/initialized")

    def tearDown(self):
        self.proc.stdin.close(); self.proc.wait(timeout=10); self.proc.stdout.close()

    def notify(self, method):
        self.proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": method}) + "\n"); self.proc.stdin.flush()

    def rpc(self, method, params=None):
        self.next_id += 1
        self.proc.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params or {}}) + "\n")
        self.proc.stdin.flush()
        response = json.loads(self.proc.stdout.readline())
        self.assertEqual(self.next_id, response["id"])
        return response

    def tool(self, name, arguments=None):
        r = self.rpc("tools/call", {"name": name, "arguments": arguments or {}})["result"]
        return r["isError"], r["content"][0]["text"]

    def proposal(self, **overrides):
        args = {"proposalId": "mcp-proposal", "version": 1, "programId": "fictional-program", "blockId": "block-base",
                "week": week(), "differences": ["Sentadilla ficticia: 1 → 2 series"], "reasons": ["Sentadilla ficticia: más volumen tolerado."]}
        args.update(overrides)
        return args

    def test_tools_offer_no_approval(self):
        names = {t["name"] for t in self.rpc("tools/list")["result"]["tools"]}
        self.assertEqual({"get_training_context", "create_week_proposal", "list_created_proposals",
                          "create_plan_outline", "get_next_week_brief"}, names)
        self.assertFalse(any("approv" in n or "activ" in n for n in names))
        error, text = self.tool("approve_proposal", {"importId": "x"})
        self.assertTrue(error); self.assertIn("no puede aprobar", text)
        self.assertEqual(-32601, self.rpc("resources/write")["error"]["code"])

    def test_reads_only_the_authorized_context(self):
        before = digest(CONTEXT)
        error, text = self.tool("get_training_context")
        self.assertFalse(error)
        self.assertEqual("TrainingContext", json.loads(text)["type"])
        self.assertEqual(before, digest(CONTEXT))

    def test_creates_a_pending_proposal_in_the_outbox(self):
        error, text = self.tool("create_week_proposal", self.proposal())
        self.assertFalse(error, text)
        result = json.loads(text)
        self.assertEqual("pendiente", result["status"])
        self.assertEqual(os.path.abspath(self.outbox), os.path.dirname(result["file"]))
        with open(result["file"], encoding="utf-8") as f:
            package = json.load(f)
        self.assertEqual(1, package["schemaVersion"]); self.assertEqual("WeekProposal", package["type"])
        self.assertIsNone(package["context"]); self.assertIsNone(package["results"])
        self.assertEqual("Agente vía MCP", package["proposal"]["source"])
        self.assertFalse([f for f in os.listdir(self.outbox) if f.endswith(".tmp")])
        _, listed = self.tool("list_created_proposals")
        self.assertEqual([os.path.basename(result["file"])], json.loads(listed))

    def test_rejects_invalid_or_unauthorized_proposals(self):
        cases = {
            "unknown program": self.proposal(programId="other-program"),
            "unknown block": self.proposal(blockId="other-block"),
            "reused saved id": self.proposal(week=week("week-one")),
            "bad unit/basis": self.proposal(week=json.loads(json.dumps(week()).replace('"PER_SIDE"', '"MACHINE_SETTING"'))),
            "rir out of range": self.proposal(week=json.loads(json.dumps(week()).replace('"rir": 2', '"rir": 11'))),
            "extra field": dict(self.proposal(), approve=True),
            "empty reason": self.proposal(reasons=[" "]),
        }
        for label, args in cases.items():
            error, text = self.tool("create_week_proposal", args)
            self.assertTrue(error, f"{label}: {text}")
        self.assertEqual([], os.listdir(self.outbox))

    def test_rejects_rest_outside_range_for_all_set_kinds(self):
        for kind, seconds in ((None, 44), ("WORK", 44), ("WORK", 121),
                              ("APPROACH", 44), ("APPROACH", 121), ("WARMUP", 44)):
            with self.subTest(seconds=seconds, kind=kind):
                args = self.proposal()
                prescribed_set = args["week"]["days"][0]["exercises"][0]["sets"][0]
                prescribed_set["restSeconds"] = seconds
                if kind is not None:
                    prescribed_set["kind"] = kind
                error, message = self.tool("create_week_proposal", args)
                self.assertTrue(error)
                self.assertEqual(
                    f"Error: Día MCP · Sentadilla ficticia, serie 1: descanso {seconds} s fuera de 45–120 s, "
                    "el rango que la persona puede ajustar durante la sesión.", message)
        self.assertEqual([], os.listdir(self.outbox))

    def test_accepts_no_rest_range_endpoints_and_omitted_rest(self):
        args = self.proposal()
        sets = args["week"]["days"][0]["exercises"][0]["sets"]
        sets[0].update(kind="APPROACH", restSeconds=0)
        sets[1].update(kind="WORK", restSeconds=0)
        sets.append(dict(sets[1], id="mcp-week-s3", restSeconds=45))
        sets.append(dict(sets[1], id="mcp-week-s4", restSeconds=120))
        sets.append(dict(sets[1], id="mcp-week-s5"))
        del sets[-1]["restSeconds"]
        error, message = self.tool("create_week_proposal", args)
        self.assertFalse(error, message)


PLAN_CONTEXT = os.path.join(ROOT, "mcp", "examples", "training-context-with-plan.json")
OUTLINE = os.path.join(ROOT, "app", "src", "main", "assets", "examples", "plan-outline-v1.json")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def plan_week(outline, n):
    """Week n built from the plan's day templates, with IDs unique to that week."""
    w = outline["weeks"][n - 1]
    days = []
    for d in outline["days"]:
        exercises = []
        for e in d["exercises"]:
            t = next((t for t in w["targets"] if e["exerciseId"] in t.get("exerciseIds", [])), None) or \
                next((t for t in w["targets"] if not t.get("exerciseIds")), {})
            sets = [{"id": f"w{n}-{d['id']}-{e['exerciseId']}-{i}", "reps": t.get("repsMax"), "rir": t.get("rirMax"), "load": {"value": 0.0, "unit": "KG", "basis": "PER_SIDE"}}
                    for i in range(t.get("sets") or e.get("sets") or 2)]
            exercises.append({"id": f"w{n}-{d['id']}-{e['exerciseId']}", "exerciseId": e["exerciseId"], "name": e["name"], "sets": sets})
        days.append({"id": f"w{n}-{d['id']}", "name": d["name"], "exercises": exercises})
    return {"id": f"w{n}", "name": f"Semana {n}", "version": 1, "days": days}


class McpPlanBase(unittest.TestCase):
    """Server running over a context with an activated 12-week plan."""
    context_path = PLAN_CONTEXT
    tearDown = McpServerTest.tearDown
    notify = McpServerTest.notify
    rpc = McpServerTest.rpc
    tool = McpServerTest.tool

    def setUp(self):
        self.outbox = tempfile.mkdtemp()
        self.proc = subprocess.Popen([sys.executable, "-I", SERVER, "--context", self.context_path, "--outbox", self.outbox],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8")
        self.next_id = 0
        self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}})
        self.notify("notifications/initialized")
        self.outline = load(OUTLINE)["outline"]

    def week_proposal(self, n, **overrides):
        args = {"proposalId": f"plan-w{n}", "version": 1, "programId": "programa-faro", "blockId": self.outline["weeks"][n - 1]["blockId"],
                "week": plan_week(self.outline, n), "differences": [f"Semana {n} del plan."], "reasons": ["Press ficticio: según el plan."]}
        args.update(overrides)
        return args


class McpPlanTest(McpPlanBase):
    """A 12-week plan: created once as an outline, then planned week by week from the app's brief."""

    def test_shipped_context_plan_is_the_example_outline(self):
        self.assertEqual(self.outline, load(PLAN_CONTEXT)["context"]["outlines"][0])

    def test_brief_names_the_week_to_plan(self):
        error, text = self.tool("get_next_week_brief", {"programId": "programa-faro"})
        self.assertFalse(error, text)
        result = json.loads(text)
        self.assertEqual("READY", result["brief"]["status"])
        self.assertEqual(1, result["brief"]["weekNumber"])
        self.assertEqual("faro-b1", result["brief"]["blockId"])
        self.assertEqual("Calibración", result["brief"]["target"]["microcycle"])
        self.assertEqual(4, len(result["plan"]["days"]))
        self.assertIsNone(result["referenceWeek"])
        self.assertTrue(self.tool("get_next_week_brief", {"programId": "otro"})[0])

    def test_week_one_is_proposed_before_the_program_exists(self):
        error, text = self.tool("create_week_proposal", self.week_proposal(1))
        self.assertFalse(error, text)
        error, text = self.tool("create_week_proposal", self.week_proposal(1, proposalId="x", blockId="faro-b2"))
        self.assertTrue(error); self.assertIn("faro-b1", text)

    def test_outline_is_validated_and_written_for_activation(self):
        error, text = self.tool("create_plan_outline", {"outline": dict(self.outline, version=2)})
        self.assertFalse(error, text)
        with open(json.loads(text)["file"], encoding="utf-8") as f:
            package = json.load(f)
        self.assertEqual("PlanOutline", package["type"]); self.assertEqual(2, package["outline"]["version"])
        weeks = self.outline["weeks"]
        bad = {
            "same version as active": self.outline,
            "other plan for the program": dict(self.outline, id="otro", version=2),
            "gap in weeks": dict(self.outline, version=2, weeks=weeks[:2] + weeks[3:]),
            "unknown block": dict(self.outline, version=2, weeks=[dict(weeks[0], blockId="x")] + weeks[1:]),
            "target outside templates": dict(self.outline, version=2, weeks=[dict(weeks[0], targets=[{"label": "X", "exerciseIds": ["nope"]}])] + weeks[1:]),
            "extra field": dict(self.outline, version=2, approve=True),
        }
        for label, outline in bad.items():
            error, text = self.tool("create_plan_outline", {"outline": outline})
            self.assertTrue(error, f"{label}: {text}")
        self.assertEqual(1, len(os.listdir(self.outbox)))


class McpPlanProgressTest(McpPlanBase):
    """Week 1 approved and trained: the brief moves to week 2, and only week 2 of block 1 is accepted."""

    def setUp(self):
        ctx = load(PLAN_CONTEXT)
        outline = ctx["context"]["outlines"][0]
        ctx["context"]["programs"] = [{"id": "programa-faro", "name": "Programa ficticio Faro", "blocks": [
            {"id": "faro-b1", "name": "Bloque 1 — Base", "weeks": [plan_week(outline, 1)]}]}]
        ctx["context"]["history"]["sessions"] = [{"id": "s1", "sessionPlanId": "w1-A", "status": "COMPLETED", "startedAt": None, "endedAt": None,
            "notes": None, "cardio": [], "sets": [{"id": "s1:0", "prescriptionId": None, "exerciseId": "sentadilla-ficticia", "status": "COMPLETED",
                                                   "reps": 12, "rir": 3, "load": None, "completedAt": None, "notes": None}]}]
        ctx["context"]["nextWeeks"] = [dict(ctx["context"]["nextWeeks"][0], plannedWeeks=1, referenceWeekNumber=1, referenceWeekId="w1",
            referenceSessionIds=["s1"], referenceDaysWithoutData=["Día B", "Día C", "Día D"], weekNumber=2, weekInBlock=2,
            target=outline["weeks"][1], message="Referencia: semana 1.")]
        fd, self.context_path = tempfile.mkstemp(suffix=".json")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(ctx, f, ensure_ascii=False)
        super().setUp()

    def test_brief_includes_the_reference_week_and_its_records(self):
        result = json.loads(self.tool("get_next_week_brief", {"programId": "programa-faro"})[1])
        self.assertEqual(2, result["brief"]["weekNumber"])
        self.assertEqual("w1", result["referenceWeek"]["plan"]["id"])
        self.assertEqual(["s1"], [s["id"] for s in result["referenceWeek"]["sessions"]])
        self.assertIsNone(result["previousWeek"])

    def test_reference_records_do_not_mix_cycles_with_the_same_day_ids(self):
        ctx = load(self.context_path)
        session = ctx["context"]["history"]["sessions"][0]
        session["programId"] = "programa-faro"
        ctx["context"]["history"]["sessions"].append(dict(session, id="old-cycle-session", programId="old-cycle"))
        with open(self.context_path, "w", encoding="utf-8") as f:
            json.dump(ctx, f, ensure_ascii=False)
        result = json.loads(self.tool("get_next_week_brief", {"programId": "programa-faro"})[1])
        self.assertEqual(["s1"], [s["id"] for s in result["referenceWeek"]["sessions"]])

    def test_only_the_next_week_of_the_plan_is_accepted(self):
        self.assertTrue(self.tool("create_week_proposal", self.week_proposal(2, blockId="faro-b2"))[0])
        self.assertTrue(self.tool("create_week_proposal", self.week_proposal(2, week=plan_week(self.outline, 1)))[0])  # reused IDs
        error, text = self.tool("create_week_proposal", self.week_proposal(2))
        self.assertFalse(error, text)

    def test_no_new_week_while_the_next_one_is_unrecorded_or_the_cycle_is_over(self):
        for status in ("ALREADY_PLANNED", "CYCLE_COMPLETE"):
            ctx = load(self.context_path)
            ctx["context"]["nextWeeks"][0]["status"] = status
            with open(self.context_path, "w", encoding="utf-8") as f:
                json.dump(ctx, f, ensure_ascii=False)
            error, text = self.tool("create_week_proposal", self.week_proposal(2, proposalId=f"p-{status}"))
            self.assertTrue(error, status); self.assertIn("No toca", text)


if __name__ == "__main__":
    unittest.main()
