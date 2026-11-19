#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
cd -- "$(dirname -- "$0")"
domain=workbench.qiwuli.com

probe() {
    local url=$1 addr=$2 expected=$3 status rc=0
    shift 3
    status=$(curl -q --noproxy '*' -s --http1.1 --connect-timeout 3 --max-time 8 \
        --resolve "$addr" -o /dev/null -w '%{http_code}' "$@" "$url") || rc=$?
    if [[ $expected == close && $status == 000 && ( $rc == 52 || $rc == 56 ) ]]; then return; fi
    if [[ $rc == 0 && $status == "$expected" ]]; then return; fi
    printf '%s: expected %s, observed HTTP %s (curl %s)\n' "$url" "$expected" "$status" "$rc" >&2
    return 1
}

smoke() {
    local tls="$domain:443:127.0.0.1" url="https://$domain" path headers
    for path in / /admin /nginx_status /internal/windows-pairing /v1/hosts /v3/unknown; do
        probe "$url$path" "$tls" close || return 1
    done
    probe "http://$domain/" "$domain:80:127.0.0.1" close || return 1
    probe "http://$domain/.well-known/acme-challenge/absent-workbench-probe" "$domain:80:127.0.0.1" 404 || return 1
    probe "$url/v1/transport" "$tls" 200 || return 1
    probe "$url/v1/threads?limit=1" "$tls" 401 || return 1
    probe "$url/v1/app-update" "$tls" 401 || return 1
    probe "$url/v3/transport" "$tls" 401 || return 1
    probe "$url/v3/probe/local/latency" "$tls" 401 || return 1
    probe "$url/v3/device/enroll" "$tls" 400 -H 'Content-Type: application/json' -d '{}' || return 1
    probe "$url/v3/device/unbind" "$tls" 400 -H 'Content-Type: application/json' -d '{}' || return 1
    probe "$url/v3/tunnel" "$tls" 401 -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
        -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' || return 1
    probe https://qiwuli.com/ qiwuli.com:443:127.0.0.1 200 || return 1
    probe https://www.qiwuli.com/ www.qiwuli.com:443:127.0.0.1 308 || return 1
    headers=$(curl -q --noproxy '*' -sf --max-time 8 --resolve "$tls" -D - -o /dev/null "$url/v1/transport") || return 1
    if grep -Eiq '^server:.*(nginx/|ubuntu)' <<< "$headers"; then return 1; fi
}

if [[ ${1:-} == smoke && $# == 1 ]]; then smoke; exit; fi
[[ $EUID == 0 && ${1:-} == apply && $# == 1 ]]
dir=/var/lib/cloud-workbench-ingress
install -d -m 700 "$dir"
exec 9>"$dir/deploy.lock"
flock -n 9
sha256sum -c SHA256SUMS
sha256sum -c BASELINE

paths=(/etc/nginx/conf.d/workbench-http.conf /etc/nginx/conf.d/workbench-https.conf /opt/cloud-workbench-v3/workbench-v3.locations)
backup=$(mktemp -d "$dir/previous.XXXXXXXX")
for path in "${paths[@]}"; do
    [[ -f "$path" && ! -L "$path" ]]
    cp -p -- "$path" "$backup/"
done

replace() {
    local source=$1 dest=$2 temp
    temp=$(mktemp "$dest.stage.XXXXXXXX")
    install -m 644 "$source" "$temp"
    mv -Tf -- "$temp" "$dest"
}
recover() {
    local code=$? path
    if (( code == 0 )); then return 0; fi
    printf 'Ingress update failed, restoring %s\n' "$backup" >&2
    for path in "${paths[@]}"; do replace "$backup/${path##*/}" "$path"; done
    nginx -t && systemctl reload nginx
}
trap recover EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for path in "${paths[@]}"; do replace "${path##*/}" "$path"; done
nginx -t
systemctl reload nginx
for _ in {1..5}; do
    if smoke; then
        printf 'Workbench ingress hardened. Previous configuration: %s\n' "$backup"
        exit 0
    fi
    sleep 1
done
exit 1
