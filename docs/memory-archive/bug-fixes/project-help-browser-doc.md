---
name: project-help-browser-doc
description: 帮助按钮 → 本地 HTML 主路径零网络依赖（M36.5.80 定稿）；JCEF 不可用降级系统浏览器打开在线文档
metadata:
  node_type: memory
  type: project
  updated: 2026-09-01
  originSessionId: sess_release_2026_9_2
---

SQL 日志侧边栏工具栏「?」帮助按钮：从 resources 读取 `marketplace-custom-page.html`，用 IDEA 内嵌 JCEF 浏览器在编辑器标签页打开**本地 HTML 帮助文档**。

**最终方案（M36.5.80 定稿）**：本地 HTML（`resources/html/marketplace-custom-page.html`）为主路径，纯内联样式，**零网络依赖**；JCEF 不可用或资源缺失时降级 `BrowserUtil.browse()` 打开在线文档；删除网络探测逻辑。

**演化关键节点**：
- 2026-08-27~31：v1~v7 多轮迭代（JBCefBrowser→在线URL→WebPreviewVirtualFile→file://→loadHTML→data:text/html 最终方案）
- M36.5.80 (2026-08-31)：data:text/html 方案在国内网络仍加载超时、且 data: URL 样式丢失 → 改为主路径（marketplace-custom-page.html，纯内联样式），JCEF 不可用/资源缺失时降级系统浏览器打开在线文档，删除网络探测逻辑

**核心 API**：
- `JBCefApp.isSupported()` — JCEF 可用性检测
- `HTMLEditorProvider.openEditor(Project, title, url, timeoutHtml?)` — 编辑器标签页打开 URL
- `BrowserUtil.browse(url)` — 系统浏览器打开（走系统代理，用于降级）
- `data:text/html;charset=utf-8,...` — 绕过 file:// CSS 限制的编码方式

**⚠️ JCEF 代理铁律**：JCEF 启动时平台硬编码 `--no-proxy-server`，强制绕过所有代理；插件无法控制此行为。

**本地 HTML 源文件**：`docs/发行说明/marketplace-custom-page.html` → 打包到 `src/main/resources/html/`。

**Why**：帮助内容硬编码在 bundle 中维护成本高；在线文档受 JCEF `--no-proxy-server` 限制无法可靠加载；本地 HTML + data: URL（或 JCEF 主路径）无需网络、无代理问题。

**How to apply**：帮助内容写在 `docs/发行说明/marketplace-custom-page.html` 中，打包到 `src/main/resources/html/`；JCEF 无法走代理是平台限制。

**参考实现**：YiiGuxing/TranslationPlugin `src/main/resources/web/release-notes.html`。