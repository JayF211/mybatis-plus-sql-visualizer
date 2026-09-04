---
name: feedback-bundle-param-placeholder
description: IntelliJ bundle 消息参数占位符是 {0} {1}，不是 __URL__ 等自定义占位符
metadata:
  node_type: memory
  type: feedback
  updated: 2026-08-31
  originSessionId: sess_4517506b-30ee-4974-aaa0-f7e7bff6d35a
---

`MpSqlVisualizerBundle.message(key, params)` 替换的是 `{0}`、`{1}` 等标准占位符，不是 `__URL__`、`__ACTION__` 等自定义占位符。

**Why**：IntelliJ 的 `BundleReference.message()` 使用 `MessageFormat` 风格，`{0}` 是第 1 个参数，`{1}` 是第 2 个参数。自定义字符串如 `__URL__` 不会被替换，会原样输出到 UI。

**How to apply**：
- bundle 消息中用 `{0}` 占位：`help.html.timeout.action=点击此处用系统浏览器打开：{0}`
- 调用时传参：`message("help.html.timeout.action", url)` → `{0}` 被替换为 url
- HTML 模板中的占位符（如 `__ACTION__`）用 `String.replace()` 替换，不走 bundle 机制
- 两者可以组合：先 `message(key, url)` 解析 `{0}`，再 `replace("__ACTION__", resolvedAction)` 注入模板

**反例**：`help.html.timeout.action=点击此处用系统浏览器打开：__URL__` + `message("help.html.timeout.action", url)` → `__URL__` 原样显示，url 参数被忽略。

**实测案例（2026-08-31）**：帮助页降级 Timeout.html 模板中 `__URL__` 占位符原样显示，修复为 `{0}` 后正常。

**参考**：[[pitfall-jcef-css-limitations]]（帮助页面实施中踩的坑）