#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
[[ $(id -un) == xux && $# -ge 1 ]]
mode=$1
live="$HOME/.local/lib/cloud-workbench"
data="$HOME/.local/share/cloud-workbench"
maintenance="$HOME/.local/share/cloud-workbench-maintenance"
marker="$data/transport-maintenance"
service=cloud-workbench.service
helper=/usr/local/sbin/cloud-workbench-ops
node=/usr/local/bin/node
files=(windows-pairing.mjs)
phase='preflight'
trap 'printf "Windows pairing flow update failed in %s at line %s (exit %s).\n" "$phase" "$LINENO" "$?" >&2' ERR

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
    local host=$1 path=$2 expected=$3 actual
    actual=$(curl -q --noproxy '*' -sS --connect-timeout 2 --max-time 5 \
        --resolve "$host:443:127.0.0.1" -o /dev/null -w '%{http_code}' "https://$host$path")
    [[ $actual == "$expected" ]] || {
        printf '%s%s expected %s, got %s.\n' "$host" "$path" "$expected" "$actual" >&2
        return 1
    }
}

smoke() {
    status workbench.qiwuli.com /v1/transport 200 &&
    status workbench.qiwuli.com /v1/app-update 401 &&
    status workbench.qiwuli.com /v1/events 401 &&
    status workbench.qiwuli.com /v3/transport 401 &&
    public_internal_denied
}

public_internal_denied() {
    local path code rc
    for path in /internal/pair-device /internal/windows-pairing; do
        rc=0
        code=$(curl -q --http1.1 --noproxy '*' -s --connect-timeout 2 --max-time 5 \
            --resolve workbench.qiwuli.com:443:127.0.0.1 -o /dev/null -w '%{http_code}' \
            "https://workbench.qiwuli.com$path") || rc=$?
        if [[ ! ( $rc == 52 && $code == 000 ) && ! ( $rc == 0 && $code == 404 ) ]]; then return 1; fi
    done
}

private_smoke() {
    "$node" --input-type=module - "$live/ops/ssh-mobile-token.mjs" "$1" "$2" <<'JS'
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
let check = 'SSH signer';
try {
  const signer = spawnSync(process.execPath, [process.argv[2]], { env: { SSH_ORIGINAL_COMMAND: 'workbench-ssh-session-v1' } });
  assert.equal(signer.status, 0);
  const { token } = JSON.parse(signer.stdout);
  assert(token.startsWith('sshm1.'));
  const retired = process.argv[3] === 'retired';
  const maintenance = process.argv[4] === 'maintenance';
  const get = (path, options = {}) => fetch('http://127.0.0.1:8766' + path, {
    ...options, headers: { Authorization: 'Bearer ' + token, ...options.headers }, signal: AbortSignal.timeout(5000),
  });
  check = 'Windows pairing list';
  const pairing = await get('/internal/windows-pairing');
  assert.equal(pairing.status, 200);
  assert.equal(pairing.headers.get('cache-control'), 'no-store');
  const { codes, devices } = await pairing.json();
  assert(Array.isArray(codes) && Array.isArray(devices));
  check = 'thread overview';
  const threads = await get('/v1/threads');
  assert.equal(threads.status, 200);
  assert(Array.isArray((await threads.json()).approvals));
  const denied = [
    ['/internal/pair-device', { method: 'POST' }, retired ? 404 : maintenance ? 503 : 401],
    ['/v1/device/renew', { method: 'POST' }, maintenance ? 503 : 401],
    ['/v1/threads', { headers: { 'X-Workbench-Ingress': 'https' } }, 401],
  ];
  for (const [path, options, expected] of denied) {
    check = path + ' expected HTTP ' + expected;
    const response = await get(path, options);
    assert.equal(response.status, expected);
    await response.arrayBuffer();
  }
  check = 'SSH events';
  const events = await get('/v1/events');
  assert.equal(events.status, 200);
  const reader = events.body.getReader();
  try { assert.match(Buffer.from((await reader.read()).value).toString(), /"type":"ready"/); }
  finally { await reader.cancel(); }
  check = 'SSH app update manifest';
  const manifest = await get('/v1/app-update');
  assert.equal(manifest.status, 200);
  const { update } = await manifest.json();
  assert(/^[a-f0-9]{64}$/.test(update?.sha256 ?? ''));
  check = 'SSH APK byte range';
  const bytes = await get('/v1/app-update/' + update.sha256 + '/apk', { headers: { Range: 'bytes=0-0' } });
  assert.equal(bytes.status, 206);
  assert.equal((await bytes.arrayBuffer()).byteLength, 1);
} catch {
  console.error('Read-only gateway smoke failed: ' + check);
  process.exitCode = 1;
}
JS
}

verify_base() {
    [[ -d "$live/src" && ! -L "$live/src" && -r "$live/ops/ssh-mobile-token.mjs" ]]
    (
        cd "$live"
        sha256sum -c - <<'SHA'
a29a27089c3a9b49a057c448e72bcd86c32610f6f517c4841a8d07fbde826cb0  src/windows-pairing.mjs
SHA
    )
}

verify_stage() {
    [[ $# == 1 ]]
    stage=$(realpath -- "$1")
    case "$stage" in "$maintenance"/stage.pairing-flow.*) ;; *) return 2 ;; esac
    (cd "$stage" && sha256sum -c SHA256SUMS)
    for file in "${files[@]}"; do "$node" --check "$stage/src/$file"; done
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
    local from=$1 name=$2
    tmp=$(mktemp "$live/src/.pairing-flow.XXXXXXXX")
    cp -p -- "$from" "$tmp"
    mv -T -- "$tmp" "$live/src/$name"
    tmp=
}

restore_files() {
    for file in "${files[@]}"; do copy_live "$backup/$file" "$file"; done
}

if [[ $mode == check && $# == 2 ]]; then
    verify_base
    verify_stage "$2"
    smoke
    private_smoke retired normal
    printf 'Windows pairing flow update preflight passed.\n'
    exit
fi
if [[ $mode == check-live && $# == 2 ]]; then
    verify_stage "$2"
    for file in "${files[@]}"; do cmp -s "$live/src/$file" "$stage/src/$file"; done
    [[ ! -e "$marker" ]]
    smoke
    private_smoke retired normal
    printf 'Windows pairing flow update verified outside maintenance.\n'
    exit
fi

[[ $# == 2 && ( $mode == deploy || $mode == rollback ) ]]
exec 9>"$maintenance/.deploy.lock"
flock -n 9
owned_marker=false
restore_on_failure=false
tmp=
cleanup() {
    local rc=$? current
    if [[ -n $tmp ]]; then rm -f -- "$tmp"; fi
    if (( rc != 0 )) && "$restore_on_failure"; then
        restore_files
        current=$(pid)
        if [[ "$current" != "$prior" || "$current" == 0 ]]; then
            if ! sudo -n "$helper" restart-workbench || ! wait_restart "$current"; then
                printf 'Previous files restored; verify the gateway manually. Backup: %s\n' "$backup" >&2
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
    phase='validate and restore the previous gateway files'
    backup=$(realpath -- "$2")
    case "$backup" in "$maintenance"/previous.pairing-flow.*) ;; *) exit 2 ;; esac
    (cd "$live" && sha256sum -c "$backup/installed.sha256")
    (cd "$backup" && sha256sum -c original.sha256)
    restore_files
    sudo -n "$helper" restart-workbench
    wait_restart "$prior"
    private_smoke retired maintenance
    printf 'Previous gateway restored from %s\n' "$backup"
    exit
fi

verify_base
verify_stage "$2"
smoke
private_smoke retired maintenance
phase='back up and install the pairing module'
backup=$(mktemp -d "$maintenance/previous.pairing-flow.XXXXXXXX")
cp -p "$live/src/windows-pairing.mjs" "$backup/"
(cd "$backup" && sha256sum windows-pairing.mjs > original.sha256)
(cd "$stage" && sha256sum src/windows-pairing.mjs > "$backup/installed.sha256")
restore_on_failure=true
for file in "${files[@]}"; do copy_live "$stage/src/$file" "$file"; done
phase='restart the idle gateway and verify legacy and private endpoints'
sudo -n "$helper" restart-workbench
wait_restart "$prior"
private_smoke retired maintenance
restore_on_failure=false
printf 'Windows pairing flow updated. Rollback: %s\n' "$backup"
