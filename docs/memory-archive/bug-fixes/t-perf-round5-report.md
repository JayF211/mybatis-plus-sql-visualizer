---
name: t-perf-round5-report
description: 第五轮插件IDEA性能影响深度分析报告+T-PerfSweep3立项已提交（master 322ecfb，20260916），五发现点待实施
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5d8372a8-0618-4138-b9fe-e7b0eafabb15
---

第五轮性能复查报告 `docs/调研报告/20260916-0348-插件IDEA性能影响深度分析-调研报告.md` **已提交（master 322ecfb，2026-09-16；锚点 0fbc8a8→8e4f485/48 提交/src +1025/-290）**。承接 [[t-perfsweep2]]（第四轮）。

- 总结论：默认零开销维持；T-PerfSweep2 六修复全落地经二遍实证；FeedbackBatch2 防抖挂平台共享池零自有线程（插件自有线程维持 2）；Thread.sleep 实点 7→6。
- 五发现点**已立项 T-PerfSweep3 且随报告同单元入库**：计划 `docs/开发计划/20260916-0651-T-PerfSweep3-性能五项收敛-开发计划.md`（T-PS3-01~05，任务编号用 T-PS3-xx 前缀非 T-PerfSweep3-xx），由并行全景审计会话起草（承接 [[t-panorama-audit]] §7 O-23）、随本报告同提交入库；随合流窗口实施、**未实施**。发现点：①中低=JdbcTemplateLogParser.kt:230 每行现场编译级别正则（提 companion 一行修，同文件 :287 T-PerfSweep F2 惯例自证漏网）；②IdeErrorReportSubmitter pendingCalls 无条数上界（:73，3s 时间兜底但条数无界）；③搜索时间匹配每条目 new SimpleDateFormat（SqlLogPanel:1526-1537，O(200) 有界）；④重放守卫全文档 getText（SqlLogConsoleBridge:380/:388-395，连续三轮在册）；⑤WrapperCache 无淘汰（:41，连续三轮在册）。
- 立项提交口径=第四轮 1e7cc9d 镜像：报告+计划+变更记录行（日期倒序置顶）+报告 §5 立项回链一个提交；提交信息经 UTF-8 文件 `git commit -F`（本机命令行中文乱码）。
- 撞车处置沉淀：立项/提交前先读工作树既有未跟踪文件防重复立项——同日全景审计会话已起草同名计划，改为「不重复立项、报告 §5 引用+计划随报告入库」；全景审计剩余 4 个未跟踪文件（UxOnboard 0645/ExplainIndex 0647/BranchHygiene 0649/0635 报告）未动、仍归原会话提交。
- 范围外口径=M56 feature 分支 + dev 94 提交待 T-MergeDev2Master 合流前另评（与第四轮同口径）。
