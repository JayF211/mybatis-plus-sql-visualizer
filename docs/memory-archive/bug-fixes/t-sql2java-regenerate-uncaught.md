---
name: t-sql2java-regenerate-uncaught
description: T-PickMpjErr SQL→Java 选类重渲染 SqlReverseException 逃逸已修复（2026-09-12 工作树未提交，
  测试绿）；附「堆栈无 WIRA lambda 帧=旧构建」判读法
metadata:
  node_type: memory
  type: project
  originSessionId: sess_35c1b041-e4ab-40e9-8402-d3def7a739bc
---

2026-09-12 真机（262）日志分析定案两类问题，同日 T-PickMpjErr 已实施（master 工作树未提交）：

**问题一（无需改码）**：`Read access is allowed from inside read-action only` 双击预览连刷 5 条——真机装的发行版**早于 master dab3d3b**（T-ReverseParenWira 已把 prepareInConsole 的 attachConsole 包 WIRA）。判读法：堆栈里 `prepareInConsole` 直接反射帧到 `attachConsole`、中间无 `WriteIntentReadAction.compute` lambda 帧 = 旧构建；新构建应出现 WIRA 帧。每次双击 5 条是因 JdbcConsole 构造链有 5 处 getDocument 各断言一次。装新构建复测 idea.log 零增量即可。

**问题二（已修）**：SQL→Java 面板类选择器（ChooseByNamePopup）`elementChosen → setPicked → regenerateWithNewFormat → render → generateCode` 在 EDT 鼠标回调里同步执行，`JavaCodeGenerator.kt:113` 对「多表 JOIN + 调用类无 MPJ 能力」主动抛 `SqlReverseException`，regenerate 路径无 catch 逃逸成 UnhandledException 红叹号。修复=新增 `generateCodeOrReport` 辅助（catch → `status(..., error = true)`，结果区保留上次内容），`render` 四个分支全部改走该辅助并据 null 短路——单点收口即覆盖选类/形态切换/弹窗回调三条触发路径。测试 `testPickCallerWithoutMpjOnJoinShowsStatusNotThrow`（SqlToJavaPanelTest，55 用例）绿。计划文档 `docs/开发计划/20260912-0812-T-PickMpjErr-*.md`。未提交，真机复验留人工。

**How to apply:** 类似「EDT 回调里同步跑的业务校验抛错」一律在分发收敛点（如 render）包 catch 转状态栏，勿散在各个回调入口。相关 [[pitfall-wira-not-in-readaction]]。
