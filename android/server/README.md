# 云端工作台网关

`cloud-workbench.service` 在 `127.0.0.1:8766` 提供工作台 API。Android 使用只授予固定业务端口访问权的 SSH 密钥和短期令牌。Nginx 将 Windows HTTPS 请求交给固定业务入口。Windows 必须证明持有其设备私钥，配对码只通过受信任的手机授权端签发。

Windows 允许使用项目、会话、任务、审批、图片附件和服务状态接口。发布、重启和 SSH 运维由管理员使用独立授权入口。`workspace-write` 是日常 Codex 模式。共享可写目录由 `codex-writable-roots.json` 管理。

## 数据与发布

- 程序目录为 `~/.local/lib/cloud-workbench`。业务数据位于 `~/.local/share/cloud-workbench`，其中 `workbench.db` 保存会话，`workstation-access.db` 保存设备、公钥和审计，`app-updates/` 保存已签名 APK。
- 部署前确认活动任务已完成，对 SQLite 数据库做一致性备份，并检查附件目录和已签名版本。通过既有 `cloud-workbench-ops restart-workbench` 运维入口在空闲时重启。回退使用工作台版本及其配置。
- Nginx 虚拟主机入口见 [生产 HTTPS 配置](ops/workbench-https.conf)。生产工作区由数据目录的 `projects.json` 配置，当前入口为栖物里网站和服务器运维。
- 手机密钥的绑定规则见 [SSH 业务连接](ops/ssh-mobile.md)。

使用 Node.js 22.13 或更高版本运行 `npm test`。生产部署后验证两端鉴权、会话恢复、图片附件、APK 分段更新和撤销行为。
