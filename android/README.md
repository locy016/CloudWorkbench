# 云端工作台 Android

通过专用 SSH 密钥连接云端工作站。工作台提供项目、Codex 会话、任务与审批、离线转写、附件和应用更新。浏览页支持设备直连网页和查看服务器数据。书签、选区、图片和截图可作为当前工作台会话的取材。

## 连接与设备

在「设置 → 连接问题」导入经授权的专用 SSH 私钥。Android Keystore 保护保存的密钥。断线后恢复工作台连接，并补齐会话事件。发送回执丢失时按请求编号核对服务器受理状态。任务执行结果在会话中独立显示。概览采集失败时，首页标记指标暂不可用，会话和审批继续同步。

APP 短时切到后台时保留现有 SSH 连接和事件流。后台满 30 秒后暂停读取事件，返回前台时补齐更新。网络变化或 SSH 连接失效时自动重连。

## 任务续接

首页的「继续任务」显示待审批、待回答、执行中和发送待确认的事项。点选待处理请求进入对应审批详情。「最近任务结果」显示最近五个有结果的会话，点选可查看最新消息或失败原因。文本草稿和待发图片按项目及会话保存，重启 APP 后可恢复。手动重试原内容会复用同一个请求编号。

## 搜索历史与继续草稿

在「会话 → 历史会话」输入标题关键词，结合项目和状态筛选。搜索范围包括尚未加载的旧会话。「加载更多会话」会沿用当前条件。

首页的「继续草稿」按最近编辑时间展示草稿，可点开原项目或原会话继续编辑。草稿和待发图片保存在设备内，重启 APP 后也可离线打开。

## 服务器概览

首页「服务器概览」可手动刷新，采集时间显示到秒。异常服务排在正常服务之前。断线或采集失败时仍可查看带原采集时间的「上次采集状态」，重开 APP 后也可离线查看。

「最近异常」显示近 24 小时内采集到的核心服务异常状态，最多展示 6 项。网关每分钟采集一次 systemd 状态，连续相同状态只记一次。摘要显示服务、状态和发现时间。

## 浏览页的服务器数据

「浏览」提供四个独立入口。「国际资讯」聚合要闻、时事和经贸动态。「数据查询与下载」按来源查询、预览和导出。「贸易指标」按国家和年份查看世界银行数据。「欧元参考汇率」查看欧洲央行发布的数据。

「国际资讯」经 SSH 获取聚合结果，支持组合使用分类、关键词、来源及 UTC 发布时间范围。日期包含起止当天，也可只填一端。每条显示来源、时间和摘要，可以复制原文地址。只有更新时间的条目标为「更新」，未提供有效日期的条目排在末尾。限定日期时仅收录来源提供发布时间的条目。

经贸资讯包括 WTO、英国商业与贸易部、欧盟贸易总司、美国贸易代表办公室和英国贸易救济署。日期筛选作用于上游资讯流当前提供的条目。[WTO RSS](https://www.wto.org/english/res_e/webcas_e/rss_e.htm) · [英国商业与贸易部](https://www.gov.uk/government/organisations/department-for-business-innovation-science-and-trade) · [欧盟贸易总司](https://policy.trade.ec.europa.eu/news_en) · [美国贸易代表办公室](https://ustr.gov/about/policy-offices/press-office/news) · [英国贸易救济署](https://www.gov.uk/government/organisations/trade-remedies-authority)

「国际资讯」按首选语言自动翻译屏幕附近资讯的英文标题和摘要，默认简体中文，也可选日语、韩语、法语、德语或西班牙语。APP 记住首选语言。单条和全页都可切换查看原文，切回译文时复用缓存。译文未返回时可先阅读原文。翻译请求通过 SSH 发送到工作台，模型在服务器本地运行。上游暂时失败时继续展示已取得的资讯，并标明使用缓存或未响应的来源。

在「来源目录」选择单个来源后，可按关键词和 UTC 日期范围查询。日期范围包含所选首尾日期。页面分别显示匹配数、预览数和可保存条数。「保存 CSV」和「保存 JSON」可下载筛选后的记录。

内置示例支持按时段获取全球地震动态、按项目获取发布记录，以及按自填关键词查询公开项目。[USGS GeoJSON 实时数据](https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php) · [GitHub 发布 API](https://docs.github.com/en/rest/releases/releases#list-releases) · [GitHub 搜索 API](https://docs.github.com/en/rest/search/search#search-repositories)

贸易及物流来源包括 WTO 新闻 RSS、美国国家气象局的沿海海运气象警报 Atom，以及 NOAA CO-OPS 纽约和洛杉矶测站近 72 小时的潮位 CSV。页面显示条目时间、来源更新时间和服务器获取时间。潮位来源每六分钟一条观测，记录保留 NOAA 的质量标记。[WTO 新闻](https://www.wto.org/english/res_e/webcas_e/rss_e.htm) · [NWS 海运气象警报](https://www.weather.gov/documentation/services-web-alerts) · [NOAA 潮位接口](https://api.tidesandcurrents.noaa.gov/api/dev/)

服务器数据的「参考汇率」提供欧元参考汇率。服务器读取欧洲央行的官方数据并经现有 SSH 连接返回结果。页面标明来源、数据日期和汇率基准。欧洲央行公布的参考汇率仅供参考。[欧洲央行官方汇率](https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html)

网关缓存成功结果 15 分钟。数据源不可达时会标明服务器缓存，SSH 断线后仍可查看本机上次取得的数据。页面使用原生界面渲染文本，无需加载第三方页面或资源。

同一页的「经贸指标」支持按国家或经济体和统计年份查询。页面展示货物和服务的出口额、进口额及贸易总额占 GDP 比重，并标明统计年度和世界银行数据集更新日期。来源尚未提供的数值显示为「暂无数据」。金额按现价美元取整，比重保留两位小数。[世界银行《世界发展指标》](https://data.worldbank.org/indicator/NE.TRD.GNFS.ZS) · [数据使用条款](https://data.worldbank.org/summary-terms-of-use)

网关经世界银行官方指标 API 读取国家目录和年度数据。国家目录缓存 24 小时，年度数据按国家和年份缓存 12 小时。来源超时会显示同一查询的上次结果及缓存状态。APP 通过现有 SSH 连接获取结果。

## 构建和发布

准备 JDK 17、Android SDK 35 和已校验的本地语音模型。从本目录执行。

```powershell
.\tools\prepare-voice-model.ps1
cd android
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

从项目 Android 根目录运行 `tools/sign-app-release.ps1` 生成同签名 APK，用 `tools/apk_metadata.ps1` 核验包名、版本、证书和摘要。签名库和密码位于受限的 `signing/` 目录。提高构建号后使用 `tools/publish-app-update.ps1` 发布。正式客户端从「设置 → 应用更新」经 SSH 获取包，支持分段续传，并在交给系统安装器前复验签名和摘要。USB 覆盖安装使用 `adb install -r`，保持正式包的签名连续。

使用 `-PusbAcceptance` 构建独立验收包 `cn.xux.cloudworkbench.acceptance`。实体设备上可与正式版本并存。

[开发计划与使用场景](ROADMAP.md) · [网关与服务端数据](server/README.md)
