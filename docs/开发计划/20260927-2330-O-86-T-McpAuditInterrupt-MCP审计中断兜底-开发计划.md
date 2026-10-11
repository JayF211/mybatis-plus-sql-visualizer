# O-86 T-McpAuditInterrupt MCP 审计中断兜底 — 开发计划

- **日期**：2026-09-27
- **编号**：O-86（十五轮候选预登记在先，占号前已实测盘点 50 版台账）
- **来源**：第十五轮调研报告 DBX #116（`5c0ce7e4`）——DBX 的 `CallHistoryGuard` 用 Drop 保证被中断/取消的请求也落审计记录；对照检查我方 McpAuditLog 现状后的真实缺口项
- **性质**：审计纵深小件（S）；实施分支自 master 拉任务分支（或 dev 直做，以派单为准）

## 一、三点对照核实结论（20260927，源码逐点核对）

| # | DBX 三点 | 我方现状 | 结论 |
|---|---|---|---|
| 1 | 中断/取消场景审计兜底（CallHistoryGuard Drop） | `McpToolsetSupport` 审计走 `Continuation.resumeWith`——success → auditOk；failure → onFailure；**CancellationException 分支直接透传不落账**（`AUDIT_CTX.remove` 后 `e !is SqlvExpectedToolException` 即 return） | **真实缺口（本计划修复）** |
| 2 | 入参 secret 字段递归脱敏（`"[redacted]"` 替换） | 工具入参全部白名单化（render_sql 的 sql/snippet、explain 的 dataSource/sql…），摘要只记 SQL 文本不记参数值（executionArgsSummary KDoc 明示脱敏口径） | **已达标（无 secret 字段面）** |
| 3 | 有界序列化（数组截 16 项防爆炸） | `McpAuditLog.clip` 120 字符收口 + `AUDIT_CTX` 有界（F34 登记的 fatal 摘除窗留一条微 entry 上界=在途调用数） | **已达标** |

## 二、方案

`McpToolsetSupport` 审计 continuation 的 `onFailure` 增 `CancellationException` 分支：落 `status=INTERRUPTED` 审计行（argsSummary 复用入队时快照，elapsed 计至取消时刻）后原样透传取消（不改变平台取消语义）。

## 三、验收

1. 单测：构造 ctx 入 `AUDIT_CTX` → `onFailure(CancellationException)` → idea.log 捕获 `[MCP-AUDIT] tool=… status=INTERRUPTED`（logger 断言或返回值面）；取消语义不被吞（异常原样返回）。
2. 反向验证：摘除 INTERRUPTED 分支 → 用例红。
3. 定向：mcp 包测试全绿（含 McpAuditTrail 既有用例不回归）。

## 四、实施记录

> 20260927 实施（dev）：`onFailure` 增 CancellationException 分支（status=INTERRUPTED，透传原异常）；修复与 O-86 立项同批提交。详版待定向测试读数回填。
