---
name: t-competitive-round3
description: 第三轮竞品横向对比已交付（2026-09-14）——r2 分支债已合流清账（4b5b964，待落 master）、t-m56-00/01 仍待合、Marketplace 三连发过审下载翻倍、DBX 精华清单与 MCP 规划 N-12、Analyzer 威胁低
metadata:
  node_type: memory
  type: project
  originSessionId: sess_8afc9bd7-fcdf-42b6-9c4c-a44484b3c8f0
---

第三轮竞品横向对比 2026-09-14 交付，主报告 `docs/调研报告/20260914-0211-竞品横向对比与功能建议-第三轮调研报告.md`，两个专项：DBX（`DBX 数据库工具调研（内部资料库）`，源码克隆 `F:\code\Database\dbx`）、MyBatis SQL Analyzer（`MyBatisSQLAnalyzer 对标调研（内部资料库）`，克隆 `F:\code\Java\Github\mybatis-sql-analyzer`）。

关键事实（后续会话直接引用，勿重查）：
- **分支债（2026-09-14 晚已清账）**：上轮九条建议（N-6~N-10/O-5~O-8，5✅4🟡零弃坑）+ T-RollbackSql 已随 `feature/r2-competition-response`（63 提交）合入 `t-fourplans-20260914`（merge 4b5b964，4167 绿，见 [[t-fourplans-batch]]，待最终集成后落 master）；合流时 O-6 增强废弃（被 T-JumpCapture 捕获位置路线取代）、N-9 废弃（master M37.4 生成测试已下线，思路留档）。t-m56-00/01 分支仍待合（合入前需独立性能评估）。
- Marketplace 状态（2026-09-14）：本插件 223 下载（8 天翻倍，小体量梯队增速第一）、0 评论冷启动未破局、hasUnapprovedUpdate=false（9.8/9.10/9.11 三连发全过审，上轮待审核积压风险解除）。评论抓取须走 `/api/plugins/{id}/comments` 新端点（旧 reviews 端点强制 family 校验已废）。
- 新建议编号承接：N-11 冷启动二推(P0 市场)、N-12 MCP 一期(P1，三个只读工具 render_sql/schema_context/explain、插件内嵌 stdio、零弹窗红线兼容)、N-13 预览交互吸收包(P1，(new)列/降级横幅/${table}快捷/多语句确认)、N-14 AI 可选集成(P3)、O-9 安全四原则、O-10 Lab 配方扩方言、O-11 ${} 注入标注。均未立项。
- DBX（Tauri 独立桌面客户端，4.5 个月 19.3k star）：印证「不做连接管理」定案；其 MCP 三级权限实现 = read_only + allow_dangerous_sql 两个 bool（非字面枚举），中央策略硬上限不可被环境变量放大（DBX_MCP_ALLOW_WRITES=1 完全被忽略）。AI 五路深挖+主智能体终版综合已入专项报告 §6~§9：铁律=静态风险分类→逐字确认绑定→一次性消费；**「AI 产物先进写前预览器」是本插件比 DBX 更顺的架构位**；N-14 优先 ride 262 平台自带 LLM API（javap 未验证，实现前必查），兜底 OpenAI 兼容单路径；动作集候选含独有 restore_incomplete_log（AI 补全残缺日志）；不吸收 8 路外部 AI CLI 集成（方向相反——该是 CLI 调插件的 MCP，不是插件 spawn CLI）；反面教材=AI key 明文存 SQLite（须走 PasswordSafe）。主智能体会话原文可从 `~/.zcode/cli/db/db.sqlite` 的 part 表按 message_id 取（subagent 会话历史持久化在 db，不在 rollout 文件）。
- MyBatis SQL Analyzer（32766）威胁低：正则注释标注器非渲染器（条件不求值）、兼容卡 251.*、停更、无 LICENSE；独有点仅 AI 流式分析。Ultra 威胁降级（3.5 个月未发版，评分 4.90 但停滞）。

- **ACP/平台原生 MCP 调研已交付（20260914-0317 报告，本机 262.10315.125 解包实证）**：IDEA 262 内置 `com.intellij.mcpServer`（253 同 EP，243 装 Marketplace 1.0.30 但 EP 完全不同是旧 HTTP 形态）与 `com.intellij.platform.acp` 双插件；N-12 主轨已改「注册进平台 MCP Server 体系」——`SqlVisualizerToolset : McpToolset` + `@McpTool/@McpToolHints(readOnlyHint)` 注解 DSL，官方明文支持下游插件，自动覆盖 AI Assistant/Junie/ACP agent/外部 Claude Code 全部 client 面，自建 stdio 移出一期；ACP 读面（agentsCatalogProvider/processLauncherProvider）聊天 UI 依赖 AI Assistant，无公开权限拦截 EP；**N-14「ride 平台 LLM API」已证伪**（干净 262 无 LLM 模块、SDK llms.txt 无 LLM API 页、2026-06 恶意插件事件后更收紧），维持 OpenAI 兼容单路径 + PasswordSafe；ACP 四值权限（allow_always）与 elicitationProvider 是未来写类工具/交互确认的 ride 对象。

相关：[[project-release-workflow]]、branch-port-loss-audit（历史台账已归档 `docs/memory-archive/bug-fixes/branch-port-loss-audit.md`，分支债是其新形态）、t-m56-agent-delivery（已归档 `docs/memory-archive/bug-fixes/t-m56-agent-delivery.md`）。
