# T-ProjectSettingsScope 项目级设置语义与呈现收口 — 开发计划

- 日期：2026-09-18 22:45
- 任务编号：T-ProjectSettingsScope
- 来源：用户 20260918 提问「插件设置中有一个项目级设置，这个项目级设置是否多余」——读码核查结论：**能力不多余，冗余感来自呈现层的四处实现问题**
- 定性：缺陷修复（设置面呈现真实性）+ 可用性补口 + 一致性收口；**不删能力、不改持久化语义**

---

## 一、背景与依据

### 1.1 为什么不多余（读码实测，非推测）

项目级设置是一套完整接线的服务，不是空壳：

- `ProjectPluginSettings`（`@Service(Service.Level.PROJECT)` + `PersistentStateComponent`，存 `.idea/mp-sql-visualizer-project.xml`）的 9 个 `effectiveXxx()` 全部有消费点；
- 消费分布：`SqlPreviewService.kt:1632-1680`（7 项经 `effective`/`effectiveString` helper）、`EntityResolver.kt:566-583`、`JpaEntityResolver.kt:746-759`；
- `plugin.xml:119-123` 已注册 `<projectConfigurable parentId="tools">`（T-SettingsTruth P4，20260918 09:09 落位）。

需求面站得住：全局 `PluginSettings` 是**应用级**（整个 IDE 一份，78 字段），而项目级覆盖的 9 项——方言、表前缀/后缀、表名推断策略、忽略字段、逻辑删除值——恰恰是**随项目变化**的语义（A 项目表名带 `t_`、B 项目带 `sys_`；A 用 MySQL、B 用 PostgreSQL）。没有项目级，多项目用户开两个项目就得反复改全局。项目作用域在本插件也非外来概念：`DataSourceSettings`、`SqlHistoryManager`、`SqlLogManager` 均为项目级服务。

### 1.2 为什么"看着多余"（本任务收敛对象）

| # | 问题 | 性质 |
|---|------|------|
| P1 | 总开关关闭时，9 个控件仍显示项目残值，而运行时用全局值——**显示值与生效值不一致** | 缺陷 |
| P2 | 项目级设置无"重置为跟随全局"入口，关开关不清值，残值 UI 上无法清除 | 可用性缺口 |
| P3 | 项目页字段未登记进设置搜索索引，搜"表前缀"只命中全局行 | 一致性缺口 |
| P4 | 导入导出（O-5 计划本体）不覆盖项目级设置，UI 未明示范围 | 一致性缺口 |
| P5 | 上述语义无守卫用例，修一处漏一处会静默回潮 | 防回归 |

**冗余感的真正来源**：9 个可覆盖项在全局页全都有同名同文案的行（T-SettingsTruth 6.5 刚把两页标签收编为同一批 bundle 键），而全局 78 字段、项目只覆盖 9 个且无规律——用户并排看两页就是同一批行，天然会问"改哪个"。

## 二、现状核实（master 实测，2026-09-18）

| 事实 | 证据 |
|------|------|
| 项目页 10 控件（总开关 + 方言 + 3 tri-state 勾选 + 4 文本框 + 1 下拉） | `ProjectPluginSettingsConfigurable.kt:87-161` |
| 9 项灌值一律 `st.X.ifBlank { global.X }`，**不判 `st.useProjectSettings`** | `createComponent` :98/:105/:112/:119/:128/:135/:142/:150/:158；`reset` :228-239 |
| 只有总开关自身读开关态 | :92（createComponent）、:227（reset）、:212（apply）、:192（isModified） |
| `apply` 无条件写回 9 项（与开关态无关） | :213-221 |
| `isModified` 对 9 项不判开关，按"state + 全局兜底"比对 | :196-206 |
| 现存用例只覆盖开关**开启**态 | `ProjectPluginSettingsConfigurableTest.kt:162` 起（`st.useProjectSettings = true`） |
| 导入导出零项目级引用 | `SettingsIO.kt` 全文 grep `Project` 为空 |
| 搜索索引仅全局行 | `PluginSettingsFolderConfigurable.kt:413-439`；`SearchableField` 结构见 :301-306（含 `configurableClass` 字段，可挂项目页） |
| 真机走查仍未做 | T-SettingsTruth 验收文档 §3.4 标"☑️待真机" |

**P1 的完整后果链**（用一条可复现路径说明）：用户开启总开关 → 方言设为 PostgreSQL → 关掉总开关 → 运行时按 MySQL 渲染，而设置页照样显示 PostgreSQL。用户没有任何手段从 UI 看出真实生效值。

