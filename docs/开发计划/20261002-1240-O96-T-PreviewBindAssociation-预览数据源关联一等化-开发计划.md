# O-96 T-PreviewBindAssociation 预览数据源关联一等化（第一阶段）— 开发计划

> 立项：20261002，用户认可「长远发展角度建议调整」后令「开始调整 实施」。定位：预览↔数据源关联升一等概念的第一步；终态（显式选择器+R37 AI 上下文透出）随阶段二 R37 批次，不在本计划。

## 一、锚点核实（已实测）

1. **持久化半已存在**：`DataSourceSelector.kt` T-SI-02 `lastUsedRunner(project)`（`LastUsedMemory(dataSourceId, displayName)`，UUID 来自 `LocalDataSource.getUniqueId` 反射封装）+ 预览准备链「last-used 静默复用」（:371~387，UUID 精确命中→名字唯一命中→歧义 `forgetLastRunner` 弃记忆弹窗）。
2. **缺口**：O-95 S3 指示行取数链 `boundConsoleDataSourceName()`（:196~208）只读活控制台（`sharedConsole ?: preparedConsole` + isValid 先探），不认 last-used 记忆 → 预览重开后指示回落「未绑定」，而下次执行实际会静默打到记忆中的同一数据源——**指示与执行语义脱节**（走查实证 o95s3-052/053）。
3. 控制台准备=M36.5 重机制（持久整控台嵌入），「开面板自动建控制台」不可取（本计划排除该形态）。

## 二、方案

- **S1 指示行关联链升级**：`boundConsoleDataSourceName()` 增加 last-used 记忆回退——无活控制台时，经 `lastUsedRunner(project)` 取 UUID → `DataSourceSettings.getDataSourceById(project, id)` 解析在籍校验（唯一性口径与 T-SI-02 一致，歧义/不存在 → 未绑定，不静默猜）→ 返回显示名。签名需带 `project`（调用点 `SqlPreviewPanel.renderSql` 尾部 project 在场）。
- **S2 语义自洽核查（待验证前提先行）**：EXPLAIN/结构化视图直送通道（boundConsoleDataSource 为 null 的 fail-closed 回退）的数据源解析是否与 last-used 同源——同源则指示「已绑定：X」为真话；不同源则本计划改指示文案区分（如「关联：X」）并对齐回退链。**实施者先证伪再动码。**
- **S3 刷新通道不变**：renderSql 尾部单点（R30 零监听器口径）；执行成功后活控制台绑定自然接管记忆回退（展示优先级：活控制台 > last-used 记忆 > 未绑定）。

## 三、验收

1. 沙盒真机复验 1b 场景反转：预览执行一次→关闭→换语句重开 → 指示行显示记忆源名（数据源仍在籍时）；删除该数据源后重开 → 回落「未绑定」。
2. 定向测试：`SqlPreviewPanelBindStateTest` 扩展（记忆命中/UUID 失效/歧义三态）+ 反向验证（摘回退分支→记忆场景红）。
3. 守卫元登记；收口定向全绿后并入 dev，沙盒 UI 复测+门禁。

## 四、待验证前提（实施先证伪）

1. 前提 S2：EXPLAIN 直送通道与 last-used 同源（大概率真：同一 M36 链；证伪则走 S2 备选文案区分）。
2. `lastUsedRunner` 的存储生命周期覆盖「跨预览对话框」（走查日志已证跨对话框 session created 仍命中记忆，真）；但**跨 IDE 重启**是否持久需核实（若为内存态则指示行重开语义=会话级，需在验收 1 注明口径）。
3. `DataSourceSettings.getDataSourceById` 对已删除数据源返回 null（解析在籍校验的根基，社区版 fail-closed 同族）。

## 五、实施记录

（待实施分支 t-o96fix 回填。）
