#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
[[ $(id -un) == xux && $# == 2 ]]
mode=$1
live="$HOME/.local/lib/cloud-workbench"
data="$HOME/.local/share/cloud-workbench"
maintenance="$HOME/.local/share/cloud-workbench-maintenance"
authorized="$HOME/.ssh/authorized_keys"
marker="$data/transport-maintenance"
service=cloud-workbench.service
helper=/usr/local/sbin/cloud-workbench-ops
node=/usr/local/bin/node
stage=$(realpath -- "$(dirname -- "${BASH_SOURCE[0]}")/..")
keytool="$stage/ops/ssh-mobile-authorized-key.py"
public="$stage/ops/mobile-device.pub"
existing=(src/gateway.mjs src/main.mjs src/browser-tunnel.mjs src/windows-pairing.mjs)
added=(src/mobile-session.mjs ops/ssh-mobile-token.mjs)
files=("${existing[@]}" "${added[@]}")
phase=preflight
trap 'printf "SSH mobile deployment failed in %s at line %s (exit %s).\n" "$phase" "$LINENO" "$?" >&2' ERR
case "$stage" in "$maintenance"/stage.ssh-mobile.*) ;; *) exit 2 ;; esac

key() { python3 -B "$keytool" "$1" "$authorized" "$public"; }
idle() {
    python3 - "$data/workbench.db" <<'PY'
import sqlite3, sys
with sqlite3.connect('file:' + sys.argv[1] + '?mode=ro', uri=True) as db:
    n = db.execute("SELECT count(*) FROM threads WHERE status IN ('starting','running','approval')").fetchone()[0]
    if n:
        raise SystemExit(f'{n} active task(s); gateway restart skipped')
PY
}

status() {
    local path=$1 expected=$2 actual
    actual=$(curl -q --noproxy '*' -sS --connect-timeout 2 --max-time 5 \
        --resolve workbench.qiwuli.com:443:127.0.0.1 -o /dev/null -w '%{http_code}' "https://workbench.qiwuli.com$path")
    [[ $actual == "$expected" ]]
}

smoke() {
    status /v1/transport 200 &&
    status /v1/app-update 401 &&
    status /v3/transport 401 &&
    public_pairing_denied
}

