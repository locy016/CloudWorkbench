"""Run an explicitly selected real-device test; handle Lenovo's test-app launch prompt only."""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

DEFAULT_ADB = Path('D:/ProjectFolder/FaceAuthentication/android/.tooling/android-sdk/platform-tools/adb.exe')

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('test', help='Fully qualified instrumentation class#method')
    p.add_argument('--output', required=True)
    p.add_argument('--adb', default=str(DEFAULT_ADB) if DEFAULT_ADB.is_file() else 'adb')
    p.add_argument('--serial', help='ADB serial when more than one device is connected')
    args = p.parse_args()
    base = [args.adb]
    if args.serial:
        base += ['-s', args.serial]
    command = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', args.test]
    command += ['cn.xux.cloudworkbench.test/androidx.test.runner.AndroidJUnitRunner']
    with Path(args.output).open('wb') as out:
        process = subprocess.Popen(command, stdout=out, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 360
        while process.poll() is None:
            if time.monotonic() > deadline:
                process.terminate()
                raise SystemExit('Instrumentation exceeded six minutes; inspect device before retrying')
            time.sleep(3)
            try:
                subprocess.run(base + ['shell', 'uiautomator', 'dump', '/sdcard/workbench-test-ui.xml'], capture_output=True, timeout=10)
                xml = subprocess.check_output(base + ['shell', 'cat', '/sdcard/workbench-test-ui.xml'], timeout=5)
                root = ET.fromstring(xml)
                labels = [n.get('text', '') for n in root.iter('node')]
                if '关联启动应用提示' in labels and any('cn.xux.cloudworkbench.test' in t for t in labels):
                    node = next(n for n in root.iter('node') if n.get('text') == '允许')
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                    subprocess.run(base + ['shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True, capture_output=True)
                    print('Allowed this instrumentation test application to finish its activity transition.', flush=True)
            except (subprocess.SubprocessError, ET.ParseError, StopIteration):
                pass
    result = Path(args.output).read_text(encoding='utf-8', errors='replace')
    print(result[-4000:])
    raise SystemExit(0 if re.search(r'OK \(\d+ tests?\)', result) else 1)
