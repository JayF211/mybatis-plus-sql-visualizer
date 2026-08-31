---
name: pitfall-gitee-hotlink-302
description: Gitee raw 图片外链被 JetBrains Marketplace 302 防盗链拦截——plugin.xml 描述图全部不可见，推荐 jsDelivr CDN 替代
metadata:
  node_type: memory
  type: project
  originSessionId: sess_db473da9-782c-45bb-9050-b7bde4fcf5c8
---

**Gitee raw 图片在 Marketplace 被 302 拦截（2026-08-19 用户实证）**：plugin.xml `<description>` 中 7 张图（screenshot-01~05 + wechat-qr + group-qr）全部走 `https://gitee.com/.../raw/master/docs/images/xxx.png`，JetBrains Marketplace 页面加载时 Gitee 返回 302 跳转到 `assets.gitee.com/favicon.ico`（防盗链 Referer 检查），图片全部不可见。

**根因**：Gitee 对 `Referer: plugins.jetbrains.com` 的請求触发防盗链，返回 302 而非图片内容。实证：`curl -H "Referer: https://plugins.jetbrains.com/" "https://gitee.com/.../raw/..."` → `HTTP 302 → https://assets.gitee.com/favicon.ico`。

**推荐方案：jsDelivr CDN（兼顾国内外访问）**：

```
https://cdn.jsdelivr.net/gh/用户名/仓库名@master/docs/images/screenshot-01-sql-preview.png
```

- 底层拉 GitHub 文件，CDN 分发（国内有节点加速，无防盗链）
- 优于 raw.githubusercontent.com（GFW 干扰，国内部分用户加载失败）

**双渠道展示策略（最优）**：
1. **Marketplace 详情页 Screenshots 区域**：网页直接上传截图 → JetBrains 自有 CDN，全球最稳
2. **plugin.xml `<description>` 内嵌图**：改用 jsDelivr CDN 链接（IDE 内弹窗也能看）

**步骤**：① 创建 GitHub 仓库（仅用于 CDN 源，源码链接也填它） ② `git remote add github` + push 含图片的 master ③ 修改 plugin.xml/README/项目介绍 中所有 Gitee raw URL → jsDelivr URL ④ 重新构建发布。

**最终决策（2026-08-19）**：放弃内嵌外链图方案，改为：
1. **plugin.xml `<description>` 移除全部 7 张图**（5 截图 + 2 二维码），替换为一行文字 "Screenshots are available on the plugin Marketplace page." 带链接
2. **Marketplace 内置 Screenshots 区域直接上传**：用户网页上传 → Jetbrains CDN 托管，全球最稳（无需 GitHub/jsDelivr/CDN 任何外链）
3. **无 GitHub 仓库**：用户不引入 GitHub，纯 Gitee 托管

**实施状态**：plugin.xml 已编辑完成（7 张图全部移除，替换为一行 Marketplace 链接文字）。Marketplace 截图上传待用户操作。

**Custom Page 已生成（2026-08-19）**：`docs/发行说明/marketplace-custom-page.html`——Marketplace Custom Tab 的完整 HTML，含 Feature Matrix（SQL 预览/日志/反向/其他四张功能对照表）+ FAQ（11 条，覆盖 IDE 版本/gutter 触发/MPJ 联表/预览差异/?占位符/日志捕获/编译保证/语言切换/方言/快捷键冲突/反馈信息）+ 默认快捷键 + 相关链接。深浅主题自适应 + 手风琴交互。用户可直接复制粘贴到 Marketplace Custom Tab 编辑器。Quick Start 节暂缓（无 GIF 素材）。

**Custom Tab 命名**：用户选定 **"Documentation"**（ Marketplace 通用命名，用户认知成本最低）。

**⚠️ Marketplace Custom Page HTML 过滤陷阱（2026-08-19 实证）**：Marketplace 编辑器对 HTML 做安全过滤（XSS 防护），`<details>` / `<summary>` 等 HTML5 交互元素被剥离或失去默认行为——浏览器本地预览正常的折叠手风琴，粘贴后无折叠效果。同样 `<script>` 标签也会被过滤。

**应对**：改用纯 CSS checkbox hack（`<input type="checkbox">` + `<label>` + `:checked` 伪类）或接受平面展示（`<h3>` + 锚点目录导航）。未来若需交互式 FAQ 须用前者。

相关：[[project-screenshot-placeholders]]（图片命名规范与三处展示位——plugin.xml 展示已废弃）、[[project-marketplace-publishing]]（发布流程与源码链接）