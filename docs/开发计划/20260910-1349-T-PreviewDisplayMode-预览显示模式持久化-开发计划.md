# T-PreviewDisplayMode：预览显示模式持久化（竖向排列/关键字换行输入参数后丢失）开发计划

> **创建日期**：2026-09-10
> **分支**：master（HEAD `f13dc60`；工作树含其余会话在途文档改动）
> **优先级**：P1（功能性缺陷——用户按主流程操作即触发，竖向排列/关键字换行两个功能实际不可用）
> **预估工时**：3~4h（F1 模式状态 + F2 按钮接线 + F3 测试，单笔 feat 提交）
> **来源**：用户真机反馈（2026-09-10，IDEA 2026.2.1）——点「竖向排列」后左侧参数输入框填入值，右侧竖排形态消失、退回 `format()` 形态

---

## 一、问题定位与根因

### 1.1 现象（两张截图对照）

- **竖排生效**（`t-vstackpersist-vertical-ok.png`）：点「竖向排列」后编辑器为 INSERT 列清单逐行 + VALUES 逐行 + 行尾 `-- 列名` 配对注释；
- **输入参数后丢失**（`t-vstackpersist-after-param.png`）：在左侧「user.age」填入 `1`，右侧立刻退回 `INSERT / INTO \`user\` (password, age, ...) / VALUES (password '123456', age 1, ?, ... )`——列清单与 VALUES 全部回到同行（即 `format()` 产物），配对注释一并消失。

### 1.2 代码链路

| 位置 | 内容 |
|---|---|
| SqlPreviewPanel.kt:2077-2081 | `onParamChanged()`：`paramValues[source] = value` → `renderSql()` → `saveToHistory()`（**逐字符**触发，无去抖） |
| SqlPreviewPanel.kt:1109-1167 | `renderSql()`：三分支（XML / 批量 / 模型 / 旧文本）从模型**全量重建** SQL 文本，再按 source 回填参数 |
| SqlPreviewPanel.kt:1389-1432 | `showSql()`：唯一收敛点。:1398 `val text = if (formatSqlSetting() && !compactMode) SqlFormatter.format(withFill) else withFill`，随后 `nativeDoc.setText(text)` / `editor.document.setText(text)` **整篇覆盖** |
| SqlPreviewPanel.kt:410-430 | `toolbar()`：格式化(:424) / 关键字换行(:426) / 竖向排列(:428) 三个按钮 |
| SqlPreviewPanel.kt:1190-1218 | 三个按钮的执行体：`formatSql()` / `formatClauseLine()` / `formatVertical()` 均为「读编辑器文本 → 变换 → 写回编辑器」的**一次性文档变换** |
| SqlPreviewPanel.kt:1236-1239 | `toggleCompact()`：改 `compactMode` 字段后 `renderSql()`（模式是字段，故能存活） |
| SqlPreviewPanel.kt:1174-1182 | `refreshView()`：`compactMode = !formatSqlSetting()` 后重渲染（有意复位，M19.3 语义） |

### 1.3 根因

**预览编辑器是「模型派生视图」，不是用户可长期持有的文档**——每次参数输入 / 动态条件勾选 / XML foreach 次数 / 批量列勾选 / 压缩切换 / 刷新 / 新预览，都会从模型全量重建文本并整篇 `setText` 覆盖。

在这个结构下，只有**挂在渲染管线里**的变换才能存活：

- `format()` 挂在 `showSql():1398`，所以「格式化」的结果在**设置 `formatSql` 开启且非压缩模式**时会被重新套上（观感上"保留"）；
- `formatClauseLine()` / `formatVertical()` **只存在于按钮回调里**，管线中没有任何位置知道"当前处于竖排/关键字换行状态"，第一次重建即被无条件抹掉。

即：**这三个按钮都是"一次性变换"，其中两个的变换在管线中完全无迹可寻**。用户看到的"状态消失"本质是「变换未被记录」而非「变换被回滚」。

### 1.4 触发面（同一根因的全部入口）

