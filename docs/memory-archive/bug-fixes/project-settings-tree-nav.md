# 设置页左侧树导航

## 元数据

- **类型**：project
- **日期**：2026-08-27
- **状态**：已完成
- **涉及文件**：`PluginSettingsPanel.kt`、`SectionConfigurable.kt`、`PluginSettingsFolderConfigurable.kt`、`plugin.xml`

## 背景

设置页从单页滚动 + 折叠面板改为 IDEA 原生风格的左侧树导航 + 右侧分页内容（见 [[project-settings-layout]]）。

## 树结构（当前顺序）

```
Settings → Tools → MyBatis-Plus SQL Visualizer（文件夹节点）
├── 界面与操作      → UiActionsConfigurable（gutter图标/语言/快捷键）  ← 排最前
├── SQL 预览        → SqlPreviewConfigurable（含代码生成）
├── 表名映射        → TableMappingConfigurable
├── SQL 日志        → SqlLogConfigurable（3 个分割线子分组）
└── 关于            → AboutConfigurable
```

## 技术实现

### 主页面（文件夹节点）
- `PluginSettingsFolderConfigurable`：搜索栏（`SearchTextField`）+ 子页面列表（`JBList`）
- **IDEA 原生风格渲染器**：图标 + 名称（粗体）+ 描述（灰色小字），固定行高 56px，`GridBagLayout` 自定义渲染
- **双层搜索**：先匹配子页面名称/描述（📁 前缀），再匹配所有子页面字段 label/tooltip（🔍 字段名 → 页面名）
- 搜索数据：`SearchItem.PageItem`（子页面）+ `SearchItem.FieldItem`（字段→页面映射）
- 可搜索字段列表：`ALL_SEARCHable_FIELDS`（每个字段含 searchLabel + searchTooltip + pageLabelKey + configurableClass）
- 空搜索时只显示 5 个子页面列表

### 导航不新开窗口 + 定位（最终方案）
- 取 Settings 用 `currentSettings()` 双保险：主方案 `Settings.KEY.getData(DataManager.getInstance().getDataContext(searchField))`（沿组件树收集 `SettingsEditor` 的 `UiDataProvider` 数据）；兜底反射读 `SettingsEditor.settings` 字段
- `settings.select(settings.find(configurableClass))` 同对话框切换
- `callback.doWhenDone { focusTarget(...) }` 切换完成后聚焦
- `SectionConfigurable.createComponent()` 已缓存面板（`if (!::panel.isInitialized)`），确保 `focusTarget` 拿到显示面板
- 聚焦：`scrollRectToVisible` 滚动到可见区域 + 柔和琥珀边框（`JBColor(0xE6C068, 0x6B5B2E)` 2px）+ 极浅米黄底（`JBColor(0xFFFAE6, 0x3A3320)`）闪烁，Timer 250ms ×6 次 ≈ 1.5 秒（使用 JBColor 适配亮/暗主题）
- 详见 [[feedback-settings-navigation-new-window]]

### 子页面（叶节点）
- `SectionConfigurable` 基类：委托 `isModified/apply/reset` 给 `PluginSettingsPanel`，`getDisplayName()` 走 `MpSqlVisualizerBundle.message(displayNameKey)`
- 5 个子类各创建只含对应分区的 panel（`setOf(SECTION_*)`）
- `SqlPreviewConfigurable` 包含 `SECTION_PREVIEW` + `SECTION_CODE_GEN`（代码生成合并到预览）
- **子页面标题中文**：`plugin.xml` 去掉 `key` + `bundle`，直接由 `getDisplayName()` 走插件语言设置（默认中文）；`key` + `bundle` 走 IDEA `DynamicBundle`（IDE locale），与插件语言设置冲突会导致英文

### PluginSettingsPanel 分区模式
- `sections: Set<String>` 构造参数 + 6 个分区 ID 常量 + `SECTION_ALL`
- `buildSections()` / `isModified()` / `apply()` / `reset()` 均按分区过滤
- `sectionGroup()` 函数：仅 `TitledSeparator` 横线 + 内容面板（无边框、无折叠）
- 已移除 `collapsiblePanel`/`CollapsiblePanelRef`/`RowRef`/`filterRows` 等折叠相关代码

### SQL 日志子分组（分割线形式）
- 原 `JTabédiePane`（3 个 Tab）→ 3 个独立 `sectionGroup`（分割线）
- 复用已有标题 key：`settings.log.tab.mybatis`（MyBatis 核心）/ `settings.log.tab.orm`（ORM 框架）/ `settings.log.tab.autoSave`（自动保存）
- 已移除 `JTabbedPane` import

### plugin.xml
- 父节点注册 `PluginSettingsFolderConfigurable`（保留 `key` + `bundle`）
- 5 个子节点 `parentId="cn.song.mybatisplus.visualizer.settings"`，**去掉 `key` + `bundle`**（由 `getDisplayName()` 走插件语言设置）
- 顺序：ui → preview → tableMapping → sqlLog → about

### SqlLogPanel 设置按钮
- `PluginSettingsConfigurable::class.java` → `SqlLogConfigurable::class.java`（直接打开 SQL 日志页）

### 子页面描述 i18n key
- `settings.section.preview.desc` / `settings.section.tableMapping.desc` / `settings.section.sqlLog.desc` / `settings.section.ui.desc` / `settings.section.about.desc`
- 中英文双语，走插件语言设置（默认中文）

## 测试
- 全量测试通过（62 个设置测试 + 全量测试）

### SettingsEditor(@ApiStatus.Internal) 依赖消除
- 取当前 Settings 的兜底逻辑原直接 `import com.intellij.openapi.options.newEditor.SettingsEditor` + `comp is SettingsEditor` + 反射读 `settings` 字段
- SettingsEditor 被 `@ApiStatus.Internal` 标记 → 改为 `Class.forName("com.intellij.openapi.options.newEditor.SettingsEditor")` 反射加载 + `editorClass.isInstance(comp)` + `getDeclaredField("settings")` 反射读
- 彻底消除对 internal API 的编译期依赖（见 [[feedback-settings-editor-internal-dep]]）

## 提交链
- 9d2e1e3：设置页改为左侧树导航 + 5 个独立子页面
- 0cda75e：补充子 Configurable 类 + 开发计划文档
- c26802b：去掉边框和折叠，主页面新增搜索栏+子页面列表
- e885e7d：导航优化（DataContext 获取 project 避免新开窗口）+ 搜索子页面内容+双语 tab 名
- **82999c9**：主设置页面 UI 参考 IDEA 原生风格优化
- **f5c8bea**：导航改为 Settings.KEY.select() 同一对话框内切换 + 目标控件聚焦闪烁高亮
- **3a1a569**：消除 SettingsEditor(@ApiStatus.Internal) 编译期依赖（Class.forName 反射）
- **本次修复**：currentSettings() 双保险 + SectionConfigurable 缓存 + scrollRectToVisible + SQL 日志 Tab→分割线 + UI Actions 排前 + 子页面标题中文

## 相关记忆
- [[project-settings-layout]] — 布局优化完整历史
- [[feedback-settings-titledborder-pitfall]] — TitledBorder 已知问题
- [[feedback-settings-navigation-new-window]] — 新开窗口 + 定位修复完整根因
- [[feedback-settings-editor-internal-dep]] — SettingsEditor internal API 依赖消除