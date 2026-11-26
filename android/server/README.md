# 云端工作台网关

`cloud-workbench.service` 在 `127.0.0.1:8766` 提供工作台 API。Android 使用只授予固定业务端口访问权的 SSH 密钥和短期令牌。

发布、重启和 SSH 运维由管理员使用独立授权入口。`workspace-write` 是日常 Codex 模式。共享可写目录由 `codex-writable-roots.json` 管理。

## 本地测试与 CI

进入本目录，使用 Node.js 22.13 或更高版本运行：

```text
npm ci --ignore-scripts
npm test
```

[GitHub Actions](https://github.com/locy016/CloudWorkbench/actions/workflows/cloud-workbench-ci.yml) 使用同一套测试。生产部署后单独验证 SSH 鉴权、会话恢复、图片附件和 APK 分段更新。

## 任务页构建

`GET /v1/builds` 通过 SSH 鉴权读取本仓库的公开 CI 状态。配置专用令牌后，任务页可从 `/v1/builds/sources` 读取分支与最近提交，通过 `POST /v1/builds` 启动构建，并通过 `/v1/builds/requests/<编号>` 查看同一次运行的状态和作业日志入口。

为 `locy016/CloudWorkbench` 创建细粒度令牌，授予 `Actions: Read and write`。部署服务账号独有的 `DATA_DIR/github-actions.token` 文件，权限设为 `0600`。文件更新在下一次请求生效。APP 和公开仓库均无需配置令牌。

网关验证提交属于所选分支，将实际源码 SHA 和唯一请求编号写入 SQLite，再调用 GitHub。工作流定义取自 `main`，实际构建执行已锁定的源码。回执丢失时，网关按请求编号核对运行记录。收到同一编号时返回已保存请求。

## 数据与发布

- 程序目录为 `~/.local/lib/cloud-workbench`。业务数据位于 `~/.local/share/cloud-workbench`，其中 `workbench.db` 保存会话。生产更新源由[独立签名服务](ops/release-service/README.md)写入，网关通过 `APP_UPDATES_DIR` 读取。
- 部署前确认活动任务已完成，对 SQLite 数据库做一致性备份，并检查附件目录和已签名版本。通过既有 `cloud-workbench-ops restart-workbench` 运维入口在空闲时重启。回退使用工作台版本及其配置。
- 工作台域名的 HTTP 和 TLS 拒绝配置见 [Nginx 切换脚本](ops/close-workbench-web-ingress.sh)。生产工作区由数据目录的 `projects.json` 配置，包括栖物里网站、服务器运维和云端助理。
- 手机密钥的绑定规则见 [SSH 业务连接](ops/ssh-mobile.md)。

退役清理通过 Nginx 配置、运行状态和旧规则检查验收。证书续期单独管理。关闭工作台域名的公网解析时，现有 HTTP-01 续期无法完成。如继续使用公开受信证书，采用 DNS-01 验证。

## 工作区与会话模型

在 `projects.json` 的数组中登记 ID 为 `cloud-assistant` 的「云端助理」及其独立绝对路径。网关将此工作区设为新会话默认，并通过项目目录接口告知 APP。配置首项仍作为历史旧会话缺少工作区字段时的归属。

模型列表从服务器 Codex 的 `model/list` 读取，过滤隐藏项。网关验证选择并写入会话记录，下轮通过 `turn/start` 使用该模型。恢复同一 Codex 会话时继续应用该选择。已有工作区停用或会话正在执行时禁止切换。

## 国际资讯来源

服务端读取 `DATA_DIR/resource-sources.json`，文件存在时以整份目录为准。每条定义采用 RSS 或 Atom，填写资讯栏目、来源说明和固定 HTTPS 地址。需要固定查询参数时使用 `query`，用 `cacheSeconds` 控制刷新间隔。将完整配置写入同目录临时文件并原子替换后，下一次资讯请求会读取新定义。[内置资讯来源](src/resource-sources.json)

SSH 资讯接口支持组合传入 `category`、`q`、`source`、`from` 和 `to`。日期为 `YYYY-MM-DD`，按 UTC 比较并包含边界，可只填一端。设置日期时，仅比较来源明确提供的发布时间。网关先筛选，再按条目时间排序。去重比较原文地址（忽略跟踪参数）或 48 小时内相同的长标题，保留展示记录的原始链接。单个来源失败时仍返回其余来源，并标明暂不可用的来源。资讯最多返回 120 条。

资讯源取数或解析失败后短暂退避。聚合接口可使用进程内 6 小时内成功解析的同源结果，并返回使用缓存的来源和采集时间。网关按公开 IPv4 地址连接，并限制取数时长和响应大小。

正文接口仅接受资讯列表返回的文章标识，经 SSH 验证后从相应来源配置中的固定 HTTPS 域名获取正文。重定向使用相同的来源域名限制。网关返回纯文本段落、标题和列表，以及来源页面声明的语言。正文上限为 500 块及 80,000 字符，截断会明确标记。正文短期缓存在网关进程内。APP 将可见英文正文分成短段，复用同一私有翻译接口和缓存。

英文资讯条目从目录的 `language: "en"` 获取语言标记。翻译接口仅接受 SSH 授权请求。自动翻译请求可包含最多三条标题和摘要，旧版单条翻译仍走原接口。两种请求共用服务器缓存，命中缓存的条目无需再次推理。每次只运行一个翻译进程。部署时用 Python 3.12 虚拟环境安装 `ctranslate2==4.6.0` 和 `sentencepiece==0.2.1`。`NEWS_TRANSLATION_DIR` 指向该虚拟环境，默认路径为 `/home/xux/.local/lib/cloud-workbench-translation`。从 Argos 官方分发目录获取 `en_zh 1.9`、`en_ja 1.1`、`en_ko 1.1`、`en_fr 1.9`、`en_de 1.3` 和 `en_es 1.0`，核对包内 `metadata.json` 的语言和版本。将每个包的 `sentencepiece.model` 和 `model/` 放入虚拟环境的 `models/<目标语言>/`。[Argos 官方模型目录](https://data.argosopentech.com/argospm/v1/)

## 实时汇率

`GET /v1/server-data/exchange-rates` 需要移动端 SSH 授权。网关读取固定的 Coinbase USD 报价源，返回支持的法币及服务器取得时间。正常请求复用 30 秒内的结果，`?refresh=1` 可请求提前更新，同一进程每 5 秒最多尝试一次。上游失败时标记已取得的缓存，首次取数失败时返回 503。[Coinbase 汇率接口](https://docs.cdp.coinbase.com/coinbase-app/track-apis/exchange-rates)
