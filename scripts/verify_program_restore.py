"""Verify an already imported library survives real process death, without editing it.

Install the debug APK and import a program through Programas first. Set
ANDROID_SERIAL when multiple devices are connected. Evidence stays in .local/.
This stops the app (including an active foreground service), but never clears data.
"""
from pathlib import Path
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / ".sdk"
if not SDK.exists():
    SDK = Path(os.environ["ANDROID_HOME"])
ADB = str(SDK / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
SERIAL = ["-s", os.environ["ANDROID_SERIAL"]] if os.environ.get("ANDROID_SERIAL") else []
PKG = "com.example.workoutpoc"
OUT = ROOT / ".local" / "ent-01-restoration"


def adb(*args):
    return subprocess.check_output([ADB, *SERIAL, *args], text=True, encoding="utf-8", timeout=30).strip()


def shell(*args):
    return adb("shell", *args)


def snapshot():
    return json.loads(shell("run-as", PKG, "cat", "files/datastore/programs-v1.json"))


def nodes():
    for _ in range(4):
        result = shell("uiautomator", "dump", "/sdcard/ent-01-restore-ui.xml")
        if "dumped" in result:
            return list(ET.fromstring(shell("cat", "/sdcard/ent-01-restore-ui.xml")).iter("node"))
        time.sleep(.3)
    raise AssertionError("Android did not expose the UI hierarchy after launch.")


def tap(text):
    for _ in range(5):
        for node in nodes():
            if node.get("text") == text:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(.3)
                return
        time.sleep(.3)
    raise AssertionError(f"Missing UI: {text}")


before = snapshot()
assert before["schemaVersion"] == 1 and before["programs"], "Import a program in the UI first."
shell("am", "force-stop", PKG)
shell("am", "start", "-W", "-n", f"{PKG}/.MainActivity")
tap("Programas")
tap(before["programs"][0]["name"])
block = before["programs"][0]["blocks"][0]
week = block["weeks"][0]
days = len(week["days"])
tap(f"{week['name']} · {days} {'día' if days == 1 else 'días'}")
day = week["days"][0]
exercises = len(day["exercises"])
sets = sum(len(e["sets"]) for e in day["exercises"])
tap(f"{day['name']} · {exercises} {'ejercicio' if exercises == 1 else 'ejercicios'} · {sets} series")
assert snapshot() == before, "Library changed across force-stop/relaunch/browse."
OUT.mkdir(parents=True, exist_ok=True)
shell("screencap", "-p", "/sdcard/ent-01-restored.png")
adb("pull", "/sdcard/ent-01-restored.png", str(OUT / "restored.png"))
summary = {"result": "PASS", "programs": len(before["programs"]),
           "weeks": sum(len(b["weeks"]) for p in before["programs"] for b in p["blocks"]),
           "check": "unchanged library after process death and UI browsing"}
(OUT / "result.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary))