public_pairing_denied() {
    local code rc=0
    code=$(curl -q --http1.1 --noproxy '*' -s --connect-timeout 2 --max-time 5 \
        --resolve workbench.qiwuli.com:443:127.0.0.1 -o /dev/null -w '%{http_code}' \
        https://workbench.qiwuli.com/internal/windows-pairing) || rc=$?
    # The production Nginx catch-all uses 444, observed as an empty HTTP/1.1 reply.
    [[ ( $rc == 52 && $code == 000 ) || ( $rc == 0 && $code == 404 ) ]]
}

mobile_smoke() {
    "$node" --input-type=module - "$live/ops/ssh-mobile-token.mjs" <<'JS'
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
try {
  const helper = process.argv[2];
  for (const command of ['', 'workbench-ssh-session-v1 extra', 'uname']) {
    const rejected = spawnSync(process.execPath, [helper], { env: { SSH_ORIGINAL_COMMAND: command } });
    assert.equal(rejected.status, 1);
    assert.equal(rejected.stdout.length, 0);
  }
  const allowed = spawnSync(process.execPath, [helper], { env: { SSH_ORIGINAL_COMMAND: 'workbench-ssh-session-v1' } });
  assert.equal(allowed.status, 0);
  const { token, expiresAt } = JSON.parse(allowed.stdout);
  assert(token.startsWith('sshm1.') && expiresAt - Date.now() > 3.9 * 3600 * 1000);
  const authorization = 'Bearer ' + token;
  const get = (path, extra = {}) => fetch('http://127.0.0.1:8766' + path, {
    headers: { Authorization: authorization, ...extra }, signal: AbortSignal.timeout(5000),
  });
  for (const path of ['/v1/threads', '/v1/app-update', '/internal/windows-pairing']) {
    const response = await get(path);
    assert.equal(response.status, 200);
    await response.arrayBuffer();
  }
  const denied = await get('/v1/threads', { 'X-Workbench-Ingress': 'https' });
  assert.equal(denied.status, 401);
  await denied.arrayBuffer();
  const events = await get('/v1/events');
  assert.equal(events.status, 200);
  const reader = events.body.getReader();
  try { assert.match(Buffer.from((await reader.read()).value).toString(), /"type":"ready"/); }
  finally { await reader.cancel(); }
} catch {
  console.error('Local SSH mobile session smoke failed (credentials omitted)');
  process.exitCode = 1;
}
JS
}

verify_base() {
    [[ -d "$live/src" && ! -L "$live/src" && -d "$live/ops" && ! -L "$live/ops" ]]
    for file in "${added[@]}"; do [[ ! -e "$live/$file" && ! -L "$live/$file" ]]; done
    (
        cd "$live"
        sha256sum -c - <<'SHA'
4a3cb8436ba426e392c3cf62279ac4b1aee82f525ed92f69eddf892c10211006  src/gateway.mjs
c2b69d8894a60b93ccb64eccd25a7fc70b65151973d2e0ad19ed521afb6b9f5f  src/main.mjs
038e90d759c4da44781cb26989ce8b2439ef0835ae2f498d864d0f02d395a783  src/browser-tunnel.mjs
9954a4fd2ac2d2a38fc24351344dea302eb100261ccd41cd7e491a0448c016fd  src/windows-pairing.mjs
SHA
    )
}

verify_stage() {
    (cd "$stage" && sha256sum -c SHA256SUMS)
    for file in "${files[@]}"; do "$node" --check "$stage/$file"; done
    sudo -n -l "$helper" restart-workbench >/dev/null
}

pid() { systemctl show "$service" -p MainPID --value; }
wait_restart() {
    local prior=$1 current
    for _ in {1..85}; do
        current=$(pid)
        if [[ "$current" != 0 && "$current" != "$prior" ]] &&
            systemctl is-active --quiet "$service" && smoke; then return 0; fi
        sleep 1
    done
    return 1
}

copy_live() {
    local from=$1 file=$2
    tmp=$(mktemp "$(dirname -- "$live/$file")/.ssh-mobile.XXXXXXXX")
    cp -p -- "$from" "$tmp"
    mv -T -- "$tmp" "$live/$file"
    tmp=
}

restore_files() {
    local file
    for file in "${existing[@]}"; do copy_live "$backup/original/$file" "$file"; done
    for file in "${added[@]}"; do
        if [[ -f "$live/$file" ]]; then
            mv -T -- "$live/$file" "$backup/rejected/${file##*/}"
        fi
    done
}

verify_stage
if [[ $mode == check && $(realpath -- "$2") == "$stage" ]]; then
    verify_base
    key check-disabled
    smoke
    printf 'SSH mobile preflight passed (single dedicated key matched).\n'
    exit
fi

[[ $mode == deploy || $mode == rollback ]]
exec 9>"$maintenance/.deploy.lock"
flock -n 9
owned_marker=false
restore_on_failure=false
key_switched=false
tmp=
cleanup() {
    local rc=$? current
    if [[ -n $tmp ]]; then rm -f -- "$tmp"; fi
    if (( rc != 0 )) && "$restore_on_failure"; then
        if "$key_switched"; then key disable; fi
        restore_files
        current=$(pid)
        if [[ "$current" != "$prior" || "$current" == 0 ]]; then
            if ! sudo -n "$helper" restart-workbench || ! wait_restart "$current"; then
                printf 'Previous gateway restored on disk; verify service. Backup: %s\n' "$backup" >&2
            fi
        fi
    fi
    if "$owned_marker"; then unlink "$marker"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
idle
if (set -o noclobber; : > "$marker") 2>/dev/null; then owned_marker=true; else exit 2; fi
idle
prior=$(pid)

if [[ $mode == rollback ]]; then
    phase='validate and restore the previous gateway and forced command'
    backup=$(realpath -- "$2")
    case "$backup" in "$maintenance"/previous.ssh-mobile.*) ;; *) exit 2 ;; esac
    (cd "$live" && sha256sum -c "$backup/installed.sha256")
    (cd "$backup/original" && sha256sum -c "$backup/original.sha256")
    key disable
    restore_files
    sudo -n "$helper" restart-workbench
    wait_restart "$prior"
    printf 'Previous gateway and mobile forced command restored.\n'
    exit
fi

[[ $(realpath -- "$2") == "$stage" ]]
verify_base
key check-disabled
smoke
phase='back up program files and the authorized key file'
backup=$(mktemp -d "$maintenance/previous.ssh-mobile.XXXXXXXX")
mkdir -p "$backup/original/src" "$backup/rejected"
cp -p -- "$authorized" "$backup/authorized_keys.before"
for file in "${existing[@]}"; do cp -p -- "$live/$file" "$backup/original/$file"; done
(cd "$backup/original" && sha256sum "${existing[@]}" > "$backup/original.sha256")
(cd "$stage" && sha256sum "${files[@]}" > "$backup/installed.sha256")
printf 'Rollback files: %s\n' "$backup"
phase='create or verify the private signing key'
"$node" --input-type=module - "$stage/src/mobile-session.mjs" "$data/ssh-mobile-session.key" <<'JS'
import { randomBytes } from 'node:crypto';
import { closeSync, constants, fsyncSync, openSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
const { readMobileKey } = await import(pathToFileURL(process.argv[2]));
const file = process.argv[3];
let fd;
try { fd = openSync(file, constants.O_NOFOLLOW | constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL, 0o600); }
catch (error) { if (error.code !== 'EEXIST') throw error; }
if (fd !== undefined) {
  const secret = randomBytes(32);
  try { writeFileSync(fd, secret); fsyncSync(fd); }
  finally { secret.fill(0); closeSync(fd); }
}
readMobileKey(file).fill(0);
JS
phase='install and verify the idle gateway before changing SSH'
restore_on_failure=true
for file in "${files[@]}"; do copy_live "$stage/$file" "$file"; done
sudo -n "$helper" restart-workbench
wait_restart "$prior"
mobile_smoke
phase='enable the exact mobile SSH forced command'
key enable
key_switched=true
key check-enabled
restore_on_failure=false
printf 'SSH mobile session enabled. Rollback: %s\n' "$backup"
