# R12 T-PaginationSim 分页拦截器模拟（IPage/Page→LIMIT+COUNT 包裹预览）— 开发计划

- **日期**：2026-09-29
- **编号**：R12（第五轮报告 §8 新功能推荐池 D16 定案排序第二；本计划为独立实施计划，不占新 O 号）
- **动机**（第五轮报告 §8 原锚点，2026-09-29 已逐条复核）：F-6 拦截器模拟族已做 TenantLine/DynamicTableName/DataPermission 三件（`PluginSettings.kt:415` F-6 节段锚，报告写 :393 已漂移），分页改写登记口径「全仓零命中」——**复核后需修正边界**：Wrapper 链分页已有（T-M29-02/03），真实缺口在 mapper 方法/XML 路径与 COUNT 包裹；分页是 MP 使用率最高的拦截器（PaginationInnerInterceptor）
- **成本**：M 级
- **状态**：已核实待立项实施（用户 D16 定案排序第二）；证伪清单继承第五轮 §8 十条，勿重复立项

---

## 一、现状锚点（2026-09-29 全部实测重锚）

| # | 现状 | 锚点（文件:行，本工作树实测） |
|---|---|---|
| ① | **已有**：Wrapper 链分页消费 → Page 实参解析为 LimitClause 追加 LIMIT | `psi/WrapperLocator.kt:968`（var pageLimit）、`:985`（pageConsumingCall→parsePageLimit）、`:1042`（chain 装配）、`:1157-1186`（parsePageLimit：`new Page<>(current, size)`，空构造默认 current=1/size=10 对齐 MP 默认）；`model/WrapperChain.kt:33`（pageLimit: LimitClause?） |
| ② | **已有**：预览侧 LIMIT 消费——仅 SELECT 追加，count/exists 消费不追加分页 | `ui/SqlPreviewService.kt:340-349`（分页消费判断）、`:1587-1594`（applyPageLimit 仅 SelectStatement.copy(limit=...)） |
| ③ | **已有**：方言 LIMIT 形态基建 | `sql/Dialect.kt:11`（分页子句 renderLimit：MySQL LIMIT / PG LIMIT+OFFSET / Oracle FETCH）、`:300-305`（renderMySqlStyleLimit）、`:220-222`（DELETE LIMIT 尾段方言限制先例） |
| ④ | **已有**：捕获侧识别 MP 分页 COUNT 查询（标记，非生成） | `log/SqlSourceMarker.kt:90-100`（MP_PAGINATION_COUNT：MP PaginationInnerInterceptor 自动执行的 COUNT 查询不经过拦截器、无源码标记，用特殊标记兜底） |
| ⑤ | **缺口 A**：mapper 方法/XML 路径无 IPage/Page 特殊处理 | `grep -rn "IPage" src/main/kotlin/` 仅 `ui/XmlSqlCompletionContextResolver.kt:44/:75/:172`（类型排除名单）——mapper 方法带 Page 参数的预览不追加分页、无 LIMIT 追加 |
| ⑥ | **缺口 B**：预览侧无 COUNT 查询包裹生成 | 全预览链无 PaginationInnerInterceptor 语义模拟——`SqlPreviewService.kt:260` 的 count() 消费是 wrapper.count() 路径（SELECT COUNT(*) FROM 原表），非「分页 COUNT 包裹（SELECT COUNT(*) FROM (原SQL) / 优化去 ORDER BY 形态）」 |
| ⑦ | 拦截器模拟族接线形态（三件先例） | `sql/interceptor/`（TenantLineEnhancer/DynamicTableNameProcessor/DataPermissionPlaceholder 三文件）+ `ui/SqlPreviewService.kt:326-337`（租户注入→动态表名串行链）+ `config/PluginSettings.kt:415`（F-6 节段） |

## 二、方案（三个子任务，缺口 A 先行）

