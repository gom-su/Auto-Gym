"""Verify saved migrations and review survive force-stop without clearing app data.

Run after MigrationDeviceTest or a manual synthetic import. Use --serial with
multiple devices. Evidence remains in .local/; no source data is printed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
parser.add_argument("--package", default="com.example.workoutpoc")
args = parser.parse_args()
sdk = ROOT / ".sdk"
if not sdk.exists():
    sdk = Path(os.environ["ANDROID_HOME"])
adb_path = str(sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
prefix = [adb_path] + (["-s", args.serial] if args.serial else [])
out = ROOT / ".local" / "ent-03-restoration"


def adb(*values):
    return subprocess.check_output([*prefix, *values], text=True, encoding="utf-8", timeout=30).strip()


def shell(*values):
    return adb("shell", *values)


def snapshot():
    return json.loads(shell("run-as", args.package, "cat", "files/datastore/migrations-v1.json"))


def nodes():
    for _ in range(4):
        result = shell("uiautomator", "dump", "/sdcard/ent-03-restore-ui.xml")
        if "dumped" in result:
            return list(ET.fromstring(shell("cat", "/sdcard/ent-03-restore-ui.xml")).iter("node"))
        time.sleep(0.3)
    raise AssertionError("Android did not expose the UI hierarchy.")


def tap(text):
    for _ in range(15):
        for node in nodes():
            if node.get("text") == text:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(0.3)
                return
        shell("input", "swipe", "500", "1800", "500", "600", "200")
    raise AssertionError("Expected migration UI control was not found.")


before = snapshot()
assert before["schemaVersion"] == 1 and before["documents"], "Save a migration first."
for document in before["documents"]:
    original = document["original"]
    digest = hashlib.sha256(f"obsidian:{document['parserVersion']}\0{original}".encode("utf-8")).hexdigest()
    assert digest == document["id"], "Source hash does not match."
    assert original == "\n".join(line["text"] for line in document["lines"]), "Source lines differ."
shell("am", "force-stop", args.package)
shell("am", "start", "-W", "-f", "0x10008000", "-n", f"{args.package}/.MainActivity")
tap("Programas")
tap("Migrar desde Obsidian")
document = before["documents"][-1]
pending = sum(not issue["acknowledged"] for issue in document["issues"])
tap(f"Importación {len(before['documents'])} · {document['title'] or document['source']} · {pending} pendientes")
tap(f"Advertencias ({len(document['issues'])})")
assert snapshot() == before, "Saved migration or review changed across process death and browsing."
out.mkdir(parents=True, exist_ok=True)
shell("screencap", "-p", "/sdcard/ent-03-restored.png")
adb("pull", "/sdcard/ent-03-restored.png", str(out / "restored.png"))
summary = {"result": "PASS", "documents": len(before["documents"]),
           "check": "unchanged original, interpretation and review after force-stop and UI browsing"}
(out / "result.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary))
