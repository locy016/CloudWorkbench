# 云端工作台服务端网关

| 组件 | 源码和职责 |
| --- | --- |
| Android 网关 | [src](src/) 包含 SSH 会话鉴权、Codex 请求与事件、工作区和会话存储、浏览代理与附件、Android 更新、Windows 授权管理及订阅签发。 |
| Windows 接入与登记 | [windows/server](../../windows/server/) 包含 HTTPS 接入与转发、设备登记及续期、出口探测和用量记账。服务配置放在其 ops 目录。 |
| 共享契约 | [public-routes.json](src/public-routes.json) 与 [device-policy.mjs](src/device-policy.mjs) 由客户端或接入服务引用。修改后同步核对客户端和反向代理配置。 |

## 本地检查

使用 Node.js 22.13 或以上版本。在本目录运行：

```bash
npm ci
npm test
```

Windows 接入与登记的隔离测试从仓库的 windows 目录运行：

```bash
node --test tests/Server/authorization-policy.test.mjs tests/Server/enrollment.test.mjs
```

接入传输测试另需固定版本的 Linux sing-box，使用 WORKBENCH_TEST_CORE 指定可执行文件。真实 Nginx 路由验收在具备 Nginx 的隔离环境运行。

## 部署入口

- Android 网关的服务单元为 cloud-workbench.service。部署和版本切换工具见 [ops](ops/)，SSH 会话、设备配对操作见 [SSH 会话](ops/ssh-mobile.md) 和 [Windows 配对](ops/windows-pairing.md)。
- Windows 接入和登记使用独立服务单元。现行路径、启动顺序及验证见 [接入交接](../../windows/docs/handoffs/server.md) 和 [登记交接](../../windows/docs/handoffs/server-enrollment-0.4.md)。
- 业务数据库、设备授权数据库、用量数据库、安装包和签名文件由生产环境单独管理。发布前核对服务身份、数据目录和活动任务。生产配置或运行中数据的备份应保存在受控位置。
