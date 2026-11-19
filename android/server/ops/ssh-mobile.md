# Android SSH 绑定

手机通过严格校验服务器主机指纹的 SSH 连接执行 `workbench-ssh-session-v1`。固定命令仅在该字符串完全匹配、且无额外参数时，向 SSH 标准输出写一行 JSON `{token,expiresAt}`。令牌以 `sshm1.` 开头，四小时有效，`expiresAt` 为 Unix 毫秒。客户端把令牌留在进程内，在过期或收到 401 时重新经 SSH 获取。手机号、设备注册身份和持久续期秘密不参与此流程。

通过同一 SSH 本地端口转发访问固定云端 `127.0.0.1:8766`，请求使用 `Authorization: Bearer <token>`。网关只在真实 IPv4 回环连接且没有 `X-Workbench-Ingress` 头时接受这一类短期令牌。它用于普通 API、`/v1/events` NDJSON 事件流、`/v1/browser-tunnel`、`/v1/app-update` 和私有 Windows 配对管理。事件流和 WebSocket 用固定主体 `ssh-mobile` 合并连接限额，并在现有定期检查中关闭过期连接。它是回环入口的限时 Bearer，信任边界包含能访问该入口且持有令牌的服务器本机主体。SSH 签发不能调用旧设备注册或共享续期接口。

## Windows 管理操作

现有 `GET /internal/windows-pairing` 的码记录新增 `label`，旧记录为空字符串。签发 `POST /internal/windows-pairing/codes` 接受 `{minutes,label?}`。返回仍为 201 `{id,code,expiresAt}`，签发是唯一返回明文的操作。私人管理 Bearer 保留给可信服务器维护。

| 请求 | 规则 |
|---|---|
| `POST /internal/windows-pairing/codes/<id>/extend` `{minutes}` | 只延长未兑换、未撤销、未过期的码。新期限最长从当前起 30 分钟，原期限更晚时维持原期限。 |
| `POST /internal/windows-pairing/codes/<id>/rename` `{label}` | 改可见配对码的标签，最多 48 个 Unicode 字符。 |
| `POST /internal/windows-pairing/codes/<id>/remove` `{}` | 隐藏配对码。未兑换码在同一事务内撤销，已兑换码继续保留设备关联。 |
| `POST /internal/windows-pairing/devices/<id>/rename` `{label}` | 改 Windows 配对渠道设备的名称。 |
| `POST /internal/windows-pairing/devices/<id>/remove` `{}` | 只有已撤销的 Windows 配对设备才能从列表隐藏，认证和撤销记录仍保留。 |

这些操作返回 200 `{changed:boolean}`。现有码/设备 DELETE 仍表示撤销。配对码标签和隐藏状态在同一 SQLite 的 `windows_pairing_admin_v1` 表中保存。现有注册模块、设备与配对表结构保持兼容。列表不返回完整设备秘密。

## 暂存、验证和回滚

已将九项源码及参考公钥上传到 `/home/xux/.local/share/cloud-workbench-maintenance/stage.ssh-mobile.uG7s80MK`。归档 SHA-256 为 `0de8671c7d3a01d2495c71221952b667aaee359185c6894d93c1c6a43a18f15c`。只读预检通过，包括四项生产源码基线、网关路径、空闲重启助手授权、唯一 TB322FC 公钥指纹与精确旧选项。早期暂存 `stage.ssh-mobile.0jX2R76W` 不用于发布。

```bash
cd /home/xux/.local/share/cloud-workbench-maintenance/stage.ssh-mobile.uG7s80MK
bash ops/deploy-ssh-mobile.sh deploy "$PWD"
```

部署脚本以 xux 运行，经既有锁、写入阻断标记和空闲重启检查，保留原源码与 `authorized_keys.before`。服务器现场创建或校验 0600 HMAC 密钥。成功通过网关和签发器本机检查后，只替换专用公钥一行的 forced command。两条 `permitopen`、`restrict` 以及其他公钥行原样保留。公网管理路径仍由现有 Nginx 回绝。

回滚在同一暂存根运行 `bash ops/deploy-ssh-mobile.sh rollback <部署输出的 previous.ssh-mobile.*>`。回滚只反向替换专用公钥的 forced command，并按源码摘要保护恢复。签名密钥和配对管理元数据留在服务器。既有旧 Android 的公开更新、事件、浏览器与设备续期仍在本次过渡部署范围内。

Node 网关 22 项测试通过。隔离 Linux 验证了精确固定命令、非 0600 密钥拒绝、授权文件仅一行修改、回滚逐字节一致以及重复公钥时拒绝。真实 SQLite 用例验证了命名、延期、撤销、隐藏及设备关联保留。尚需线上验证实际 TB322FC 的 SSH 命令、重连刷新、对话和事件、浏览器 WebSocket、当前 APK 更新及手机配对管理。

TB322FC 实测 0.4.25 的完整 SSH 链路后，再独立安排去掉旧 Android 的 HTTPS API、事件、浏览器和移动设备授权入口。先核对活跃设备权限及归属，再撤销明确属于已迁移手机的历史设备。正式 Windows 的 `/v1/device/renew` 与 v3 注册、解绑、browser 权限继续运行，不应通过关闭共享续期或删除设备库退役 Android。
