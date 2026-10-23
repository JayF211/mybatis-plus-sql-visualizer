# T-SettingsSearchA：让插件设置进入 IDE 自带设置搜索索引

2026-10-23。来源：用户报「在 IDE 的 Settings 搜索框里搜插件设置，什么都搜不到」。本项处理 IDE 自带搜索（Settings 对话框左上角那个），与插件设置页内的自建搜索框是两条链，后者另立 T-SettingsSearchB。

## 一、现象

在 IDE 设置对话框搜索框里输入插件的任意设置名（如「方言」「参数前缀」甚至「SQL Visualizer」），结果为空。插件设置页本身在树下看得见、点得开、改得动，只是搜不到。

## 二、两道门（证据链）

搜不到不是卡在一个开关上，是两道门同时关着，得一起开。

### 门一：插件 jar 里没有搜索索引

IDE 设置搜索读的是插件 jar 内的 `search/searchableOptions.xml`。本仓没有这个文件——全仓 `find . -name searchableOptions.xml` 零命中，主 jar 内也没有；`build/tmp/buildSearchableOptions/` 与 `build/tmp/prepareJarSearchableOptions/` 都是空目录，说明这一步根本没跑。

原因写在 `build.gradle.kts:203-206`：`buildSearchableOptions = false`。上面三行注释记了实证——Windows 中文环境跑 `buildSearchableOptions` 会报 `"Locale must be default"`（IDEA 搜索索引器要求 JVM locale 为默认值），所以这个任务在构建期就被关掉了。该索引由构建期 fork 一个 IDE 跑 `TraverseUIStarter.traverseUI` 生成，不是运行时能补的。

### 门二：即便有了索引也搜不到

平台读索引前后各有一道类型过滤，两道都只认 `SearchableConfigurable`：

- `SearchUtil.processConfigurables`（`SearchUtil.java:52-57`）对 `!(configurable instanceof SearchableConfigurable)` 直接 `continue`；
- `SearchableOptionsRegistrarImpl.filterById` 同样把非 `SearchableConfigurable` 的命中过滤掉。

而本仓所有设置类都是裸 `Configurable`：

- `PluginSettingsFolderConfigurable.kt:48`
- `SectionConfigurable.kt:27`（基类，6 个子页继承：`:124` SqlPreview / `:130` TableMapping / `:136` SqlLog / `:142` UiActions / `:148` About / `:154` Shortcuts）
- `McpConfigurable.kt:38`
- `ProjectPluginSettingsConfigurable.kt:79`

注册点：根页 `plugin.xml:138-142`（applicationConfigurable，id=`cn.song.mybatisplus.visualizer.settings`）、项目页 `:146-150`、7 个子页 `:157-210`。注册用的都是 `instance=`，没有一处声明 `SearchableConfigurable`。

## 三、A1 方案（推荐）：运行时 EP 补索引

不动构建，绕开门一；实现平台的 `SearchableOptionContributor` EP 在运行时喂索引，同时补 `SearchableConfigurable` 开门二。

### 3.1 EP 与 Contributor

平台 EP 点位是 `com.intellij.search.optionContributor`。新增一个实现类，在 `processOptions` 里对每条设置项调：

```
processor.addOptions(text, path, hit, configurableId, configurableDisplayName, false)
```

`plugin.xml` 增一条注册（`<search.optionContributor implementation="..."/>`）。因为不依赖 `buildSearchableOptions`，也就不会碰到中文 locale 那个失败。

### 3.2 Configurable 改造清单

让目标类实现 `SearchableConfigurable`（`getOriginalClass()` 有默认实现，只需补 `getId()`）。不补的话门二照旧把命中滤掉，Contributor 白喂。

四个编辑落点，覆盖 9 个类：

1. `PluginSettingsFolderConfigurable.kt:48`——根页，id 取 `cn.song.mybatisplus.visualizer.settings`。
2. `SectionConfigurable.kt:24-27`——基类改一次覆盖 6 个子页。注意现构造器只有 `sections` 与 `displayNameKey` 两个参数（`:24-27`），没有 id；6 个子类的 EP id 各不相同，所以 `getId()` 不能写死一个常量。要么给基类加个 id 构造参数、6 个子类分别传（`:124-159` 六处构造调用同步），要么靠平台包装层的 id（见第五节待验证前提）。
3. `McpConfigurable.kt:38`——id 取 `cn.song.mybatisplus.visualizer.settings.mcp`。
4. `ProjectPluginSettingsConfigurable.kt:79`——id 取 `cn.song.mybatisplus.visualizer.settings.project`。

这些 id 不用另起一份常量：`PluginSettingsFolderConfigurable.kt:469-509` 已经有全套 `ID_*` 常量（`ID_UI` / `ID_PREVIEW` / `ID_TABLE_MAPPING` / `ID_SQL_LOG` / `ID_SHORTCUTS` / `ID_MCP` / `ID_ABOUT` / `ID_PROJECT`），且已有守卫 `registeredTargetsForTest()` 断言 id 与类指向同一页。Contributor 里的 `configurableId` 直接复用 `SearchableField.ref.id`。

### 3.3 数据源与 B 共享

Contributor 的条目数据源直接复用插件设置页那份清单——`PluginSettingsFolderConfigurable.kt:547` 的 `ALL_SEARCHABLE_FIELDS`（由 `:549` 的 `buildSearchableFields()` 构建）。把它从 private 提为可共享的数据源（同包 internal，或抽成一个中立的索引对象），两套搜索读同一份，天然避免 B 那类「面板加了控件、索引忘了登记」的漂移在 IDE 搜索侧重演。

