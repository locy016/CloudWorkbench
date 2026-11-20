#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[[ $(id -u) == 0 ]]
exec 9>/run/cloud-workbench-ops.lock
flock -n 9

systemctl is-active --quiet nginx
systemctl is-active --quiet cloud-workbench
[[ -z $(ss -H -ltn '( sport = :8768 or sport = :18770 or sport = :18771 or sport = :18772 )') ]]
for unit in cloud-workbench-v3-core cloud-workbench-v3-ingress cloud-workbench-enrollment; do
    [[ $(systemctl show "$unit" -p LoadState --value) == not-found ]]
done
if nginx -T 2>/dev/null | grep -E '/opt/cloud-workbench-(v3|enrollment)/|/var/www/cloud-workbench-portal/' >/dev/null; then
    echo 'Nginx 仍引用退役组件' >&2
    exit 1
fi

shopt -s nullglob
retired=(
    /var/backups/cloud-workbench-authorization
    /var/backups/cloud-workbench-mihomo
    /var/backups/cloud-workbench-portal
    /var/backups/cloud-workbench-private-import
    /var/backups/cloud-workbench-retirement.*
    /var/backups/workbench-retired.*
    /etc/nginx/conf.d/.workbench-v3.*
)
for path in "${retired[@]}"; do
    [[ -e $path || -L $path ]] || continue
    rm --recursive --force --one-file-system -- "$path"
    [[ ! -e $path && ! -L $path ]]
    printf '已清理 %s\n' "$path"
done

if nft list table inet cloud_workbench_v3 >/dev/null 2>&1; then
    nft delete table inet cloud_workbench_v3
fi
if getent passwd workbench-v3-core >/dev/null; then
    account=$(getent passwd workbench-v3-core)
    [[ $account == *:/nonexistent:/usr/sbin/nologin ]]
    if pgrep -u workbench-v3-core >/dev/null; then
        echo '退役账号仍有进程' >&2
        exit 1
    fi
    userdel workbench-v3-core
fi

nginx -t
[[ $(sysctl -n net.ipv4.ip_forward) == 0 ]]
[[ $(sysctl -n net.ipv6.conf.all.forwarding) == 0 ]]
if nft list table inet cloud_workbench_v3 >/dev/null 2>&1; then exit 1; fi
printf '退役规则、账号及管理员归档清理完成\n'
