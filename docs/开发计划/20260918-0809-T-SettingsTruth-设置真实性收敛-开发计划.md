# T-SettingsTruth 设置真实性收敛（假配置假开关清零）— 开发计划

- 日期：2026-09-18 08:09
- 任务编号：T-SettingsTruth
- 来源：用户指令「核实插件设置当中的所有配置项是否为真实的，不允许出现假配置，假开关」——20260918 全量核查（设置面板 60+ 控件 × 全局 State 71 字段 × 项目级 10 字段，UI 绑定 → 状态持久化 → 业务消费三层链路逐项追查，含 Java 访问器补扫），查出 1 个假开关 + 1 个死状态行 + 1 条幽灵搜索项 + 项目级设置半接线
- 定性：**缺陷修复（设置面真实性）+ O-5 Phase B 半接线补完 + 守卫测试防回归**；不新增用户可见功能面（P4 属既定承诺的收尾）

---

## 一、需求

核查结论（读码实测）：

**真实面（不改动）**：全局 71 字段中 68 个有业务消费点；设置面板全部控件在 isModified/apply/reset 三处成对绑定，apply 统一 `loadState(s.copy())` 强制持久化 + `SETTINGS_CHANGE_TOPIC` 广播；UpdateSection 状态机/导入导出/快捷键写 Keymap/缓存清除/语言写穿全真实；不在 UI 展示的内部字段（`logAutoCaptureSuppressed`/`enhancedCaptureConfirmed`/`paramHistoryJson`/`ignoredUpdateVersions`/`reportSubmitterName|Email`）均有真实消费。

**四个问题（本任务收敛对象）**：

| # | 问题 | 性质 |
|---|------|------|
| P1 | `autoInjectSqlSourceInterceptor` 勾选态持久化但全仓库零消费 | 假开关 |
| P2 | `enhancedStatusLabel` 承诺状态展示但全历史无更新逻辑 | 死 UI 行 |
| P3 | 搜索索引收录不存在的「显示筛选栏」；一批真实设置未进索引 | 幽灵项 + 覆盖缺口 |
| P4 | 项目级设置 10 个 effective* 仅 3 个被消费，死类未注册 | 半接线 |
| P5 | （新增）源码扫描守卫测试，防假配置回潮 | 防回归 |

## 二、方案设计

### P1 源码捕获拦截器开关改纯动作（假开关）

现状（PluginSettingsPanel.kt）：勾选即时触发 `triggerInterceptorInjection()`（:708-714，失败回弹勾选），但 apply 写 state（:1290）、reset 回填（:1424）、isModified 比较（:1197）——重启后**任意项目**勾选框恒显示历史勾选态（应用级状态跨项目泄漏），而注入动作只发生过一次；State 字段自 `PluginSettings.kt:378` 声明后无任何业务读取。tooltip 文案本就声明动作语义（zh bundle :533「勾选时立即在项目中生成一次 SqlSourceInterceptor 和配置类」）。

定案——**改纯动作开关**：

1. 删除 State 字段 `autoInjectSqlSourceInterceptor`（XmlSerializer 对残留 XML 属性自动忽略，旧 `mp-sql-visualizer.xml` 无迁移成本）；
2. 删除 isModified/apply/reset 三处对应行；
3. 注入**成功后也回弹**为未勾选（勾选 → 注入 → 回弹，动作完成即复位；现有失败回弹分支保留，两分支合流为「无论成败，终态未勾选」）；
4. tooltip 补一句「本开关不保存状态」。

### P2 增强捕获状态行接线（死 UI 行）

现状：`PluginSettingsPanel.kt:211` 声明、`:730` 摆放，KDoc 承诺「已挂载 pid=x / 已回落控制台捕获 / 连接中」，全历史无更新逻辑（4623a12 创建即缺更新器；795c136 删「增强捕获状态：」前缀标签时定案「状态值保留无标签显示」——即用户要的是**保留状态值**，接线是兑现该定案而非新需求）；配套 bundle 键 `settings.enhancedCapture.status` 现无人消费。

方案（状态源 = AgentManager，项目级服务，已有完整挂载生命周期 `AgentManager.kt:61` attachedPids、init 块 5s `sweepOffline` monitor、shutdownAll 回落链）：

1. `AgentManager` 新增 `internal fun statusSummary(): String?`：null = 无活动会话；「已挂载 pid=x[,y]」；「连接中断，已回落控制台捕获」（sweepOffline 摘除后）；「增强捕获未开启」态显示空串（开关自身可见，无需状态行叙事）。
2. 新增 app 级 Topic `AgentStatusListener`（仿 `PluginSettings.SETTINGS_CHANGE_TOPIC` 的 companion Topic 形态）：attach 成功 / sweepOffline 摘除 / shutdownAll 三个迁移点经 Application messageBus 广播 `onStatusChanged()`。
3. 面板侧：构造时订阅 Topic，回调遍历 `openProjects` 聚合各项目 `statusSummary()` 写入 `enhancedStatusLabel`（多项目以「；」连接）；reset() 里同步拉一次现值（页面打开即见）；`PluginSettingsPanel` 新增 `internal fun disposeResources()` 断开 messageBus 连接，由 `SectionConfigurable.disposeUIResources()` 调用（当前该方法空置，正好归位）。

