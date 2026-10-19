---
name: t-m56-00-delivery
description: M56 T-M56-00
  消费端先导已交付（9cba20b，feature/t-m56-00-consumer，2026-09-12）——条目四字段+AGENT来源/标记行3~5段/pd载荷扩展回环/聚焦过滤层；T-M56-01
  PoC 门禁为下一步
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3da5e6cf-62af-447f-aa90-8df08c999579
---

M56 T-M56-00 消费端先导已交付（`feature/t-m56-00-consumer` @ `9cba20b`，worktree `F:/code/Java/Gitee/SongRenShuo/mpsv-m56`，2026-09-12）：

- **模型**：`LogSqlEntry` 新增 `mapperStatement`/`errorText`/`requestTag`/`updateCount`（追加尾部默认缺省）+ `SqlSource.AGENT`。
- **标记行 3~5 段**：split 确定性解析（regex 惰性回溯对空段歧义弃用）；**踩坑：段数基准按去掉前缀后的字段算（2~4），按用户可见「3~5 段」直接 split 会差一 IndexOutOfBounds**。第 5 段仅第 4 段在场时读（段位固定不可跳位）。
- **pd 载荷扩展**：可选键 m/e/q/u，gson 宽松解码、扩展键损坏降级 null 不丢条目；`SqlLogParamCodec.decode` 返回 `DecodedPayload` 数据类——**既有解构测试 `val (raw, params) = decode(...)!!` 零改动**。
- **聚焦过滤层**：`FocusFilter`/`filterByFocus` 插 filterByType→filterBySearch 之间；`SqlLogPanel.setFocusFilter` 为 internal（public 会暴露 internal 参数类型编译错）；桥通道恒 null 与现状一致。
- **验证**：全量 269 类/3343 用例/0 失败/6 跳过；verifyPlugin 243/253/262 Compatible。
- **并行协作坑**：goal 运行时在「继续」回合结束会自动快照提交工作树（9cba20b，含 AI 生成提交信息），与正式交付提交（d13241d）叠加致台账双行——交付时查重；另 T-CrudSql 会话同日活跃（主工作树有其未提交改动，勿动）。
- **下一步**：T-M56-01（agent 基座与动态挂载，PoC 门禁四项先行：agentmain 挂 transformer+retransform 双 JDK 实证、插桩两路对照、二次挂载幂等、失败回落）。计划：docs/开发计划/20260912-0817（§4.2 #2/#3、§5 T-M56-01）。分支 `feature/t-m56-01-agent-base` 已建（worktree mpsv-m56，HEAD 20f689c = T-M56-00 交付），**PoC 门禁尚未跑**（2026-09-12 核对）。关联 [[project-m56-agent-bytecode]]。
