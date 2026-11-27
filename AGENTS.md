# 云端工作台开发

APP 中选择「云端工作台」工作区再开发本项目。生产开发副本在 `/home/xux/projects/cloud-workbench`，对应 `locy016/CloudWorkbench`。先运行 `git status --short --branch`，检查现有改动并保留。查找相邻实现和测试，按用户要求修改并验证。

## 源码和验证

- Android 界面和 SSH 客户端在 `android/android/app`。Windows 开发使用本机 Android SDK 和 Gradle 生成 APK，命令见 [Android 构建说明](android/README.md)。
- 网关源码和测试在 `android/server`。使用 Node.js 22.13+，在该目录运行 `npm ci --ignore-scripts` 和 `npm test`。Linux 上的发布服务测试使用 `python3 android/server/test/release_service_test.py`。
- 网站源码在 `site`。当前项目的网关、APP 和签名服务由各自的部署入口处理，见 [网关](android/server/README.md) 和 [正式发布](android/server/ops/release-service/README.md)。

## 服务端开发与发布

1. 在开发副本创建分支，完成修改、测试和提交。使用仓库专用 `origin` 推送获授权的提交，源码进入公开仓库前检查变更，排除生产数据、凭据和发布包。Git 或部署命令若被会话沙箱拦截，只为当前命令发起审批。
2. Android 代码由 GitHub Actions 构建。也可从服务器项目根目录运行 `node android/server/ops/cloud-build.mjs start <分支> <完整提交SHA>`。用返回的请求编号执行 `node android/server/ops/cloud-build.mjs status <请求编号>`。发送超时时，用原编号查询或在 `start` 参数末尾带原编号重试。工作流定义取自 `main`，构建检出的源码由 SHA 锁定。保持同一运行编号跟踪三个检查结果。
3. Android 正式发布要求源码已进入 `main`、版本号已递增、测试和验收符合发布要求。运行 `cloud-workbench-release submit <运行编号> '更新说明'` 后用 `cloud-workbench-release status <运行编号>` 查看候选。把版本和批准摘要交给用户，在 APP「会话 → 任务 → 正式发布审批」批准，再查发布结果。签名与私有更新清单由独立服务处理。
4. 网关发布使用 `bash android/server/ops/deploy-gateway.sh --check <完整提交SHA>` 检查 GitHub `main` 上的版本。获得部署授权后使用 `--deploy`，网关将在空闲时重启。当前 APP 会话可能暂时断开，重连后检查网关状态、工作区读取和会话。受保护的系统单元、签名部署和权限调整走各自的管理员入口。
