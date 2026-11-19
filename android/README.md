# 云端工作台

当前线上 APK 为 **0.4.35（构建 48）**。应用图标与 Windows 使用同一图案和纯白底色。手机通过专用 SSH 密钥连接 `workbench.qiwuli.com`。工作台业务、事件、浏览和授权管理复用主 SSH 连接；更新下载按需增加最多三条临时 SSH 连接，完成或取消后释放。新版由维护者在本机构建并手动发布。

如果旧版应用内更新无法安装，可用 维护者提供的同一签名 APK 覆盖安装。发布包为 270,730,076 字节，SHA-256 为 `879bcfa2d98b7a08259bd6b579a776b14caa12bdec7738618b4c7ebf99aa6ab7`。线上整包摘要和分段下载已通过 SSH 鉴权验证。应用内更新由系统安装器处理。

应用启动后使用已保存的专用 SSH 密钥自动连接，覆盖升级保留密钥。正常设置只显示连接状态；未配置或连接异常时，可通过「连接问题」恢复连接，密钥维护操作需要主动展开。

从「主菜单 → 授权 → 新增授权」选择「授权码」或「订阅」。「授权码」用于 Windows 首次登记，24 小时内可使用一次。已登记设备也在「授权码」列表中，凭据可自动续期。新增设备及订阅授权预填 7 天、1 GiB 上下行累计额度。订阅链接默认自签发起 5 分钟内最多成功下载 3 次，时长与次数可调。浏览器下载计数，达到任一限制即关闭领取。已保存的配置通过独立地址刷新。见 [授权记录与 Windows 登记](../windows/docs/PAIRING-FLOW.md)。

两组列表共用「查看」「复制」「邮件发送」「撤销」和「移除记录」。仍在领取窗口且有剩余次数的有效订阅链接可再次查看、复制和发送。撤销会让凭据失效，订阅连接会在服务端下一次校验时断开。已撤销或过期的记录可以从列表移除。已登记设备和订阅的卡片使用相同的登录与用量字段。

[项目索引](../README.md) · [网关维护入口](server/README.md)

## 连接与使用

- 点顶栏左侧的菜单图标展开侧栏，选择页面后自动收起。应用使用沉浸式全屏，可从屏幕边缘滑出系统栏。
- 「云端」页的「核心服务」显示中文名称和运行状态。点击服务可查看用途和异常影响。
- 右上角连接状态会显示附件上传进度、等待响应和 Codex 执行状态。点击可查看本次连接的累计收发量、近一分钟收发图和后台同步汇总。最近操作可按会话、文件和管理筛选。收发量按工作台请求、响应和取材图片的正文大小统计。
- Android Keystore 保护保存的 SSH 密钥。可在设置中查看绑定状态，并在密钥更换时恢复绑定。
- 短暂断网或网关重启后，应用会自动重连并恢复当前会话。发送回执丢失时，应用会核对会话记录。确认已收到时清除对应的草稿和附件，尚未收到时保留草稿供手动重试。网关重启时已中断的任务会显示为「已中断」。
- 平板默认网络断开时，客户端等待系统报告网络恢复，再立即恢复连接、重新订阅通知并按游标补齐。只读 JSON 请求可以重试失效的 HTTP 连接，消息提交仍使用请求标识核对回执。
- 在「会话」页用「工作区」切换执行目录，用「新对话」打开草稿。通过「历史会话」搜索已加载的标题、打开或删除会话，也可加载更多会话。打开对话后可向上加载更早的消息。当前工作区有可清理的空会话时，历史列表会显示清理入口。应用运行期间按会话保留草稿。在「任务」查看运行中的任务和待审批请求。
- 前台通过 SSH 连接接收事件通知并触发会话增量读取。切到后台会关闭通知和浏览隧道，返回应用后补齐遗漏的更新。
- 点击麦克风图标开始说话，首次使用需授予麦克风权限。录音时可查看用时和实时转写，点停止图标后识别结果进入当前会话草稿。点击草稿或键盘图标可修订，确认后点击发送。应用使用随安装包提供的中英流式模型，断网时也能生成草稿。
- 对话输入区的「＋」可拍照、从相册或文件选择图片，也可将 UTF-8 文本文件导入草稿。每条消息最多附 4 张图，发送前可预览或移除。仅附图片时，发送会使用「请分析附图。」作为提示词。
- 「浏览」页先显示本地起始页。可从「常用地址」打开网页，也可在地址栏输入网址或搜索词。主页按钮返回起始页。切换工作台页面时保留当前网页，仍可将网页文字、选区、图片和截图交给 Codex。
- 点击星标收藏当前网页，再点实心星标可编辑。书签管理支持嵌套文件夹、按标题或网址搜索、移动和删除，以及手动顺序和多种排序。宽屏浏览时可显示书签栏，已固定的书签同时出现在起始页。
- 「云端」页的「浏览模式」可选「云端代理」或「平板直连」，也可以点击浏览地址栏左侧的状态图标直接切换。每次冷启动默认使用云端代理，SSH 连接后自动配置。云端浏览和工作台业务共用 SSH 连接。平板直连在本次运行期间生效。服务器仅在回环地址监听 8766 和 8768。
- 网页加载进度和错误显示在网页区域，加载失败后可以重试、返回浏览主页或输入新网址。浏览代理重连时保留已打开的页面和历史。

