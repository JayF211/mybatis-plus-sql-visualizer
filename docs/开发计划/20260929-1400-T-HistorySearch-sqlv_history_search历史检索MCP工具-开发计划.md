# R14 T-HistorySearch sqlv_history_search 历史检索 MCP 工具 — 开发计划

- **日期**：2026-09-29
- **编号**：R14（第五轮报告 §8 新功能推荐池 D16 定案「随 MCP 线」；本计划为独立实施计划，不占新 O 号）
- **性质判定**：**代码任务，非外部/生态动作**——MCP 工具面（工具注册、白名单、设置面）全部在我方仓内，新增工具属纯代码改动；「随 MCP 线窗口」的编排含义是搭 MCP 线车实施，不改变任务性质
- **动机**（第五轮报告 §8 原锚点，2026-09-29 已逐条复核）：SQL 历史全量落盘是我方独有资产（F-43 自动落盘），agent 通道摸不到它——`sqlv_render_logs` 只收 agent 自带的日志文本入参，历史落盘文件对其不可见；补 `sqlv_history_search` 工作流闭环单件，非工具数竞赛（10→11 与红线「MCP 工具数竞赛」不冲突，红线指的是盲目堆数量，本件有明确资产闭环动机）
- **成本**：S 级
- **状态**：已核实待立项实施（用户 D16 定案随 MCP 线）；证伪清单继承第五轮 §8 十条，勿重复立项

---

## 一、现状锚点（2026-09-29 全部实测重锚）

| # | 现状 | 锚点（文件:行，本工作树实测） |
|---|---|---|
| ① | MCP 工具面：十工具单一清单（设置页 UI 与守卫测试共用） | `mcp/McpToolGate.kt:32-41`（TOOL_NAMES：sqlv_render_sql/schema_context/explain/render_logs/check_sql/rollback_plan/perf_advice/crud_sql/reverse_sql/mapper_check） |
| ② | 动态注册机制（EP 形态动态壳注册器，十工具类清单驱动） | `mcp/McpToolsetDynamicRegistrar.kt:53`（TOOL_CLASSES listOf）、`:75`（registerToolset 循环）——新工具在此清单加一行即可注册 |
| ③ | 历史落盘资产：SQL 历史自动保存到文件 | `log/SqlLogAutoSaver.kt:1-50` 类头（默认路径 `.idea/sql-visualizer/sql-history/`，文件名模板 `sql-history-$yyyy-MM-dd$.sql`，单文件 ≥100MB 滚动，格式 `-- 时间戳\nSQL\n\n` 纯文本） |
| ④ | 读取面已有：历史文件读取器 | `log/SqlLogFileReader.kt:56`（readAll(dir): List<LogSqlEntry>）、`:65`（readFromFile(file): List<LogSqlEntry>）——**检索器缺口就在此之上** |
| ⑤ | agent 通道现状：sqlv_render_logs 只收 logText 入参 | `mcp/SqlVisualizerRenderLogsTool.kt:47-53`（sqlv_render_logs(logText, ...)）——落盘历史对 agent 完全不可达，R14 缺口实证 |
| ⑥ | 工具接入需同步的配置面 | `config/McpConfigurable.kt:117`（descKey 动态描述键）、`config/PluginSettings.kt:483`（工具级禁用清单注释）+ bundle（settings.mcp.tool.desc.* 键族） |

**边界澄清（防混淆）**：R14 检索对象是 ③ 的**落盘历史文件**（log 域）；`history/` 包仅 2 文件（`SqlHistoryEntry.kt`/`SqlHistoryManager.kt`，面板内历史）是 R15/R16 的域——两条资产同名异实，R14 不触碰 history/ 包。

## 二、方案（三个子任务，检索器先行）

