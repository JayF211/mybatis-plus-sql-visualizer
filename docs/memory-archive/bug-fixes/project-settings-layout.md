# 设置页布局优化

## 元数据

- **类型**：project
- **日期**：2026-08-26
- **涉及文件**：`PluginSettingsPanel.kt`、`PluginSettings.kt`、`plugin.xml`、`MpSqlVisualizerBundle.properties`、`MpSqlVisualizerBundle_zh.properties`、`SectionConfigurable.kt`、`PluginSettingsFolderConfigurable.kt`

## 背景

原设置页 6 组 TitledSeparator + ~44 行平铺，信息密度过高。经多轮迭代，最终改为左侧树导航 + 主页面 IDEA 风格搜索/子页面列表 + 无边框无折叠的分隔线布局。

## 最终架构

### 主页面（文件夹节点）
- `PluginSettingsFolderConfigurable`：搜索栏 + 子页面列表（JBList），点击跳转
- **IDEA 原生风格 UI**：图标 + 名称（粗体）+ 描述（灰色小字），固定行高 56px
- **双层搜索**：先匹配子页面名称/描述，再匹配所有子页面字段 label/tooltip
- 搜索结果：📁 页面项 + 🔍 字段项（含所属页面路径）
- 子页面名称 + 描述走 `settings.section.*` / `settings.section.*.desc` i18n key（双语自动切换）

### 子页面（5 个叶节点）
- `SectionConfigurable` 基类 + 5 个子类
- 每个子页面创建 `PluginSettingsPanel(settings, sections)` 只含对应分区
- 分区内用 `sectionGroup()` 函数：仅 `TitledSeparator` 横线 + 内容面板（**无边框、无折叠**）

### PluginSettingsPanel 分区模式
- 新增 `sections: Set<String>` 构造参数（默认 `SECTION_ALL`）
- 分区 ID 常量：`SECTION_PREVIEW` / `SECTION_TABLE_MAPPING` / `SECTION_CODE_GEN` / `SECTION_SQL_LOG` / `SECTION_UI` / `SECTION_UPDATE_DIAGNOSTICS`
- `buildSections()` 按 `sections` 参数只构建启用的分区
- `isModified()` / `apply()` / `reset()` 按分区过滤字段
- 搜索框已移至主页面，子页面不再包含搜索框

### 输入框宽度
- `addRow` 函数 `fill` 从 `HORIZONTAL` 改为 `NONE`，组件保持自然宽度左对齐

### 边框/折叠迭代历史（7 次提交）
1. `customLine` 四周边框 → 用户反馈"白色边框割裂感强"
2. 去掉边框 → 用户要求"用 TitledSeparator 做边框"
3. `customLine` + `TitledSeparator` → 用户要求"分割线样式四周边框，不是白色边框"
4. `TitledBorder`（标题嵌入上边框）→ 用户反馈"白底凹陷 + toggle 错位"（[[feedback-settings-titledborder-pitfall]]）
5. 回退到 `customLine` + `TitledSeparator` → 用户要求"只保留分隔线，不要四周边框，不要折叠"
6. **最终**：去掉边框和折叠，仅保留 `TitledSeparator` 横线分隔
7. **IDEA 风格 UI**：主页面子页面列表改为图标+名称+描述+固定行高渲染器

## i18n 新增 key

- `settings.section.about` / `settings.search.placeholder` / `settings.log.tab.mybatis` / `settings.log.tab.orm` / `settings.log.tab.autoSave`
- `settings.section.preview.desc` / `settings.section.tableMapping.desc` / `settings.section.sqlLog.desc` / `settings.section.ui.desc` / `settings.section.about.desc`（子页面描述，双语）

## 提交链

- b90a059：输入框宽度修复 + 移除套娃 projectConfigurable
- 92b116d：设置页布局优化三阶段（折叠面板 + Tab + 搜索）
- a159ac4/56be579/4d74fcd/0741578/43487d6：边框样式迭代（6 次）
- 6adfae4/0f373d0：左侧树导航实施
- 0c7379c：去掉边框和折叠，主页面新增搜索栏+子页面列表
- f962769：导航优化（DataContext 获取 project 避免新开窗口）+ 搜索子页面内容+双语 tab 名
- **82a24f0**：主设置页面 UI 参考 IDEA 原生风格优化（图标+名称+描述+固定行高+自定义渲染器）
- **2bade45**：导航用 Settings.KEY.select() 同一对话框内切换 + 目标控件聚焦闪烁高亮（showSettingsDialog 会新开窗口）

## 相关记忆

- [[project-settings-tree-nav]] — 左侧树导航实施细节
- [[feedback-settings-titledborder-pitfall]] — TitledBorder 已知问题
- [[feedback-settings-navigation-new-window]] — showSettingsDialog 新开窗口修复