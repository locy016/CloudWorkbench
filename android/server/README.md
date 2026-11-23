# 云端工作台网关

`cloud-workbench.service` 在 `127.0.0.1:8766` 提供工作台 API。Android 使用只授予固定业务端口访问权的 SSH 密钥和短期令牌。

发布、重启和 SSH 运维由管理员使用独立授权入口。`workspace-write` 是日常 Codex 模式。共享可写目录由 `codex-writable-roots.json` 管理。

## 数据与发布

- 程序目录为 `~/.local/lib/cloud-workbench`。业务数据位于 `~/.local/share/cloud-workbench`，其中 `workbench.db` 保存会话，`app-updates/` 保存已签名 APK。
- 部署前确认活动任务已完成，对 SQLite 数据库做一致性备份，并检查附件目录和已签名版本。通过既有 `cloud-workbench-ops restart-workbench` 运维入口在空闲时重启。回退使用工作台版本及其配置。
- 工作台域名的 HTTP 和 TLS 拒绝配置见 [Nginx 切换脚本](ops/close-workbench-web-ingress.sh)。生产工作区由数据目录的 `projects.json` 配置，当前入口为栖物里网站和服务器运维。
- 手机密钥的绑定规则见 [SSH 业务连接](ops/ssh-mobile.md)。

退役清理通过 Nginx 配置、运行状态和旧规则检查验收。证书续期单独管理。关闭工作台域名的公网解析时，现有 HTTP-01 续期无法完成。如继续使用公开受信证书，采用 DNS-01 验证。

使用 Node.js 22.13 或更高版本运行 `npm test`。生产部署后验证 SSH 鉴权、会话恢复、图片附件和 APK 分段更新。

## 按需资源目录

服务端读取 `DATA_DIR/resource-sources.json`。该文件存在时以整份目录为准。内置 [资源定义示例](src/resource-sources.json) 包含 USGS GeoJSON 及 GitHub 公开接口。以同目录临时文件写入完整配置并原子替换后，在 APP 点「刷新目录」即可读取新定义。

每个来源需要固定的 HTTPS 接口、来源说明、返回记录数组路径和导出字段。筛选条件支持选项和文本。选项可绑定到预先配置的路径占位符或查询参数，文本只绑定到固定查询参数。接入前核对数据源允许的访问和使用方式。网关按公开 IPv4 地址连接，限制请求时间与响应大小，并直接解析 JSON 或 GeoJSON。
