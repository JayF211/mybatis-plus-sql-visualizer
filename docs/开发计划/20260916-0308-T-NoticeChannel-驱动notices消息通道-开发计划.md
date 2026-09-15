# T-NoticeChannel 驱动 notices 消息通道 — 开发计划

- 日期：2026-09-16 03:08
- 任务编号：T-NoticeChannel（第五轮新建议 **O-16**，P1）
- 来源：第五轮报告 §7 DBX 吸收清单 **#21**——DBX 20e026ee+dffd3025：执行前 drain 连接上的陈旧 notices（postgres.rs:7579 附近）；无结果集语句同样挂载 notices；**出错语句的 notices 直接丢弃**防泄漏到下一条；前端独立 Messages 区
- 性质：MCP 执行通道信息增强（PG NOTICE/WARNING、MySQL warning 计数当前无处安放）
- 实施分支：自 master 拉任务分支 t-notice-channel
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

PG 的 NOTICE/WARNING（`relation already exists, skipping`、`ANALYZE` 提示等）与 MySQL 的 warning 计数对调试极有价值，但本插件执行链当前零消费（grep `getWarnings|SQLWarning` 功能代码零命中）。经锚点核实，落点要收窄：**IDE 控制台面做不了**——执行走 DBN 原生链，插件不持有 Connection 句柄（notices 是否呈现由 DBN 决定）；**MCP 执行面可以**——McpDbExecutor 自持连接，executeQuery 完成后可取尽 warnings。故本任务定位为 MCP 链先行，IDE 面先实测定案（若 DBN 原生已展示则不重复造）。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| warnings 消费 | 全 src/main 零命中（唯一 "sqlwarning" 是关键字表、"Notice" 是隐私文案） | `sql/SqlKeywords.kt:73`、`feedback/IdeErrorReportSubmitter.kt:50` |
| MCP 执行通道 | McpDbExecutor.executeQuery 自持连接，collect 返回 `(headers, rows, truncated)`——无 messages 位 | `mcp/McpDbExecutor.kt:85,132-134` |
| 消费方 | ExplainTool 输出 = McpExplainFormatter.format + 方言尾行（MAX_ROWS 200/单元格 2000 预算已有） | `mcp/ExplainTool.kt:37-39`、`mcp/McpExplainFormatter.kt:13,16` |
| 错误路径 | 建连失败包 DATASOURCE_UNREACHABLE；执行异常 throwExpected | `mcp/ExplainTool.kt:103-112`、`McpToolsetSupport.kt:81` |
| IDE 控制台面 | DBN 原生结果页签 + Output 区，插件无连接句柄、无结果对象 | `execute/DataSourceSelector.kt:2615-2655` |

## 三、方案

**T-NC-01（warnings 收集）**：RawResult 增加 `notices: List<String>`；executeQuery 完成后 `statement.getWarnings()` 循环取尽并 `clearWarnings()`（**执行前先 drain 一次**对齐 DBX 防陈旧串扰；连接级与语句级 warnings 都取）。

**T-NC-02（输出接线）**：ExplainTool 输出尾附 `notices:` 节（每条一行，空则不加行）；**执行失败路径的 notices 丢弃**（DBX 口径：防错误语句的陈旧 notices 泄漏到下一次调用）。

**T-NC-03（IDE 面实测定案）**：真机跑一次 PG NOTICE 场景核实 DBN 原生 Output 区是否已呈现——已呈现则 IDE 面定案「不重复造」写入实施记录；未呈现则记为 IDE 面缺口挂账（不在本任务展开——无连接句柄，硬做要侵入反射链，成本收益不成立）。

**T-NC-04（测试 + 文档）**：mcpStub 桩扩展（notices 字段）；表驱动（取尽/clearWarnings/失败丢弃/空 notices 不占行）；CHANGELOG [Unreleased] Added、变更记录行、第五轮报告 O-16 状态回填。

## 四、验收标准

1. notices 取尽且 clearWarnings（不残留到下一条语句）；失败路径 notices 不出现在输出。
2. 空 notices 时输出逐字节与现状一致（防回归断言）。
3. mcpStub 桩挂 testShardN classpath 惯例不破；全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. PG 数据源建 `DO $$ BEGIN RAISE NOTICE 'x'; END $$` 类样例（或函数内 NOTICE）经 MCP explain/查询链：notices 节出现且内容正确。
2. IDE 控制台跑同款：记录 DBN 原生是否已展示（T-NC-03 定案依据）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 驱动差异 | H2/SQLite warnings 几乎恒空；PG NOTICE 仅 session 通道可达 | 空输出零差异断言；PG 真机走查必测，其余方言「环境受限未验证」口径 |
| getWarnings 抛异常 | 个别驱动实现抛 UnsupportedOperationException | 取 warnings 全程 runCatching，失败=空 notices（不连坐查询结果） |
| 陈旧 notices 串扰 | 连接复用场景上一条的 notices 冒到下一条 | 执行前 drain + 失败丢弃（DBX 两件套都抄） |

## 七、实施顺序

```
T-NC-01（收集+RawResult 扩展）→ T-NC-02（ExplainTool 接线）→ T-NC-03（IDE 面实测定案）
→ T-NC-04（测试+文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

（未实施）
