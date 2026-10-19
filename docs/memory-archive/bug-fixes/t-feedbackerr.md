---
name: t-feedbackerr
description: T-FeedbackErr 上报失败可见性 + T-FeedbackBatch 批量合并限流规避（2026-09-14 真机三轮交付，c406063/d192216/9a5091f，4083/0/0/6 绿）：一轮=400 正文透出/附件降级/提交状态机；批量=2~5 条合并一帖、≥6 条错峰补发；二轮=gzip 未解压假失败根治（myGzip 默认 true）+状态行换行；三轮=复制按钮对齐预览窗口/导入堆栈自动勾选/附件乱码（charset+BOM）；含 D1 诊断法
metadata:
  node_type: memory
  type: project
  originSessionId: sess_99e4f134-4b60-4599-99f6-6640d9f7e97a
---

用户真机（日常 IDEA）反馈链路三轮迭代，均已交付 master：第一轮 T-FeedbackErr（c406063 feat + 3a5379a docs，4072 绿）、同日追加 T-FeedbackBatch（批量合并限流规避）、真机第二轮（d192216 fix + c9938da docs，4078 绿）、真机第三轮（9a5091f fix + 0301be4 docs，**4083/0/0/6 绿**，bundle 中英 622/622）。

## 第一轮根因与修复（c406063）

- 真机报 `Server returned HTTP response code: 400 for URL: …/feedback/posts` 且**没有任何对话框**。根因两条在插件侧：① 平台 `HttpRequests` 的 `readString()` 只读成功流，服务端写在 4xx 响应体里的中文原因被整条丢弃；② `ErrorReportDialog.doOKAction()` 先 `super.doOKAction()` 关窗再提交，失败只剩 toast。
- 修复：`HttpRequestsSender` 手写 POST（tuner 设 method/doOutput）→ 读 responseCode → 非 2xx 读 `errorStream` → 抛 `HttpFailure(status, body)`；`describeHttpFailure` 服务端 message 优先。附件上传失败**降级不连坐**：`submit()` 返回 `SubmissionOutcome(result, warning)`。提交状态机：点提交不关窗、按钮转「提交中…」禁用、失败留窗红字原因可原地重试，成功才 `close(OK_EXIT_CODE)`。
- headless 判定用 `window == null || !window.isDisplayable`（`window.peer` 是包私有，编译不过）；**`DialogWrapper` 构造即 `ThreadingAssertions.assertEventDispatchThread` 硬抛**（测试 worker 非 EDT）——对话框实例上的行为无法 headless 测，状态机文案必须抽纯函数（`submitStatusText(SubmitPhase, message, warning)` 唯一事实源）。

## T-FeedbackBatch（用户定案「不能被轻易限流」，随 c406063 交付）

- 服务端 `POST /feedback/posts` 限流 **5 次/小时/IP**（限流中间件计在 handler 前），且平台错误列表可多选——旧实现只取 `events.firstOrNull()`，用户全选后其余条目被**静默丢弃**。
- 分流（`BatchSubmitter`）：1 条原样单帖；2~`BATCH_MERGE_MAX`(5) 条**合并成一条帖子**（`buildBatchTitle` 带「等 N 条异常」+ `buildBatchDescription` 逐条 `#N 类名: 消息` 清单 + `buildStackAttachment(List)` 多节 `----- #N -----` 附件，总量 ≤60_000 字符）；≥6 条首发仍是合并帖（附件含全部条目），其余各成独立帖交 `PendingReportScheduler`（APP 级守护线程单线程池）**每 12 分钟补发一条**（3600s÷5 留余量），首发成功才入队、失败不重试、内存队列不落盘。
- 对话框错误形态新增「其他 N 条异常」分节（逐条复选框默认全勾）；`composeSubmission` 纯函数提交前重写标题/描述/附件；手动形态不受影响。

## 第二轮（d192216，gzip 假失败根治 + 对话框收敛）

- 真机现场：手动提交显示「提交失败：Expected BEGIN_OBJECT but was STRING at line 1 column 1」与「帖子已提交」同屏矛盾、对话框 ~1900px 宽。**线上 D1 实查 FR-0023 已完整入库**——请求与服务端处理全成功，假失败纯在客户端读响应。
- **gzip 根因（字节码实锤）**：javap app-client.jar `HttpRequests$RequestBuilderImpl`——构造器 `iconst_1 → putfield myGzip`（**gzip 默认 true**），connect 时 `if (myGzip) setRequestProperty("Accept-Encoding", "gzip")`；CF 实测带该头返回 `Content-Encoding: gzip`。平台 `readString()` 会解压，手写 `readBody` 直接把 gzip 二进制按 UTF-8 解码 → 乱码被 Gson lenient 读成 STRING token。util-8.jar 全量扫描佐证 Accept-Encoding 字面量只在 HttpRequests.class。
- 修复：`readBody` 按 `Content-Encoding: gzip` 用 `GZIPInputStream` 解压 + tuner 显式协商 gzip（协商与解压配套）；`nonJsonHint` 纯函数——正文不以 `{` 开头（HTML 拦截页/空响应/纯文本）给可读归类并截取 80 字符片段，parsePost/parseUpload/describeHttpFailure（附 `（HTTP 403）` 状态）全接入，Gson 英文原文不再透出。
- 状态行 `JBLabel` → `JBTextArea(2,40)`（lineWrap+wrapStyleWord+透明），**JBLabel 单行渲染长失败消息把 GridBag 整行撑到 ~1900px**；`buildStatusRow` 纯函数（文本区居中 + 按钮居东）。附件降级文案中性化：`attachmentFailed` 替换「帖子已提交，但…」（状态行只在失败时出现，成功语境文案必矛盾）；`notifyOutcome` 附件 toast 只在成功时发。

