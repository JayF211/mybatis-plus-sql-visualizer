# T-DisplayModeToSettings：SQL 显示模式迁入插件设置页开发计划

- 立项日期：2026-09-14
- 任务性质：UI 配置项迁移（工具栏按钮 → 设置页单配置项）
- 用户需求原话：将 SQL 日志和 SQL 历史页面的【SQL 显示模式】改到插件设置中，**只保留一个设置，两处生效**

## 一、需求分析

**现状**（T-ListMode 交付形态 + T-MergeFollowup-04 复查后形态）：

| 位置 | 形态 |
|------|------|
| SQL 日志页工具栏 | 「显示模式 [关键字换行 ⌄]」按钮（`SqlListDisplayModeButton` + JLabel 单元） |
| SQL 历史页工具栏 | 同款按钮单元 |
| 插件设置页 | **无**显示模式配置项 |
| 设置存储 | `PluginSettings.State.logListSqlDisplayMode`（PluginSettings.kt:288，默认 `DEFAULT_LOG_LIST_DISPLAY_MODE`=关键字换行）——两页面共享同一键，经 `SETTINGS_CHANGE_TOPIC` 双向同步 |

**目标形态**：

| 位置 | 形态 |
|------|------|
| 两页面工具栏 | 移除显示模式按钮（工具栏更清爽，窄侧栏折行压力同步缓解） |
| 插件设置页 → SQL Visualizer → SQL 日志 | 新增「列表显示」子分组，含「SQL 显示模式」下拉（单行/关键字换行/格式化，默认关键字换行）——**唯一配置入口** |
| 生效范围 | 一处配置，日志页与历史页同时生效（既有广播链原样复用） |

**需求合理性**：显示模式是低频调整项（用户定下偏好后很少切换），工具栏常驻按钮性价比低；迁入设置后与 T-CrudColor 颜色标记等列表类设置归拢同区，符合 T-M31.8「高频上/低频下」的分组原则。

## 二、现状取证（2026-09-14 git 实证）

1. **设置字段与读取器已就绪**：`logListSqlDisplayMode`（PluginSettings.kt:288）+ `sqlListDisplayMode()` 容错读取器（PluginSettings.kt:392）——**无需新增设置字段**。
2. **bundle 键齐全**：`log.list.displayMode`（「SQL 显示模式：」双语）+ `.single/.keywordWrap/.formatted` 三项——设置页行标签与下拉项文案**零新键**（子分组标题键除外，见 §三）。
3. **设置页接入先例**：组 4「SQL 日志」下 5 个子分组（mybatis/crudColor/orm/autoSave/sourceCapture）；T-CrudColor 样式下拉是完整先例——`JComboBox` + `DefaultComboBoxModel(options())` + `addRow(标签, combo)` + apply/isModified/reset/load 四处接线 + `com.intellij.openapi.ui.ComboBox`（平台原生下拉）。
4. **面板侧响应链已就位**（迁移后原样复用、零改动）：
   - 日志页 `settingsListener`（SqlLogPanel.kt:443~）：syncDisplayModeCombo（改删）→ `applyListCellHeight()` + `list.updateUI()` + `refreshList()`
   - 历史页 `onDisplayModeChanged()`（HistorySqlLogPanel.kt）：sync → `applySqlListCellHeight()` + `sqlList.updateUI()` + `applySqlSearch()` 全量重建
   - 设置页 apply 后广播 `SETTINGS_CHANGE_TOPIC`（T-CrudColor 颜色标记同链实证），改模式即两列表刷新
5. **SettingsIO 导入导出不含该键**：现状即界面偏好不随配置导入导出——保持现状（决策点见 §四-3）。
6. **受影响测试面**：
   - `HistorySqlLogPanelNavigationTest`：`displayModeSelectedLabelForTest`（按钮文本断言）——按钮删除，断言改删；`onDisplayModeChanged` 等价路径用例与「多次切换行高缓存失效」防回归用例**保留**（回归锁不随 UI 迁移退役）
   - `HistoryCellRendererTest`/`SqlLogPanelRendererUiTest`/`SqlListFragmentRendererTest`：仅用 SINGLE_LINE setUp/渲染断言，与迁移无关
   - `PluginSettingsPanelBindingTest`：增补显示模式 combo 绑定用例（§三-4）
   - `SqlLogPanelTypePopupToggleTest`：无关

