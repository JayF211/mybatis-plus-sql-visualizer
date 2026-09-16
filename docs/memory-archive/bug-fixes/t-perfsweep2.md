---
name: t-perfsweep2
description: T-PerfSweep2 六发现点已全部交付并合入 master（merge 7edb2fb，2026-09-15，4103 绿）——第四轮 IDEA 性能影响深度分析报告 + 六任务实施要点与三处实施修订
metadata:
  node_type: memory
  type: project
  originSessionId: sess_dcad83ad-2ffd-42a5-b331-e7c5af46263d
---

2026-09-15 第四轮插件 IDEA 性能影响深度分析（`docs/调研报告/20260915-0342-插件IDEA性能影响深度分析-调研报告.md`；系列四轮：0902/0910 在 docs/性能分析，0914/0915 在 docs/调研报告，增量复查体例）。结论维持「默认零开销、高负载有界」：锚点 50d3fb9 后 61 提交零新增扩展点、无新增常驻开销（唯一常驻体 PendingReportScheduler=懒加载 APP 服务+一次性 720s 延时+daemon 闲时零唤醒）、T-ExecSlowOps 两段式线程纪律收紧、WrapperSearchService 整文件清退。

**六任务已全部交付并合入 master**（merge 7edb2fb，落地前分支 t-perfsweep2，六提交每任务一个；全量 4103/0/0/6 绿=基线 4081+22；计划 `docs/开发计划/20260915-0820-T-PerfSweep2-…`，工作树与分支用后即删）。实施要点与三处与计划的差异：

- **01 中低（已修）**：extractConsoleError 改 `getCharsSequence().subSequence(baseline,len)` 片段物化 + debug 列表加 `isDebugEnabled` 守卫；`consoleErrorBaseline`/`extractConsoleError` 提 internal 供测试。新建 ConsoleErrorExtractTest 6 例。
- **02（已修）**：`readBounded(input, maxBytes=2MB)` 对响应体与 gzip 解压输出统一封顶；截断产物走既有 nonJsonHint。FeedbackApiTest +5。
- **03 实施修订**：原计划「批量每条固定 8k」**不可行**——20×8k=160k 仍触发末尾 `take(60_000)` 终裁、第 8 节起整节丢失；改为**半幅均分** `60_000/(N*2)`、下限 1k/条，同时修掉内存面与丢节面；单条路径保持 60k byte 级兼容（`ATTACHMENT_MAX_CHARS` 常量收编）。
- **04 实施修订**：01 的 `getCharsSequence` 与 04 的关闭钩子同文件，用**补丁过滤暂存**按 hunk 拆分（临时补丁文件放仓库内、勿用 /tmp：Bash 工具的 /tmp 对 python.exe 不可见）。
- **05 实施修订（自测抓出真缺陷）**：链式化首版 tick 收尾用 `scheduled?.isDone` 误判「在途拍接管」→ 队列无人消费；改为**队列非空必续链 + `scheduled?.cancel(false)` 抢排旧拍**。行为增强=发送期间新批次从静默丢弃改队尾顺延（KDoc 语义约定同步）。
- **06（已修）**：plugin.xml 更新检查注释对齐代码默认值。

**Why**：实施期新沉淀三条（超计划范围）。①**测试代码若调用生产网络路径必须先接 seam**：PendingReportSchedulerTest 首版忘 `FeedbackApi.sender = fake`，tick 走默认 HttpRequestsSender 真触网，前两轮权威 XML 实测生成 14 个测试帖标题（GatedSender 的 gateFirstPost 门控在真 sender 不存在，正是宇宙射线实证）——**生产需求池疑似收到 ≤14 条测试帖待清理**（与 FR-0023 测试帖同批）；②BasePlatformTestCase 的 `project.basePath` 为 null 使 isCacheUsable 恒 false，且 **VirtualFile 是抽象类不能 JDK 代理**——桩改真实 vf（myFixture.addFileToProject 后从 `vf.path` 反推 basePath）；③JUnit XML 解析时用例可能自闭合 `<testcase .../>`，块切分须到下一个 `<testcase` 边界，否则失败归因串块到别的用例。
**How to apply**：全部已合 master、分支已删——后续若需回溯实施细节看计划文档 §变更记录（含 03 修订与交付行）；残余低危（WrapperCache 无淘汰、预热 EDT sleep-poll 60ms、重放守卫行 0 拷贝每 stamp 一次）为三轮报告延续观察项，下轮复查继续跟。