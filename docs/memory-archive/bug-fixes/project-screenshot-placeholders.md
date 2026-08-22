---
name: project-screenshot-placeholders
description: 功能截图占位方案（7ca3f05）——docs/images/screenshot-01~04
  命名规范、1200×760、二维码占位图、三处展示位置（plugin.xml 外链/README/项目介绍）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_59b5b8b2-2456-4526-a6e9-f90baa01516f
---

**功能截图占位已就位（2026-08-16，提交 7ca3f05，已推送）**：插件说明与项目文档此前无功能截图（Marketplace 流程文档 §2.2 待办 P1）。用户要求预留位置 + 二维码占位 + 命名规范 + 尺寸调整，后续只替换图片。

- **图片命名**（docs/images/，4 张）：`screenshot-01-sql-preview.png`（Wrapper→SQL 预览弹窗）/ `screenshot-02-log-panel.png`（SQL 日志工具窗口）/ `screenshot-03-sql-to-java.png`（SQL→Java 反向转换）/ `screenshot-04-xml-preview.png`（XML·注解动态 SQL 预览）。规则：`screenshot-{序号}-{功能短名}.png`，带序号保证展示顺序。
- **尺寸**：占位图 1200×760（Marketplace 推荐比例 1.58）；展示处 width=720 控制不溢出。
- **占位内容**：微信二维码居中 + 浅灰底 + 文件名/尺寸英文标注（生成脚本 `dev-support/make-screenshot-placeholders.ps1`——PowerShell System.Drawing，无 PIL 依赖；重跑即重新生成 4 张）。
- **三处展示位置**：① ~~`plugin.xml` `<description>` 内嵌外链图~~ **已废弃**（2026-08-19 Gitee 防盗链 302 阻断，全部 7 张图已移除，改为 Marketplace 内置 Screenshots 区域网页上传 → JetBrains CDN 托管）；② `README.md` 「📸 功能截图」节（相对路径 + HTML img width=720）；③ `docs/项目介绍.md` 核心功能后同款。
- **plugin.xml 当前描述（2026-08-19 更新）**：仅一行文字 "Screenshots are available on the plugin Marketplace page."（带 Marketplace 链接），无任何 `<img>` 标签。二维码（wechat/group）也一并移除。
- **用户替换方式（README/项目介绍）**：替换 docs/images/screenshot-*.png 同名文件（保持 1200×760）→ 提交推送。Marketplace 截图：网页直接上传（URL 不变无需改 plugin.xml）。
- ⚠️ 提交后并行会话在途：SQL→Java 反向增强（手动实体选择/@TableName 反查/? 占位符 round-trip，CHANGELOG 新建 [2026.8.17] 分区）——与本改动不相交。

相关：[[project-marketplace-publishing]]（§2.2 待办截图项）、[[project-v2026816-release]]
