#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[[ $(id -u) == 0 ]]
exec 9>/run/cloud-workbench-ops.lock
flock -n 9

python3 - <<'PY'
import os
from pathlib import Path
import subprocess
import tempfile

config = Path("/etc/nginx/conf.d/workbench-https.conf")
if config.is_symlink() or config.stat().st_uid != 0:
    raise SystemExit("Nginx 配置文件校验失败")
original = config.read_text()
old = "        proxy_set_header X-Workbench-Ingress workstation-v2;"
new = ("        proxy_set_header X-Workbench-Ingress workstation-v3;\n"
       "        proxy_set_header X-Workbench-Client-IP $remote_addr;")
if original.count(new) == 2 and old not in original:
    print("设备来源地址已经启用")
    raise SystemExit(0)
if original.count(old) != 2 or new in original:
    raise SystemExit("未匹配两个固定工作台入口")

def replace(text):
    fd, staged = tempfile.mkstemp(prefix=".workstation-address.", dir=config.parent)
    try:
        with os.fdopen(fd, "w") as output:
            os.fchmod(output.fileno(), 0o644)
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(staged, config)
    finally:
        if os.path.exists(staged):
            os.unlink(staged)

replace(original.replace(old, new))
try:
    subprocess.run(["nginx", "-t"], check=True)
    subprocess.run(["systemctl", "reload", "nginx"], check=True)
except subprocess.CalledProcessError:
    replace(original)
    subprocess.run(["nginx", "-t"], check=True)
    subprocess.run(["systemctl", "reload", "nginx"], check=True)
    raise
print("Windows 来源地址采集已启用")
PY
