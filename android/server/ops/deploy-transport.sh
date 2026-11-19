#!/usr/bin/env bash
set -euo pipefail
umask 077
tag=${1:?usage: deploy-transport.sh <staging-directory>}
stage=$(realpath -- "$tag")
case "$stage" in "$HOME"/.local/share/cloud-workbench-maintenance/stage.*) ;; *) exit 2 ;; esac
live="$HOME/.local/lib/cloud-workbench"
data="$HOME/.local/share/cloud-workbench"
maintenance="$HOME/.local/share/cloud-workbench-maintenance"
sudo_helper=/usr/local/sbin/cloud-workbench-ops
node=/usr/local/bin/node
service=cloud-workbench.service
marker="$data/transport-maintenance"
exec 9>"$maintenance/.deploy.lock"
flock -n 9

idle() {
    python3 - "$data/workbench.db" <<'PY'
import sqlite3, sys
with sqlite3.connect('file:' + sys.argv[1] + '?mode=ro', uri=True) as db:
    active = db.execute("SELECT count(*) FROM threads WHERE status IN ('starting','running','approval')").fetchone()[0]
    if active:
        raise SystemExit(f'There are {active} active tasks; deployment skipped')
PY
}

unless_changed() {
    local old=$1
    for _ in {1..75}; do
        local current
        current=$(systemctl show "$service" -p MainPID --value)
        if [[ "$current" != 0 && "$current" != "$old" ]] && systemctl is-active --quiet "$service"; then
            return
        fi
        sleep 1
    done
    return 1
}

[[ -d "$stage/src" && -r "$stage/package-lock.json" && -r "$stage/ops/workbench-devices.mjs" ]]
[[ -d "$live/src" && ! -L "$live" ]]
cd "$stage"
if [[ ! -d node_modules/ws ]]; then
    /usr/local/bin/npm ci --omit=optional --ignore-scripts --no-fund --no-audit --cache "$maintenance/npm-cache"
fi
"$node" --test test/*.test.mjs
idle

backup=$(mktemp -d "$maintenance/previous.XXXXXXXX")
printf 'Rollback files: %s\n' "$backup"
cp -a "$live/." "$backup/program"
python3 - "$data/workbench.db" "$backup/workbench.db" <<'PY'
import sqlite3, sys
with sqlite3.connect('file:' + sys.argv[1] + '?mode=ro', uri=True) as db, sqlite3.connect(sys.argv[2]) as backup:
    db.backup(backup)
PY
if [[ -f "$data/app-updates/current.json" ]]; then
    cp -p "$data/app-updates/current.json" "$backup/current.json"
fi

old_pid=$(systemctl show "$service" -p MainPID --value)
restore=false
owns_marker=false
cleanup() {
    if "$restore"; then
        current_pid=$(systemctl show "$service" -p MainPID --value)
        previous_code="$backup/live-src"
        if [[ -d "$previous_code" ]]; then
            mv "$live/src" "$backup/rejected-src" || true
            mv "$previous_code" "$live/src" || true
        fi
        for file in package.json package-lock.json ops/workbench-devices.mjs; do
            if [[ -f "$backup/program/$file" ]]; then
                install -D -m 600 "$backup/program/$file" "$live/$file" || true
            elif [[ -f "$live/$file" ]]; then
                mv "$live/$file" "$backup/rejected-${file##*/}" || true
            fi
        done
        if [[ "$current_pid" != "$old_pid" || "$current_pid" == 0 ]]; then
            sudo -n "$sudo_helper" restart-workbench || true
            unless_changed "$current_pid" || true
        fi
    fi
    if "$owns_marker"; then unlink "$marker" || true; fi
}
trap cleanup EXIT
if (set -o noclobber; : > "$marker") 2>/dev/null; then owns_marker=true; else exit 2; fi
idle
restore=true
if [[ ! -d "$live/node_modules" ]]; then mkdir "$live/node_modules"; fi
if [[ -d "$live/node_modules/ws" ]] &&
    [[ $("$node" -p "require('$live/node_modules/ws/package.json').version") != 8.23.0 ]]; then
    echo 'Unexpected live WebSocket dependency version' >&2
    exit 1
fi
if [[ ! -d "$live/node_modules/ws" ]]; then cp -a node_modules/ws "$live/node_modules/ws"; fi
"$node" --input-type=module -e "import ws from '$live/node_modules/ws/wrapper.mjs'; if (!ws) process.exit(1)"
install -m 600 package.json "$live/package.json"
install -m 600 package-lock.json "$live/package-lock.json"
install -D -m 600 ops/workbench-devices.mjs "$live/ops/workbench-devices.mjs"
cp -a src "$backup/new-src"
mv "$live/src" "$backup/live-src"
mv "$backup/new-src" "$live/src"
sudo -n "$sudo_helper" restart-workbench
unless_changed "$old_pid"
for _ in {1..15}; do
    result=$(curl -q --noproxy '*' --connect-timeout 1 --max-time 2 -sS -o /dev/null -w '%{response_code}' -H 'X-Workbench-Ingress: https' http://127.0.0.1:8766/v1/transport || true)
    if [[ "$result" == 200 ]]; then
        restore=false
        printf 'Gateway transport ready. Existing clients can continue to use SSH.\n'
        exit 0
    fi
    sleep 1
done
exit 1