## 三、方案

### D1 决策点：总开关去留（先定后做）

两套闸门并存是本页困惑的根源：`useProjectSettings`（总开关）与"空值/null = 跟随全局"（逐项回退）**相互独立**，组合出用户无法预测的状态（开关关 + 有值 = 静默失效）。

| 方案 | 内容 | 风险 |
|------|------|------|
| **A（推荐）保留总开关 + 诚实化呈现** | 关闭时 9 行置灰并显示全局生效值，开关语义不变 | 低；不改持久化语义 |
| B 去总开关，改逐项三态 | 每项一个"跟随全局/自定义"控件 | 中；老 XML 中 `useProjectSettings=false` + 有值 的项目升级后会突然开始覆盖，属语义变更需明示 |

**推荐 A**：总开关"一键停用本项目覆盖但保留自定义值"是正当功能（形如"临时改用全局"），问题只在呈现不诚实，不在闸门本身。B 方案记录在册备选，本次不做。

### P1 总开关关闭时呈现生效值（核心缺陷）

改 `ProjectPluginSettingsConfigurable`，四处联动改，缺一处即回到原缺陷：

1. **灌值分支**（createComponent + reset 共 18 处表达式）：引入 `val showProject = st.useProjectSettings`，9 项一律改为
   `if (showProject) st.X.ifBlank { global.X } else global.X`（Boolean 项 `if (showProject) st.X ?: global.X else global.X`）。
2. **控件可用性**：9 项 `isEnabled = st.useProjectSettings`；总开关需挂 `addActionListener` 即时联动刷新 9 项的 enabled 与显示值。注意 T-RA-04 删监听器是为去掉假 `modified` 标志位，此处监听器有真实职责（刷新联动），重新引入不违该定案。
3. **apply 分态写入**（关键，写错即静默丢数据）：
   `if (st.useProjectSettings) { 写 9 项 }`，总开关态恒写。
   关闭态若仍无条件写回，会把**显示的全局值**当成项目自定义值写进 state——用户重新开启开关后发现自定义值已被全局值覆盖。此条必须有用例钉住（见 P5）。
4. **isModified 分态比对**：关闭态跳过 9 项比对（只比开关），否则控件显示全局值、state 存残值 → 恒报"已修改"（与 T-SettingsTruth 6.3 问题 1 同类的误报形态）。

可选增强（同一处代码，随 P1 一起做）：开关关闭时在面板顶部给一行状态文案（如"当前项目使用全局设置"），复用既有状态行形态；不做也不影响 P1 成立。

### P2 重置为跟随全局（可用性缺口）

面板新增按钮"重置为跟随全局"：

- 行为：`dialect = ""`、三个 Boolean = `null`、`logicDeleteValue`/`tablePrefix`/`tableSuffix`/`tableNameInference`/`ignoredFields` = `""`；**`useProjectSettings` 保持不动**；随后刷新控件（等价 `reset()`），`apply` 即持久化。
- 若 `feature/dialect-auto-infer` 已合流，`dialectAuto` 一并置 `null`（跟随全局）。
- 形态**照抄既有同类实现**（项目定例：新增 UI 交互须照抄既有同类实现）——参照 `PluginSettingsPanel.kt:754-762` 的"恢复默认颜色"：`JButton` + bundle 文案 + tooltip + `addActionListener`，无双确认弹窗。项目页走 DSL v2，等价形态为 `row { button(...) }`。
- 双语 bundle 新增两键（按钮文案 + tooltip），命名沿 `settings.project.*` 前缀（注意：T-SettingsTruth 6.5 已把项目页**标签**键收编为全局键，此处的**新**键无全局对应物，保留 `settings.project.` 前缀不违该定案）。

### P3 搜索索引登记项目页字段（一致性）

**前置核实（必须，不得跳）**：`SearchableField` 携带 `configurableClass`，但平台 settings 搜索对 **project-level** configurable 的 `navigateTo` 跳转是否可用，本任务开工前须做对照实验（runIde 沙盒中打开设置搜索、点项目页条目看是否跳转）。

- 若**支持**：登记 10 项（9 个可覆盖项 + 总开关），`pageLabelKey` 用 `configurable.settings.project.displayName`。
- 若**不支持**：**不登记**——登记了点不动等于制造新的"幽灵搜索项"，正是 T-SettingsTruth P3 刚清理过的形态。
- 结论（支持/不支持 + 实验方式）回填本文档 §十。

### P4 导入导出范围明示（一致性）

