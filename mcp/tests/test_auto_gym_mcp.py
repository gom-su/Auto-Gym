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
        self.assertEqual({"get_training_context", "create_week_proposal", "list_created_proposals"}, names)
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


if __name__ == "__main__":
    unittest.main()
