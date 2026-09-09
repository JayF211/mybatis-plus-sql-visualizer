---
name: project-m54-perf-redline-audit
description: M54 性能红线全面治理——24 任务已全部交付（perf/redline-hardening 28
  提交，2026-09-05）；**2026-09-05 已合入 dev**（未合 master 未 push）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d9b78293-002f-4e38-aad5-4c0dc19f0de2
---

2026-09-05 用户提出性能红线要求（禁卡死/禁拖慢 IDEA/禁插件报错弹窗），五子智能体并行审查 236 源文件定稿计划 `docs/开发计划/20260905-1024-插件性能红线全面治理-开发计划.md`（M54，T-Perf-01~24 四波），随后**单个 general-purpose 子智能体在 worktree `F:/code/Java/Gitee/SongRenShuo/mpsv-perf`（分支 `perf/redline-hardening`，基线 master 37027d5）实施全部 24 任务并完成验收**。

**Why:** 24 任务交付对应 28 提交（24 任务 + 3 回归修复 + 1 docs 变更记录）；全量 test 0 失败（3084 用例，6 跳过为 Docker 条件测试——计划里"基线 3321"是含 testDatabase 集成测试口径，默认 test 任务本就排除）、buildPlugin 通过；grep 红线 LOG.error=0、NotificationType.ERROR=0、showErrorDialog 仅剩设置导入导出/图导出 6 处允许项。主会话独立复核（git log/status/grep/复跑 test）通过。

**How to apply:**
- 分支领先 origin，**未合并 master、未 push**（与 M37/M39 分支同状态，合并是用户决策）。
- 实施偏差备忘：T-Perf-23 正则提常量 13/17（SqlPreviewService/ComplexityScorer/PerfRulesBatch1 三文件脚本化重构反复出错，按最小风险回退未改）；T-Perf-13 反射缓存以探针 debug 门控替代（正常路径零反射）；T-Perf-18 缓存键选 URL 键方案且键含 identityHashCode 辅助维度（防 light fixture url+stamp-ms 跨测试污染）；T-Perf-20 未用 HintManager（jar 检索无先例）改 WARNING 通知等价达成。
- 防抖/异步化在 isUnitTestMode 下同步执行保测试语义；真实防抖行为待 runIde 真机验证（下一步）。
- 并行隔离已还原（build.gradle.kts 无 sandboxContainer），登记表已注销。
- 11 个 LineMarkerProvider 互不共享判定的问题在 T-Perf-02/23 部分缓解（CustomDaoHql 预筛+stamp 缓存），provider 间共享判定仍是未来方向。

相关：[[project-overview]] [[project-architecture]]
