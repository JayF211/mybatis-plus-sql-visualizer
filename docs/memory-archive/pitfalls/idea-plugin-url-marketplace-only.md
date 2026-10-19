---
name: idea-plugin-url-marketplace-only
description: idea-plugin 根元素 url 属性在 IDE 插件列表零消费（262 全量反编译实证），展示位仅在 Marketplace
  插件页 Homepage；IDE 详情页六链接位与 vendor 行全读 Marketplace 元数据
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3eaac055-4e1d-492f-8325-fe6d2130e6e2
---

`<idea-plugin url="...">`（plugin.xml 根元素）在 IDEA 插件列表（Settings → Plugins）**完全不显示**——2026.2（262）平台全量 jar 扫描实证：除 `PluginDescriptor` 接口（lib/util.jar）与 `DefaultPluginDescriptor` 实现自身，零个类调用 `getUrl()`。详情页 `PluginDetailsPageComponent`（intellij.platform.ide.impl.jar，`com/intellij/ide/plugins/newui/`）的六个链接位（Forum/License/Bug tracker/Documentation/Source code/Report plugin）全部读 `PluginUiModel`，其实现 `PluginUiModelAdapter` 直接转发 `PluginNode`（Marketplace 仓库元数据）；url getter 只有 forum/license/bugtracker/documentation/sourceCode/report/download 七个，无 homepage 项。vendor 行 `VendorInfoPanel` 只读 `PluginNodeVendorDetails.getUrl()`（Marketplace 厂商档案），连 plugin.xml 的 `<vendor url>` 也不读。链接位空串/null 即隐藏（`updateUrlComponent`）。

属性展示位唯一在 Marketplace 网页端：上传时自动从 plugin.xml 抓取收录——`https://plugins.jetbrains.com/api/plugins/33633` 实证 `urls.url = https://sql-visualizer.jvtools.cn`（同对象还有 forumUrl/licenseUrl/privacyPolicyUrl/bugtrackerUrl/docUrl/sourceCodeUrl；本项目 docUrl/bugtrackerUrl/forumUrl 全空）。Marketplace 页面是 JS 渲染，WebFetch 只能拿到标题，查 listing 链接字段走该 API。

想在 IDE 插件列表露出官网：去 plugins.jetbrains.com 插件管理页填 Documentation URL（docUrl，对应 IDE 详情页 Documentation 链接位，当前空）；description 的 HTML 链接本来就渲染在详情页（已在用，plugin.xml 37/52 行）。属性于 556f0a2（2026-09-14）加入，发行 42eb7f8 已含，Marketplace v2026.9.14 与本机 2026.2 安装副本（`AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\mybatis-plus-sql-visualizer\lib\`）均已带。

**Why:** 属性加了但 IDE 不显示，容易误判为构建/发布遗漏；实际平台 UI 从不消费本地 descriptor 的 url，消费方分工=IDE 插件列表只认 Marketplace 元数据。
**How to apply:** 再遇「插件列表缺某属性显示」先分清消费方是 IDE 还是 Marketplace；核对线上 listing 字段走 `api/plugins/<id>`；想让官网在 IDE 详情页露出就补 Marketplace listing 的 Documentation URL。相关 [[idea-262-platform-jar-verify]]、[[project-website]]、[[project-release-workflow]]。
