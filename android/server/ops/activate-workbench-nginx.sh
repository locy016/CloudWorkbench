#!/usr/bin/env bash
set -euo pipefail
# Run from a trusted copy of server/ops as root once the public A record exists.
[[ $EUID == 0 ]]
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
domain=workbench.qiwuli.com
http=/etc/nginx/conf.d/workbench-http.conf
https=/etc/nginx/conf.d/workbench-https.conf
[[ ! -e "$http" && ! -e "$https" ]]
[[ $(curl -q --noproxy '*' --fail --silent --show-error --max-time 5 -H 'X-Workbench-Ingress: https' http://127.0.0.1:8766/v1/transport) == '{"protocol":1}' ]]
[[ $(dig +time=3 +tries=1 +short "$domain" A @1.1.1.1) == 43.110.34.152 ]]
[[ -z $(dig +time=3 +tries=1 +short "$domain" AAAA @1.1.1.1) ]]

install -d -m 755 /var/www/workbench-acme
cert_hook=/etc/letsencrypt/renewal-hooks/deploy/90-reload-workbench-nginx
[[ ! -e "$cert_hook" ]]
stage=$(mktemp -d /etc/nginx/.workbench-stage.XXXXXXXX)
installed_http=false
installed_https=false
installed_hook=false
rollback() {
    local status=$?
    if (( status != 0 )); then
        if "$installed_https"; then mv "$https" "$stage/workbench-https.conf"; fi
        if "$installed_http"; then mv "$http" "$stage/workbench-http.conf"; fi
        if "$installed_hook"; then mv "$cert_hook" "$stage/renewal-hook"; fi
        nginx -t && systemctl reload nginx
        printf 'Routing restored. Staged files: %s\n' "$stage" >&2
    else
        rmdir "$stage"
    fi
}
trap rollback EXIT

install -m 644 workbench-http.conf "$stage/workbench-http.conf"
mv "$stage/workbench-http.conf" "$http"
installed_http=true
nginx -t
systemctl reload nginx
certbot certonly --non-interactive --webroot -w /var/www/workbench-acme --cert-name "$domain" -d "$domain"
install -m 644 workbench-https.conf "$stage/workbench-https.conf"
mv "$stage/workbench-https.conf" "$https"
installed_https=true
nginx -t
systemctl reload nginx

probe() {
    local domain=$1 expected=$2 path=$3 status rc=0
    status=$(curl -q --noproxy '*' --silent --http1.1 --max-time 10 --resolve "$domain:443:127.0.0.1" \
        --output /dev/null --write-out '%{http_code}' "https://$domain$path") || rc=$?
    if [[ $expected == close && $status == 000 && ( $rc == 52 || $rc == 56 ) ]]; then return 0; fi
    [[ $status == "$expected" ]]
}
# Allow new workers to take over the listening sockets.
for _ in {1..5}; do
    if probe "$domain" 200 /v1/transport; then break; fi
    sleep 1
done
probe "$domain" 200 /v1/transport
probe "$domain" 401 /v1/threads
probe "$domain" close /internal/pair-device
probe qiwuli.com 200 /
probe www.qiwuli.com 308 /

cat > "$stage/renewal-hook" <<'SH'
#!/bin/sh
if [ "${RENEWED_LINEAGE:-}" = /etc/letsencrypt/live/workbench.qiwuli.com ]; then
    /usr/sbin/nginx -t && /usr/bin/systemctl reload nginx
fi
SH
chmod 755 "$stage/renewal-hook"
mv "$stage/renewal-hook" "$cert_hook"
installed_hook=true
printf 'Workbench HTTPS enabled. Qiwuli HTTPS and redirect verified.\n'
