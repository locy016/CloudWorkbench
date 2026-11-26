# 独立签名与私有发布

本机开发由 Windows 直接生成未签名 APK。服务端开发可由 GitHub Actions 生成未签名 APK、源码提交和校验摘要。生产签名服务使用独立 Linux 账号，经批准后才签名并发布。工作台网关通过原有 SSH 业务连接分发更新，读取发布目录的权限独立于签名和批准权限。

## 首次部署

1. 将本目录的 `install.sh`、`release.py`、`.service` 和 `.timer` 文件与同一提交的 `src/main.mjs`、`src/gateway.mjs`、`src/releases.mjs`、`src/release-approvals.mjs`、`ops/publish-app-update.mjs` 平铺到服务器部署目录。管理员运行 `sudo /bin/bash <部署目录>/install.sh`。脚本验证当前正式 APK 后迁移更新源，并在工作台空闲时切换。升级已有服务时保留已导入的凭据，直接重复这一步。
2. 在持有正式签名密钥和密码的 Windows 电脑，用 PowerShell 7 运行 `android/tools/import-release-key.ps1`。脚本通过现有 SSH 获取服务器生成的临时公钥，在内存中加密签名材料，再上传密文。
3. 管理员运行 `sudo /usr/local/sbin/cloud-workbench-release-install import`。安装程序验证证书指纹，导入 systemd 加密凭据并启动定时服务。导入完成后清理临时传输材料。

## 提交与批准

本机发布先从干净的 `main` 构建，网关测试、Android Lint 和未签名 APK 构建通过后，使用 `android/tools/submit-local-release.ps1` 提交。工具通过本机 SSH 管理密钥给构建记录签名，将记录和 APK 送到签名服务的收件目录。服务器核对可信构建机、源码提交、APK 摘要、包名和版本，随后等待平板审批。

管理员首次登记构建机时，将其 SSH 公钥以 OpenSSH allowed-signers 格式配置在 `/var/lib/cloud-workbench-release/approvals/local-builders`，主体为 `workbench-windows`，命名空间限制为 `cloud-workbench-local-apk-v1`。配置由 `root` 持有，签名服务组可读。该公钥只证明 APK 来源，正式发布仍需平板审批。

服务端 Codex 在确认验收结果后，用工作台账号提交 GitHub 运行编号和更新说明。

```bash
cloud-workbench-release submit <运行编号> '更新说明'
cloud-workbench-release status <运行编号>
```

服务独立核对构建结论、源码是否已纳入 `main`、GitHub 产物摘要、APK 摘要、包名和版本。状态为 `awaiting-approval` 时，核对其中的源码、版本、更新说明和批准摘要。管理员在有管理授权的 SSH 终端执行批准命令，输入本次状态中的完整 `approvalSha256`。

```bash
sudo -k /usr/local/bin/cloud-workbench-release approve <运行编号> <approvalSha256>
```

定时服务复核批准摘要和 APK 内容，签名并校验正式证书，再原子更新私有发布清单。`status` 显示 `published` 后，可在真机更新。管理 SSH 终端可来自任一获授权的设备，日常发布无需 Windows 开机或参与签名。`xux` 的免密运维入口未授予发布批准权限。

## 平板审批

在 Android 11 或更新版本上先设置系统屏幕锁。打开「会话 → 任务 → 正式发布审批」，点击「生成审批公钥」。管理员在可信终端核对平板显示的完整指纹，然后绑定设备。

```bash
sudo -k /usr/local/bin/cloud-workbench-release enroll <平板公钥指纹>
```

绑定后，平板可查看版本、源码、验收结果和批准摘要。批准与拒绝均要求系统身份验证。签名服务只认管理员绑定的公钥，并独立检查签名、候选摘要和决定。拒绝会丢弃该候选 APK，再次申请发布需提交新的构建运行。网关转交请求和展示状态，平板私钥保存在 Android Keystore。

管理员可检查 `systemctl status cloud-workbench-release.timer` 和 `journalctl -u cloud-workbench-release.service`。本地前置校验使用 `python android/server/test/release_service_test.py` 和网关的 `npm test`。