## 第三轮（9a5091f，复制按钮对齐 + 导入自动勾选 + 附件乱码）

- **复制按钮与 SQL 预览窗口完全对齐**（用户退回我第二轮的做法）：第二轮的常驻按钮 + 自定义键 `error.report.dialog.status.copy` 不符——预览窗口 `SqlPreviewPanel.errorCopyButton` 的形态是「文案键 `panel.copyError` + `smallButton`（`font.deriveFont(11f)`）+ **默认隐藏、仅错误时出现**（`isVisible = currentWarning != null`）」。改法：复用同一文案键（自定义键删除，bundle 回 622/622）、同 11f 字号、默认 `isVisible=false`，`beginSubmitting()` 收起、仅失败分支置 true。**这是「照抄既有同类实现」的又一实例，见 [[feedback-ui-match-existing]]**。
- **从 IDE 错误池导入堆栈自动勾选**：`applyImportedStack(fields, stack)` 纯函数 = 填 `stackPreview.text` + `attachStackCheck.isSelected = true`——用户挑了一条堆栈即「这条要带上」，不自动勾选会让刚导入的内容不随帖提交（手动形态勾选框与预览区本就恒显示，勾上即展开，无副作用）。
- **附件中文乱码：字节无损、根因在响应头 charset**。用户给出线上附件 URL，下载逐字节核验：`SQL Visualizer 错误报告` = `e9 9499 e8af af e6 8a a5 e5 91 8a`——**合法 UTF-8**，即存储与上传链路都没问题，乱码只在展示层：R2 对象 `httpMetadata.contentType` 是上传时的 `text/plain`（不带 charset），浏览器对无 charset 的 `text/plain` 按本地编码猜（中文环境猜 GBK）。修复双保险：**服务端** `/feedback/files/*` 对 `text/*` 且未带 charset 的响应补 `; charset=utf-8`（字节不动、覆盖历史附件，admin 仓 03ca678，新增 `tests/attachment-charset.test.ts` 4 例，vitest 102/102，已部署 version 19ab8e22）+ **插件侧** `buildStackAttachment` 开头写 U+FEFF BOM 自描述编码。附件 URL 带 `Cache-Control: max-age=3600`，用户浏览器可能缓存旧响应头，须强刷验证。
- **乱码定性法（可复用）**：先 `curl` 下载附件 → 检查字节（UTF-8 中文三字节序列 vs GBK 两字节）与 `Content-Type` 响应头 charset。字节合法＝展示层解码问题（改响应头）；字节就是乱码＝写入侧编码问题（改上传/落库）。别一看到乱码就改编码转换代码。

## 线上需求池诊断法（可复用）

- 后端仓库是同级 `F:\code\Java\Gitee\SongRenShuo\sql-visualizer-admin`（Cloudflare Worker + D1）；根目录 `.cf-token`（gitignored）即 API token，`CLOUDFLARE_API_TOKEN=$(cat .cf-token) npx wrangler d1 execute sql-visualizer-admin --remote --json --command "SELECT ..."` 直查 `fb_posts`/`fb_attachments`/`sys_alert_events`/`sys_audit_logs`。
- **D1 有没有帖子是分水岭**：第一轮排查时帖子不存在→服务端在写库前拒绝（正文被插件吞）；第二轮 FR-0023 存在→假失败在客户端读响应。别把「用户报失败」当「服务端没收到」。
- 限流 5 次/小时/IP 计在 handler 前——探针 4 次后第 5 次即 429，一小时内读不到真结论（别把 429 当 bug）。注意限流 kv.put 现已有 fail-open try-catch（20260913 修），admin 仓运行手册 §5「无兜底」描述过时。

**Why:** 绕开平台封装自读响应时，必须连同平台的隐式行为（gzip 协商/解压、错误流语义）一起接管——javap `RequestBuilderImpl` 构造器即见 myGzip=true；「编译过、单测绿、真机假失败」要靠线上库实查来分辨服务端真相；展示层乱码与写入层乱码的定性方法（下载看字节）能一步排除一半猜测。
**How to apply:** 改反馈链路先读 [[t-feedbackdlg]]（对话框形态红线）与 [[pitfall-http-error-body-swallowed]]（手写 HTTP 的 gzip/错误流两个坑）；新增 UI 交互照抄 [[feedback-ui-match-existing]]；HTTP 错误呈现一律「服务端 message 优先 + 非 JSON 可读归类」；遗留=附件上传路径仍未端到端验证过成功（`fb_attachments` 恒 0 行，第三轮只做了编码层修复）、FR-0023 测试帖待用户后台清理、三轮改动的真机走查均留人工；verify 口径见 [[feedback-verify-scope]]、云侧运维见 [[sql-visualizer-admin-cloud]]。
