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

现状：`PluginSettingsPanel.kt:211` 声明、`:730` 摆放，KDoc 承诺「已挂载 pid=x / 已回落控制台捕获 / 连接中」，全历史无更新逻辑（ae0c549 创建即缺更新器；92bcf6f 删「增强捕获状态：」前缀标签时定案「状态值保留无标签显示」——即用户要的是**保留状态值**，接线是兑现该定案而非新需求）；配套 bundle 键 `settings.enhancedCapture.status` 现无人消费。

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
- **P2 状态行只报会话态，不报历史**：设置页打开时若无活动会话即空白行（保留一行占位与 92bcf6f「无标签显示」定案一致）；不引入轮询，仅事件驱动。
- **P4 是本任务唯一可能超小修门禁的子项**（跨 config/plugin.xml/psi 四层）；若评审希望控制单任务体量，可拆为 T-SettingsTruth（P1/P2/P3/P5 先行）+ T-ProjectSettingsWire（P4）两任务串行，P5 断言三在拆分场景下随 P4 任务交付。
- **P5 断言四的动态键白名单**是宽松机制（人工维护），防误报而非防恶意；若后续动态拼 key 增多，再考虑 message() 调用点静态收集。
- **SettingsIO 导入重置副作用**（导入仅覆盖 21 项，其余字段重置为默认）核查中确认属设计内行为，但语义上接近「假导入」——不在本任务处理，立候选任务 T-SettingsIoScope（导入前 diff 预览或全量导出）由用户定夺。
- 用户侧文档（README/官网帮助页）经 grep 实测未单独描述上述任一开关，交付时复核一次即可，不触发八处同步全流程。

## 六、实施记录（20260918 交付回填）

**交付形态**：sam 调度并行实施——工作树 `mpsv-settings-truth`（分支 `t-settings-truth`，基 master `a9d5525`），三波派发（P1+P2 与 P3 并行 → P4 → P5），合并树独立复核后并入 master。

### 6.1 任务 → 提交映射

| 子项 | 提交 | 落点摘要 |
|------|------|---------|
| 立项（计划文档） | `a9d5525` | docs/开发计划/20260918-0809 |
| P3 搜索索引 | `cb55e54` | PluginSettingsFolderConfigurable 删 filterBar 条目 + 补录 13 项；双语 bundle 删两键 |
| P1 假开关 | `7f41182` | PluginSettings 删字段；面板删三处绑定 + 成败均回弹；双语 tooltip 补说明 |
| P2 状态行接线 | `7f41182` | AgentManager `statusSummary` + `AGENT_STATUS_TOPIC` + 三广播点；面板订阅/聚合/disposeResources；SectionConfigurable 接线 |
| P4 项目级接线 | `f576d4d` | plugin.xml 注册 projectConfigurable；Configurable 扩 10 控件；SqlPreviewService 7 直读点 + effectiveString 转正；EntityResolver/JpaEntityResolver effectiveTableSetting；TableConfigSignature.projectSignature(project) |
| P5 守卫测试 | `d4f96a3` | SettingsRealityGuardTest 四断言（先红后绿已验） |
| 幽灵键收尾 | `f3a746f` | 删 `settings.update.envNotSupported` / `settings.search.placeholder` 双语四行；白名单收窄至两枚真动态键 |
| 门禁收口 | `20e1229` | P4 三缺陷修复 + 用例锚点对齐（见 6.3） |
| 合入 master | `ecdb09f` | merge（唯一冲突 AgentManager.kt，见 6.4） |

### 6.2 与计划的偏离

1. **P4 走「补完接线」而非兜底瘦身**：用户拍板补完（计划 §五 曾列二选一）。`BatchOperationConverter` 的 `tenantColumnName`/`injectTenantLine` 不属项目级 State 字段范围，维持全局读取（非本子项七字段之列，已在交付报告注明）。
2. **P5 断言二白名单新增 `autoInjectInterceptorCheck`**：P1 定案后该控件为纯动作开关（不进 apply/reset），须豁免，否则守卫与 P1 语义自相矛盾。
3. **额外产出**：守卫断言四首跑抓出两枚存量幽灵 bundle 键（计划未预见），按 P3 filterBar 先例清理并收窄白名单。
4. **P4 面板控件数由计划的「8 行」实为 10 项**（总开关 + 方言 + 三 tri-state 勾选 + 四文本框 + 一下拉），计划 §二 表述为「8 行」指可覆盖项行数，实际含开关与方言共 10 控件。

### 6.3 门禁暴露的问题（testFast 未覆盖，全量 test 抓出）

