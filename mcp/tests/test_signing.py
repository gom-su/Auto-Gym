"""Signed envelopes (INT-02). Run: python -m unittest discover -s mcp/tests"""
import hashlib
import hmac
import json
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SERVER = os.path.join(ROOT, "mcp", "auto_gym_mcp.py")
CONTEXT = os.path.join(ROOT, "app", "src", "main", "assets", "examples", "training-context-v1.json")
# Synthetic test-only key; never a real pairing.
with open(os.path.join(ROOT, "mcp", "examples", "test-pairing-key.txt"), encoding="utf-8") as f:
    TEST_KEY = f.read().strip()


class SigningTest(unittest.TestCase):
    def test_signed_proposal_is_an_envelope_with_a_valid_mac(self):
        outbox = tempfile.mkdtemp()
        proc = subprocess.Popen([sys.executable, "-I", SERVER, "--context", CONTEXT, "--outbox", outbox, "--sign-key", TEST_KEY],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8")
        def rpc(i, method, params):
            proc.stdin.write(json.dumps({"jsonrpc": "2.0", "id": i, "method": method, "params": params}) + "\n"); proc.stdin.flush()
            return json.loads(proc.stdout.readline())
        rpc(1, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}})
        week = {"id": "signed-week", "name": "Semana firmada", "version": 1, "days": [{"id": "signed-day", "name": "Día", "exercises": [
            {"id": "signed-squat", "exerciseId": "squat", "name": "Sentadilla ficticia", "sets": [{"id": "signed-s1", "reps": 6, "rir": 2, "load": {"value": 0.0, "unit": "KG", "basis": "PER_SIDE"}}]}]}]}
        r = rpc(2, "tools/call", {"name": "create_week_proposal", "arguments": {"proposalId": "signed", "version": 1, "programId": "fictional-program",
            "blockId": "block-base", "week": week, "differences": ["x"], "reasons": ["Sentadilla ficticia: prueba"]}})
        proc.stdin.close(); proc.wait(timeout=10); proc.stdout.close()
        self.assertFalse(r["result"]["isError"], r)
        result = json.loads(r["result"]["content"][0]["text"])
        key_id = TEST_KEY.split(".")[1]
        self.assertEqual(key_id, result["signedWith"])
        with open(result["file"], encoding="utf-8") as f:
            env = json.load(f)
        self.assertEqual(1, env["syncVersion"]); self.assertEqual("WeekProposal", env["payloadType"])
        import base64
        secret = base64.urlsafe_b64decode(TEST_KEY.split(".")[2] + "==")
        message = "\n".join(["auto-gym-sync-v1", env["keyId"], env["nonce"], env["issuedAt"], env["payloadType"], env["payload"]]).encode()
        self.assertEqual(hmac.new(secret, message, hashlib.sha256).hexdigest(), env["mac"])
        self.assertEqual("WeekProposal", json.loads(env["payload"])["type"])

    def test_invalid_key_is_refused_at_start(self):
        out = subprocess.run([sys.executable, "-I", SERVER, "--context", CONTEXT, "--outbox", tempfile.mkdtemp(), "--sign-key", "agk1.k.short"],
                             capture_output=True, text=True, timeout=10)
        self.assertNotEqual(0, out.returncode)


if __name__ == "__main__":
    unittest.main()