| 子任务 | 内容 | 写集 |
|---|---|---|
| **T-01 历史检索器（主体）** | 新增纯逻辑检索器：readAll 全量条目上按关键词/正则/时间范围/来源过滤，返回匹配条目（时间戳 + SQL + 来源文件）；**后台线程 + 上限量保护**（对齐 R16 同类性能预算口径，防 EDT 卡顿与超大返回体） | 新文件 `mcp/HistorySearchService.kt`（纯函数可测，照 McpToolsetSupport 逻辑分层先例） |
| **T-02 工具注册（第十一工具）** | 新增 `SqlVisualizerHistorySearchTool`（@McpTool name=sqlv_history_search，readOnlyHint=TRUE 对齐十工具口径）；TOOL_CLASSES 清单加一行；TOOL_NAMES 清单加一行；描述文案强调「检索历史落盘、只读不执行」 | `mcp/SqlVisualizerHistorySearchTool.kt`（新）+ `mcp/McpToolsetDynamicRegistrar.kt:53` + `mcp/McpToolGate.kt:32-41` |
| **T-03 设置面 + 守卫测试对账** | McpConfigurable descKey 补键 + bundle 双语；工具级禁用清单支持新工具名；守卫测试 TOOL_NAMES 对账更新（单一清单口径） | `config/McpConfigurable.kt:117` 邻域 + bundle 双语 + `src/test/` 守卫测试 |

**实施顺序**：T-01 → T-02 → T-03（串行小件，S 级体量一次收口）。
**红线自查**：R14 为工作流闭环单件（历史资产 → agent 通道），非工具数竞赛——第五轮 §8 与第九轮红线清单已两次过审。

## 三、验收

1. T-01 先红后绿：检索器在实现前工具调用返回 NOT_IMPLEMENTED 类错误（红），实现后按夹具条目命中（绿）；
2. 过滤语义夹具：关键词/正则/时间范围/来源四维过滤各一组 + 组合过滤；
3. 上限量保护断言：超限返回截断说明而非全量倾泻（防 agent 上下文爆炸）；
4. 只读断言：工具不产生任何写盘/执行副作用（readOnlyHint 与实现一致）；
5. 十工具零回归：既有 MCP 工具注册与守卫测试全绿；收口定向测试（不跑全量）。

## 四、决策点

| 编号 | 决策点 | 推荐 |
|---|---|---|
| **D1（本计划）** | 检索范围默认值 | **A：默认当日文件 + 可参数扩展到全部日期**——日常 agent 场景集中当页，全量检索显式请求防上下文爆炸；B：默认全量（简单但返回体不可控） |
| **D2（本计划）** | 返回体形态 | **A：条目摘要（时间戳+SQL 前 N 行+来源）+ 命中计数**——agent 自行决定是否请求全文；B：全文返回（配合 D1=A 的上限量也可，但 token 成本高）；C：只返回计数（信息量不足无闭环价值） |
| **D3（本计划）** | 与 R16（历史跨文件全文检索 UI）的关系 | **A：T-01 检索器设计为域中立纯逻辑，R16 UI 后续复用**——一次投入两处收益；B：各自实现（重复造轮子不值） |

## 五、待验证前提（显式标注推断项）

- **V14a（新增推断）**：MCP 工具注册链的完整触点按②③⑥三处清单推断——**未核 mcp-optional.xml（若有 XML 形态声明）是否仍需同步**：McpToolsetDynamicRegistrar 头注释称动态壳「替代 mcp-optional.xml 的 10 条 XML toolset 声明」，但 XML 文件是否已删除/退役未实证；T-02 开工前先核 resources/META-INF 实际形态，若 XML 在场需同步加条目（漏加=工具不出现）。
- **V14b（新增推断）**：readAll 的多文件读取顺序与滚动文件（.2.sql/.3.sql）的排序语义未核实（SqlLogFileReader.kt:56 只证签名，未证排序保证）——检索器按时间戳字段自行排序兜底，不依赖读序。
- **V14c（新增推断）**：平台 MCP 工具数上限/工具描述长度限制未核——11 工具是否触发平台侧分页或裁剪属平台行为推断，真机验证列入验收。

## 六、红线继承

付费订阅 × 纯格式化插件化 × 订阅制 AI × 插件生态平台化 × 数据库管理工具化 × 独立桌面应用 × **MCP 工具数竞赛（R14 系工作流闭环单件，非竞赛——第五轮 §8 原文口径）** × inspection 校验器 × 悬停预览回归——均不做。

## 七、实施记录

**已实施收口（2026-09-30，批次 9 第三次启动——前两次因账户并发限制零产出，本次全新开始）**。分支 t-r14histsrch（基=dev 32f356f），交付 SHA：9279844（T-01）、d13cbb6（T-02/T-03）。

