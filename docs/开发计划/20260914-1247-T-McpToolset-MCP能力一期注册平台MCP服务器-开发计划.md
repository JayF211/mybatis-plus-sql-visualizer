# T-McpToolset：MCP 能力一期（注册平台 MCP Server 体系）开发计划

- 立项日期：2026-09-14
- 任务性质：新能力立项（N-12，AI agent 生态位卡位）
- 依据文档：`docs/调研报告/20260914-0211-DBX数据库工具深度调研与MCP吸收规划-调研报告.md` §5（工具面/架构/验收标准）、`docs/调研报告/20260914-0317-IDEA-ACP与平台AI原生能力-调研报告.md` §3.2/§5.2/§5.4（主轨修订依据）、`docs/调研报告/20260914-0211-竞品横向对比与功能建议-第三轮调研报告.md` §8.3 N-12
- 目标：三个只读 MCP 工具（mpsv_render_sql / mpsv_schema_context / mpsv_explain）注册进 IDEA 平台内置 MCP Server，253/262 自动生效，243 明确不支持并写进发行说明

## 一、架构定案（ACP 专项修订后）

**主轨：注册进平台 MCP Server 体系**（`com.intellij.mcpServer` 插件，253/262 内置、1,808 万下载）。自建 stdio launcher 移出一期。

- 注册面：`mcpServer.mcpToolset` 扩展点 + `@McpTool/@McpDescription/@McpToolHints` 注解 DSL（官方 README 明文支持下游插件注册，intellij-community `plugins/mcp-server/README.md` 为 canonical reference）
- 载体：`SqlVisualizerToolset : McpToolset` 单 toolset 三方法
- 可选依赖：`<depends optional="true" config-file="mcp-optional.xml">com.intellij.mcpServer</depends>`（照 kotlin-optional.xml / database-optional.xml / diagram-optional.xml 三先例，plugin.xml:66-78 既有模式）
- 243 策略：MCP Server 1.0.30 旧 EP（`org.jetbrains.mcpserverplugin.McpTool`，HTTP handler 形态，接口包名全不同）薄适配成本 > 收益，**明确不支持**；发行说明与 README 写明「MCP 面需 IDEA 2025.2+（内置）或已装 MCP Server 插件的 2024.3/2025.1」
- 生命周期坑：`isExperimental` 默认 true，须显式覆写为 false 才进稳定工具集（McpToolset.kt KDoc / README 2.3）
- 一次注册自动覆盖全部 client 面：AI Assistant、Junie、经 `use_idea_mcp` 开启的 ACP agent、外部 Claude Code/Cursor 等

## 二、工具面（P1 三工具，全部只读）

| 工具 | 入参 | 出参 | 实现锚点 |
|------|------|------|---------|
| mpsv_render_sql | 项目相对文件路径 + 行号；或源码片段 + 触发面类型 | 渲染后可执行 SQL + 触发面类型 + 降级说明 | 复用 13 类 gutter 定位与 `ui/SqlPreviewService` 渲染链；MCP 线程 → ReadAction（既有线程模型纪律） |
| mpsv_schema_context | 数据源别名 + database/schema + 表名白名单（max_tables 默认 8、上限 20，参照 DBX 口径） | 紧凑表列上下文（含注释） | `ui/highlight/DatabaseSchemaResolver` 既有通道；数据源凭证不出 IDE |
| mpsv_explain | 数据源别名 + 单条 SELECT | 计划文本 | `ui/SqlPreviewPanel.explainSql()` 同链（`renderExplainStatement` 七方言派发，含金仓/达梦） |

三工具名保留 `mpsv_` 前缀（命名空间天然隔离，不与平台 toolsets 冲突）。

## 三、安全红线（DBX 四原则的只读子集）

1. `@McpToolHints(readOnlyHint=TRUE, destructiveHint=FALSE, idempotentHint=TRUE)` 平台原生只读声明
2. SELECT 白名单：JSqlParser（运行时既有依赖）解析校验，非 SELECT / 多语句 / 解析失败一律拒绝（fail closed）
3. 零弹窗：agent 触发不弹任何确认框，拒绝走 `McpExpectedError`（错误文案 agent 可读，参照 DBX `MCP_READ_ONLY` / `SQL_BLOCKED` 语义）
4. 不提供任何连接管理工具（与「不做连接管理」产品定案一致）
5. 审计日志：每次调用记 idea.log（时间、工具、入参摘要、结果状态）
6. 数据源不可达 / 项目未索引：明确报错不挂死；会话空闲超时自动回收（参照 DBX 30 分钟口径）

## 四、实施顺序

1. `mcp-optional.xml` + plugin.xml 可选依赖声明 + toolset 骨架（编译期与 MCP Server 插件解耦，未装环境自动降级）
2. mpsv_render_sql（价值最高，纯复用渲染链，竞品 MCP 给不了的确定性渲染）
3. mpsv_schema_context（max_tables 限幅 + 紧凑格式化 + 截断提示）
4. mpsv_explain（SELECT 校验 + 既有执行链）
5. 审计日志 + `isExperimental=false` + README「外部 agent 接入指南」（`claude mcp add` 与 AI Assistant `use_idea_mcp` 两种姿势）
6. 测试 + 真机验证 + verifyPlugin

## 五、测试与验收（对齐 DBX 专项 §5.3 验收标准）

- 单测：三工具入参校验 / 只读守卫 / 降级文案 / max_tables 限幅 / 未装 MCP Server 时 toolset 不注册
- 越权用例全拒：UPDATE / DDL / 多语句夹带 / 非白名单数据源 → 结构化错误码
- 真机：ZCode 与 Claude Code 实测三工具可用；无数据源 / 未索引明确报错不挂死
- 全流程零主动弹窗；审计日志可查
- verifyPlugin 三目标 Compatible（大功能口径）
- 排序依赖：**排 T-MergeR2IntoMaster 之后**（渲染链锚点在 master 侧持续演进，避免在漂移基线上开发）

## 六、范围外（明确不做）

- P2（mpsv_restore_log 日志还原 / mpsv_recent_executions 执行记录 / CRUD 与资产生成工具）：P1 真机验证后按用户反馈排序
- P3 写通道：无限期搁置；若开必须继承 DBX 验证过的四原则（中央只读策略硬上限 / 客户端环境变量不可放大 / 用户确认与执行逐字绑定 / 解锁窗口只存内存）
- 自建 stdio server：移出一期，二期评估
- N-14 AI SQL 分析：另立任务（OpenAI 兼容单路径 + PasswordSafe 存 key；「ride 平台 LLM API」已被 ACP 专项证伪）

## 七、实施记录（交付后回填）