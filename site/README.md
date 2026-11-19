# 栖物里 · QIWULI

网站：<https://qiwuli.com>。品牌：**栖物里，让作品与想法安放的地方**。联系邮箱：`lo.c@live.cn`。

## 项目与功能

本站使用 Vue、Vue Router、Vite，本地构建，由 Nginx 分发静态文件，不需要 Node 常驻服务、数据库或管理后台。

项目集共有 7 个项目，支持分类、关键词搜索、空状态与项目详情：

| 项目 | 状态与入口 |
| --- | --- |
| 云端工作台 | 私有项目，仅展示实践方向，不提供注册、接口或安装包 |
| 拾色之间 | `/lab?tool=palette`：四组自然配色，复制色值与整组颜色、下载 SVG 色卡 |
| 片刻专注 | `/lab?tool=focus`：5/15/25 分钟，暂停、继续、重置，站内切换页面继续计时 |
| 像素花园 | `/lab?tool=garden`：12 × 12 像素画，鼠标拖绘/触摸/键盘，8 色含橡皮擦，撤销最近 30 步、本机保存、SVG 下载 |
| 灵感抽签 | `/lab?tool=ideas`：观察/创作/生活三个主题，18 条预设练习，避免连续重复上一张，复制签文 |
| 今天选什么 | `/lab?tool=choice`：自定义 2–20 个选项，忽略空行与重复项，每项最多 40 字，随机选择、结果复制 |
| 呼吸留白 | `/lab?tool=breath`：4 秒吸气/6 秒呼气，1/2/3 分钟练习，暂停、继续、重置，支持减少动效 |

首页展示 3 个精选项目与笔记入口，项目集保留全部 7 个项目。笔记包含三篇完整文章，支持分类搜索、本地收藏、阅读进度、目录、分享和相关阅读。关于页说明隐私与使用边界；联系页可复制邮箱或准备邮件草稿，不会自动发送。手机导航、键盘焦点、状态反馈和全局动效开关保持可用。右上角动效使用 14px 无边框、透明底的播放 / 暂停图标，桌面点击区域 32 × 32、手机 44 × 44，保留状态区分、悬停提示、键盘焦点和读屏标签。

## 数据与使用边界

- 功能都在浏览器内运行，绘画、候选项、随机结果不上传服务器，没有第三方统计。
- 收藏与动效偏好存于当前浏览器的 localStorage。像素画仅在主动点击“保存到本机”后保存，重新打开会加载该画作；保存失败会明确提示并建议下载。清理网站数据会删除这些内容。
- 四个新工具在实验室内切换时保留当前状态；离开实验室或刷新页面后，除已保存的像素画外均重置。候选项和抽签历史不持久化。
- 呼吸练习切到其他实验、栏目或浏览器后台时暂停。使用实际时间计算，避免后台延迟导致错误计时。它只是节奏提示，按舒适程度自然呼吸即可。
- 原专注计时器切换栏目继续计时，刷新或关闭页面后重置。呼吸和专注工具都没有声音或通知。
- SVG 下载由用户触发，在浏览器生成。联系表单仅准备本机邮件草稿。
- Nginx 保留常规访问日志。使用 Vue 单页路由，未知项目/文章显示站内 404 页面；SPA 页面仍是 HTTP 200。未公开 API 和缺失资源返回 HTTP 404。

## 开发与验证

源码目录：`/home/xux/projects/qiwuli-site`。Node 要求 22.12+，本次使用服务器已有的 Node 26.13.0。依赖版本固定在 `package-lock.json`，本次未新增应用依赖。

```bash
npm ci
npm run dev
npm test
python3 -m unittest discover -s tests -p 'test_*.py' -v
npm run build
python3 deploy_local.py --check
npm run preview -- --port 8780
```

`src/content.js` 集中维护项目/文章；`src/experiments.js` 提供新工具的数据和独立逻辑；`src/components/` 中的四个工具组件实现交互。`src/experiments.css` 扩展原有自然色系布局。新增项目或文章时同步更新 `public/sitemap.xml`。

可选的真实浏览器验收使用 `tests/browser_smoke.mjs`，需先在另一个终端启动生产预览。Playwright 不加入网站运行依赖：

```bash
npm install --prefix /tmp/qiwuli-browser-qa --no-audit --no-fund playwright
/tmp/qiwuli-browser-qa/node_modules/.bin/playwright install chromium
QIWULI_PLAYWRIGHT_MODULE=file:///tmp/qiwuli-browser-qa/node_modules/playwright/index.mjs node tests/browser_smoke.mjs
```

浏览器验收需要可用的系统运行库和中文字体，截图输出至 `qa/`。测试向预览响应加入生产站点相同的 CSP，检查交互、移动端、减少动效和浏览器报错。

## 本服务器发布

先以普通用户构建和测试，再使用固定的运维命令发布至 `/var/www/qiwuli.com`：

```bash
sudo -n /usr/local/sbin/cloud-workbench-ops check-site
sudo -n /usr/local/sbin/cloud-workbench-ops deploy-site
python3 tests/live_smoke.py
```

发布助手检查构建产物和 Nginx 配置，替换站点后验证 HTTPS，上一版由助手暂存于 `/var/www/.qiwuli.com-previous`。`deploy_local.py --check` 可独立校验本地构建产物。

## Nginx 与证书

现有配置位于 `/etc/nginx/sites-available/qiwuli.com`。HTTP 跳转 HTTPS，www 跳转主域名；静态入口 no-cache、哈希资源缓存一年；CSP 只允许本站资源；dotfiles 禁止访问。配置模板仍在源码中，本次内容更新不需要改动它们。

证书由现有 certbot.timer 续期，路径 `/etc/letsencrypt/live/qiwuli.com/`，webroot `/var/lib/letsencrypt`。证书、SSH、CloudWorkbench 和防火墙不属于本次内容扩展。

## 源码记录

本目录从栖物里维护源码导入 CloudWorkbench 仓库。服务器上的旧源码校验清单属于历史迁移基线。本次验证与发布状态见 `SERVER-README.md`。

## 工作台下载页

工作台下载页独立于栖物里主站。模板和样式见 [windows/portal](../windows/portal/)。由 [构建脚本](../windows/tools/portal/build-portal.mjs) 读取 Windows 发行清单和 ZIP 并校验 SHA-256，再生成页面及公开版本清单。

```bash
node windows/tools/portal/build-portal.mjs <windows-release.json> <Windows-ZIP> <输出目录>
```

Windows 发行与发布核对见 [Windows 交接](../windows/docs/handoffs/rust-0.4.md)。