每条的 `text` 取 `searchLabel`，`hit`/说明取 `searchTooltip`，`configurableId` 取 `ref.id`，`configurableDisplayName` 取对应页的显示名。

## 四、A2 备选（不推荐）

`build.gradle.kts:206` 改回 `true`，让构建期生成索引。

不推荐的理由有两条。一是风险高：中文 locale 下构建直接失败，注释里已有实证（`:203-205`），要开就得先解决 locale 问题，等于把文档任务变成构建改造。二是覆盖面更差：构建期遍历只认 JLabel / JCheckBox / JRadioButton / JButton / TitledBorder / JComboBox 这些静态文本，而本插件落地页是 `JBList` 列表渲染（`PluginSettingsFolderConfigurable.kt:52`），列表内容它索引不到。同样还得补 `SearchableConfigurable`，A1 要做的它一样不少，却多了构建期的坑。

保留记录，不为本项采用。

## 五、待验证前提（实施者先证伪再动手）

这几条不证清就动手，很可能白做。

1. **按名命中为何失效。** 平台在 `getConfigurables` 里有一条按名路径——`displayName.contains(option)`，不依赖索引。理论上搜「SQL Visualizer」整词该直接命中根节点 configurable（`PluginSettingsFolderConfigurable.kt:54` 的 `getDisplayName()` 返回 `plugin.name`），但用户环境实测搜不到。说明按名这条路也没走通，原因未明。**猜测是设置对话框走了新 UI 的搜索路径**，绕开了老的按名匹配。动手前必须先弄清这一点：如果按名路径在当前 IDE 版本里压根不生效，那 A1 只做「补索引 + 补接口」可能仍然搜不到，得连带确认新 UI 的入口在哪、是否还有别的门槛。这是整项任务最大的不确定点，先证伪再铺开。

2. **平台看到的是裸 Configurable 还是 ConfigurableWrapper。** plugin.xml 注册用的是 `instance=`，平台会把实例包进 `ConfigurableWrapper`。门二那句 `instanceof SearchableConfigurable` 判的到底是包装层还是原始类，直接决定 3.2 的改造是否必要——如果 `ConfigurableWrapper` 自身已经是 `SearchableConfigurable` 且 `getId()` 取自 EP id，那么 9 个类一行都不用改，Contributor 喂完就能搜到。以平台源码为准核对，别照着「裸类必须实现接口」的推断先改了 9 个类。

3. **`processor.addOptions` 各参数语义。** 六个参数（`text` / `path` / `hit` / `configurableId` / `configurableDisplayName` / 最后一个 boolean）的含义、`path` 参数该怎么填才能让搜索结果定位到具体设置页（是「父页 > 子页」的路径字符串，还是别的形态），必须以平台源码为准核对。填错 `path` 的后果是搜得到但点了跳不到，属静默失效。

4. **新 UI 搜索路径是否影响。** 若前提 1 坐实「走了新 UI」，还要查清新 UI 是否读取同样的 EP 与索引；否则 A1 在旧路径生效、用户实际界面仍是空的。

## 六、分阶段实施建议

不要一上来就改 9 个类 + 铺全量条目。先做最小闭环，机制通了再铺开：

1. 最小验证：注册一个 `SearchableOptionContributor`，只让 1 个设置项可搜，只让 1 个 Configurable 实现 `SearchableConfigurable`。跑 runIde 沙盒，在设置搜索框里输入那个词，确认能搜到、能点、能跳到对应页。
2. 前提 2 在这一步顺手定音：如果最小验证发现只做 Contributor、不改 Configurable 就能搜到，说明包装层已满足门二，后续省掉 9 处改造。
3. 机制确认后再全量铺开：补齐 9 个类的 `getId()`（或确认不需要），把 Contributor 的数据源切到 `ALL_SEARCHABLE_FIELDS` 全量。
4. 若前提 1 在最小验证阶段就证伪（按名与新 UI 路径都不通、Contributor 也无效），立即停下回报，不进入全量阶段。

## 七、验收口径

- 在 IDE 设置搜索框输入「脱敏」「方言」「参数前缀」等词，能搜到对应设置项，点击能跳到插件设置页并选中对应子页。
- 与 B 共享数据源后，`settings.display.mask` 这类条目在两套搜索里都能命中——两套同源的直接检验。
- 中英两种界面语言下各验一遍。
- 反向验证：临时摘掉 EP 注册（或摘掉某个类的 `getId()`），对应搜索必然落空；恢复后回到可搜。证明读数不是别的原因凑出来的。

## 八、风险

- 前提 1 是硬风险：按名路径失效原因未明，存在「A1 全做完仍然搜不到」的可能，故第六节把最小验证放在最前，先买断这个不确定性。
- `SearchableConfigurable` 改造触达 9 个类，其中 `SectionConfigurable` 是基类，构造器签名一改，6 个子类的构造调用（`:124-159`）与可能的既有测试都要同步。前提 2 若成立可完全规避，优先级高于改造本身。
- Contributor 的 `processOptions` 在设置对话框初始化路径上执行，实现里不能做重量级 IO 或触发 PSI/索引访问；数据源是内存里的静态清单，符合要求。
- 本项不碰 `build.gradle.kts`，无构建期风险。

## 九、实施记录

（待回填）
