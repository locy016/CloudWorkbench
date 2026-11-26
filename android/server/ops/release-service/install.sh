#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

[[ $EUID == 0 ]] || { echo 'Run in an administrator SSH session.' >&2; exit 1; }
runtime=/usr/local/lib/cloud-workbench-release
operator=/usr/local/bin/cloud-workbench-release
signer=cloud-workbench-release
config=/etc/cloud-workbench-release
spool=/var/spool/cloud-workbench-release
state=/var/lib/cloud-workbench-release
gateway=/home/xux/.local/lib/cloud-workbench
old=/home/xux/.local/share/cloud-workbench/app-updates
certificate=b5aaa0f7308677f16157d37260884e4d51b2cd52edc2d373722ff52be126014a

if [[ ${1:-} == import ]]; then
  test -f "$config/bootstrap/key.pem"
  temp=$(mktemp -d /run/workbench-release.XXXXXXXX)
  cleanup() { rm -f -- "$temp/envelope" "$temp/material.json" "$temp/release.p12" "$temp/password" "$temp/release.p12.cred" "$temp/password.cred"; rmdir -- "$temp"; }
  trap cleanup EXIT
  python3 - "$spool/import/credentials.cms" "$temp/envelope" <<'PY'
import os, stat, sys
from pathlib import Path
fd = os.open(sys.argv[1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
with os.fdopen(fd, 'rb') as source:
    info = os.fstat(source.fileno())
    assert stat.S_ISREG(info.st_mode) and 0 < info.st_size <= 256 * 1024
    Path(sys.argv[2]).write_bytes(source.read(256 * 1024 + 1))
PY
  openssl cms -decrypt -binary -inform DER -in "$temp/envelope" \
    -recip "$config/bootstrap/certificate.pem" -inkey "$config/bootstrap/key.pem" -out "$temp/material.json"
  python3 - "$temp" <<'PY'
import base64, json, sys
from pathlib import Path
root = Path(sys.argv[1]); data = json.loads((root / 'material.json').read_text())
for name in ('release.p12', 'password'):
    value = base64.b64decode(data[name], validate=True)
    if not 0 < len(value) <= 65536:
        raise ValueError('Invalid signing material length')
    (root / name).write_bytes(value)
PY
  actual=$(openssl pkcs12 -in "$temp/release.p12" -passin "file:$temp/password" -clcerts -nokeys |
    openssl x509 -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr '[:upper:]' '[:lower:]')
  [[ $actual == "$certificate" ]] || { echo 'Signing certificate does not match the installed app.' >&2; exit 1; }
  systemd-creds setup >/dev/null
  systemd-creds encrypt --with-key=host --name=release.p12 "$temp/release.p12" "$temp/release.p12.cred"
  systemd-creds encrypt --with-key=host --name=password "$temp/password" "$temp/password.cred"
  install -m600 -- "$temp/release.p12.cred" "$config/release.p12.cred"
  install -m600 -- "$temp/password.cred" "$config/password.cred"
  systemctl start cloud-workbench-release.service
  systemctl enable --now cloud-workbench-release.timer
  rm -f -- "$spool/import/credentials.cms" "$spool/import-cert.pem" "$config/bootstrap/key.pem" "$config/bootstrap/certificate.pem"
  rmdir -- "$config/bootstrap"
  echo 'Signing material loaded and release timer active.'
  exit
fi

[[ $# == 0 ]] || { echo 'Expected no argument or import.' >&2; exit 1; }
source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
for package in aapt apksigner openjdk-17-jre-headless; do
  if ! dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null | grep -qx installed; then
    apt-get install -y --no-install-recommends aapt apksigner openjdk-17-jre-headless
    break
  fi
done
if ! id "$signer" >/dev/null 2>&1; then
  useradd --system --user-group --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin "$signer"
fi
install -d -m755 -- "$runtime" "$runtime/ops" "$runtime/src" "$state" "$spool"
install -d -m700 -- "$config"
install -d -o root -g "$signer" -m750 -- "$state/approvals"
install -d -o "$signer" -g "$signer" -m700 -- "$state/requests"
install -d -o "$signer" -g xux -m2750 -- "$state/updates" "$state/receipts"
install -d -o root -g xux -m2770 -- "$spool/inbox"
chmod 2750 "$state/updates" "$state/receipts"
chmod 2770 "$spool/inbox"
install -m755 -- "$source_dir/release.py" "$operator"
install -m755 -- "$source_dir/install.sh" /usr/local/sbin/cloud-workbench-release-install
install -m644 -- "$source_dir/publish-app-update.mjs" "$runtime/ops/publish-app-update.mjs"
install -m644 -- "$source_dir/releases.mjs" "$runtime/src/releases.mjs"
install -m644 -- "$source_dir/cloud-workbench-release.service" "$source_dir/cloud-workbench-release.timer" /etc/systemd/system/

if [[ ! -f "$state/updates/current.json" ]]; then
  digest=$(python3 -c 'import json,re,sys; d=json.load(open(sys.argv[1]))["sha256"]; assert re.fullmatch(r"[0-9a-f]{64}", d); print(d)' "$old/current.json")
  info=$("$operator" verify-apk "$old/$digest.apk")
  python3 -c 'import json,sys; a=json.loads(sys.argv[1]); m=json.load(open(sys.argv[2])); assert (a["sha256"], a["version"]) == (m["sha256"], [m["versionCode"], m["versionName"]])' "$info" "$old/current.json"
  install -o "$signer" -g xux -m640 -- "$old/$digest.apk" "$state/updates/$digest.apk"
  install -o "$signer" -g xux -m640 -- "$old/current.json" "$state/updates/current.json"
fi

for file in main.mjs releases.mjs; do
  /usr/local/bin/node --check "$source_dir/$file"
  install -o xux -g xux -m600 -- "$source_dir/$file" "$gateway/src/.release-$file"
  mv -fT -- "$gateway/src/.release-$file" "$gateway/src/$file"
done
install -d -m755 /etc/systemd/system/cloud-workbench.service.d
printf '[Service]\nEnvironment=APP_UPDATES_DIR=%s/updates\n' "$state" >/etc/systemd/system/cloud-workbench.service.d/20-release-feed.conf
chmod 644 /etc/systemd/system/cloud-workbench.service.d/20-release-feed.conf
systemctl daemon-reload
/usr/local/sbin/cloud-workbench-ops restart-workbench

if [[ ! -f "$config/release.p12.cred" || ! -f "$config/password.cred" ]]; then
  install -d -m700 -- "$config/bootstrap"
  if [[ ! -f "$config/bootstrap/key.pem" || ! -f "$config/bootstrap/certificate.pem" ]]; then
    openssl req -x509 -newkey rsa:3072 -nodes -days 2 -subj '/CN=Cloud Workbench Release Import/' \
      -keyout "$config/bootstrap/key.pem" -out "$config/bootstrap/certificate.pem" 2>/dev/null
  fi
  install -m644 -- "$config/bootstrap/certificate.pem" "$spool/import-cert.pem"
  # Only the encrypted import envelope is placed in the operator-writable inbox.
  install -d -o root -g xux -m2770 "$spool/import"
  chmod 2770 "$spool/import"
  echo 'Ready for encrypted signing material import.'
fi
