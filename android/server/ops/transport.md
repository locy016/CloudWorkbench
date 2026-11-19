# 工作台 TCP 443 上线记录（Android 0.4.19）

以下为 2026 年 11 月 14 日部署记录。现行 Android 客户端及 SSH 绑定运维见 [云端工作台说明](../../README.md)。

1. 在阿里云 DNS 为 `workbench.qiwuli.com` 添加 A 记录 `43.110.34.152`。添加 AAAA 之前先核验源站 IPv6 可达性。
2. 将本次网关的 `src`、`test`、`ops` 和两个 npm 清单上传到服务器的 `~/.local/share/cloud-workbench-maintenance/stage.*`。以 `xux` 运行 `bash <暂存目录>/ops/deploy-transport.sh <暂存目录>`。脚本在暂存目录运行测试，检查活动任务，备份程序、SQLite 数据库和当前更新清单，使用已授权的空闲重启入口，并验证回环网关。重启失败会尝试切回原程序。
3. 等公开 DNS 生效。服务器管理员审阅 [启用脚本](activate-workbench-nginx.sh) 和 [HTTP 配置](workbench-http.conf)、[HTTPS 配置](workbench-https.conf)，复制到管理员控制的目录并以 root 运行 `bash <管理员目录>/activate-workbench-nginx.sh`，无需参数。脚本以 HTTP-01 签发独立证书，添加验证路由及续期部署钩子，每次平滑重载前运行 `nginx -t`。启用探测失败会撤回工作台路由并尝试重载。
4. 从服务器外部验证 `https://workbench.qiwuli.com/v1/transport` 返回 `{"protocol":1}`，无凭据访问 `/v1/threads` 返回 `401`，栖物里主域和 `www` 分别返回 `200` 和 `308`。先用验收设备验证首次配对、真实网站、事件重连、业务请求和更新元数据，再发布同签名 APK。旧版从原更新入口获取新版。

2026 年 11 月 14 日已部署网关并启用公网 HTTPS。启用文件的线上暂存目录为 `/home/xux/.local/share/cloud-workbench-maintenance/stage.xTuQ5LVp/ops`，原 0.4.18 网关及数据库备份为 `/home/xux/.local/share/cloud-workbench-maintenance/previous.bnC1aayn`，启用前的 Nginx 备份为 `/root/workbench-ingress.oI55AE8f/nginx-before.tgz`。已核对正式证书、工作台协议入口、设备鉴权、事件流、WSS 和浏览 CONNECT，以及栖物里主域和 www 的原有响应。

TB322FC 已用同签名 APK 覆盖安装 0.4.19，自动配对后实测云端代理网页、网页截图附件、Codex 回复、返回前台和 Wi-Fi 断开后恢复。服务器已发布 0.4.19（构建 32）的在线更新。本地隔离验收的单次通知到界面可见用时 255 毫秒。未获得可靠的新版与旧版实际延迟及耗电对照。

验收后已从 TB322FC 移除独立验收版及其测试包。正式版是唯一拥有启动入口的工作台应用，复测连接、云端代理和在线更新均正常。

设备管理命令在网关目录执行。

```bash
node ops/workbench-devices.mjs list
node ops/workbench-devices.mjs scopes <device-id> api,events,browser
node ops/workbench-devices.mjs revoke <device-id>
```

回退公网入口时，由管理员把 `workbench-http.conf` 和 `workbench-https.conf` 从 `/etc/nginx/conf.d` 移到备份目录，运行 `nginx -t` 后平滑重载。若线上更新已经指向新版，把部署备份的 `current.json` 原子切回 `app-updates/current.json`。新版已安装设备需要保留可用的 HTTPS 入口，或由管理员发布采用同签名且构建号更高的修复包。只在确认工作台无活动任务时回退服务器程序，保留当前业务数据库，数据库备份用于恢复受损数据。
