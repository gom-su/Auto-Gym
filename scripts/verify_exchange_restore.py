"""Verify exchange archive across force-stop on a selected Android test installation.

Only local evidence is written. Does not clear app data or change exchange contents.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='com.example.workoutpoc')
parser.add_argument('--evidence', type=Path, default=ROOT / '.local/ent-02-restoration')
args = parser.parse_args()
adb = ROOT / '.sdk/platform-tools/adb.exe'
if not adb.exists():
    adb = 'adb'

def call(*parts):
    return subprocess.check_output([str(adb), '-s', args.serial, *parts])

def archive():
    return json.loads(call('shell', 'run-as', args.package, 'cat', 'files/datastore/exchanges-v1.json'))

def hierarchy():
    for _ in range(8):
        dump = call('shell', 'uiautomator', 'dump', '/sdcard/window-ent02.xml')
        if b'UI hierchary dumped' in dump:
            return ET.fromstring(call('shell', 'cat', '/sdcard/window-ent02.xml'))
        time.sleep(0.3)
    raise AssertionError('Android UI hierarchy unavailable after launch')

def click(label):
    for _ in range(10):
        for node in hierarchy().iter('node'):
            if node.get('text') == label:
                bounds = node.attrib['bounds'].replace('][', ',').strip('[]').split(',')
                x1, y1, x2, y2 = map(int, bounds)
                call('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                return
        time.sleep(0.3)
    raise AssertionError(f'Missing UI label: {label}')

before = archive()
assert before['packages'], 'Import a synthetic exchange before running this check'
call('shell', 'am', 'force-stop', args.package)
call('shell', 'am', 'start', '-f', '0x10008000', '-n', f'{args.package}/com.example.workoutpoc.MainActivity')
click('Programas')
click('Intercambio JSON')
expected_id = before['packages'][0]['importId']
for _ in range(10):
    if any(expected_id in n.get('text', '') for n in hierarchy().iter('node')):
        break
    time.sleep(0.3)
else:
    raise AssertionError('Saved exchange was not restored into the UI')
assert before == archive(), 'Archive changed after restart'
args.evidence.mkdir(parents=True, exist_ok=True)
(args.evidence / 'restored.png').write_bytes(call('exec-out', 'screencap', '-p'))
result = {'passed': True, 'package': args.package, 'packages': len(before['packages']),
          'archiveUnchanged': True, 'restoredInUi': True, 'physicalDevice': call('shell', 'getprop', 'ro.kernel.qemu').strip() != b'1'}
(args.evidence / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf8')
print(json.dumps(result))