### P3 设置搜索索引清幽灵 + 补缺口

1. 删 `PluginSettingsFolderConfigurable.kt:435` filterBar 条目；双语 bundle 删 `settings.log.filterBar` / `.tip`（zh :444-445、en :455 附近）——该键除搜索索引外全仓库零消费，删除无行为影响。
2. 补录真实未索引项（沿 `buildSearchableFields` 现有格式，逐项挂正确子页）：时间来源单选（log/capture）、SQL 列表显示模式、CRUD 标记样式 + 四色 + 恢复默认、自动保存保留上限、增强捕获开关/脱敏、源码捕获帧过滤前缀、原生 JOIN 图开关、竖排 VALUES 配对标注、自动折叠开关 + 阈值、字面量参数恢复、JDBC 模板 SQL 预览开关。

### P4 项目级设置接线补完（O-5 Phase B 收尾）

现状：`ProjectPluginSettingsConfigurable` 未注册 plugin.xml（死类，:27-29 自审注释已声明）；`ProjectPluginSettings` 10 个 `effective*` 仅 `isEnabled`/`effectiveDialect`/`effectiveFormatSql` 被 SqlPreviewService 消费（:1561-1573），**其余 7 个（replaceParams/injectLogicDelete/logicDeleteValue/tablePrefix/tableSuffix/tableNameInference/ignoredFields）零调用**——SqlPreviewService 直读全局（:190/:259/:387-388 等），`effectiveString`（:157）零调用。即使手工编辑 `.idea/mp-sql-visualizer-project.xml` 开启 `useProjectSettings`，也只有方言和格式化生效，其余静默沿用全局。

方案（补完接线，四步）：

1. **注册**：plugin.xml 增 `<projectConfigurable parentId="tools" id="cn.song.mybatisplus.visualizer.settings.project" bundle+key 同款/>`——项目级节点不得挂应用级 configurable 之下（平台限制），独立 Tools 子节点与全局页平级，displayName 加「（当前项目）」区分（bundle 新键）。
2. **面板扩容**（DSL v2，沿用 T-UN-15 试点形态）：`useProjectSettings` 总开关 gating 其余行；方言/参数替换/格式化/逻辑删除注入 + 值/表前缀/表后缀/推断策略/忽略字段 8 行（与 State 十字段一一对应，空值 = 使用全局语义在 tooltip 说明）；apply/isModified/reset 手写逻辑对齐（保留 modified 标志位范式）。
3. **读取点改走 effective**：SqlPreviewService 各直读点改经已有 `effective(...)`/`effectiveString(...)` helper；`EntityResolver`/`JpaEntityResolver`/`BatchOperationConverter` 持 project 上下文，读点改调 `ProjectPluginSettings.getInstance(project).effectiveXxx()`（`effectiveString` 由零调用转正）。缓存维度无需扩：`TableConfigSignature.projectSignature` 已含项目表配置四项（SqlPreviewService.kt:109-127），逻辑删除/参数替换不进表签名。
4. **联动**：项目级 apply 后不依赖广播（预览每次现读现生效）；`tableConfigSignature` 已把项目表配置计进 Wrapper/解析缓存键，改表配置即失效，天然成立。

**兜底瘦身方案**（若评审决定不做完整接线）：删 7 个未消费 `effective*` + `effectiveString` + 死类 + State 七字段，仅留方言/格式化两覆盖项，`tableConfigSignature` 项目维同步收缩——二选一，**不得维持现状**（现状即半假面）。

### P5 守卫测试 SettingsRealityGuardTest（防回潮）

形态仿 `BannedApiSourceScanTest` 源码扫描（读 src/main 文本断言，无平台依赖，进 testFast 纯逻辑分桶）：

- **断言一（写而不读）**：`PluginSettings.State` 每个 `var` 字段在 src/main 非 config 包存在大小写不敏感消费（覆盖 Java `getXxx` 访问器）——本次 P1 删除的字段由此防回潮。
- **断言二（摆而不绑）**：`PluginSettingsPanel` 中每个控件字段（JBCheckBox/JBTextField/JBTextArea/JComboBox/ComboBox/ColorPanel）在 isModified、apply、reset 三方法体内各出现至少一次（方法边界以 `private fun isModifiedPreview|TableMapping|CodeGen|SqlLog|Ui` + `fun apply(`/`fun reset(` 切片；updateSection.autoCheckCheck 与按钮类控件走白名单）。
- **断言三（声明而不消费）**：`ProjectPluginSettings` 每个 `fun effectiveXxx` 至少一个 config 外调用点（P4 完成后转绿；走瘦身方案则改为 State 字段同款断言）。
- **断言四（幽灵键）**：bundle 中 `settings.*` 键均被 src/main 消费或列入白名单——注意存在动态拼 key（UpdateSection.kt:246 `settings.update.${...}`），白名单机制先行，初始白名单收动态键 + `settings.enhancedCapture.status`（P2 接线后从白名单摘除转正）。

