#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 || ! $1 =~ ^--(check|deploy)$ || ! $2 =~ ^[0-9a-f]{40}$ ]]; then
  echo '用法: deploy-gateway.sh --check|--deploy <GitHub main 完整提交SHA>' >&2
  exit 2
fi
mode=$1
commit=$2
repo=$(git -C "$(dirname -- "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)

require_main() {
  local head
  head=$(git ls-remote --exit-code https://github.com/locy016/CloudWorkbench.git refs/heads/main)
  if [[ ${head%%$'\t'*} != "$commit" ]]; then
    echo '指定提交已不是 GitHub main 当前版本，请先同步后重新检查' >&2
    exit 1
  fi
}

require_main
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
git -C "$repo" archive "$commit" android/server | tar -xf - -C "$stage"
candidate="$stage/android/server"
(cd "$candidate" && npm ci --ignore-scripts && npm test)
if [[ $mode == --check ]]; then
  printf '网关检查通过 %s\n' "$commit"
  exit 0
fi

require_main
runtime="$HOME/.local/lib/cloud-workbench"
if [[ ! -d "$runtime/src" || ! -f "$runtime/ops/ssh-mobile-token.mjs" ]]; then
  echo '生产网关尚未完成管理员初始化' >&2
  exit 1
fi
exec 9>"$runtime/.deploy.lock"
flock -n 9

swap=$(mktemp -d "$runtime/.deploy.XXXXXXXX")
parts=(src node_modules package.json package-lock.json)
installed=()
prev="$swap/previous"
accepted=0
finish() {
  local part
  if [[ $accepted == 0 ]]; then
    for part in "${installed[@]}"; do mv -T -- "$runtime/$part" "$swap/staged/$part" || return 1; done
    for part in "${parts[@]}"; do
      if [[ -e "$prev/$part" ]]; then mv -T -- "$prev/$part" "$runtime/$part" || return 1; fi
    done
  fi
  rm -rf -- "$stage" "$swap"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -- "$prev" "$swap/staged"
for part in "${parts[@]}"; do mv -T -- "$candidate/$part" "$swap/staged/$part"; done
for part in "${parts[@]}"; do
  mv -T -- "$runtime/$part" "$prev/$part"
  mv -T -- "$swap/staged/$part" "$runtime/$part"
  installed+=("$part")
done
sudo -n /usr/local/sbin/cloud-workbench-ops restart-workbench
accepted=1
printf '网关版本 %s 已安装，等待空闲重启。\n' "$commit"