| # | 问题 | 根因 | 修法 |
|---|------|------|------|
| 1 | 设置页未改动却显示「已修改」 | 平台 ComboBox 对**同值** `setSelectedItem` 也派发 ActionEvent（262 实证），createComponent/reset 灌值即置 `modified=true` | isModified 去掉 `modified` 标志位短路，改纯逐项比对（标志位降级为不可信） |
| 2 | 逻辑删除值字段对全局兜底值恒误报 | 先 `setText` 后换自定义 `PlainDocument`，换 document 清空了已灌值 | 先换 document 再 setText |
| 3 | 项目级 replaceParams 覆盖用例红 | 用例锚点用**字面量实参**（`eq(::getId, 1001L)`），而字面量恒内联是 ArgResolver 既有语义（M14.6 起开关只拦变量追踪） | 用例改变量实参，对齐被测语义 |

另：合并后首跑出现 `PluginSettings$State.copy$default` 成片 NoSuchMethodError，为陈旧编译产物劫持（State 签名变化后旧 class 残留），清 `build/classes`+`build/kotlin` 后消失——非真回归。

### 6.4 合并与冲突

- master 在分支实施期间被并行会话推进 10 提交（T-PerfSweep4 并入 / T-HqlInhCols / T-BrowserLeak2 / T-CaptureBack / T-FramePrefixVisibility），双方共同触及 6 文件；`git merge-tree` 预演判定仅 1 处真冲突。
- **冲突**：`AgentManager.kt` —— 并行会话 T-PS4-01/03 把 `sweepOffline` 改 `internal` 并加注释，与本分支 P2 在同位置插入成员相撞。解法=保留 master 侧签名与注释、叠加 P2 新增成员（`statusSummary`/`everAttached`/`publishStatusChanged`），三广播点完整。
- **合并后修**：`SettingsRealityGuardTest` 断言二 CRLF 脆弱性——本机 `core.autocrlf=true` 检出为 CRLF，而 Java 正则 `.` 不匹配 回车符，全匹配失配；改为行尾归一再做行扫描（分支工作树为 LF 故未暴露）。

### 6.5 后续修复（20260918 用户真机反馈）

**反馈**：项目级设置页「表名推断」下拉显示 `SNAKE` 裸枚举值，与全局设置页同项文案不一致（语言不一致）。

**根因**：项目页下拉用 `comboBox(listOf(PluginSettings.INFERENCE_SNAKE, PluginSettings.INFERENCE_CAMEL))` 直接以常量值作显示项（无 label 层），而全局面板走 `InferenceOption(value, label)` + `settings.tableNameInference.snake/.camel` bundle 文案。

**修法**（三处一并收口，避免只修症状）：
1. 项目页引入同款 `InferenceOption` data class（值 + 本地化文案，复用全局 bundle 键），构建/回灌/取值三处按 equals 匹配（`setSelectedItem` 传裸值会静默 return）；
2. **8 个项目级重复标签键收编为全局键**——`settings.project.{replaceParams,formatSql,injectLogicDelete,logicDeleteValue,tablePrefix,tableSuffix,tableNameInference,ignoredFields}` 与全局键语义重复且措辞漂移（表名推断 vs 表名推断策略、添加表名前缀 vs 添加表前缀），删双语 16 行改用全局键，项目页与全局页逐字一致；
3. 节点名改**语言中性** `SQL Visualizer (Project)`（原 zh「（当前项目）」/en「(Current Project)」——configurable 显示名由平台 `ResourceBundle`/`BundleBase` 按 **IDE locale** 解析，而面板内容跟插件语言，英文 IDE + 中文插件时露出英文后缀；全局页当初即以两语言同串规避，项目页现按同惯例对齐）。

**回归守卫**：`ProjectPluginSettingsConfigurableTest` 新增断言——下拉项文案须等于全局面板同一组 bundle 键文案、且不得为枚举原值；行标签须含全局键文案。防「裸值当显示项 / 标签键自造」同类回潮。

**验证**：settings 面 + i18n 面定向测试全绿；全量 test 见 6.6。

### 6.6 验证结果

- 合并树全量 `test`：**4406/0/0/6 绿**；`:sql-visualizer-agent:test` 绿。
- 独立复核（写验分离）通过；三项 P3 观察项在册（多项目同开项目级表设置取值歧义 / 状态行文案未 bundle 化 / 守卫断言三 KDoc 宽松命中）。
- 真机走查五项留人工，见 [验收文档](../验收文档/20260918-1238-T-SettingsTruth-设置真实性收敛-验收文档.md)。