## 三、实施方案

### 3.1 两页面工具栏移除（删代码）

- 删除 `SqlListDisplayModeButton.kt`（T-MergeFollowup-04 引入）。
- `SqlLogPanel`：删 `displayModeButton` 字段、`syncDisplayModeCombo()`、buildToolbar 的 `displayModeUnit` 块；settingsListener 内 `syncDisplayModeCombo()` 调用行删除（其余 applyListCellHeight/updateUI/refreshList 保留）。
- `HistorySqlLogPanel`：删 `displayModeButton` 字段、`displayModeSelectedLabelForTest` 访问器、buildToolbar 的 `displayModeUnit` 块；`onDisplayModeChanged()` 内 `displayModeButton.syncFromSettings()` 行删除。
- `JLabel` import 若仅图例使用则同步清理。

### 3.2 设置页新增「列表显示」子分组

- 组 4（SQL 日志）新增 `sectionGroup("settings.log.tab.list")`（标题「列表显示」，新 bundle 键 1 个），置于 crudColor 子分组之前（列表基础设置靠前）。
- 新字段 `logDisplayModeCombo`：`JComboBox<DisplayModeOption>`（data class 选项，value=`SqlListDisplayMode.name`，label=复用 `log.list.displayMode.*` 三键），控件用 `com.intellij.openapi.ui.ComboBox`（与 crudColorStyleCombo 同款原生下拉）。
- `addRow(p, g, MpSqlVisualizerBundle.message("log.list.displayMode"), logDisplayModeCombo)`——行标签零新键。
- 四处接线（照抄 crudColorStyleCombo 范式）：
  - **load/reset**：`logDisplayModeCombo.selectedItem = options().firstOrNull { it.value == s.logListSqlDisplayMode } ?: options().first()`（数据类默认值兜旧 xml 无字段升级，PluginSettings 默认值已兜底）
  - **apply**：`s.logListSqlDisplayMode = (logDisplayModeCombo.selectedItem as DisplayModeOption).value`
  - **isModifiedSqlLog**：追加 `|| optionValue(logDisplayModeCombo) != s.logListSqlDisplayMode`
- 即时生效链：apply 保存 → `SETTINGS_CHANGE_TOPIC` 广播 → 两面板既有监听刷新（applyListCellHeight/updateUI/refreshList 与 applySqlListCellHeight/updateUI/applySqlSearch）——**T-MergeFollowup-03 的 updateUI 失效修复继续兜底多次切换**。

### 3.3 文档与设置台账

- 新 bundle 键：`settings.log.tab.list`（双语 1 键）；如需 tooltip 再 1 键（倾向复用 `log.list.displayMode` 既有文案作 tip，零新键）。
- **不新增复选框**（复选框总数台账 25 不变，对齐 T-CrudColor 定案）。
- 验收文档 §3.3（SQL 列表三种显示模式）入口描述更新为设置页路径；§六修复轮增量表补一行。
- 变更记录总册 + docs/MEMORY 台账 + 本计划 §六。

## 四、决策点（倾向已给，实施遇阻再议）

1. **子分组归属**：新开「列表显示」子分组（倾向）vs 并入 mybatis 子分组末尾——倾向前者：显示模式是日志+历史两列表共享的界面偏好，与「SQL类型」等 mybatis 采集类设置性质不同；未来列表类偏好（行号开关等）可归拢。
2. **设置页控件**：平台 `ComboBox`（倾向，与 crudColorStyleCombo 一致）vs JBRadioGroup——三选项纵向占空间大，下拉紧凑。
3. **SettingsIO 导出**：保持不含（界面偏好不随配置迁移）；如未来需要随配置分享，另立项。
4. **工具栏残留空间**：移除后日志页左侧剩 动作图标 + SQL类型 + 耗时排序 + 慢计数，右侧去重勾选——不再折行（顺带收益，验收时确认）。

## 五、测试与验收

### 测试计划

