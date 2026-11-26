# 云端工作台

云端工作台用于在 Android 上继续 Codex 会话、跟进任务和审批，以及查看服务器状态。客户端通过专用 SSH 密钥访问私有工作站，支持云端助理语音聊天、历史搜索、草稿续接、国际资讯和实时汇率。

## 从这里开始

| 内容 | 说明 |
| --- | --- |
| Android 客户端 | [使用、构建与发布](android/README.md) |
| Node.js 网关 | [本地测试、工作区配置与部署](android/server/README.md) |
| 栖物里网站 | [站点开发与发布](site/README.md) |
| 开发记录 | [版本日志](android/CHANGELOG.md) · [路线图](android/ROADMAP.md) |

## GitHub Actions

[云端工作台 CI](https://github.com/locy016/CloudWorkbench/actions/workflows/cloud-workbench-ci.yml) 运行网关测试、Android Lint 和未签名 APK 构建。评审请求会触发检查，也可手动指定一个完整的 Git 提交 SHA。运行详情提供 Lint 报告、未签名 APK、SHA-256 和实际检出的提交号。

Android APP 的「会话 → 任务」通过现有 SSH 选择分支或提交并发起构建。断线后 GitHub 继续执行，重连可查看同一次运行的检查结果、作业日志和产物入口。

公开仓库用于源码阅读和构建验证。正式应用仍通过私有更新源分发。SSH 私钥、正式签名材料、运行数据和正式发布包保存在受控目录。

[栖物里](https://qiwuli.com/)
