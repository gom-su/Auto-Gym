"""Crash-loop durability check for ENT-07: no confirmed set is lost to a forced close, with the network off.

Uses the built-in demo through the real Activity on an emulator or test device. Each round taps "Completar serie" and
force-stops the process either immediately (before confirmation may have happened) or after the UI confirms the set.
After every relaunch the persisted snapshot must parse, must keep every confirmed record, and the app must not report
a storage error. Airplane mode is on for the whole run. "Reiniciar demo" archives earlier demo records instead of
discarding them, so no data is deleted. Evidence remains in .local/; no workout data is printed.
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
out = ROOT / ".local" / "ent-07-durability"
ERROR_TEXT = "No se pudo guardar o recuperar la sesión. Reintenta desde la aplicación."


def adb(*values):
    return subprocess.check_output([*prefix, *values], text=True, encoding="utf-8", timeout=30).strip()


def shell(*values):
    return adb("shell", *values)


def snapshot():
    return json.loads(shell("run-as", args.package, "cat", "files/datastore/workout.json"))


def texts():
    for _ in range(5):
        if "dumped" in shell("uiautomator", "dump", "/sdcard/ent-07-ui.xml"):
            nodes = list(ET.fromstring(shell("cat", "/sdcard/ent-07-ui.xml")).iter("node"))
            return nodes
        time.sleep(0.3)
    raise AssertionError("Android did not expose the UI hierarchy.")


def find(predicate, attempts=10):
    for i in range(attempts):
        for node in texts():
            if predicate(node.get("text") or ""):
                return node
        # Search downwards first, then back up.
        if i < attempts // 2:
            shell("input", "swipe", "540", "1700", "540", "900", "250")
        else:
            shell("input", "swipe", "540", "900", "540", "1700", "250")
    return None


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def launch():
    shell("am", "start", "-W", "-f", "0x10008000", "-n", f"{args.package}/.MainActivity")
    time.sleep(1.0)
    assert find(lambda t: t == ERROR_TEXT, attempts=1) is None, "App reported a storage error after relaunch."


def kill():
    shell("am", "force-stop", args.package)
    alive = subprocess.run([*prefix, "shell", "pidof", args.package], capture_output=True, text=True, timeout=30).stdout.strip()
    assert not alive, "Process still alive after force-stop."


shell("pm", "grant", args.package, "android.permission.POST_NOTIFICATIONS")
shell("cmd", "connectivity", "airplane-mode", "enable")
rounds = []
try:
    kill(); launch()
    reset = find(lambda t: t == "Reiniciar demo", attempts=4)
    if reset is not None:
        tap(reset); time.sleep(1.0)
    start = find(lambda t: t == "Iniciar demo — Notificación")
    assert start is not None, "Demo start button not found."
    tap(start); time.sleep(1.5)
    confirmed = 0
    immediate = True
    for round_index in range(30):
        state = snapshot()
        if state["phase"] == "Completed":
            break
        if state["phase"] == "Resting":
            skip = find(lambda t: t == "Saltar descanso")
            assert skip is not None, "Skip rest not found."
            tap(skip); time.sleep(0.8)
            continue
        complete = find(lambda t: t == "Completar serie")
        assert complete is not None, "Complete button not found."
        expected = len(state["records"]) + 1
        tap(complete)
        if not immediate:
            label = find(lambda t: t.startswith(f"Series registradas · {expected}/"), attempts=6)
            assert label is not None, "Set was not confirmed in the UI."
            confirmed = expected
        kill()
        launch()
        restored = snapshot()
        kept = len(restored["records"])
        assert kept >= confirmed, f"Confirmed records lost: {kept} < {confirmed}"
        assert kept in (expected - 1, expected), "Unexpected record count after crash."
        confirmed = max(confirmed, kept) if not immediate else confirmed
        rounds.append({"mode": "immediate-kill" if immediate else "kill-after-confirmation", "kept": kept, "confirmedBefore": confirmed})
        immediate = not immediate
    final = snapshot()
    assert final["phase"] in ("Completed", "ActiveSet", "Resting")
    assert len(final["records"]) >= confirmed
finally:
    shell("cmd", "connectivity", "airplane-mode", "disable")

out.mkdir(parents=True, exist_ok=True)
summary = {"result": "PASS", "airplaneMode": True, "rounds": rounds, "finalRecords": len(final["records"]),
           "check": "every confirmed set survived force-stop; no storage error after any relaunch"}
(out / "result.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps({"result": "PASS", "rounds": len(rounds), "finalRecords": len(final["records"])}))