现状：`SettingsIO` 只处理全局 State（21 项），项目级 9 项不在范围内，而 O-5 计划本体就叫"设置导入导出"且同时引入了项目级设置——用户在项目页做完配置后导出，会以为配置已带走。

二选一：

- **(a) 推荐先行**：UI 明示"导入/导出仅作用于全局设置"（全局页导入导出按钮的 tooltip 或结果弹框文案补一句），零风险、文案级。
- **(b) 立候选**：扩展 `SettingsIO` 覆盖项目级。暂不做——导入目标语义未定（多项目场景导入到哪个项目？影响所有项目？），且无用户反馈驱动。

### P5 守卫用例（防回潮）

`ProjectPluginSettingsConfigurableTest` 扩四条：

1. 总开关关闭 → 9 个控件显示值 == 全局值（构造 `state` 带残值 + 全局设不同值，断言控件显示全局值）；
2. 总开关关闭 → `apply()` 后 state 的 9 项**不变**（钉住 P1 第 3 条的数据丢失风险）；
3. 总开关关闭 → `isModified()` 恒 false（钉住 P1 第 4 条的误报形态）；
4. 切换总开关 → 9 项 enabled 与显示值同步联动。

可选源码级守卫（并入 `SettingsRealityGuardTest`）：断言 9 个控件的灌值表达式均含 `useProjectSettings` 判据——防"只修一处、其余回潮"。

### 关联项（不在本任务范围，勿重复登记）

多项目同开且项目级表配置不同时，`EntityResolver.effectiveTableSetting`（`EntityResolver.kt:554-563`）兜底路径取 `openProjects.firstOrNull()` 的取值歧义——**已登记**于 T-DevMergeFix 验收文档遗留项 11（P3，11 处无 PSI 兜底调用方补 project，其中 `WrapperLocator.kt:729`、`EntityClassFinder.kt:108` 手头有 project 优先）。本任务不重复登记、不顺手改。

## 四、改动落点

- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/ProjectPluginSettingsConfigurable.kt`：P1 四处联动（灌值/可用性/apply/isModified）+ P2 重置按钮（主战场）。
- `src/main/resources/messages/MpSqlVisualizerBundle.properties` + `_zh.properties`：P2 按钮文案 + tooltip；P1 状态行文案（若做）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsFolderConfigurable.kt`：P3 登记（**视前置核实结论**）。
- P4 二选一落点：选 (a) 改 `PluginSettingsPanel`/`SettingsIO` 的结果文案；选 (b) 改 `SettingsIO.kt`。
- `src/test/kotlin/cn/song/mybatisplus/visualizer/config/ProjectPluginSettingsConfigurableTest.kt`：P5 四条用例。
- `src/test/kotlin/cn/song/mybatisplus/visualizer/config/SettingsRealityGuardTest.kt`：P5 可选源码级断言。
- 交付时文档同步：`CHANGELOG.md` [Unreleased]（P1 是用户可见行为变更）+ `docs/变更记录.md`。

## 五、测试计划

- **P1**：P5 四条用例先红后绿（对修复前代码回放验证能抓到——用 stash 或注释式验证，防守卫假绿）。
- **P2**：点重置 → 9 项归零/null、`useProjectSettings` 保持、`isModified` 复位；apply 后持久化生效；再次打开设置页值仍为空（`reset` 回灌全局值）。
- **P3**：核实结论落地后——登记项逐个命中且跳转正确（`navigateTo` 路由断言）；`BundleParityGuardTest` 绿。
- **P4**：(a) 文案断言；(b) `SettingsIOTest` 扩项目级往返用例。
- **回归门禁**：`testFast` 先行（守卫与配置页用例在此桶），后全量 `test`；触及 config 层，`PluginSettingsPersistenceTest`/`PluginSettingsPanelBindingTest`/`PluginSettingsPanelUiTest`/`SettingsIOTest` 必跑。
- **环境提醒**：本机 `core.autocrlf=true`，源码扫描类断言须行尾归一（T-SettingsTruth 6.4 教训）；State 签名变化后首跑若成片 `NoSuchMethodError`，先清 `build/classes` + `build/kotlin` 再判（陈旧产物劫持，非真回归）。

## 六、验收标准（判定卡）

1. 总开关关闭时，9 个控件置灰且显示值等于全局生效值——页面所见即运行时所用；
2. 关闭态点"应用"后再开启开关，原项目自定义值仍在（未被全局值覆盖）；
3. "重置为跟随全局"可清除残值，`.idea/mp-sql-visualizer-project.xml` 中对应字段归空；
4. 关闭态打开设置页不显示"已修改"；
5. 搜索索引结论落地（支持则 10 项可跳转，不支持则不登记且有实验记录）；
6. 导入导出范围在 UI 上无歧义；
7. 全量 `test` 绿；真机走查清单（§七）逐项勾选。