`renderSql()` 的调用点（除按钮自身外）：`onParamChanged`(:2079)、动态条件勾选(:1747/:1811/:1845/:1857)、XML foreach 次数与勾选(:1893/:1904/:1919/:1935/:1983/:1992)、`toggleCompact`(:1238)、`refreshView`(:1181)、`showResult` 初次渲染(:879)。

任何一处都会抹掉竖排/关键字换行形态——用户报告的"输入参数"只是最常走的那条。

---

## 二、相关按钮同问题核查（用户要求）

逐个核对工具栏与底部按钮的状态载体：

| 按钮 | 状态载体 | 参数输入后 | 结论 |
|---|---|---|---|
| 压缩 / 多行（modeButton） | `compactMode` 字段（:1236） | 保留（且 `renderSql` 按它重建） | ✅ 无问题 |
| **格式化** | 无状态；靠 `showSql():1398` 的 `format()` 重新套用 | 设置 `formatSql=true` 且非压缩 → 观感保留；**设置为 false 时丢失**；**压缩模式下丢失** | ⚠️ 条件性丢失 |
| **关键字换行** | 无状态，纯一次性变换 | **必丢** | ❌ 同缺陷 |
| **竖向排列** | 无状态，纯一次性变换 | **必丢**（本次用户报告） | ❌ 同缺陷 |
| 刷新 | 有意复位（M19.3「刷新=回到默认状态」） | 设计如此 | ✅ 非缺陷 |
| 更多：导入参数 / 差异对比 / 分享 | 均为对话框，无编辑器状态 | 不涉及 | ✅ 无问题 |
| 底部：执行 SQL / 复制 SQL / 取消 | 只读编辑器当前文本 | 不涉及 | ✅ 无问题 |

结论：**同一根因命中三个按钮**——竖向排列、关键字换行（无条件丢失），格式化（条件性丢失）。修复须一次性覆盖三者，只修竖向排列会留下同类缺陷。

另有一处既有语义边界需明确写清：**用户在编辑器里的手动编辑同样会在参数变更时被重建覆盖**（同一套全量重建机制），本计划不改变该行为——编辑器文本始终是模型派生视图，保留手改需另立"手改即脱离派生"的模型，超出本次范围。

---

## 三、方案设计

### F1：引入面板级显示模式状态，落在唯一收敛点

新增面板字段与枚举：

```kotlin
enum class PreviewDisplayMode { NORMAL, FORMAT, KEYWORD_WRAP, VERTICAL }
private var displayMode = PreviewDisplayMode.NORMAL
```

`showSql():1398` 的单行条件替换为统一的模式应用函数：

```kotlin
private fun applyDisplayMode(sql: String): String = when {
    compactMode -> sql                       // 压缩优先：模式不套用（延续既有"压缩模式保持紧凑"）
    else -> when (displayMode) {
        NORMAL        -> sql
        FORMAT        -> SqlFormatter.format(sql)
        KEYWORD_WRAP  -> SqlFormatter.formatClauseLine(sql)
        VERTICAL      -> SqlFormatter.formatVertical(sql)
    }
}
```

要点：

- **压缩模式优先级最高**（`compactMode` 短路在前）——压缩语义是"全部挤一行"，若再叠模式变换会互相打架；与今天"压缩模式跳过 format"一致。
- `KEYWORD_WRAP` / `VERTICAL` 无需再叠 `FORMAT`：`formatVertical()` 对非 INSERT 已内含 `format()`，`formatClauseLine()` 自带完整排版规则。
- 收敛点唯一（`showSql`）：XML / 批量 / 模型 / 旧文本四条重建路径**自动全部覆盖**，无需逐分支处理——这也是选这里的理由（对比"在 renderSql 每个分支末尾各套一次"必然漏一处）。

### F2：三个按钮改为「设模式 + 重渲染」

- 「格式化」→ `displayMode = FORMAT`；「关键字换行」→ `KEYWORD_WRAP`；「竖向排列」→ `VERTICAL`；三者统一改为 `setDisplayMode(mode)`：赋值后调 `renderSql()` 重渲染（不再直接改编辑器文本）。按钮回调于是从"改文档"变为"改状态 + 让管线产出"，与 `modeButton` 的既有形态一致。
- 保留 `formatSql()` / `formatClauseLine()` / `formatVertical()` 面板公开方法**原语义**（直接变换当前文档文本）供既有测试与 `Ctrl+Alt+L` 快捷键路径复用；新增 `setDisplayMode()` 供按钮走状态路径。若实施时发现两条路径语义重叠造成困惑，则以「按钮走状态、快捷键调 `formatSql()` 后再把模式设为 FORMAT」收口。

