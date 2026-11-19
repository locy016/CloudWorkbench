# 2026-11-17 网关路由清理

0.4.22 APK 的三个 DEX 文件均无 `/v1/hosts`、`/v1/upgrades` 或 `/v1/releases`，当前 App 源码也无这些调用。三个 GET 及 `/v1/releases/<ID>/apk` 下线，授权请求返回 404。历史 SQLite 表、行和 APK 文件保留给离线维护。当前 `/v1/app-update` 继续提供清单及 APK，包括 Range 下载。

`GET /v1/threads` 的非分页响应默认包含 `approvals`。0.4.22 使用的 `?include=approvals` 获得相同响应，`limit` 和 `before` 分页维持原形态。现行续期、内部设备绑定、手机配对管理及浏览器隧道继续使用原入口。

已上传无秘密归档至独立暂存，归档、三项逐文件摘要、两项生产源码基线、Node/Bash 语法、原路由状态及当前 APK 真实单字节 206 已预检通过。归档 SHA-256 为 `4143cf4cdee8ac302df1d6099742c8627033359f77cd5b65e9281ee48088597f`。

```bash
cd /home/xux/.local/share/cloud-workbench-maintenance/stage.gateway-cleanup.pEO5UA9u
bash ops/deploy-gateway-cleanup.sh deploy "$PWD"
```

作为 xux 运行。脚本使用原维护锁和写入阻断标记。存在启动、执行或待审批任务时退出。只替换 `gateway.mjs` 和 `releases.mjs`，保留前一版本副本，走现有空闲重启助手。重启后核对退役路由、会话概览、v1/v3、配对管理和当前 APK。失败会尝试恢复两份文件。手动回滚在同一暂存目录执行 `bash ops/deploy-gateway-cleanup.sh rollback <本次部署输出的 previous.gateway-cleanup.* 路径>`。

Node 回归 22 项通过，覆盖授权后的退役路由 404、数据记录保留、本版更新下载、审批概览和分页，以及配对和隧道回归。2026-11-17 已执行上线。生产旧路由、会话概览、分页、Windows 签发兑换、WSS 与撤销验证通过。回滚备份为 `/home/xux/.local/share/cloud-workbench-maintenance/previous.gateway-cleanup.kwRYHkDY`。