- **T-01 检索器**：新增 `mcp/HistorySearchService.kt`——四维过滤（关键词/正则/时间范围/来源文件）纯逻辑，域中立零 com.intellij/mcpserver 依赖（D3=A，R16 UI 可复用）；默认当日条目（D1=A，按条目 timestamp ≥ 当日本地零点过滤而非按文件名挑文件——滚动块/自定义模板/跨天追加天然正确）、`allDates=true` 扩展全目录且默认范围内显式 fromMs 不可放宽；摘要返回（D2=A）：SQL 预览 ≤8 行/600 字符 + 命中计数 + truncated 标记，maxResults 默认 20/硬上限 100 越界钳制；扫描封顶 200_000 条（scanTruncated 警示）；命中最新在前（V14b 排序兜底按 timestamp 自排）。读取面复用 SqlLogFileReader 流式解析，未重造。定向测试 HistorySearchServiceTest 23/23 绿；先红读数=实现前 compileTestKotlin 编译失败（red 在案），后绿。
- **T-02 注册**：新增 `mcp/SqlVisualizerHistorySearchTool.kt`（@McpTool name=sqlv_history_search，readOnlyHint=TRUE/destructiveHint=FALSE/idempotentHint=TRUE，描述强调只读不执行）；TOOL_CLASSES 与 TOOL_NAMES 双清单各加一行（十→十一）；审计入参摘要显式传（T-MAT-04）；输出预算在 McpToolsetSupport 基类登记表补行（守卫约定）。**V14a 已核**：mcp-optional.xml 十条 XML toolset 声明已随 T-McpDynamicToolset 退役（文件仅剩 postStartupActivity 一条），注册只走 McpToolsetDynamicRegistrar 运行期动态壳，**零 XML 改动**。零 PSI/索引依赖故不 requireSmart（dumb 模式可用）。
- **T-03 设置面+守卫**：bundle 双语补 `settings.mcp.tool.desc.historysearch`（en：search persisted SQL execution history (read-only)／zh：历史落盘 SQL 检索（只读））；SettingsRealityGuardTest 动态拼键白名单补 historysearch；McpToolGateTest 新增两守卫：①注册器 TOOL_CLASSES ↔ TOOL_NAMES 按 T-McpToolNaming 命名约定一一对账（此前 TOOL_CLASSES 为 private 无对账，摘任一侧即静默漂移——本次转为 internal 钉死）②每工具描述键双语在场。
- **反向验证读数**：临时摘除 TOOL_NAMES 注册项 → McpToolGateTest `test十一个工具类钩子跟门控且稳定集恒定`＋`test注册器工具类清单与工具名清单按命名约定一一对账` 两处红（2 failed / 10 completed，BUILD FAILED）；恢复后同套件绿（BUILD SUCCESSFUL）。
- **实施期重锚证伪（口径微调）**：计划「来源过滤」原设想按通道（SqlSource）实现，实测落盘数据里**通道标记仅 MCP 存活**（SqlLogParamCodec.encodeEntry 只对 MCP 落 `s` 键，控制台/agent 来源读回一律 UNKNOWN，头部 `source:` 段已被 parseSource 占用为源码位置）——按数据实况改为**按来源文件（sourceFile）大小写不敏感子串过滤**，通道标记仅在命中元信息透出；对 agent「找某服务类落过的 SQL」才具实用价值。
- **文档口径**：`docs/架构设计/02-核心流程详解.md` 十工具→十一工具两处（顺带订正注册面仍写 mcp-optional.xml 的过时描述）；`docs/mcp/MCP-Tools-Agent-Guide.md` 在本工作树不存在（dev 侧并行道交付，「已立项未注册」句由主控合并时对齐，本道登记未改）；汇总计划 `20260930-1712-竞品对比近轮汇总-*.md` 自 master 仓 828a313 拷入工作树副本回填 §十（副本来源已在文内注明）。
- **验收对账**：①先红后绿＝检索器编译期红＋反向验证摘注册红，两读数在案；②四维+组合过滤夹具齐（含 pd 行不劈开条目）；③上限截断断言（30 命中 maxResults=5 → totalHits=30/returned=5/truncated=true）；④只读断言＝readOnlyHint 与实现一致（纯文件读零写零执行，无平台写 API 触点）；⑤既有工具零回归＝McpToolGateTest/McpConfigurableGateTest/McpToolsetShellFactoryTest/McpScopeSourceScanTest/SettingsRealityGuardTest 定向全绿（未跑全量，收口门禁归主控）。遗留：V14c（平台 11 工具是否触发分页/裁剪）属真机行为，留 runIde 沙盒批次验证。