| 子任务 | 内容 | 写集 |
|---|---|---|
| **T-01 mapper 方法 Page 参数解析（缺口 A）** | mapper 方法预览链识别 `Page`/`IPage` 类型参数（首参或任意位置），按 MP PaginationInnerInterceptor 语义追加 LIMIT 子句（复用 Dialect.renderLimit 方言基建与 LimitClause 模型，对齐①已有口径：current/size 解析、空构造默认值）；XML 语句路径同参数标签解析 | `psi/`（mapper 方法参数定位，实施时锚 MapperMethodLocator 归属文件）+ `ui/SqlPreviewService.kt` + `model/LimitClause` 消费点 |
| **T-02 COUNT 包裹预览（缺口 B）** | 分页预览附产出 COUNT 查询预览：`SELECT COUNT(*) FROM (原SQL)` 基础形态 + MP 3.5.x 优化形态（简单查询去 ORDER BY 直查表）作为可选项；对齐④捕获侧已有的 COUNT 识别口径（捕获标记 ↔ 预览生成闭环） | `ui/SqlPreviewService.kt` + `sql/`（COUNT 包裹生成器，可挂 interceptor/ 新文件 PaginationSimulator.kt 对齐三件先例形态） |
| **T-03 设置面 + 文案** | F-6 节段增分页模拟开关与文案（默认跟随既有三件口径）；预览面板 COUNT 产出展示位置对齐既有 statement 列表形态 | `config/PluginSettings.kt:415` 邻域 + `config/PluginSettingsPanel.kt` + `ui/SqlPreviewService.kt` 展示层 + bundle 双语 |

**实施顺序**：T-01 → T-02 → T-03（T-01 独立可交付；T-02 依赖 T-01 的 Page 解析产物；T-03 收口）。
**写集预警**：`SqlPreviewService.kt` 全仓最大热文件——开工前 `git log --oneline -5 -- <file>` 实测在途；R22 T-02/T-03 与本计划同文件，实施前对账。

## 三、验收

1. T-01 先红后绿：mapper 方法带 Page 参数预览在修复前无 LIMIT（红），修复后按方言追加（绿）；XML 路径同断言；
2. 与 Wrapper 链路径不重不漏：同一调用同时走 mapper 方法与 Wrapper 链时 LIMIT 不重复追加（边界断言，对齐①注释「last("limit ...") 与分页并存以分页为准」口径）；
3. T-02 COUNT 形态断言：包裹 SQL 与 MP 优化形态逐字节比对夹具；count/exists 消费不产 COUNT（复用②口径）；
4. 三件既有拦截器模拟零回归 + 方言矩阵（MySQL/PG/Oracle）LIMIT 形态夹具；
5. 收口定向测试（不跑全量，按验证分层纪律交收口代理）。

## 四、决策点

| 编号 | 决策点 | 推荐 |
|---|---|---|
| **D1（本计划）** | COUNT 包裹预览的产出位置 | **A：预览结果附第二条语句**（与主 SQL 并列展示，对齐捕获侧 COUNT 独立条目口径）；B：面板折叠附注（照 R10 Actual 段形态，跨计划一致性）；C：不做 COUNT（缺口 B 不修，R12 名不副实） |
| **D2（本计划）** | 优化 COUNT 形态是否实现 | **A：先只做基础包裹形态，优化形态（去 ORDER BY 直查）登记后续**——MP 优化规则含多表/嵌套判断，成本易失控；B：一步到位（成本 +M） |
| **D3（本计划）** | `searchCount=false` 语义 | **A：识别 Page.searchCount 并跳过 COUNT 产出**（对齐 MP 语义）；B：忽略该开关恒产出（简单但语义错误） |

## 五、待验证前提（显式标注推断项）

- **V13（继承第五轮）**：方言差异（各库 LIMIT/OFFSET 形态）在 11 方言矩阵下的覆盖面未盘点——③已证 MySQL/PG/Oracle 三形态在 Dialect 基建内，**其余 8 方言的 LIMIT 渲染现状未核**（部分方言可能无 LIMIT 概念，如 SQL Server TOP）；T-01 实施前逐方言盘点。
- **V13a（新增推断）**：mapper 方法参数定位的归属文件与入口签名（⑤仅证「现状无 IPage 处理」，未锚定改动落点的方法链），实施时先锚 MapperMethodLocator 类归属。
- **V13b（新增推断）**：MP 优化 COUNT 的确切形态按 3.5.x 源码记忆（`SELECT COUNT(*)` 替换查询列 + 去尾 ORDER BY + 简单查询去包裹）推断，未对照当前 MP 版本源码核实——T-02 动码前以本地 MP jar 反编译为准（方法名以反编译为唯一权威纪律）。
- **V13c（新增推断）**：XML 路径 Page 参数标签（`#{page.current}`/`#{page.size}` 显式写法）与拦截器自动追加的关系——用户 XML 里手写 LIMIT 与模拟追加冲突的形态未采样，T-01 边界断言覆盖。

## 六、红线继承

付费订阅 × 纯格式化插件化 × 订阅制 AI × 插件生态平台化 × 数据库管理工具化 × 独立桌面应用 × MCP 工具数竞赛 × inspection 校验器 × 悬停预览回归——均不做。R12 属免库预览主轴纵深。

## 七、实施记录

（待续——实施时回填本节）
