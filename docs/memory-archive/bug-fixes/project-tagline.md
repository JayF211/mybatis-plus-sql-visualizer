---
name: project-tagline
description: 产品标语定稿「服务未启，SQL 先知」（2026-09-12），五处口径统一
metadata:
  node_type: memory
  type: project
  originSessionId: sess_603e7096-afc1-4f07-afa4-299ab8d86b1c
---

标语定稿：**「服务未启，SQL 先知」**，英文对应 **"See the exact SQL your code will run — before the server starts."**（2026-09-12 由多轮候选对比选定，弃用「让 SQL 调试变得简单」「一眼看到代码将要执行的真实 SQL」）。

已统一落地的位置：`plugin.xml` description 中英首句、`src/main/resources/html/marketplace-custom-page.html` tagline、README.md 首行 blockquote（改名说明已按用户要求删除）、文档站 `HeroSection.vue` 中文首页主标题（高亮 span 在「SQL 先知」）、文档站 `en/index.md` hero tagline 开头。后续新增对外文案（发行说明、功能页）应沿用此口径，不要再造新标语。

同批交付（master 37fafcb / 文档站 3d51026）：plugin.xml 描述重构（一句话定位+核心能力+最近更新三层）、marketplace-custom-page.html 精简（FAQ 14→7、功能矩阵 4 表→3 表、首屏亮点流样式统一为「**加粗标题**：说明」）、全站反馈入口从 Gitee Issues 切官网需求池 `https://sql-visualizer.jvtools.cn/about/feedback`（英文 `/en/about/feedback`）。历史台账（开发计划实施记录、CHANGELOG）中的 Issues 记录有意保留未改。相关 [[project-doc-conventions]]。

marketplace-custom-page.html 暗色适配铁律：该页有**两条**暗色路径须成对覆盖——`prefers-color-scheme: dark` 媒体查询（系统暗色）+ `body.dark` class 注入（JCEF 检测主题注入），改任何视觉规则两处都要同步。2026-09-12 实证踩坑：`table tr:hover td { background:#fafafa }` 漏了暗色覆盖，IDEA 暗色下悬停单元格浅底浅字看不清；已修并提交（暗色 hover=#383838，两路径均已补，master 5c3165a）。