## 三、改动落点

- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt`：删 `autoInjectSqlSourceInterceptor`（P1）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt`：P1 三处删行 + 成功回弹；P2 状态行订阅/聚合/dispose；isModified 相应调整。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/SectionConfigurable.kt`：`disposeUIResources` 调 `panel.disposeResources()`（P2）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt`：`statusSummary()` + 三迁移点广播 + `AgentStatusListener` Topic（P2）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsFolderConfigurable.kt`：删 filterBar 条目 + 补录缺口项（P3）。
- `src/main/resources/messages/MpSqlVisualizerBundle*.properties`：删 filterBar 两键；新增项目页 displayName 键 + P1 tooltip 补句 + P2 状态话术键复用 `settings.enhancedCapture.status` 转正（P1/P2/P3/P4）。
- `src/main/resources/META-INF/plugin.xml`：`<projectConfigurable>` 注册（P4）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/ProjectPluginSettingsConfigurable.kt`：死类复活扩容（P4）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/ProjectPluginSettings.kt`：视 P4 走向，补 effective 语义注释（接线后不变）或瘦身删除（兜底案）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt` + `psi/EntityResolver.kt` + `psi/jpa/JpaEntityResolver.kt` + `psi/BatchOperationConverter.kt`：读取点改走 effective（P4）。
- `src/test/kotlin/cn/song/mybatisplus/visualizer/config/SettingsRealityGuardTest.kt`：新建（P5）。
- 既有测试对齐：`PluginSettingsPersistenceTest`/`PluginSettingsPanelBindingTest`/`PluginSettingsPanelUiTest`（P1 删字段与绑定的断言面）、`SettingsIOTest`（不受影响，导出子集本就不含该字段，复核即可）。

## 四、测试计划

- **P1**：勾选 → 注入成功 → 勾选框回弹未选中；失败路径维持回弹；重启语义（reset 恒未选中）；State 序列化残留字段忽略（旧 XML 加载不抛）。
- **P2**：AgentManager 三迁移点各触发一次 Topic（测试替换 seam 注入 fake）；面板聚合多项目文案拼接；无活动会话显示空串；disposeResources 后不再回调（无泄漏）。
- **P3**：搜索「筛选」不再命中任何项；补录项逐个命中且跳转正确子页（navigateTo 路由断言）；BundleParityGuardTest 绿。
- **P4**：项目级开启 + 覆盖 replaceParams=false → 预览保留 `?`（此前恒全局）；覆盖表前缀 → EntityResolver 表名生效且 `tableConfigSignature` 变化触发缓存重建；关闭项目级 → 回退全局；`effective*` 九方法逐一有消费断言（并入守卫测试断言三）。
- **P5**：守卫测试自身先红后绿（对 P1 修复前的代码回放验证能抓到 autoInject 假开关——用 git 历史 stash 校验或注释式验证，防守卫假绿）。
- **回归门禁**：`testFast` 秒级通道先行（守卫测试在此），后全量 test；P4 触及 psi 层，`EntityResolverCacheTest`/`EntityResolverTableConfigTest`/`JpaEntityResolver*` 必跑。

## 五、已知边界 / 取舍

- **P1 删除而非补自动语义**：「重启后自动重新注入」需在项目打开时静默改用户工程源码，打扰面大于收益，且注入产物一旦生成就长期存在——动作语义 + 不持久化最诚实。旧 XML 里的残留 `autoInjectSqlSourceInterceptor` 属性由 XmlSerializer 自动忽略，无需迁移。
- **P2 状态行只报会话态，不报历史**：设置页打开时若无活动会话即空白行（保留一行占位与 795c136「无标签显示」定案一致）；不引入轮询，仅事件驱动。
- **P4 是本任务唯一可能超小修门禁的子项**（跨 config/plugin.xml/psi 四层）；若评审希望控制单任务体量，可拆为 T-SettingsTruth（P1/P2/P3/P5 先行）+ T-ProjectSettingsWire（P4）两任务串行，P5 断言三在拆分场景下随 P4 任务交付。
- **P5 断言四的动态键白名单**是宽松机制（人工维护），防误报而非防恶意；若后续动态拼 key 增多，再考虑 message() 调用点静态收集。
- **SettingsIO 导入重置副作用**（导入仅覆盖 21 项，其余字段重置为默认）核查中确认属设计内行为，但语义上接近「假导入」——不在本任务处理，立候选任务 T-SettingsIoScope（导入前 diff 预览或全量导出）由用户定夺。
- 用户侧文档（README/官网帮助页）经 grep 实测未单独描述上述任一开关，交付时复核一次即可，不触发八处同步全流程。
