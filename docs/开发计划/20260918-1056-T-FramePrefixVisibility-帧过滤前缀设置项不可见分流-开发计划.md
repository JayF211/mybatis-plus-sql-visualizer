# T-FramePrefixVisibility 源码捕获帧过滤前缀设置项不可见 — 开发计划

- 日期：2026-09-18 10:56
- 任务编号：T-FramePrefixVisibility
- 来源：用户反馈——「源码捕获帧过滤前缀没有看到输入框」（附截图一张）
- 定性：**已定谳并修复（H4 布局压扁）**——用户补发正确截图（IDE Settings → SQL Visualizer → SQL Log 页）：标签「源码捕获帧过滤前缀（一行一个）：」可见、右侧输入框不可见，与 H4 完全吻合。F1 已实施（20260918），守卫测试锁定。

---

## 一、现象与用户截图判读

用户报设置页看不到「源码捕获帧过滤前缀」输入框，附截图一张。

**截图判读（关键）**：截图内容为 **ZCode 应用左侧栏**（新建任务 / 搜索 / 自动化 / 插件市场 / 归档按钮），不是 IntelliJ IDEA 的 Settings 窗口——第一分流即「看错窗口」。该输入框只存在于 IDE 设置页，ZCode 界面中不可能出现。

## 二、代码层核验（读码实测，输入框构建链完整在位）

| 环节 | 位置 | 实测 |
|---|---|---|
| 字段声明 | `PluginSettingsPanel.kt:217` | `JBTextArea(3, 24)` + `emptyText` 占位提示（870affc） |
| 构建注册 | `:732-736` | 在「源码位置捕获」分组（`sectionGroup("settings.log.tab.sourceCapture")`，:704）**内部**，`addRow` 已调用（:736），括号归属逐行核对无误 |
| 分组位置 | `:704-737` | 「SQL 日志」子页面（`SECTION_SQL_LOG`）的第 4 组「源码位置捕获」**最后一行**——排在自动注入/增强捕获/脱敏/状态行之后，**页面底部，需向下滚动** |
| 子页面注册 | `plugin.xml:129-135` | Settings → Tools → SQL Visualizer → **SQL 日志**（`SqlLogConfigurable` → `SECTION_SQL_LOG`），groupWeight=20 |
| bundle 键 | `MpSqlVisualizerBundle_zh.properties:532` 等 | 分组标题「源码位置捕获」、行标签、占位、tooltip 四键双语齐 |
| reset/apply/isModified | `:1383` / `:1264` / `:1175` | 三处均已接（reset 灌入、apply 写回、改动检测） |

结论：代码无「漏挂」；不可见是以下四类原因之一。

## 三、分流表（按概率排序，逐项排除）

| # | 假设 | 判据 | 处置 |
|---|------|------|------|
| H1 | **窗口/页面不对**（截图即 ZCode 侧栏；或进了 IDE 但停在别的子页面） | 用户当前窗口是否为 IDE Settings → Tools → SQL Visualizer → **SQL 日志** | 引导到正确页面（§四 走查 1），零代码改动 |
| H2 | **装的发行包是旧构建**（不含 b117d8c/870affc） | 设置页 SQL 日志分组里连「增强捕获状态」行下方也没有任何新行；或「关于」页构建号早于本次 | 重跑 `./gradlew buildPlugin` 装新 zip；走查 2 |
| H3 | **Settings 页缓存旧面板实例**（`SectionConfigurable.createComponent` 首建后缓存 `panel`，动态安装/更新插件后不重建——`SectionConfigurable.kt:31-37`） | 重开 Settings 窗口仍不见；但重启 IDE 后可见 | 走查 3；若证实可考虑 Configurable 生命周期修正（§五 F2） |
| H4 | **布局压扁**（`addRow` 的 `fill=NONE` + GridBag 高度分配下，3 行 JBTextArea 被上方行挤压成一条线，视觉近似不存在） | 页面能滚到「源码位置捕获」组、状态行可见但下方无框 | 走查 4；证实则按 §五 F1 修（`fill=HORIZONTAL` + `preferredSize` 保底，或包 ScrollPane） |

H1 概率最高（截图直接佐证）；H4 是唯一需要真代码修复的分支，修复方案已备。

## 四、真机走查清单（按序执行，留人工）

1. **确认窗口**：在 **IDEA**（非 ZCode）打开 File → Settings → Tools → SQL Visualizer → **SQL 日志**，滚到页面底部「源码位置捕获」分组——增强捕获状态行之下应有一行「源码捕获帧过滤前缀（一行一个）：」+ 3 行高多行输入框，空态显示灰色占位「示例：com.corp.engine.（一行一个包名前缀）」。
2. **确认构建**：Settings → 插件 → SQL Visualizer 版本号是否为本轮构建（或「关于」页构建时间）；旧包则重装 `build/distributions/*.zip`。
3. **确认缓存**：关闭 Settings 窗口重开一次；仍不见则重启 IDE 再看（H3 判据）。
4. **确认布局**：若分组可见但输入框像一条细线/缺失，记录窗口宽度与缩放（H4 判据），截图回报。

## 五、修复方案（按分流结果启动对应项）

| # | 项 | 触发条件 | 改动 |
|---|----|---------|------|
| F1 | 布局加固 | H4 证实 | `addRow` 对多行文本组件特化：`fill = GridBagConstraints.HORIZONTAL`（垂直仍 NONE）+ `frameSkipPrefixesField.preferredSize` 高度保底（或包 `JBScrollPane` 定高）——一行改动级，附 UI 测试断言组件树含该行 |
| F2 | Configurable 面板刷新 | H3 证实 | `SectionConfigurable.createComponent` 缓存 panel 增「构建戳比对」或响应插件动态装卸重置缓存（对照 `DynamicInstallSupport` 语义谨慎评估，防既有 M19.1 rebuild 语义回归） |
| F3 | 无代码修复 | H1/H2 | 仅归档走查结论，本计划关闭 |

## 六、验收标准

1. 正确窗口 + 新构建下，「SQL 日志」页「源码位置捕获」分组底部可见「源码捕获帧过滤前缀」输入框（含空态占位）。
2. 输入多行前缀 → Apply → `logLevel=info` 打点或跳转行为证实热下发生效（对齐 T-SourceJumpFunnel 验收文档 §3.1）。
3. 若触发 F1/F2：全量 testShards 绿 + 新增守卫用例（组件树断言 / 缓存重建断言）。

## 七、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| F1 改 addRow 波及全部分组 | addRow 是全设置页共用来函数 | 特化逻辑按组件类型判断（仅 JBTextArea/JScrollPane），其余行零行为变化；改后全设置页目检 |
| F2 缓存重建引入重复构建 | rebuild 已有先例（M19.1 invokeLater 语义） | 复用既有 rebuild 路径，不新造重建机制 |

## 八、范围边界

只处理「设置项不可见」的定位与修复；跳转解析逻辑本身（T-SourceJumpFunnel 已交付部分）不在本计划范围。
