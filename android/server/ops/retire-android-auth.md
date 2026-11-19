# 第二阶段退役旧 Android 设备授权

部署决定在正式 Android App 已用固定 SSH 连接重新绑定并通过实际测试后作出。现有旧版的更新路径在这次部署前继续可用。

| 入口 | 退役补丁生效后的行为 |
|---|---|
| `/internal/pair-device` | 回环网关返回 404，公网继续由 Nginx 关闭连接。 |
| 常规 API 和 `/v1/app-update` | 真实回环 SSH 短期 Bearer 可用，受控回环运维 Bearer 保留。公网旧 Android 设备 Bearer 返回 401。 |
| `/v1/events` | 只接受真实回环 SSH 短期 Bearer。 |
| `/v1/browser-tunnel` | 保留回环 SSH 隧道和 HTTPS 入口的 `browser` 单权限设备。含 `api` 或 `events` 的历史设备令牌被拒绝。 |
| `/v1/device/renew` | 保留仅 `browser` 权限设备的续期，在同一条 SQL 更新内校验权限、到期和撤销。旧多权限 Android 续期返回 401。 |
| `/v3/*` | 独立 Windows 入口使用原规则。已有且未过期的历史 `browser` access token 仍按其原规则工作，旧 Android 不能再经共享续期补发。 |

生产只读统计确认当时有两条有效的 `api,events,browser` 记录及一条仅 `browser` 记录。补丁不改写这些历史记录、权限或撤销状态，也不改 Nginx、SSH 授权、Windows 注册模块及 v3 服务。

公开 Nginx allowlist 可暂时保留。公开旧业务、事件和 APK 接口由网关返回 401，其他非 allowlist 路径由生产 Nginx 返回 444。`/v1/transport` 仍返回 200 以保持现有 SSH 部署和入口健康检查。确定所有正式 Android 客户端已迁移后，可另行把旧公开路由从 Nginx allowlist 去掉，并同步更新仍探测 `/v1/transport` 的运维脚本。保留 Windows 使用的公开续期、授权 WSS 和 v3。

## 暂存、部署及回滚

已部署暂存 `/home/xux/.local/share/cloud-workbench-maintenance/stage.retire-android-auth.rZUHDB6K`，归档 SHA-256 为 `0b150783c263b3013db596752bf90c1939cbd9f357e0a8f4f8f9ff47e0c6ad37`。归档及三项清单、生产源码基线、Bash 与 Node 语法、重启助手权限、生产只读冒烟均通过。首轮暂存的预检把维护标记拦截写请求时的 503 误判为失败。现脚本分别校验维护状态和正常状态，释放标记后使用 `check-retired` 独立复核。

本次已由 xux 执行：

```bash
cd /home/xux/.local/share/cloud-workbench-maintenance/stage.retire-android-auth.rZUHDB6K
bash ops/deploy-retire-android-auth.sh deploy "$PWD"
bash ops/deploy-retire-android-auth.sh check-retired "$PWD"
```

脚本使用既有维护锁和阻断写入标记。遇到正在启动、执行或等审批的任务即退出。只替换 `gateway.mjs` 与 `devices.mjs`，失败时尝试恢复上一版。手动回滚在同一暂存根执行 `bash ops/deploy-retire-android-auth.sh rollback /home/xux/.local/share/cloud-workbench-maintenance/previous.retire-android-auth.0LHZsNpZ`。

隔离网关 22 项和 Windows 注册测试通过。覆盖旧令牌的 API、事件、更新、WSS 和续期拒绝，SSH 普通链路、正式 Windows 注册身份续期，以及带 HTTPS 入口的 Windows WSS 实际传输和撤销。

2026-11-17 10:44（北京时间）生产上线并独立复核。网关、Nginx、Windows v3 core/ingress 和注册服务均 active。生产 SSH 会话的业务读取、设备码列表、事件流和 WSS 握手均通过。0.4.25 更新清单可读，实际 APK 的 `Range: bytes=0-0` 返回 206 和 1 字节。维护标记已释放，旧配对返回 404，无效续期返回 401，公网旧路由维持原拒绝策略。正式 Windows 客户端的授权正向操作仅在隔离测试中验证。
