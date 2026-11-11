"""Export/restore UI check on a disposable emulator. Requires explicit clean-install opt-in.

Run BackupTest first to generate app/build/backup-fixture.json. No physical device is accepted.
The selected emulator app is uninstalled twice; use an ephemeral/read-only AVD instance.
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
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', type=Path, default=Path(os.environ.get('ANDROID_HOME', str(ROOT / '.sdk'))) / 'platform-tools/adb.exe')
parser.add_argument('--apk', type=Path, default=ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
parser.add_argument('--fixture', type=Path, default=ROOT / 'app/build/backup-fixture.json')
parser.add_argument('--evidence', type=Path, default=ROOT / '.local/full-backup')
parser.add_argument('--allow-clean-install', action='store_true', required=True)
args = parser.parse_args()
PACKAGE = 'com.example.workoutpoc'
args.evidence.mkdir(parents=True, exist_ok=True)


def call(*parts):
    return subprocess.check_output([str(args.adb), '-s', args.serial, *parts], stderr=subprocess.STDOUT)


def hierarchy():
    for _ in range(5):
        call('shell', 'uiautomator', 'dump', '/sdcard/backup-ui.xml')
        try:
            return ET.fromstring(call('shell', 'cat', '/sdcard/backup-ui.xml'))
        except ET.ParseError:
            time.sleep(0.2)
    raise AssertionError('No Android UI hierarchy')


def labels(root):
    return [(n.get('text'), n.get('content-desc')) for n in root.iter('node') if n.get('text') or n.get('content-desc')]


def find(label, scroll=False):
    for _ in range(8):
        root = hierarchy()
        for node in root.iter('node'):
            if label in (node.get('text'), node.get('content-desc')):
                return node
        if scroll:
            call('shell', 'input', 'swipe', '550', '1850', '550', '700', '300')
        time.sleep(0.2)
    raise AssertionError(f'Missing {label!r}: {labels(root)}')


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    call('shell', 'input', 'tap', str((x1 + x2)//2), str((y1 + y2)//2))


def click(label, scroll=False):
    tap(find(label, scroll))


def capture(name):
    (args.evidence / f'{name}.xml').write_bytes(call('shell', 'cat', '/sdcard/backup-ui.xml'))
    (args.evidence / f'{name}.png').write_bytes(call('exec-out', 'screencap', '-p'))


def launch():
    call('shell', 'am', 'start', '-n', f'{PACKAGE}/.MainActivity')
    time.sleep(0.7)
    for _ in range(3):
        click('Ajustes')
        time.sleep(0.4)
        if any(n.get('text') == 'Registro' for n in hierarchy().iter('node')):
            break
    click('Respaldo completo', True)
    find('Exportar respaldo completo')


def clean_install():
    if call('shell', 'pm', 'path', PACKAGE).strip():
        call('uninstall', PACKAGE)
    call('install', str(args.apk))


FILES = dict(session='workout.json', history='history-v1.json', programs='programs-v1.json', planOutlines='plan-outlines-v1.json',
             exchanges='exchanges-v1.json', proposals='proposals-v1.json', measurements='measurements-v1.json',
             exercises='exercises-v1.json', planningAnswers='planning-answers-v1.json', weekAnnotations='week-annotations-v1.json',
             migrations='migrations-v1.json', settings='app-settings-v1.json')


def local_files():
    try:
        names = call('shell', 'run-as', PACKAGE, 'ls', 'files/datastore').decode().split()
    except subprocess.CalledProcessError as error:
        if b'No such file or directory' in error.output:
            return {}
        raise
    return {name: call('exec-out', 'run-as', PACKAGE, 'cat', f'files/datastore/{name}') for name in names if name.endswith('.json')}


def assert_stores(expected):
    files = local_files()
    for key, filename in FILES.items():
        assert json.loads(files[filename]) == expected[key], f'{key} changed'
    return files


def choose_backup(name):
    click('Restaurar respaldo')
    click(name)
    find('Revisar respaldo')


assert call('shell', 'getprop', 'ro.kernel.qemu').strip() == b'1', 'Only a disposable emulator is accepted'
fixture = json.loads(args.fixture.read_text(encoding='utf8'))
assert fixture['format'] == 'auto-gym-backup'
clean_install()
call('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files/datastore')
for key, name in FILES.items():
    path = args.evidence / name
    path.write_text(json.dumps(fixture['stores'][key], ensure_ascii=False), encoding='utf8')
    call('push', str(path), f'/data/local/tmp/{name}')
    call('shell', 'run-as', PACKAGE, 'cp', f'/data/local/tmp/{name}', f'files/datastore/{name}')
launch()
source = assert_stores(fixture['stores'])
click('Exportar respaldo completo')
root = hierarchy()
print('Save picker opened', flush=True)
# Save in Downloads; check the suggested name before using a unique verification filename.
if not any(n.get('text') == 'Downloads' for n in root.iter('node')):
    click('Show roots')
    click('Downloads')
root = hierarchy()
filename_node = next(n for n in root.iter('node') if n.get('class') == 'android.widget.EditText')
assert filename_node.get('text').startswith('auto-gym-respaldo-')
filename = f'auto-gym-respaldo-verification-{int(time.time())}.json'
tap(filename_node)
call('shell', 'input', 'keycombination', '113', '29')
call('shell', 'input', 'text', filename)
call('shell', 'input', 'keyevent', '4')  # dismiss keyboard
click('SAVE')
find('Respaldo completo exportado correctamente.')
capture('01-exported')
exported = json.loads(call('shell', 'cat', f'/sdcard/Download/{filename}'))
assert exported['stores'] == fixture['stores'], 'UI export lost local data'
assert local_files() == source, 'Export wrote local stores'
print('Export validated:', filename, flush=True)

# The file remains in shared storage after the source installation is removed.
clean_install()
launch()
before = local_files()
choose_backup(filename)
capture('02-review')
assert local_files() == before, 'Preview wrote stores'
click('Cancelar', True)
assert local_files() == before, 'Review cancel wrote stores'
choose_backup(filename)
click('Restaurar y reemplazar datos', True)
find('¿Reemplazar todos los datos?')
capture('03-confirmation')
assert local_files() == before, 'Opening confirmation wrote stores'
# The dialog has its own Cancelar, in addition to the underlying review.
root = hierarchy()
buttons = [n for n in root.iter('node') if n.get('text') == 'Cancelar']
tap(buttons[-1])
assert local_files() == before, 'Dialog cancel wrote stores'
click('Restaurar y reemplazar datos', True)
click('Sí, restaurar y reemplazar')
find('Respaldo restaurado correctamente.')
restored = assert_stores(exported['stores'])
assert 'sync-v1.json' not in restored, 'Pairing secrets were restored'
capture('04-restored')
call('shell', 'am', 'force-stop', PACKAGE)
launch()
assert_stores(exported['stores'])
capture('05-reopened')
result = dict(passed=True, serial=args.serial, physicalDevice=False, storesCompared=12,
              exportUnchanged=True, previewUnchanged=True, reviewCancelUnchanged=True, dialogCancelUnchanged=True,
              cleanInstallRestoreMatches=True, survivesForceStop=True, syncSecretsExcluded=True)
(args.evidence / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf8')
print(json.dumps(result), flush=True)
