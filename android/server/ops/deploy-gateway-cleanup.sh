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
files=(gateway.mjs releases.mjs)
phase='preflight'
trap 'printf "Gateway cleanup deployment failed in %s at line %s (exit %s).\n" "$phase" "$LINENO" "$?" >&2' ERR

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
    status workbench.qiwuli.com /v3/transport 401 &&
    status workbench.qiwuli.com /internal/windows-pairing 404
}

private_smoke() {
    "$node" --input-type=module - "$data/access.token" "$1" <<'JS'
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
try {
  const headers = { Authorization: 'Bearer ' + readFileSync(process.argv[2], 'utf8').trim() };
  const cleaned = process.argv[3] === 'cleaned';
  const get = (path, extra = {}) => fetch('http://127.0.0.1:8766' + path, {
    headers: { ...headers, ...extra }, signal: AbortSignal.timeout(5000),
  });
  const pairing = await get('/internal/windows-pairing');
  assert.equal(pairing.status, 200);
  assert.equal(pairing.headers.get('cache-control'), 'no-store');
  const { codes, devices } = await pairing.json();
  assert(Array.isArray(codes) && Array.isArray(devices));
  const threads = await get('/v1/threads?include=approvals');
  assert.equal(threads.status, 200);
  assert(Array.isArray((await threads.json()).approvals));
  const summary = await get('/v1/threads');
  assert.equal(summary.status, 200);
  assert.equal(Array.isArray((await summary.json()).approvals), cleaned);
  const page = await get('/v1/threads?limit=1&include=approvals');
  assert.equal(page.status, 200);
  assert.equal(Object.hasOwn(await page.json(), 'approvals'), false);
  for (const path of ['/v1/hosts', '/v1/upgrades', '/v1/releases']) {
    const response = await get(path);
    assert.equal(response.status, cleaned ? 404 : 200);
    await response.arrayBuffer();
  }
  const manifest = await get('/v1/app-update');
  assert.equal(manifest.status, 200);
  const { update } = await manifest.json();
  assert(/^[a-f0-9]{64}$/.test(update?.sha256 ?? ''));
  const bytes = await get('/v1/app-update/' + update.sha256 + '/apk', { Range: 'bytes=0-0' });
  assert.equal(bytes.status, 206);
  assert.equal((await bytes.arrayBuffer()).byteLength, 1);
} catch {
  console.error('Authenticated read-only gateway smoke failed');
  process.exitCode = 1;
}
JS
}

verify_base() {
    [[ -d "$live/src" && ! -L "$live/src" && -r "$live/src/windows-pairing.mjs" ]]
    (
        cd "$live"
        sha256sum -c - <<'SHA'
9b9f0a0cc48a419f11c66b41f80c56983bf7f585a591fe0b32612c57bff14cba  src/gateway.mjs
4567f6a3f7e781466ef69fb9f7ee1c91c682d1b7dfd2752cdcb7c6a1c9a35980  src/releases.mjs
SHA
    )
}

verify_stage() {
    [[ $# == 1 ]]
    stage=$(realpath -- "$1")
    case "$stage" in "$maintenance"/stage.gateway-cleanup.*) ;; *) return 2 ;; esac
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
    tmp=$(mktemp "$live/src/.gateway-cleanup.XXXXXXXX")
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
    private_smoke baseline
    printf 'Gateway cleanup preflight passed.\n'
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
    case "$backup" in "$maintenance"/previous.gateway-cleanup.*) ;; *) exit 2 ;; esac
    (cd "$live" && sha256sum -c "$backup/installed.sha256")
    (cd "$backup" && sha256sum -c original.sha256)
    restore_files
    sudo -n "$helper" restart-workbench
    wait_restart "$prior"
    private_smoke baseline
    printf 'Previous gateway restored from %s\n' "$backup"
    exit
fi

verify_base
verify_stage "$2"
smoke
private_smoke baseline
phase='back up and install the two gateway files'
backup=$(mktemp -d "$maintenance/previous.gateway-cleanup.XXXXXXXX")
cp -p "$live/src/gateway.mjs" "$live/src/releases.mjs" "$backup/"
(cd "$backup" && sha256sum gateway.mjs releases.mjs > original.sha256)
(cd "$stage" && sha256sum src/gateway.mjs src/releases.mjs > "$backup/installed.sha256")
restore_on_failure=true
for file in "${files[@]}"; do copy_live "$stage/src/$file" "$file"; done
phase='restart the idle gateway and verify legacy and private endpoints'
sudo -n "$helper" restart-workbench
wait_restart "$prior"
private_smoke cleaned
restore_on_failure=false
printf 'Gateway cleanup installed. Rollback: %s\n' "$backup"
