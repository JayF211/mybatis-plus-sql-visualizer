---
name: t-perfbatch-plan
description: T-PerfBatch 已交付（第二轮）——监听器三出口分层 + 内容通知「20条/200ms」节流 +
  onContentNotifySuppressed 兜底接幂等 flush（dev fb73825，2026-09-11，3696
  绿）；单通道耦合三语义是初版回退根因，repo 计划文档 §七 有完整教训
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e0e07d2a-d172-4423-9c52-10427eff7a48
---

T-PerfBatch 于 2026-09-11 按 dev 计划文档 §七「监听器分层→列表通知节流」正确路径交付（dev fb73825，与 [[t-perfsweep-delivery]] 同会话，全量 3696/0/6 绿，基线 3693+3）。完整教训（初版回退根因、正确步骤、交付形态）已沉淀在 repo：`docs/开发计划/20260909-1537-T-PerfBatch-吸收MyBatisCodeHelperPro批量异步UI与性能精华-开发计划.md` §七（含二轮交付记录），此处只留跨会话要点：

- **自动展开必须挂「每次成功入队」（onEntryAccepted）而非「空→非空」沿**——`SqlLogManagerAutoShowTest.testDebounceSuppressesRepeat` 末步钉死：clear 后缓冲非空、reset 防抖基准后，下一条 SQL 仍须再次触发展开判定。60s 防抖负责收敛，入队回调负责即时。
- **节流的兜底是 onContentNotifySuppressed → notifyUi 幂等 flush**（flush 惰性读当下快照，测试 pump EDT 即见全部条目）；「面板尾批 Timer 300ms」有意不做（invokeLater flush 已是 EDT 最终一致兜底，Timer 无额外收益），偏离已在计划文档留档。
- **去重丢弃分支只走徽章通道**：重复 SQL 不是新 SQL（不触发自动展开），且空缓冲不可能产生重复——分层后自动展开零可观测变化。
- 四个旧「逐条通知计数」测试（BufferTest×3 + CaptureRealtime×1）是有意改写的契约测试，不是回归；面板即时性由 DedupeToggle/Sort/Layout 等 pump-EDT 测试守卫。

master 侧 20260909-0000 同名计划是旧初稿，dev 1537 版为超集——**勿再复制**（2026-09-11 复制任务中已核实并留档于提交 0a855bc 说明）。

**How to apply**: 后续调 SqlLogEntryBuffer 通知相关行为时，先读 dev 计划文档 §七 再动手；新增监听器语义时按三出口归位（接受/徽章/内容节流）。相关 [[project-dev-branch-state]]
