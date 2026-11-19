# 云端工作台 · CloudWorkbench

本库维护 Android 客户端、Windows 客户端、服务端网关，以及栖物里和工作台下载页。四个项目共用同一个 Git 仓库，便于同步检视客户端和服务端契约。

## 项目索引

| 项目 | 代码入口 | 功能与维护 |
| --- | --- | --- |
| Android 客户端 | [Android Gradle 工程](android/android/) | 通过 SSH 使用云端会话和任务，提供浏览、文件取材、授权管理、离线语音和应用更新。见 [Android 说明](android/README.md)。 |
| Windows 客户端 | [Rust、Tauri 与 Vue 工程](windows/rust-client/) | 配对与订阅、系统代理和增强 TUN、全局和规则模式、直连例外、连接诊断和网络恢复。见 [Windows 说明](windows/README.md)。 |
| 服务端网关 | [手机网关](android/server/) · [Windows 接入与登记](windows/server/) | 会话与任务、浏览中继、应用更新、设备登记与续期、订阅授权及用量。见 [网关维护入口](android/server/README.md)。 |
| 站点 | [栖物里](site/) · [下载页模板](windows/portal/) | 栖物里提供项目集、笔记和浏览器内工具。下载页提供 Windows 安装包、版本和校验摘要。见 [站点发布入口](site/README.md)。 |

## 开发与发布

- 保留完整检出。Windows Rust 构建读取 [共享公开路由契约](android/server/src/public-routes.json)，Windows 接入服务复用 Android 网关的授权策略。跨端修改应同时复验调用方和网关。
- Android 使用 JDK 17、Android SDK 35 和 Gradle Wrapper。先按客户端说明准备固定版本的语音组件与模型。Windows 使用项目固定 Rust 工具链、MSVC、Windows SDK 和 Node.js。网关及站点支持各自的 Node.js 测试入口。
- 仓库记录源码、依赖锁、构建及部署工具。发布包、语音模型、签名材料、设备授权、连接日志、数据库与服务器账户配置由各自的受控位置管理。
- APK 通过客户端的鉴权更新通道交付。Windows x64 便携包通过工作台下载页交付。站点和网关的部署按项目文档执行。

[Windows 下载页](https://workbench.qiwuli.com/) · [栖物里](https://qiwuli.com/)
