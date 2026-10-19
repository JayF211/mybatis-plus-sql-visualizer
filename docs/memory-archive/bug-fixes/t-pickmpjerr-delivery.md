---
name: t-pickmpjerr-delivery
description: SQL转Java 选类重渲染 MPJ 业务异常逃逸 EDT 根治（master ce62959，2026-09-12）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_35c1b041-e4ab-40e9-8402-d3def7a739bc
---

T-PickMpjErr 已交付（master ce62959 + docs a6bdfc7，2026-09-12，SqlToJavaPanelTest 55 用例绿）：SQL→Java 面板 `render` 四分支裸调 `generateCode`，`SqlReverseException`（多表 JOIN 无 MPJ 能力等**设计内业务拒绝**，`JavaCodeGenerator.kt:113` 抛出）从选类 `elementChosen → setPicked → regenerateWithNewFormat → render` EDT 鼠标回调逃逸成平台 UnhandledException 红叹号。修复 = `generateCodeOrReport` 单点收口（catch → 状态栏 `status(error=true)`，结果区保留上次内容，返回 null 短路），覆盖选类/形态切换/弹窗回调三条路径；全文件解析期 catch（convert :790）之外这是第二个捕获点。新用例 `testPickCallerWithoutMpjOnJoinShowsStatusNotThrow` 钉死。

同源日志另一问题=attachConsole 读权限硬抛 5 连，系**旧构建**日志——master a24a30e（T-ReverseParenWira）WIRA 包裹已修，判据=堆栈里 `prepareInConsole` 直接反射 invoke 无 WIRA lambda 帧；真机装新构建复验即可，非代码缺陷。相关 [[pitfall-wira-not-in-readaction]]。
