---
name: t-ui-native-research
description: UI/功能原生化调研+四批次实施已交付（t-uinative 8 提交，全量 4147 绿，未合 master）——取证路线、P0-P2 落点与四条新沉淀
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f22647d8-8769-4437-8e2c-6d2684c1b887
---

UI 原生化「调研 + 全四批次实施」均已交付。调研报告 `docs/调研报告/20260915-2327-IDEA原生能力UI与功能优化-调研报告.md`（master 4d4fc74）；实施计划 `docs/开发计划/20260916-0238-UI与功能原生化四批次实施-开发计划.md`（master 93f0b13，§九实施记录+偏差全量回填）；实现落 **t-uinative 分支 8 提交（0e857ec..5ca0b76，worktree F:/code/Java/Gitee/SongRenShuo/mpsv-uinative）**，全量 **4147/0/0/6 绿**（基线 4123），**未合 master 未 push**。落点速览：剪贴板统一 util/UiTextClipboard（12 处收口）、19 处模态轻提示换 preview 组通知、OnePixelSplitter（键 mpvs.preview.splitter）、右键菜单 DefaultActionGroup+ActionPopupMenu（保留原 MouseAdapter 触发防双菜单）、ListSpeedSearch×3、SqlListSelectionOutline/ensureOutlineContrast 退役、JOIN 图与历史导航 ActionToolbar、捕捉开关挂 ToolWindow.setTitleActions、33 项色 namedColor 集中 util/MpvsUiColors、JBLoadingPanel/AnimatedIcon/EditorEx.setPlaceholder、项目级设置页迁 ui.dsl.builder 试点、落地页渲染器归一；明确不做：com.intellij.diagram 迁移/快捷键录制裁/SqlClickPreview 换 EditorTextField（243 SQL FileType 依赖 Database 插件）/SqlPreviewPanel 文本按钮行转图标（观感重设计，防真机退回）/主设置面板全量 DSL（立后续）。

**本轮新沉淀**：
- **编译门禁=2024.3.7.1（243 distro）**：gradle transforms 解压目录 `~/.gradle/caches/9.7.0/transforms/*/ideaIU-2024.3.7.1-win`，243 平台类多在 `lib/app-client.jar`（不是 262 的 intellij.platform.ide.jar 布局）；javap 用 `~/.jdks/ms-21.0.11/bin/javap.exe` 且 `-cp` 必须 Windows 反斜杠路径（MSYS `/c/...` 路径报「找不到类」）。
- **CopyPasteManager 在 headless 测试只存平台句柄、不回写系统剪贴板**——断言剪贴板内容的测试必须读 `CopyPasteManager.getContents(stringFlavor)` 而非 Toolkit 直读（CopyFeedbackUiTest 实证，轮询无效）。
- **BannedApiSourceScanTest「ActionToolbar 刷新」规则已反转**（5ca0b76）：钦定 `updateActionsImmediately()`+AnAction.update() 声明外观，禁「"ActionButton" 判等 / getMethod("getAction")」反射 hack；updateActionsAsync/updateImmediately 仍禁。未来调 ActionToolbar 刷新别走老反射路线。
- **基线 flake 修复范式**：测试 finally 里「读当前值再还原」= 污染泄漏（HistoryCellRendererTest 时间戳格式实证），快照必须取 setUp 时值；同类隐患 grep `restore.*saved` 可查。
- 基线遗留待收口：master build.gradle.kts:144 T-ListParamCollect 临时 sandboxContainer 覆盖未还原（无条件覆盖 gifRecord 分支）；SqlToJavaPanel 同款 inputHint/resultHint 待换 placeholder；主设置面板 DSL 迁移立 T-UN-15b。
- 相关：[[t-ui-native-research]] 前篇取证方法论（262 Writerside 文档站/JBR 无 javap/262 包名迁移清单）仍有效。
