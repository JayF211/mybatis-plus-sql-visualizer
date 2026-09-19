---
name: project-merge-dev-batch-round6
description: 20260917 dev 批次会话：master 合入 dev + 第六轮五计划 + T-PerfSweep4 + 尾巴项全交付的提交图谱与关键定案
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2283dbb9-5a2f-456f-9fb3-dac6c32e5832
---

2026-09-17 用户指令「切 dev、master 合入 dev、实施盘点 2.2 五计划 + T-PerfSweep4 + 2.4 尾巴项」批次会话（dev worktree 直接实施，未建任务分支——用户指令）。提交图谱：3552d4b merge master（mcpStub×3+snakeyaml 四块增量回植，shard 桩 classpath 落 mpv-testing.gradle.kts 惰性求值）→ 9399fee 负缓存 TTL `<=`→`<` flake 修复 → f3c0007 T-TxnReadOnlyProof+T-ExecTimeoutGuard 合笔 → afb3a8a T-DmlAliasGuard → 69a6095 T-TsqlBatchSplit → e24f536 T-UpdateZipBounds → 5fa6187 T-PerfSweep4 → 99370a9 文档批次（盘点第 3 版）→ bd863f0 基线回填（412 类/4817 用例）→ **ed3e1f1 验收文档 `docs/验收文档/20260917-1435-master合入dev与第六轮五计划PerfSweep4尾巴项-验收文档.md`（8 入口速查+六节步骤+真机走查 13 条总清单，MEMORY.md 已登记）**。全量 testShards 4817/0/0/6 绿，dev 已全推送（origin/dev=ed3e1f1）。

**关键定案/教训**：
- 盘点 2.4 SpeedSearch 尾巴是**陈旧条目**——t-uinative 5b0e2c3+0330cb8 已修（git 实测在位），勘误而非重做；盘点引用条目必须先实测（[[pitfall-stale-session-git-snapshot]] 再证）
- T-TsqlBatchSplit 批首收窄为**仅 IF**：裸 BEGIN 与 MySQL `BEGIN…COMMIT` 事务形态冲突（既有保守测试钉死）
- T-ExecTimeoutGuard：RemoteStatement（DBN 镜像）setQueryTimeout/cancel 243+262 javap 同形；控制台链 DBN 托管无语句句柄 v1 如实登记不覆盖
- UpdateZipGuard 头谎报双路检测：本机 JBR17 实测「读超声明+1」命中（JDK 实现差异，两路都拦）；测试手工造 zip 用 `Deflater(6, true)`（nowrap——默认 zlib 包装流 zip 内非法）
- T-PerfSweep4 06/07 复核销账（T-PerfTail 已收口实测在位）；01 拒绝冷却 24h 持久化、设置 off→on 沿重置
- heredoc 传 python 的 `\\d`/`\\u` 会被 JSON 层吃一层反斜杠（控制字符惨案）——跨层写字面量用 chr(92) 或行号手术
- bash 命令里 Windows 路径反斜杠必须改正斜杠；管道 `| tail` 会吞 gradle 退出码（假绿）

**Why:** 该批次图谱是后续合流（T-MergeDev2Master）与真机走查的底账；五计划+PS4 的真机走查清单散在各计划 §五。

**How to apply:** 判「第六轮五计划/PS4 是否交付」直接看本条提交号；真机走查按各计划 §五；dev 推送状态见 [[project-pending-plans]]。
