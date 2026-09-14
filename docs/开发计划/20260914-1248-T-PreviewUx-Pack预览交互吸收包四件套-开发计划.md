# T-PreviewUx-Pack：预览交互吸收包（DBX 四件套）开发计划

- 立项日期：2026-09-14
- 任务性质：既有链路体验增强（N-13，DBX 精华吸收清单 #1~#4）
- 依据文档：`docs/调研报告/20260914-0211-DBX数据库工具深度调研与MCP吸收规划-调研报告.md` §2（四大功能实现源码深挖，文件:行 出证）、第三轮调研报告 §7 吸收清单 v2 #1~#4 / §8.3 N-13
- 目标：四件增强全部落在既有面板/链路，无新增子系统，用户可感知度最高

## 一、四件套明细

| # | 功能 | DBX 做法（源码证实） | 落地形态 | 优先级 |
|---|------|---------------------|---------|--------|
| 1 | UPDATE 预览 (new) 列 | SET 表达式原样搬为 `<expr> AS "列名 (new)"`，按旧值求值即得新值（`dml_preview_sql.rs` 599 行纯函数） | 预览面板 UPDATE 渲染增加「原值列 + (new) 列」并排形态 | P1 |
| 2 | 诚实降级横幅 | 无法安全改写时明示 "Preview is not supported: 原因" 且不阻塞 | 预览/还原失败时面板顶部显示降级原因（哪段没还原、为什么），绝不静默 | P1 |
| 3 | `${table}` 快捷模板 | 选中表名 → 快捷键 → 模板展开走正常执行；冲突检测阻断保存 | mapper 编辑器选中表名一键 EXPLAIN / COUNT / 抽样送内嵌控制台 | P1 |
| 4 | 多语句执行确认 | 执行目标选择器标记每条可执行语句 + 预览高亮；键盘流 bypass | 日志一次还原多条时送控制台前勾选确认 | P1~P2 |

### 1.1 UPDATE 预览 (new) 列（吸收 #1）

- 触发面：Wrapper 链（Lambda/Query/Update）、XML 动态 SQL、注解 SQL 的 UPDATE 渲染产物
- 实现锚点：`ui/highlight/SqlFormatter.formatVertical`（竖排标注既有范式，T-VPairAnno 行尾列名标注先例）与 `converter/UpdateWrapperConverter` 系
- 展示形态：SET 子句每项渲染为 `列名 = 原值表达式, 列名 (new) = 表达式`；`a = a + 1` 类自引用按 DBX 口径「原样搬表达式」即正确（按旧值求值即得新值）
- 纯函数实现：解析 SET 子句 → 列名/表达式分离 → 追加 (new) 列；禁止 PSI 操作，headless 可测

### 1.2 诚实降级横幅（吸收 #2）

- 实现锚点：`ui/SqlPreviewPanel` 顶部提示区 + `log/LogToSqlPanel` 转换结果区
- 触发面：① 渲染失败（部分条件无法求值）② 日志还原失败（参数缺失/残缺日志）③ 降级链触发（T-PreviewExecDs 三级降级链生效时提示当前层级）
- 文案三要素：哪段没还原 / 为什么 / 用户能做什么（如「补全参数后重试」）；复用既有 i18n 双语键规范，绝不静默失败
- 弹窗克制红线约束：横幅为面板内嵌非模态提示，不算弹窗（`docs/设计规范-弹窗克制红线.md`，随 r2 合流入库）

### 1.3 `${table}` 快捷模板（吸收 #3）

- 触发面：SQL 预览编辑器 / mapper XML 编辑器内选中文本
- 动作集：EXPLAIN / COUNT / 抽样 10 行（LIMIT 10）——三动作共享一个 action group，注册 EditorPopupMenu 边界
- 执行通道：既有内嵌控制台链路（`execute/DataSourceSelector` + `SqlExecutor`），凭证不出 IDE
- 冲突检测：注册时检测既有快捷键冲突（DBX sqlShortcutActions.ts:15-43 冲突检测逻辑可参照），冲突即换备选键位并提示
- 与「生成 CRUD SQL」右键入口并存：目标互补（CRUD 生成 vs 计划/行数/抽样诊断），不合并菜单

### 1.4 多语句执行确认（吸收 #4）

- 触发面：日志还原产物含多条语句（`log/LogToSqlPanel` 批量还原 / SqlLogPanel 条目「转 SQL」）
- 实现锚点：既有 `SqlSelectionDialog`（T-CrudSql 已原生化为 JBPopup 浮层 + Row 模型）——复用候选选择浮层形态，条目=还原出的每条 SQL（摘要行 + 完整语句详情行）
- 交互：勾选确认后送内嵌控制台；单条语句直通不弹确认（不增加低价值打断）
- 键盘流 bypass：Enter/双击执行选中项，与 SqlSelectionDialog 既有交互一致

## 二、实施顺序

1. #2 诚实降级横幅（独立于其余三件，收益立现——所有失败路径统一收口文案）
2. #1 UPDATE (new) 列（纯函数 + 渲染层,headless 可测）
3. #4 多语句确认（复用 SqlSelectionDialog 浮层能力）
4. #3 `${table}` 快捷模板（action 注册 + 冲突检测，工程量最重）
5. 全量回归 + verifyPlugin（大功能口径）+ 真机走查

## 三、测试与验收

- 纯函数单测：SET 子句解析（含自引用/函数调用/嵌套括号）、降级文案三要素、max 语句数边界
- headless 面板测试：横幅显示/隐藏条件、(new) 列渲染、浮层勾选流（参照 T-CrudSql CrudSqlPreviewFlowTest 套路）
- 真机走查：暗色主题横幅视觉、XML 编辑器选中表名三动作、多语句日志还原勾选流
- 全量测试 0 失败；verifyPlugin 三目标 Compatible
- 排序依赖：#2/#4 依赖 T-MergeR2IntoMaster 先行（弹窗红线文档 + SqlSelectionDialog Row 化均在 master 演进侧）；#1/#3 可与合流并行开发（锚点稳定）

## 四、范围外（明确不做）

- DBX 吸收清单 #5~#13（无界写判定 / keyless 守卫 / EXPLAIN 多视图 / Lab 配方 / 多结果页 / 临时写解锁 / AI 安全前置 / 提案执行决策矩阵 / schema 预算注入）：O-9/O-10 与 N-14 各自归口，不在本包
- 连接管理、独立应用形态、消息队列/Redis/Mongo：不吸收定案不变
- UPDATE (new) 列不做「按行查出旧值再代入」——仅表达式层展示，不发起查询

## 五、实施记录（交付后回填）