- **增补** `PluginSettingsPanelBindingTest`：显示模式 combo 绑定四断言（load 后选中态=state 值 / apply 写回 state / 修改后 isModified=true / 恢复默认回落 keywordWrap 默认）。
- **修改** `HistorySqlLogPanelNavigationTest`：删按钮文本断言（`displayModeSelectedLabelForTest`），保留 `onDisplayModeChanged` 等价路径、行高重设、全量重建、「多次切换行高布局缓存失效」防回归用例（这些锁的是**响应链**，不随入口迁移退役）。
- 定向：`PluginSettingsPanelBindingTest` + `HistorySqlLogPanelNavigationTest` + `SqlLogPanelRendererUiTest`；全量 test 绿（构建按用户口径，非发版不跑 verifyFull/buildPlugin）。

### 验收清单

- [ ] 两页面工具栏无「显示模式」按钮；日志页窄侧栏单行容纳全部工具栏元素
- [ ] 设置页 SQL 日志组新增「列表显示」子分组，含「SQL 显示模式」下拉，默认「关键字换行」
- [ ] 设置页改模式 → 应用后日志页与历史页同时切换，三模式循环多次无渲染错乱（updateUI 链）
- [ ] 恢复默认回落关键字换行；isModified 语义正确；SettingsIO 导入导出不含该键（现状保持）
- [ ] 全量测试 0 失败

## 六、实施记录（2026-09-14 回填）

### 交付内容

- **工具栏移除**：`SqlListDisplayModeButton.kt` 删除；SqlLogPanel（displayModeButton 字段/syncDisplayModeCombo/displayModeUnit/settingsListener 的 sync 调用）与 HistorySqlLogPanel（displayModeButton/displayModeSelectedLabelForTest/displayModeUnit/onDisplayModeChanged 的 sync 行/JLabel import）全部清理——两页面工具栏不再有显示模式入口。
- **设置页新增**：组 4「SQL 日志」新开「列表显示」子分组（`settings.log.tab.list`，crudColor 组之前）；`logDisplayModeCombo`（`ComboBox<ListDisplayModeOption>`，选项 label 复用 `log.list.displayMode.*` 三键零新文案）+ 行标签复用 `log.list.displayMode`；tooltip 新键 `log.list.displayMode.tip`（双语）；四处接线照抄 crudColorStyleCombo 范式（load 回填/apply 写回 `selectedListDisplayMode()`/isModifiedSqlLog 追加比对/reset 经共享回填段覆盖）；apply 后经既有 `SETTINGS_CHANGE_TOPIC` 广播驱动两面板刷新（updateUI 失效链延续兜底多次切换）。
- **测试**：`PluginSettingsPanelBindingTest` 新增 `testListDisplayModeChangeEnablesApplyAndPersists`（初始一致/仅改显示模式 isModified=true/apply 写回 state/reset 回填后一致）；`HistorySqlLogPanelNavigationTest` 删按钮文本断言（响应链与「多次切换行高布局缓存失效」防回归用例保留）。
- **守卫登记**（T-CrudColor 同款欠账模式）：`PluginSettingsPanelBuildInfoTest` 分组标题清单插 `settings.log.tab.list`（10→11，SQL 日志子分组 5→6）；`PluginSettingsPanelUiTest` SQL 日志段下拉计数 1→2（标记样式 + 显示模式）。

### 门禁

- 定向：PluginSettingsPanelBindingTest + HistorySqlLogPanelNavigationTest + 两个守卫类绿。
- 全量：见下方回填计数（首跑 2 失败即上述守卫欠登记，修正后绿；另有一次并行任务 t-intrup 活跃期间的 build 级失败为互踩假失败，环境空后重跑定位）。

### 验收清单勾销

- [x] 两页面工具栏无显示模式按钮
- [x] 设置页 SQL 日志组「列表显示」子分组含「SQL 显示模式」下拉，默认关键字换行
- [x] apply 后两列表同刷（响应链既有测试 + updateUI 失效兜底）
- [x] isModified/reset 语义正确（绑定用例锁定）
- [x] SettingsIO 不含该键（现状保持，界面偏好不随配置导出）
- [x] 全量测试绿（计数见下）
- [ ] 真机走查：设置页改模式 → 两列表即时生效观感 + 多次切换稳定（留人工）
