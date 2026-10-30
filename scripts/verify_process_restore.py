"""Device integration check: real notification edits and process-death restoration.

Requires an installed debug APK, an unlocked device and an Idle demo. Does not clear
app data. Uses adb from this project's SDK or ANDROID_HOME; needs Python 3.10+.
"""
from pathlib import Path
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / '.sdk'
if not SDK.exists():
    SDK = Path(os.environ['ANDROID_HOME'])
ADB = str(SDK / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb'))
PKG = 'com.example.workoutpoc'
OUT = ROOT / 'docs' / 'evidence' / 'restoration'
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.check_output([ADB, *args], text=True, encoding='utf-8', timeout=30).strip()


def shell(*args):
    return adb('shell', *args)


def state():
    return json.loads(shell('run-as', PKG, 'cat', 'files/datastore/workout.json'))


def wait_state(predicate, timeout=5):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        saved = state()
        if predicate(saved):
            return saved
        time.sleep(.1)
    raise AssertionError(f'State did not match: {saved}')


def nodes():
    shell('uiautomator', 'dump', '/sdcard/workout-restore-ui.xml')
    return list(ET.fromstring(shell('cat', '/sdcard/workout-restore-ui.xml')).iter('node'))


def tap(*, text=None, resource=None):
    for _ in range(4):
        for node in nodes():
            if (text is not None and node.get('text') == text) or (resource is not None and node.get('resource-id') == resource):
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
                shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(.25)
                return
        time.sleep(.25)
    raise AssertionError(f'Missing UI: text={text!r}, resource={resource!r}')


def screenshot(name):
    shell('screencap', '-p', '/sdcard/workout-restore.png')
    adb('pull', '/sdcard/workout-restore.png', str(OUT / f'{name}.png'))


def launch():
    shell('cmd', 'statusbar', 'collapse')
    shell('am', 'start', '-n', f'{PKG}/.MainActivity')
    time.sleep(.75)
    for _ in range(4):
        if any(n.get('text') == 'Iniciar demo — Notificación' for n in nodes()):
            return
    raise AssertionError('Activity did not render after launch')


shell('pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
launch()
# Only this closed demo is exercised. Never erase an existing non-idle session.
has_snapshot = subprocess.run([ADB, 'shell', 'run-as', PKG, 'test', '-f',
                               'files/datastore/workout.json'], timeout=10).returncode == 0
assert not has_snapshot or state()['phase'] == 'Idle', 'Start this verification with an Idle demo.'
tap(text='Iniciar demo — Notificación')
wait_state(lambda s: s['phase'] == 'ActiveSet')
shell('cmd', 'statusbar', 'expand-notifications')
time.sleep(.5)
if not any(n.get('resource-id') == f'{PKG}:id/weight_plus' for n in nodes()):
    tap(resource='android:id/expand_button')
for control, predicate in [
    ('weight_plus', lambda s: s['values']['weightTenths'] == 325),
    ('reps_minus', lambda s: s['values']['reps'] == 7),
    ('rir1', lambda s: s['values']['rir'] == 1),
]:
    tap(resource=f'{PKG}:id/{control}')
    wait_state(predicate)
edited = state()
shell('am', 'force-stop', PKG)
launch()
assert state() == edited, 'Edited active set did not survive process death.'
screenshot('active-restored')
# Re-enable the user-started service, then complete using the real notification.
tap(text='Iniciar demo — Notificación')
shell('cmd', 'statusbar', 'expand-notifications')
time.sleep(.5)
if not any(n.get('resource-id') == f'{PKG}:id/complete' for n in nodes()):
    tap(resource='android:id/expand_button')
tap(resource=f'{PKG}:id/complete')
resting = wait_state(lambda s: s['phase'] == 'Resting')
assert resting['records'][0]['values'] == edited['values']
shell('am', 'force-stop', PKG)
launch()
restored = state()
assert restored['records'] == resting['records']
assert restored['rest'] == resting['rest'], 'Unexpired rest deadline changed on reopen.'
screenshot('rest-restored')
shell('am', 'force-stop', PKG)
time.sleep(10.5)
launch()
advanced = wait_state(lambda s: s['phase'] == 'ActiveSet' and s['index'] == 1)
assert advanced['values']['weightTenths'] == 325
assert advanced['records'] == resting['records']
screenshot('expired-rest-reconciled')
result = {'device': adb('get-serialno'), 'android': shell('getprop', 'ro.build.version.release'),
          'activeEditsRestored': True, 'recordsRestored': True, 'restDeadlineRestored': True,
          'expiredRestReconciled': True, 'finalState': advanced}
(OUT / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in result.items() if k != 'finalState'}, ensure_ascii=False))
