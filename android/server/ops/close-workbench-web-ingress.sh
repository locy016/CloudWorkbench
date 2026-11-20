#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[[ $EUID == 0 ]]
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
exec 9>/run/cloud-workbench-ops.lock
flock -n 9

http=/etc/nginx/conf.d/workbench-http.conf
https=/etc/nginx/conf.d/workbench-https.conf
for config in "$http" "$https"; do
    [[ -f $config && ! -L $config ]]
    grep -qE '^[[:space:]]*server_name workbench[.]qiwuli[.]com;' "$config"
done

stage=$(mktemp -d /etc/nginx/.workbench-web-cutoff.XXXXXXXX)
changed=false
finish() {
    local status=$?
    if (( status != 0 )) && "$changed"; then
        if ! { cp -p -- "$stage/workbench-http.conf" "$http" &&
            cp -p -- "$stage/workbench-https.conf" "$https" &&
            nginx -t && systemctl reload nginx; }; then
            printf '无法恢复 Nginx 配置，临时副本位于 %s\n' "$stage" >&2
            return "$status"
        fi
    fi
    rm -f -- "$stage/workbench-http.conf" "$stage/workbench-https.conf"
    rmdir -- "$stage"
    return "$status"
}
trap finish EXIT

cp -p -- "$http" "$https" "$stage/"
changed=true
install -m 644 -- workbench-http.conf "$http"
install -m 644 -- workbench-https.conf "$https"
nginx -t
systemctl reload nginx
systemctl is-active --quiet nginx

probe() {
    local http_rc=0 tls_rc=0
    curl -q --noproxy '*' -s --max-time 5 --resolve workbench.qiwuli.com:80:127.0.0.1 \
        -o /dev/null http://workbench.qiwuli.com/ || http_rc=$?
    curl -q --noproxy '*' -s --max-time 5 --resolve workbench.qiwuli.com:443:127.0.0.1 \
        -o /dev/null https://workbench.qiwuli.com/ || tls_rc=$?
    [[ $http_rc == 52 || $http_rc == 56 ]] && [[ $tls_rc == 35 ]] &&
        curl -q --noproxy '*' -fsS --max-time 5 --resolve qiwuli.com:443:127.0.0.1 \
            -o /dev/null https://qiwuli.com/
}
for attempt in {1..5}; do
    if probe; then
        printf '工作台 HTTP 和 TLS 入口已关闭；栖物里 HTTPS 验证通过。\n'
        exit 0
    fi
    sleep 1
done
printf '入口状态核对失败。\n' >&2
exit 1
