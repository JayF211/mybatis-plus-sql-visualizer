# 设置页 AboutConfigurable 缺少 displayName 报错修复

> **创建时间**：2026-09-02
> **优先级**：P0（阻塞设置页打开）
> **影响范围**：plugin.xml 中所有子页面 `<applicationConfigurable>` 注册项
> **预计改动**：1 文件 1 处（实际 5 行属性添加）

---

## 一、问题描述

点击插件设置按钮（`SqlLogPanel.SettingsAction`）打开 Settings 对话框时，IDE 抛出 `PluginException`：

```
com.intellij.diagnostic.PluginException: No display name specified in plugin descriptor XML file
for configurable cn.song.mybatisplus.visualizer.config.AboutConfigurable;
specify it using 'displayName' or 'key' attribute to avoid necessity to load the configurable
class when Settings dialog is opened
```

堆栈入口：`ConfigurableWrapper.getDisplayName(ConfigurableWrapper.java:158)` → `SettingsTreeView$MyNode.<init>`

---

## 二、根因分析

### 2.1 IntelliJ 平台机制

`SettingsTreeView` 在构建设置树时，需要为每个 `<applicationConfigurable>` 节点获取展示名称。平台提供两种**免加载类**的解析方式：

| 属性组合 | 解析来源 | 是否触发类加载 |
|----------|----------|----------------|
| `displayName` | XML 静态值 | 否 |
| `bundle` + `key` | Bundle 属性文件 | 否 |
| 两者皆无 | 调用 `Configurable.getDisplayName()` | **是**（触发 `PluginException`） |

从 2021.2 起，平台在 `ConfigurableWrapper.getDisplayName()` 中强制校验：若两者皆无，直接抛 `PluginException`，阻止加载类来获取名称。

### 2.2 当前注册状态

`src/main/resources/META-INF/plugin.xml` 中 6 个 `<applicationConfigurable>` 注册项：

| 行号 | ID | instance | bundle + key | 状态 |
|------|----|----------|--------------|------|
| 85-89 | `cn.song.mybatisplus.visualizer.settings` | `PluginSettingsFolderConfigurable` | ✅ 有 | 正常 |
| 92-94 | `.settings.ui` | `UiActionsConfigurable` | ❌ 无 | **隐患** |
| 97-99 | `.settings.preview` | `SqlPreviewConfigurable` | ❌ 无 | **隐患** |
| 102-104 | `.settings.tableMapping` | `TableMappingConfigurable` | ❌ 无 | **隐患** |
| 107-109 | `.settings.sqlLog` | `SqlLogConfigurable` | ❌ 无 | **隐患** |
| 112-114 | `.settings.about` | `AboutConfigurable` | ❌ 无 | **报错** |

### 2.3 为什么只报 AboutConfigurable

设置树按 ID **字母序**构建子节点，顺序为：`about` → `preview` → `sqlLog` → `tableMapping` → `ui`。

`about` 排在第一位，第一个触发异常即中断 rebuild，所以堆栈只显示 `AboutConfigurable`。**其余 4 个子页面同样缺陷**，只是在 `about` 修复后才会轮到它们报错。

### 2.4 为什么之前没报错

此前设置页使用 `Settings.KEY.select()` 同对话框导航，且 `PluginSettingsFolderConfigurable` 有 `bundle`+`key` 能正常展示根节点。但当子节点需要渲染时（搜索过滤、树展开），仍会触发子节点 `getDisplayName()` 校验。具体触发条件与 IDE 版本对校验的严格程度有关——当前版本（2026.2.1）严格执行。

---

## 三、修复方案

### 3.1 改动文件

仅 `src/main/resources/META-INF/plugin.xml`，为 5 个子页面注册项补加 `bundle` + `key` 属性。

### 3.2 Key 复用策略

Bundle 中已有 `settings.section.*` key（中英文齐全），直接复用：

| 子页面 | key | 英文值 | 中文值 |
|--------|-----|--------|--------|
| UiActions | `settings.section.ui` | UI & Actions | 界面与操作 |
| SqlPreview | `settings.section.preview` | SQL Preview | SQL 预览 |
| TableMapping | `settings.section.tableMapping` | Table Name Mapping | 表名映射 |
| SqlLog | `settings.section.sqlLog` | SQL Log | SQL 日志 |
| About | `settings.section.about` | About | 关于 |

**无需新增 bundle key**，中英文属性文件均已存在。

### 3.3 具体改动

```xml
<!-- 修改前（以 About 为例） -->
<applicationConfigurable parentId="cn.song.mybatisplus.visualizer.settings"
                         id="cn.song.mybatisplus.visualizer.settings.about"
                         instance="cn.song.mybatisplus.visualizer.config.AboutConfigurable"/>

<!-- 修改后 -->
<applicationConfigurable parentId="cn.song.mybatisplus.visualizer.settings"
                         id="cn.song.mybatisplus.visualizer.settings.about"
                         bundle="messages.MpSqlVisualizerBundle"
                         key="settings.section.about"
                         instance="cn.song.mybatisplus.visualizer.config.AboutConfigurable"/>
```

其余 4 项同理，分别使用对应 key。

---

## 四、改动清单

| # | 文件 | 行号 | 操作 |
|---|------|------|------|
| 1 | `src/main/resources/META-INF/plugin.xml` | 92-94 | 加 `bundle`+`key="settings.section.ui"` |
| 2 | `src/main/resources/META-INF/plugin.xml` | 97-99 | 加 `bundle`+`key="settings.section.preview"` |
| 3 | `src/main/resources/META-INF/plugin.xml` | 102-104 | 加 `bundle`+`key="settings.section.tableMapping"` |
| 4 | `src/main/resources/META-INF/plugin.xml` | 107-109 | 加 `bundle`+`key="settings.section.sqlLog"` |
| 5 | `src/main/resources/META-INF/plugin.xml` | 112-114 | 加 `bundle`+`key="settings.section.about"` |

---

## 五、验证方案

1. **编译验证**：`./gradlew compileKotlin` 通过
2. **runIde 验证**：启动沙盒 IDE，点击 SQL 日志面板设置按钮，确认 Settings 对话框正常打开
3. **子页面全覆盖**：依次点击左侧树 5 个子节点，确认均不报错
4. **搜索过滤**：在 Settings 搜索框输入 "SQL"，确认过滤后树节点正常渲染
5. **中英文切换**：IDE 切换中英文，确认 5 个子页面名称正确本地化

---

## 六、风险评估

- **风险等级**：极低
- **影响面**：仅 plugin.xml 属性添加，不涉及 Kotlin 代码逻辑
- **回滚难度**：git revert 单行提交即可
- **兼容性**：`bundle`+`key` 是 IntelliJ 平台标准属性，所有版本支持
