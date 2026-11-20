# 云端工作台

云端工作台用于私有 Codex 开发和服务器运维。Android 通过专用 SSH 密钥连接固定业务网关。Windows 通过 HTTPS 和本机设备密钥访问指定的工作台接口。管理员通过独立 SSH 入口维护服务器。栖物里由 Nginx 提供静态站点服务。

| 项目 | 入口 |
| --- | --- |
| Android 工作台 | [构建、签名和部署](android/README.md) |
| Windows 工作台 | [绑定、构建和私下交付](windows/README.md) |
| 服务端 | [业务网关、数据和运维](android/server/README.md) |
| 栖物里 | [站点发布](site/README.md) |

客户端在完整本地检出中构建。网关和站点按服务器维护流程部署，发布前确认活动任务和受影响的入口。源代码、构建工具和部署说明进入 Git。签名材料、设备配置、业务数据和发布包保存在受控目录。

[工作台说明页](https://workbench.qiwuli.com/) · [栖物里](https://qiwuli.com/)
