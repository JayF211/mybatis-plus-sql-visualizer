# T-ModuleScopedLastUsed：「上次使用的数据源」按模块隔离（模块优先、项目级兜底）

> **创建日期**：2026-10-24 20:41
> **触发**：用户提问「sql 预览窗口右上角灰色的『上次使用』的数据源应该是全局的吧，是否应该按照模块隔离更合适？」
> **相关**：`T-DialectInferPollution`（W8~W12 跨模块错源系列）、`T-ExplainConsoleFirst` W7（跨源列证据）

---

## 一、先纠正前提：它不是全局，是**项目级**

`SqlPreviewPanelExecution.kt` / `DataSourceSelector.kt` 的 last-used 记忆落在：

```kotlin
// DataSourceSelector.kt:1293 / 1304
val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
pc.setValue("mpsql.visualizer.last.runner", name)      // LAST_RUNNER_KEY
pc.setValue("mpsql.visualizer.last.runner.id", uuid)   // LAST_RUNNER_ID_KEY
```

`PropertiesComponent.getInstance(project)` = **项目作用域**（随 `.idea/workspace.xml`），同一项目内所有模块共享一份；
不同项目之间不共享。所以是「项目级全局」，不是「IDE 全局」。

## 二、现状：两套取源记忆并存，粒度不一致

| 机制 | 存储位置 | 粒度 | 消费方 |
|---|---|---|---|
| last-used 双键 | `PropertiesComponent(project)` | **项目级（一份）** | 指示行灰字（`bindStateText` 第二级）、执行/EXPLAIN 静默复用 |
| `ModuleDataSourceBindingStore` | `mp-sql-visualizer-datasource.xml` | **模块级**（稳定键 → UUID） | **只喂方言决策**（`Dialect.kt:964` `learnedCandidate`） |

关键事实：**模块级学习早就在跑**——`DataSourceSelectorConsole.kt:540` 在执行成功后调
`ModuleDataSourceBindingStore.remember(moduleKey, dataSourceId)`。但它只服务方言推断，
**没有参与「上次使用」这条更靠前的取源链路**，所以指示行灰字永远显示项目级那一份。

这就是用户观察到的现象：在 `jpa_demo` 模块的实体上执行并选了 `jpa_demo@localhost`，切到 `mp_demo`
模块的预览，灰字仍显示同一个源。

## 三、结论：按模块隔离更合适

三条理由：

1. **多模块工程的源本来就是按模块分的**。demo 仓即典型：`mp_demo` / `jpa_demo` / `mpj_demo`
   各连各库，跨模块共用一个 last-used 必然错配。
2. **这是本轮 W8~W12 那批错源的根因之一**。用户此前报的
   `mp_demo.wms_inventory_jar_demo doesn't exist`、`Unknown column 'first_name'`
   （`first_name` 属 `jpa_demo` 的 `user` 表），本质都是「模块 A 的 SQL 拿了模块 B 的源」。
   W7 的跨源列证据是**事后**纠错，模块隔离是**事前**不犯错。
3. **基建已就位**，不需要新造存储结构，只需把 last-used 从「项目级单键」升级为
   「按 moduleKey 分桶 + 项目级兜底」。

## 四、方案

### 4.1 分层记忆（三层，优先级从高到低）

```
① 活控制台绑定（boundConsoleDataSource）          —— 现状不变，最高优先
② 模块级 last-used（新）  mpsql.visualizer.last.runner.<moduleKey>
③ 项目级 last-used（现状） mpsql.visualizer.last.runner
```

读取：②命中即用；②未命中回落③（**不回落到「无」**）。
写入：执行/选源成功时**同时**写②（有 moduleKey 时）和③（保持既有语义，供无模块上下文时兜底）。

### 4.2 硬约束：moduleKey 缺失必须回落，不能硬隔离

`sourceModuleKey` 可能为 null（纯 `.sql` 文件、非 Java 源触发预览等入口）。这类入口若按模块硬隔离，
会退化成「每次都未绑定」——比现在更糟。故：

- moduleKey 为 null/blank → **只读写项目级**，行为与改前逐字一致；
- moduleKey 存在 → 读写模块级，**同时**更新项目级（作为其它入口的兜底）。

### 4.3 指示行文案要能看出层级

现在二级文案是「数据源（上次使用）：X」。模块化之后建议区分：

- 模块级命中 → 「数据源（本模块上次使用）：X」
- 项目级兜底 → 保留「数据源（上次使用）：X」

差异只在括号内的限定词，不新增控件（W12 定案：自研换源 UI 已全部退役，这里只改文案）。

### 4.4 失效清理

现有 `forgetLastRunner(project)` 要扩成 `forgetLastRunner(project, moduleKey)`：只清对应层级的键。
`ProjectPluginSettingsConfigurable.kt:345` 的「清空模块学习」入口要连带清 last-used（否则用户点了
「清空」，灰字仍显示旧源）。

## 五、工作项

| # | 内容 | 落点 |
|---|---|---|
| W1 | 新增 `LastUsedKey` 纯函数：`(moduleKey, tier) -> propertyKey`，含 null 回落的键选择 | `execute/DataSourceSelector.kt`（新增 internal object） |
| W2 | `lastUsedRunner` / `rememberRunner` / `forgetLastRunner` 加 moduleKey 参数，按 4.1 分层读写 | 同上 |
| W3 | 指示行 `bindStateText` 区分模块级/项目级文案（两处 i18n） | `ui/SqlPreviewPanelParamPanel.kt` + bundle |
| W4 | 「清空模块学习」连带清 last-used 双层级 | `config/ProjectPluginSettingsConfigurable.kt` |
| W5 | 守卫 + 纯逻辑表驱动测试（模块命中/回落/无模块/失效清理四个形态） | 新增测试类 |

## 六、待验证前提（先证伪再动码）

| # | 前提 | 证伪方式 |
|---|---|---|
| P-1 | `sourceModuleKey` 在预览打开时就已确定（不是渲染后才填） | 读 `SqlPreviewPanel.kt:1077` 赋值时序；确认 `bindStateText` 调用点晚于它 |
| P-2 | 执行链实际写入时能拿到同一个 moduleKey（不能是 null 而指示行有值，否则读写错位） | 追 `rememberRunner` 的所有调用点，确认 moduleKey 透传路径 |
| P-3 | 模块级键数量有界（不会因模块重命名/移动无限膨胀） | 确认 moduleKey 是稳定键（canonicalModuleKey）而非路径 |
| P-4 | 无模块上下文入口（纯 .sql）的现有行为不被改变 | 跑既有 bind-state 相关测试；对比改前后行为 |

## 七、验收判据

1. 模块 A 选源后，模块 B 的指示行不显示 A 的源（定向用例）。
2. moduleKey 为 null 时行为与改前逐字一致（回归用例）。
3. 三层优先级正确：活控制台 > 模块级 > 项目级。
4. 反向探针：删掉模块级读取分支 → 必须红。

---

## 八、实施记录

（实施后回填）