### F3：初值、复位与跨预览语义

- **初值**：面板构造时按设置派生——`formatSql=true → FORMAT`，`false → NORMAL`，完整保持今天的默认观感（`PluginSettings.state.formatSql` 默认 `true`，PluginSettings.kt:41）。
- **刷新**（`refreshView`）：复位为设置派生值（延续 M19.3「刷新=回到默认状态」），即竖排/关键字换行状态下点刷新 → 回到 FORMAT/NORMAL。
- **新预览**（`showResult`）：**保留当前模式**（同一会话连续看多条 SQL 时，显示偏好不应被打断）；与刷新的"复位"语义区分开，写进 KDoc。
- **不持久化到设置**：本期为面板级状态（弹窗生命周期内有效），跨 IDE 重启不保留。理由：日志列表的持久化显示模式（dev 分支 T-ListMode 的 `logListSqlDisplayMode`）是另一个面板的既有设计，预览面板是否需要跨会话记忆属偏好问题，等用户提出再立项，避免扩大本次改动面。

### F4：文本读取面核查（无需改动，仅确认）

模式产物会被下列链路消费，均为已核对的安全路径：复制 SQL（`SqlCommentStripper.strip` 剥注释，:1236 既有）、复制可执行 SQL、执行 SQL（注释语法级无害）、差异对比、分享。竖排产物的 `-- 列名` 注释接入上述链路的安全性在 T-VStack 首交付已验证，本计划不新增风险。

### F5：可选增强（评估后决定是否随本次交付）

- 激活态视觉反馈：当前 `smallButton()` 为纯文本 JButton，无选中态。若要高亮当前激活模式（竖排生效时按钮呈选中态），需换 `JToggleButton` 或自绘背景——**建议本次不做**，避免与 T-PreviewTopOverlap 的工具栏宽度预算/折叠分组决策相撞；如需，另立项。
- 再次点击已激活按钮回到默认模式：行为增强，交互上合理但非缺陷所需，同样留待用户反馈。

---

## 四、实施顺序

1. F1 枚举 + 字段 + `applyDisplayMode()` 接入 `showSql`（先不动按钮，跑回归确认无行为回归）；
2. F2 三按钮改走 `setDisplayMode()`；
3. F3 初值与刷新复位；
4. F5 测试（F4 无需改动）；
5. 定向 + 全量回归；
6. 文档三处同步（变更记录总册 / docs MEMORY / 本计划回填）；
7. runIde 真机走查（留人工，见 5.2）。

---

## 五、测试与验收

### 5.1 测试用例（SqlPreviewPanelTest 为主）

核心回归断言（本次缺陷的直接锁定）：

1. **竖排保持**：`showResult` → 点竖向排列（或 `setDisplayMode(VERTICAL)`）→ 触发参数变更（走 `onParamChanged` 同一路径，如直接改参数输入框文本）→ 断言编辑器文档仍为竖排形态（含 `INTO \`user\`\n(` 与 `-- ` 配对注释特征）；
2. **关键字换行保持**：同路径断言子句顶格换行形态未被抹平；
3. **格式化保持（设置关闭时）**：`formatSql=false` 下点格式化 → 触发参数变更 → 仍为主题格式化形态（当前会丢失，是本缺陷的条件性分支）；
4. **压缩优先**：压缩模式 + `VERTICAL` → 重渲染产物仍为单行（模式不生效）；
5. **刷新复位**：`VERTICAL` → `refreshView()` → 恢复设置派生模式（`formatSql=true` 时 FORMAT）；
6. **勾选触发同样保持**：`VERTICAL` → 切换动态条件勾选（`renderSql` 另一入口）→ 形态保持；
7. **既有用例校正**：`testVerticalNonInsertFallsBackToFormat`、`testVerticalButtonPresentAndFormatsInsert` 等若因按钮语义改为状态路径需同步调整；`testRefreshViewResetsCompactMode`（:137 附近）保持绿。

