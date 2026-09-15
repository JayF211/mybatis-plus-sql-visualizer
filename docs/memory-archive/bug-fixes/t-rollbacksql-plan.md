---
name: t-rollbacksql-plan
description: T-RollbackSql 预览回滚SQL按钮已交付（2026-09-12，r2 分支）——F1~F6 全落地、262 连接API不可达走降级半自动、JSqlParser 5.3 四坑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_560029f8-8f73-4659-9d13-aa970739026e
---

T-RollbackSql 已交付（2026-09-12，feature/r2-competition-response，计划 §六 实施记录回填）。P1/P2 合并一次交付（用户定案 verifyPlugin 三目标放最后一次）。

交付物：`sql/rollback/RollbackSqlGenerator.kt`（INSERT→DELETE 整行谓词/主键偏好、UPDATE/DELETE 诚实拒绝+指引+快照模板、评级 Complete/Partial/Unable、500语句/256KB 上限、parseSnapshotTsv+generateFromSnapshotRows 三模式）+ `sql/rollback/RollbackSnapshotStore.kt`（project light service，指纹索引/LRU 100/parentExecutionId 留痕链）+ `ui/RollbackSqlDialog.kt`（MODELESS，评级横幅/只读编辑器/快照粘贴工作流）+ `SqlPreviewPanel.executeAdhocSql(sql, dmlConfirmed, rollbackOfEntry)` + 面板【回滚SQL】直达按钮 + rollback.* i18n 34 键双 bundle。

**F5 路线定夺（javap 实证，勿重试程序化 JDBC）**：262 的 `AbstractDataSource` 无连接方法；连接建立收敛到 `DatabaseConnectionManager.build().createBlocking()`→`GuardedRef<DatabaseConnection>`→`getRemoteConnection()`，返回类型 `RemoteConnection` **不在任何本地发行 jar**（remote 驱动运行时按需加载）——反射桥最后一跳无可用 API。落计划预设降级：快照查询模板一键执行（executeAdhocSql 落面板控制台网格）→ 用户复制网格 TSV → 弹窗粘贴+主键标记 → 生成完整回滚。dbx「有 before-image 才回滚」保留。

设计定案：评级按 §4.1 细则——整行谓词恒 Partial+COUNT 自查（§4.3 的 Complete 场景实为主键定位/快照路径）；多语句混合任一 Unable→整体 Unable；**Unable 但可解析的 UPDATE/DELETE 也进弹窗**（红色横幅+快照工作流），仅完全不可推导只出消息框——降级方案下粘贴回填是 UPDATE/DELETE 唯一回滚入口。

JSqlParser 5.3 实证坑（单测双验）：①`getSetUpdateSets()` 运行时可空，Kotlin 判空内建硬抛，先按可空收窄；②`Values extends Select` 且 `getValues()` 是 `(Values) getSelect()` 强转——INSERT…SELECT 直调 CCE，判别走 `getSelect() as? Values`；③单行 INSERT 的 expressions 扁平、多行嵌套，按首元素归一；④`StringValue.getValue()` 保留原始转义（`O''Brien`），渲染只补外引号。

测试：生成器 35 + store 7 + 面板 9 + 弹窗 7 + H2 往返 5（含乐观锁二次执行 no-op；testcontainers 真库由 DialectDockerVerificationTest 体系承担）。并行沙盒 t-rollbacksql/MPSV-Rollback（rollbackDev 开关临时加，已还原）。runIde 真机走查留人工。
