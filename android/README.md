# 云端工作台 Android

0.4.40（构建 53）通过专用 SSH 密钥连接云端工作站。工作台提供项目、Codex 会话、任务与审批、离线转写、附件、设备授权和应用更新。浏览页从设备直接加载网页，书签、选区、图片和截图可作为当前工作台会话的取材。

## 连接与设备

在「设置 → 连接问题」导入经授权的专用 SSH 私钥。Android Keystore 保护保存的密钥。断线后恢复工作台连接，并补齐会话事件。发送回执丢失时核对请求标识，已受理的消息不会重复执行。

在侧栏「授权」为 Windows 生成有效期 10 分钟的一次性配对码。授权列表显示截止时间和连接状态。展开客户端信息可查看设备名称、来源地址、系统、客户端版本和最近请求。可以调整授权名称与时限、撤销设备，并清理过期或撤销的记录。

## 构建和发布

准备 JDK 17、Android SDK 35 和已校验的本地语音模型。从本目录执行。

```powershell
.\tools\prepare-voice-model.ps1
cd android
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

从项目 Android 根目录运行 `tools/sign-app-release.ps1` 生成同签名 APK，用 `tools/apk_metadata.ps1` 核验包名、版本、证书和摘要。签名库和密码位于受限的 `signing/` 目录。提高构建号后使用 `tools/publish-app-update.ps1` 发布。正式客户端从「设置 → 应用更新」经 SSH 获取包，支持分段续传，并在交给系统安装器前复验签名和摘要。USB 覆盖安装使用 `adb install -r`，保持正式包的签名连续。

使用 `-PusbAcceptance` 构建独立验收包 `cn.xux.cloudworkbench.acceptance`。实体设备上可与正式版本并存。

[网关与服务端数据](server/README.md)