### 5.2 验收标准

- 竖排 / 关键字换行 / 格式化三种形态下，输入参数、勾选条件、切 foreach 次数、切压缩再切回，右侧形态均保持（压缩态除外）；
- 压缩模式仍是单行优先，不被任何模式变换破坏；
- 刷新回到默认（设置派生）模式；新预览保留模式；
- 复制 SQL / 复制可执行 / 执行 / 差异对比 / 分享读取到的仍是可执行的合法 SQL；
- 全量回归绿；发版前 verifyPlugin 三版本例行门禁。

### 5.3 真机走查清单（runIde，留人工）

按用户复现路径逐条对照：竖排 → 逐字段输入参数（含勾选/取消勾选动态条件、XML foreach 次数增减）→ 形态保持；竖排后点刷新 → 复位；竖排后复制 SQL → 产物无注释且合法；切压缩 → 单行；切回多行 → 竖排恢复（若保留模式）或回到 FORMAT（若按 5.1-5 复位）；亮/暗主题各走一遍。

---

## 六、并行协调与风险

### 6.1 并行协调

- 跑 gradle 前 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，有活跃任务则登记独立沙盒，跑完注销；
- `SqlPreviewPanel.kt` 与两份 bundle、两份 docs 长期有其余会话在途（T-PreviewTopOverlap / T-ExecSilentNoop 等），本计划改动落在 `SqlPreviewPanel.kt` + `SqlPreviewPanelTest.kt`：提交前先 `git status` 确认在途面，必要时走 hash-object + update-index 索引级拆分（删行==0 断言），工作树零接触；
- 清零提醒：`build/classes/java/main` 陈旧类会劫持 classpath（本日第三次实证），提交前先核对该目录无残留旧类。

### 6.2 风险

| # | 风险 | 处置 |
|---|---|---|
| 1 | 模式 × 压缩 × 设置三态组合出现未定义优先级 | F1 明确 `compactMode` 短路在前，模式 → else 分支；测试 5.1-4 锁定 |
| 2 | 参数输入**逐字符**触发 `renderSql`（无去抖），大 SQL 下每击多跑一次 `formatClauseLine`/`formatVertical` | 变换为纯字符串 O(长度) 处理，与既有 `format()` 同量级；实测若观感卡顿，再引入输入去抖（独立子任务，不并入本次语义改动） |
| 3 | XML / 批量路径的重建产物套竖排后语义是否可读（如批量 INSERT 多组） | `formatVertical` 对批量 INSERT 已有 `),` 接排规则与用例（T-VStack 首交付 testVerticalInsertMultiRowGroups）；实施后在 XML 预览场景真机复核 |
| 4 | 按钮语义从"改文档"变"改状态"影响既有测试 | 保留 `formatSql()`/`formatClauseLine()`/`formatVertical()` 原方法不动，仅按钮接线改走 `setDisplayMode()`，把测试影响面压到"按钮回调断言"一处 |

### 6.3 明确不做

- 不做编辑器手动编辑的保留（模型派生视图语义不变，另立项才有意义）；
- 不做模式持久化到设置、不做激活态按钮高亮、不做再次点击回到默认（F5 留待反馈）；
- 不改 `refreshView` 既有的压缩复位语义，只在其上补"模式一并复位"。

---

## 附录：截图取证存档

- 竖排生效：`~/.zcode/cli/memories/projects/mybatis-plus-sql-visualizer-*/memory/assets/t-vstackpersist-vertical-ok.png`
- 参数输入后丢失：`~/.zcode/cli/memories/projects/mybatis-plus-sql-visualizer-*/memory/assets/t-vstackpersist-after-param.png`
- 关键证据：两图右侧同为 INSERT `user` 表，上图 VALUES 为「值 + `-- 列名`」逐行，下图退回 `VALUES ( password '123456', age 1, ?, ?, ... )` 同行——差异仅由左侧输入框填入 `user.age = 1` 引发。（临时需求图不入 `docs/images` 正式图档，按用户要求存本地记忆归档。）
