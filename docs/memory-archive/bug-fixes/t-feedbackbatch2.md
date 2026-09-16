---
name: t-feedbackbatch2
description: 批量报告多次弹窗合并（T-FeedbackBatch2）已合 master——262 平台「报告全部」逐簇调
  submit、events 恒 1 条的字节码实证与提交器去抖累积修法
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6dc70ac0-3f5e-41b1-bd6f-1543fbbd1baf
---

T-FeedbackBatch2 已交付并合 master（merge 751759c，2026-09-15，testShards 4173/0/0/6 绿；分支 t-feedbackbatch2 与工作树已删，计划 20260915-2325 含交付记录）。

**根因（定案，字节码实证）**：262 平台 `IdeErrorsDialog`（intellij.platform.ide.impl.jar）「Report All」（用户口中「包含全部」）= `reportAll` 逐 `ErrorMessageCluster` 循环调 `access$reportMessage`（`IdeErrorsDialog$reportAll$reported$1` 引用实证）；`reportMessage` 固定 `anewarray 1` + `cluster.getFirst()` 构造**长度 1** 的事件数组调 `ErrorReportSubmitter.submit`。所以 T-FeedbackBatch 的「平台多选一次传 N 条 events」前提在 262 不成立：N 个错误 = N 次独立 submit = N 个弹窗、每框批次只有 1 条（同簇多次重复也只报首条）。用户假设「多次触发而非一次多堆栈」正确。日常 IDEA 2026.2 当天日志（8 文件）无本插件 ERROR——定案不依赖日志。

**修法（落在插件侧，勿再怀疑平台）**：`IdeErrorReportSubmitter` 累积合并——PendingCall 队列 + 500ms debounce（自最后一次调用起算，入队取消重排）+ 自首条 3s 强制兜底 + 复合 consumer（全簇同一 SubmittedReportInfo）+ 逐事件指纹去重（整条全命中才 DUPLICATE）+ 提交时记录**全部**条目指纹（原只记首条，其余条目 24h 去重从未生效）。`ErrorReportDialog`：批次条目仅 1 条（=主预填自己）不出分节；标题改「本批共 N 条」如实计数；头部行复选框禁用仅镜像、收集时以主表单「附带异常堆栈」单源回写。`openReportFlow` seam 第三参 String→List\<String\>。

**How to apply**：错误报告/批次链路改动先读本文再动手；新增平台交互断言一律先 javap 本机 262 jar 取证（与 [[idea-262-platform-jar-verify]] 同路线）。

**沉淀坑点**：
- javap 反编译输出含 CR 字节时 Git Bash grep 判二进制静默零匹配（exit 1）——字节码文本检索用 python `data.count/遍历`，勿信 grep。
- Kotlin override JDK `scheduleAtFixedRate/scheduleWithFixedDelay`：接口是非泛型 `ScheduledFuture<?>`，override 写成 `<V>` 或 `ScheduledFuture<*>`+可空参数都会「overrides nothing」；正解=非泛型返回+非空参数。
- headless 测 DialogWrapper 实例方法不可行（构造断言 EDT）：批次渲染/收集逻辑放 companion 纯函数（`applyBatchItemsToFields`/`selectedBatchItemsFor`），实例只做薄委托。
- seam 属性带平台默认值（如 `AppExecutorUtil`）必须在首次使用时才解析（可空 + null 兜底），否则 headless 测试构造即炸。
