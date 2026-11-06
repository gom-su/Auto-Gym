"""Verify an interrupted planned session resumes exactly after a real process kill (ENT-06).

Run after SessionLifecycleDeviceTest, which leaves a synthetic planned session paused with a recorded set and
cardio. The app is force-stopped (process death, not a test double), relaunched, and the persisted session must be
identical. Resuming through the real UI must then keep every set and cardio entry and continue the rest exactly.
Use --serial with multiple devices. Evidence remains in .local/; no workout data is printed.
"""
import argparse
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
out = ROOT / ".local" / "ent-06-restoration"


def adb(*values):
    return subprocess.check_output([*prefix, *values], text=True, encoding="utf-8", timeout=30).strip()


def shell(*values):
    return adb("shell", *values)


def snapshot():
    return json.loads(shell("run-as", args.package, "cat", "files/datastore/workout.json"))


def nodes():
    for _ in range(4):
        result = shell("uiautomator", "dump", "/sdcard/ent-06-restore-ui.xml")
        if "dumped" in result:
            return list(ET.fromstring(shell("cat", "/sdcard/ent-06-restore-ui.xml")).iter("node"))
        time.sleep(0.3)
    raise AssertionError("Android did not expose the UI hierarchy.")


def find(text):
    for _ in range(12):
        for node in nodes():
            if node.get("text") == text:
                return node
        shell("input", "swipe", "500", "1600", "500", "900", "200")
    raise AssertionError(f"Expected UI text was not found: {text}")


def tap(text):
    node = find(text)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.5)


before = snapshot()
assert before.get("source") and before.get("paused"), "Run SessionLifecycleDeviceTest first (expects a paused planned session)."
assert before["records"] and before["cardio"], "Expected recorded sets and cardio."
shell("am", "force-stop", args.package)
# pidof exits non-zero when no process exists, which is the expected outcome here.
alive = subprocess.run([*prefix, "shell", "pidof", args.package], capture_output=True, text=True, timeout=30).stdout.strip()
assert not alive, "Process still alive after force-stop."
time.sleep(2)  # Real time passes while the process is dead; a paused rest must not run out.
shell("am", "start", "-W", "-f", "0x10008000", "-n", f"{args.package}/.MainActivity")
find("Sesión en pausa")
restored = snapshot()
assert restored == before, "Persisted session changed across process death."
tap("Reanudar sesión")
time.sleep(1)
resumed = snapshot()
assert resumed["paused"] is None, "Session did not resume."
assert resumed["records"] == before["records"], "Recorded sets changed on resume."
assert resumed["cardio"] == before["cardio"], "Cardio changed on resume."
assert resumed["index"] == before["index"] and resumed["phase"] == before["phase"], "Session position changed."
pause_ms = resumed["pausedMillis"] - before["pausedMillis"]
assert pause_ms >= 2000, "Pause length was not accounted for."
if before.get("rest"):
    shift = resumed["rest"]["wallMillis"] - before["rest"]["wallMillis"]
    assert shift == pause_ms, "Rest deadline was not shifted by exactly the pause length."
out.mkdir(parents=True, exist_ok=True)
shell("screencap", "-p", "/sdcard/ent-06-restored.png")
adb("pull", "/sdcard/ent-06-restored.png", str(out / "restored.png"))
summary = {"result": "PASS", "records": len(before["records"]), "cardio": len(before["cardio"]),
           "pauseMillisAccounted": pause_ms, "restShiftedExactly": bool(before.get("rest")),
           "check": "identical snapshot after force-stop; resume keeps sets, cardio and position"}
(out / "result.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary))
