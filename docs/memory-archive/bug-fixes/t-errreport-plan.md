---
name: t-errreport-plan
description: T-ErrReport 已交付（2026-09-08，dev 51efbe3）——错误一键上报官网需求池：errorHandler
  EP 直报通道、需求池 API 契约、平台 API javap 结论、实施修正（Gson 数字/错误响应 data）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_ed4e1284-dfef-4ff3-ae04-95641e567490
---

用户反馈 IDE Internal Errors 对话框无法报告插件错误（vendor 链接只跳 Gitee 主页）。已交付：注册平台 `com.intellij.errorHandler` EP（`ErrorReportSubmitter`）→ 自建报告确认框 → 直连官网需求池 API（`source='ide'`+ideVersion+pluginVersion+堆栈 txt 附件），SHA-256 栈指纹 24h LRU 去重（DUPLICATE 不弹框不触网）；Help 菜单「SQL Visualizer 反馈 / 需求池」+ 设置页「报告问题…」手动入口；官网 FeedbackForm 读 `?source=ide&…` 参数展示环境徽标并随帖携带（docs 仓库 12727f9）。

**提交链**：master 计划 4a15230 → dev 计划同步 b47d7c9 → dev feat 51efbe3（feedback/ 新包七类 + plugin.xml EP/通知组/HelpMenu + UpdateSection 按钮 + Settings 两字段 + bundle 34 键）→ dev docs c8b6cad（三处同步+§九回填）。测试：新增 4 类 34 用例，全量 **3592/0/0/6 绿**（基线 3558+34）、verifySmoke(243) 过。

**平台 API javap 结论（243 基线，复用勿重查）**：EP `errorHandler` dynamic、接口 `getReportActionText()` + `submit(Array<IdeaLoggingEvent>, String?, Component, Consumer<SubmittedReportInfo>): Boolean`（parentComponent **非空**）；`SubmittedReportInfo.SubmissionStatus` 仅 NEW_ISSUE/DUPLICATE/FAILED；`HttpRequests.post(url, body)` 二参即请求体；multipart 走 `request(url).tuner{ setRequestMethod("POST") + doOutput }` + `Request.write(byte[])`。

**实施修正（三类教训）**：① Gson 以 Object/Any? 目标反序列化数字得 `LazilyParsedNumber` 而非 Double——统一 `as? Number`；② 错误响应（无 data 字段）不得在 code 判断前 `?: error("响应缺少 data")`，否则吞服务端真实 message；③ 指纹正则剔除行号（防漂移），指纹测试须用方法名差异构造。

**遗留人工跟进**：runIde 真机端到端（制造插件异常 → 报告按钮 → 真实提交 → FR 编号/详情页/附件/后台 source 展示）；管理后台 source='ide' 展示（admin 仓库另案）。测试跑前按铁律查并行表（本次无活跃任务）。

相关：[[t-loglistfreeze04-delivery]]（截图错误本身已修）、[[runide-demo-verify-workflow]]、[[project-i18n]]（DialogWrapper cancel 坑已按规覆盖）。
