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

实施分支 `t-o96fix`（自 dev tip 2e8115f 切出），20261002 完成。

### 待验证前提证伪结论（先行，后动码）

1. **前提 S2（EXPLAIN 直送与 last-used 同源）——证伪（部分），走 S2 备选文案区分**。取证：EXPLAIN 直送链 `SqlPreviewPanel.submitExplainDirect → SqlExecutor.execute → DataSourceSelector.selectAndExecute → chooseAndPrepare(executeAfterPrepare=true)`，而 last-used 静默复用分支被 `if (!executeAfterPrepare)` 门控（DataSourceSelector.kt :371）——同一条 M36 链但记忆分支只在 prepareConsole 路径生效。即：无活控制台窗口内，直送通道的数据源解析=单源直连或**用户弹窗**（多源），与记忆非严格同源；结构化视图通道要求 `hasBoundDataSource`（活控制台），不涉及记忆。主执行链（面板打开 prepareConsole）与记忆严格同源。→ 按计划 S2 备选执行：记忆态指示文案区分（`panel.bindstate.associated`=「关联（上次使用）：{0}」），不冒称「已绑定」；回退链对齐=指示行记忆判定**复用 T-SI-02 `resolveLastUsedRunnerIndex` 同一纯函数**（UUID 精确→名唯一→歧义弃），与执行链静默复用同口径。
2. **前提 2（记忆生命周期）——证实**：`lastUsedRunner` 存储为 `PropertiesComponent.getInstance(project)`（DataSourceSelector.kt :3410~3413），项目级持久化，**跨 IDE 重启**存活；指示行重开语义即跨重启级。验收 1 口径按持久化记忆执行，无需会话级注记。
3. **前提 3（getDataSourceById 删除语义）——证实**：`DataSourceSettings.getDataSourceById` 反射调用 `DataSourceStorage.getDataSourceById(String)`，未命中返回 null、反射异常 runCatching 兜底 null（DataSourceSettings.kt :85~93），fail-closed 成立。

### 改动清单

- `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt`：新增 `rememberedDataSourceName(project)`（记忆回退取名，**只读**——歧义不清记忆不弹窗，展示路径禁副作用；记忆缺失快速早退）+ `resolveRememberedDataSourceName`（纯函数壳，复用 T-SI-02 判定）+ 测试缝 `rememberedLookupForTest`（headless 无 Database 插件真链恒空）+ `plantLastUsedMemoryForTest`（直写记忆双键）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt`：新增 `rememberedDataSourceNameForDisplay()`（project 在场才走记忆层）；`bindStateText()` 升级三级：活控制台「已绑定」> 记忆态「关联（上次使用）」> 未绑定。既有 `bindStateNameProvider` 测试缝与 O-95 S3 语义不动。
- `src/main/resources/messages/MpSqlVisualizerBundle.properties` + `_zh.properties`：新增 `panel.bindstate.associated=Associated (last used): {0}` / 「关联（上次使用）：{0}」（占位符 arity 两语种一致）。
- `src/test/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelBindStateTest.kt`：扩展 4 例（详见下）。

### 定向读数

- `SqlPreviewPanelBindStateTest`：**7 例 0 失败 0 错误 9.6s**（恢复后权威复跑；新增 4 例=test记忆命中显示关联上次使用文案 / testUUID失效且名字不在籍回落未绑定 / testUUID失效但名字唯一命中仍显示关联 / test同名歧义回落未绑定，三态覆盖记忆命中、UUID 失效（名字不在籍回落 + 名字唯一命中按 T-SI-02 口径仍关联）、同名歧义弃记忆）。
- 相关既有定向：`DataSourceSelectorTest` 2 例全绿（0.021s）、`SessionIdentityGuardTest` 23 例全绿（0.034s，T-SI-02 判定回归无扰动）。

### 反向验证（红→绿）

- 红：临时摘除 `bindStateText` 记忆回退分支（`&& false` 短路，REVERSE-PROBE）→ `SqlPreviewPanelBindStateTest` **7 例 2 失败**——恰好两条记忆命中断言红（test记忆命中显示关联上次使用文案 :137 ComparisonFailure「关联（上次使用）：mp_…」、testUUID失效但名字唯一命中仍显示关联 :165），未绑定断言 3 例与版式 2 例保持绿（预期内——回落未绑定本就是摘除后的行为）。
- 绿：恢复分支后复跑 → **7 例 0 失败 0 错误 9.6s**，工作树与提交 ac826e4 逐字节一致（git diff 空）。

### 偏差说明与遗留

- 签名偏差：计划 S1 写「`boundConsoleDataSourceName()` 增加记忆回退、签名带 project」——实际拆为「`boundConsoleDataSourceName()`（活控制台，无参不动）+ `rememberedDataSourceName(project)`（记忆层）」两函数、面板侧组合优先级（活控制台 > 记忆 > 未绑定）。原因：S2 证伪后记忆态需要**独立文案**，单函数返回 String 无法区分来源；拆分后 O-95 S3 既有调用面零扰动。
- 验收 1（沙盒真机 1b 场景反转：执行一次→关闭→重开显示记忆源名 / 删除数据源后回落未绑定）与守卫元核对留待收口代理 runIde 沙盒复测+门禁（本代理无沙盒环境，headless 定向已覆盖三态判定逻辑）。
- 展示优先级中「记忆命中后活控制台接管」依赖既有 renderSql 尾部单点刷新（S3 通道不变，零监听器口径维持）。