## 构建与安装

在 `android/local.properties` 配置 Android SDK，使用 JDK 17 和项目的 Gradle Wrapper。从仓库的 `android` 目录 运行以下命令。准备脚本下载并校验固定版本的 sherpa-onnx 和 Zipformer 模型。真机语音验收需要一并准备测试音频，常规构建可省略 `-IncludeTestSample`。

```powershell
.\tools\prepare-voice-model.ps1 -IncludeTestSample
cd android
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

应用输出为 `android/app/build/outputs/apk/debug/app-debug.apk`。从 0.4.18 开始，正式 APK 使用同一份发布签名。签名材料现位于项目相对目录 `signing/`：`release.p12` 为原发布私钥，`release-password.txt` 为 UTF-8 明文密码文件；打包脚本直接读取密码文件，不依赖 Windows DPAPI、原 Windows 用户或原电脑。迁移时应私下复制完整项目及被 Git 忽略的 `signing/` 目录，并在目标设备配置 JDK 17 的 `JAVA_HOME` 和 Android SDK Build Tools 35.0.0（通过 `ANDROID_SDK_ROOT`、`ANDROID_HOME` 或 `android/local.properties` 指定 SDK）。私钥和密码不得提交到 Git、打入 APK 或公开发布。旧签名设备首次迁移需要重装并重新配对。迁移后的设备可用同一新签名覆盖安装。现行 SSH 登录配置通过独立的维护目录管理。

在「设置 → 应用更新」检查版本，通过当前 SSH 连接下载更新，再在 Android 系统界面确认安装。安装包按 1 MiB 分片、最多四路并行下载，显示已下载量、近期速度和预计剩余时间。失败只重试对应分片，取消或进程重启后保留有效进度；兼容旧版八分段缓存。完整包在重启后可离线恢复，经摘要、包名、版本和签名复验后交给系统安装。首次使用时按系统提示允许云端工作台安装应用。

维护者在本机提高构建号并构建。设置 `JAVA_HOME` 后，用发布密钥签名，再核对元数据并手动发布。签名脚本使用项目内的独立密码文件，可在其他 Windows 用户和设备上使用，无需原账号解密。服务器发布脚本校验签名连续性和构建号，再切换在线版本。

```powershell
$releaseApk = '.\artifacts\cloud-workbench-<版本号>.apk'
.\tools\sign-app-release.ps1 -Apk .\android\app\build\outputs\apk\debug\app-debug.apk -Output $releaseApk
.\tools\apk_metadata.ps1 -Apk $releaseApk
.\tools\publish-app-update.ps1 -Apk $releaseApk -Notes '本版更新说明'
```

发布新版时，若设备已通过 USB 连接并授权调试，直接用 `adb -s <设备序列号> install -r <签名APK路径>` 覆盖更新，再核对正式包版本。覆盖安装保留应用数据与配对密钥。

指定 `-PusbAcceptance` 可构建独立 USB 验收版，包名为 `cn.xux.cloudworkbench.acceptance`，使用自己的配对凭据，与正式版并存时会出现第二个启动图标。验收结束后，清理对应设备上的验收版及其 `cn.xux.cloudworkbench.acceptance.test` 测试包。

## 网关与历史归档

网关服务为 `cloud-workbench.service`，程序目录为 `~/.local/lib/cloud-workbench`，数据目录为 `~/.local/share/cloud-workbench`。部署前检查活动任务并备份待替换文件与数据库。回退备份单独放在 `~/.local/share/cloud-workbench-maintenance/`。

服务器保留历史需求、发布元数据和已有安装回执。历史 APK 和源码存放在本机归档。手动发布的在线更新文件存放在数据目录的 `app-updates`，更新接口通过现有网关认证提供当前清单和 APK。自动构建提交、构建重试和安装回执写入已退役。

SSH 会话部署与回退使用 `server/ops/deploy-ssh-mobile.sh`。Android 使用随 SSH 会话签发的短期凭据，旧 Android 配对入口已退役（旧入口返回 404）。Windows 设备登记和续期继续由 Windows 网关处理。电脑管理流程部署与回退见 `server/ops/windows-pairing.md`。

### 服务端 Codex 权限

线上网关从数据目录的 `codex-access-mode` 读取 Codex 权限。当前值为 `danger-full-access`，新会话和续接会话均使用 `xux` 的 Linux 文件和网络权限，Codex 沙箱审批策略为 `never`。改为 `workspace-write` 并重启网关可恢复工作区沙箱和按需审批，可写范围由所选项目和 `codex-writable-roots.json` 决定。当前可选工作区为 `~/projects/qiwuli-site` 和 `~/projects/server-ops`。原云端工作区 `~/projects/mobile-workspace` 已停用，历史会话和附件保留。

在 `~/projects/qiwuli-site` 运行 `npm run build` 生成 `dist`。获得用户发布或维护授权后，使用以下由 Linux sudoers 授权的运维命令。

| 操作 | 命令 |
| --- | --- |
| 检查网站构建产物和 Nginx 配置 | `sudo -n /usr/local/sbin/cloud-workbench-ops check-site` |
| 发布 `dist` 到栖物里站点 | `sudo -n /usr/local/sbin/cloud-workbench-ops deploy-site` |
| 重载 Nginx | `sudo -n /usr/local/sbin/cloud-workbench-ops reload-nginx` |
| 重启 Nginx | `sudo -n /usr/local/sbin/cloud-workbench-ops restart-nginx` |
| 在任务结束后重启云端工作台 | `sudo -n /usr/local/sbin/cloud-workbench-ops restart-workbench` |

发布会检查 Nginx 配置和 HTTPS，并把上一个站点版本保存在 `/var/www/.qiwuli.com-previous`。工作台重启请求最多等待空闲 5 分钟，仍有活动任务时跳过。运维脚本和 sudo 授权清单在 `server/ops`，线上副本由 root 持有。

历史验收资料和 APK 保存在维护归档中。

## 测试

从仓库的 `android` 目录 使用 Node 22.13 或以上版本运行 `node --test server/test/*.test.mjs`。真机可通过 `tools/device_test.py` 执行指定的验收用例，真实 Codex 测试会留下验收会话。

0.4.0 在 TB322FC 上通过了飞行模式下的中英流式转写、切页结束录音、草稿键盘修订，以及联网手动发送。独立模型测试的三个样本首段文字用时为 0.53～1.44 秒，混说样本仍有漏词。

2026-11-13，0.3.9 通过 Android 构建和 lint（0 错误、21 警告）。TB322FC 已覆盖安装并验证菜单和全屏、键盘避让、浏览器前进后退与切回保留文档、取材预览、无效配对凭据回退、真实 Codex 往返，以及切换会话后拒绝待审批请求。14 项网关测试通过。
