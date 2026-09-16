# T-DdlRecover 断连后 DDL 已生效判定 — 开发计划

- 日期：2026-09-16 03:06
- 任务编号：T-DdlRecover（第五轮新建议 **O-15**，P1；**依赖 T-ErrorLocate T-EL-02 富错误通道先行**）
- 来源：第五轮报告 §7 DBX 吸收清单 **#20**——DBX e498fc37：PG 系执行 CREATE TABLE 遇连接类错误 → 解析确认非临时表并提取表名（query.rs:1524）→ 新会话重试/duplicate relation 判定 + pg_class EXISTS 探测 → 合成带证据的成功结果（query.rs:2890~2969）
- 性质：执行链错误语义增强（防「连接断开但建表已生效」被呈现成纯失败，误导用户重跑或回滚）
- 实施分支：自 master 拉任务分支 t-ddl-recover
- 验证基线：合流后 master 全量 testShards 绿

---

## 一、背景与依据

执行链跑 DDL 撞上连接断开时，数据库侧可能已经提交生效（DDL 多数库隐式提交）。当前呈现是一条连接错误字符串，用户重跑会撞 duplicate、回滚无从谈起。DBX 的解法是「失败不等于未生效」：先判连接类异常，再对目标对象做存在性探测，探测命中就呈现「断开但已生效」的带证据警示。经锚点核实，本插件的落点在 IDE 执行链——报告 §7 设想的 MCP 侧落点（rollback_plan 区分未执行/已生效）**不成立**：MCP 链从不执行 DDL（explain 只读 SELECT 白名单，其余工具纯静态），故本任务收窄为 IDE 执行链单面。

边界先钉死：探测未命中或探测本身失败，**一律维持原错误呈现**（诚实降级）——绝不把「没查到」包装成「没生效」，那和虚报成功同样危险。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 连接类异常判定 | 无错误码级判定；唯一手段是 extractConsoleError 字符串签名（broken pipe/connection refused/deadlock 等）与 8s 监控 namespaceOk 间接判定 | `execute/DataSourceSelector.kt:610-615`、`:2560-2566` |
| SQLState/错误码 | 执行链零消费（T-ErrorLocate T-EL-02 将补富通道，本任务消费其产物） | 见 T-ErrorLocate 计划 §二 |
| DDL 判定 | MetadataInvalidator.mayChangeMetadata（AST+关键字双路 fail closed）现成 | `execute/MetadataInvalidator.kt:65` |
| 对象名提取 | JSqlParser 判 CREATE TABLE；残缺语句被 5.3 包成 UnsupportedStatement（O-12 实证），需正则兜底 | `execute/MetadataInvalidator.kt:27` 附近双路先例 |
| 存在性探测 | DatabaseSchemaResolver.tableMeta(project, name)（DasModel 内省，带缓存）可直接复用为「对象是否已存在」探测 | `ui/highlight/DatabaseSchemaResolver.kt:104-108` |
| 呈现位 | SqlResultPanel.showError 单行红字 + tooltip；有 showIdle 保留 ERROR 态先例 | `ui/SqlResultPanel.kt:170-186,214-222` |

## 三、方案

**T-DR-01（连接类异常分类器）**：`execute/ConnectionErrorClassifier.kt` 纯函数——SQLState 08xxx 优先，vendor 错误码表（MySQL 2002/2003/2006/2013、PG 08006/08003/57P01、Oracle 03113/03114 等常见连接类），既有 ERROR_SIGNATURES 字符串兜底；表驱动测试。

**T-DR-02（已生效性探测）**：错误分类=连接类 ∧ mayChangeMetadata(原语句) → 提取 DDL 目标对象名（JSqlParser CREATE 路由 + 残缺语句正则兜底，`CREATE TABLE/TABLE t` 词法提取）→ 延迟探测（DBN 自动重连窗口，退避重试 2~3 次）→ tableMeta 命中即「已生效」。

**T-DR-03（呈现升级）**：探测命中 → SqlResultPanel 错误态升级为独立警示形态：「连接已断开，但对象 `t` 已创建（探测于 HH:mm:ss）」——警示不消除错误原文（tooltip 保留），不自动重跑、不自动回滚；未命中/探测失败维持原错误 + tooltip 注明「已尝试生效性探测：未检出对象」。

**T-DR-04（i18n + 测试 + 文档）**：双语键 3~4 个；分类器与提取纯函数表驱动；CHANGELOG [Unreleased] Added、变更记录行、第五轮报告 O-15 状态回填。

## 四、验收标准

1. 分类器表驱动全绿（SQLState/vendor 码/字符串兜底三路 + 非连接类不误判）。
2. 探测命中→警示条；未命中/失败→原错误原文不变，仅 tooltip 增注；**任何路径都不虚报成功**。
3. 警示条为信息级呈现，无新弹窗（弹窗克制红线）。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 沙盒/容器数据源执行 CREATE TABLE 后立刻断网（或停库）：警示条出现且对象名正确；恢复网络后 information_schema 与警示一致。
2. 非 DDL 语句遇断连：维持原错误，无警示条（不误伤）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| DBN 重连时机不可控 | 探测过早会失败 | 退避重试 2~3 次；失败即降级原错误（不阻塞主流程） |
| 探测误报 | 同名旧对象恰存在 | 探测命中仅呈现「已存在」事实与时间戳，不声称「本次执行所建」——文案口径「对象已存在（可能为本次已生效）」 |
| 残缺 DDL 提取失败 | 5.3 解析形状实证（UnsupportedStatement/Block） | 正则兜底 + 提取失败即放弃探测（诚实降级） |
| 环境受限 | 断连场景沙盒难构造 | compose Lab 容器 stop 模拟；不可行则「环境受限未验证」口径 + 表驱动桩测 |

## 七、实施顺序

```
（前置）T-ErrorLocate T-EL-02 → T-DR-01（分类器）→ T-DR-02（探测）
→ T-DR-03（呈现）→ T-DR-04（i18n+测试+文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

（未实施）