## 七、真机走查清单（留人工）

1. 打开设置 → Tools → `SQL Visualizer (Project)`：默认（未启用）状态下 9 行置灰、显示值与全局页同项逐字一致；
2. 勾选总开关 → 9 行可编辑；把表前缀设为 `p_` → 应用 → 预览该项目 Wrapper，表名带 `p_`；
3. 取消总开关 → 9 行置灰且显示全局值 → 应用 → 预览表名回全局前缀；再次勾选总开关 → 表前缀仍为 `p_`（值未丢）；
4. 点"重置为跟随全局" → 应用 → 重新勾选总开关，9 项均为跟随全局态；
5. 关闭态反复打开设置页，不出现"已修改"；
6. 设置搜索输入"表前缀"，看是否命中项目页条目并能跳转（P3 结论复核）；
7. 打开设置页时与全局页并排对比，两页同项文案逐字一致（防 T-SettingsTruth 6.5 措辞漂移回潮）。

## 八、风险与取舍

- **与在途分支冲突（最高优先级风险）**：`feature/dialect-auto-infer`（tip `a481a4f`，2026-09-18 10:23）**不含** P4 提交 `6fa282f`（09:09，未推远端），其 `ProjectPluginSettingsConfigurable.kt` 仍是 2 控件旧形态 + "智能推断"哨兵项（`dialectAuto`），并已改 `ProjectPluginSettings.kt`（+`dialectAuto: Boolean?`）。两分支同改本文件，合流必冲突。**本任务应在该分支合流后再开工**，或明确以本任务为准、合流时按 10 控件形态吸收 auto 哨兵（`dialectAuto` 的 null 语义与"空=跟随全局"同构，可与 P1 的分态呈现一并处理）。开工前先确认该分支状态。
- **"空 = 跟随全局"的固有歧义不因本任务消失**：开关开启时，留空字段显示的是全局值，用户可能误以为"已覆盖为这个值"。P2 的重置按钮 + tooltip 缓解；彻底消除需逐项显式三态控件（B 方案），本次不做。
- **开关语义的用户面 ≈ 0**：项目页 2026-08-26 至 2026-09-18 未注册（死类），残值只能靠手工编辑 `.idea/mp-sql-visualizer-project.xml` 产生——故 P1 无升级迁移负担，但也意味着**本任务的实际价值要等真机走查确认该页真有人用**；若走查后判定使用频率极低，"删 UI 保后端"仍是干净退路（历史先例：2026-08-26 曾因"套娃"困惑移除注册）。
- **P1 属用户可见行为变更**，须进 CHANGELOG [Unreleased]；不涉及发行元数据与八处同步全流程（用户侧文档 grep 实测未单独描述该页）。
- **P3 的前置核实不得跳过**：平台能力未验证就登记，产出的是幽灵项而非功能（本项目定例：能力验证先做对照实验，环境不具备时结论只能写"环境受限未验证"）。

## 九、实施顺序

1. **前置确认**：`feature/dialect-auto-infer` 状态（已合流 / 待合流 / 放弃）——决定本任务是等待还是并做 auto 哨兵；
2. **D1 决策**：总开关去留拍板（推荐 A）；
3. **P3 前置核实**：设置搜索对 project-level configurable 的跳转能力（对照实验）；
4. **P1 + P5**：缺陷修复与守卫用例同批（先红后绿）；
5. **P2**：重置按钮 + 双语文案；
6. **P4**：(a) 文案明示（推荐）或 (b) 扩展导入导出；
7. **P3 落地**：按第 3 步结论登记或记录不登记理由；
8. **门禁与交付**：`testFast` → 全量 `test` → CHANGELOG/变更记录同步 → 真机走查（§七）→ 验收文档。

## 十、实施记录（实施后回填）

待实施。回填要求：

- 任务 → 提交映射表（含立项提交 SHA）；
- 与计划的偏离（含 D1 实际决策、P3 对照实验结论与方式、P4 实际选择）；
- 门禁暴露的问题（若 P1 触及 isModified 语义，参照 T-SettingsTruth 6.3 的"误报"形态记录）；
- 合流与冲突（若与 `feature/dialect-auto-infer` 相撞，记录解法）；
- 验证结果（全量 `test` 计数，按 mtime 过滤统计）+ 独立复核结论。
