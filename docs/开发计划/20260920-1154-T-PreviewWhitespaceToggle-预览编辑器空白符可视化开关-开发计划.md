# T-PreviewWhitespaceToggle 预览编辑器空白符可视化开关 — 开发计划

- 日期：2026-09-20 11:54
- 任务编号：T-PreviewWhitespaceToggle（第九轮报告 §9.4 **O-44a**，P3，**含决策点 D1**）
- 来源：第九轮报告 §8 DBX 增量清单 **#44**——DBX `fa3925c`：SQL 预览加空白符可视化开关（`codemirrorShowWhitespace.ts:15` 纯显示层装饰：空格 ·、Tab →、行尾 ↵，仅可见区、**永不改文档文本**），设置弹窗开关 + 12 语言 i18n + 52 行 spec
- 性质：**编辑器显示偏好**——不改 SQL 内容、不改渲染；对排查 MP 动态拼接 SQL 里的多余空格/Tab 有真实排障价值
- 实施分支：自 master 拉任务分支 t-preview-whitespace-toggle
- 验证基线：master `22d7c49`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 决策定案（2026-09-20，用户认可本会话推荐）

| 决策点 | 定案 | 一句话理由 |
|---|---|---|
| **D1** | **A——做（全局布尔开关，默认关＝不设置该属性）** | 预览编辑器当前**不设置** whitespace 属性、**继承 IDE 全局设置**，故开关的真实价值是**双向**的：①全局关时只让预览显示空白符；②**全局开时只让预览不显示**（「别处需要看空白符，但预览 SQL 想要干净」）。第②点是「重复能力」之外的真实差异化，且成本低、纯显示层、零正确性风险 |

---

## 一、背景与依据（现状核实，含两处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「预览面板/内嵌控制台加 toggle，平台原生 `EditorSettings.setWhitespacesShown(true)` 一行即得」 | **部分成立（口径修正）**：预览 SQL 展示区**确实是真实编辑器**（`EditorFactory` 直建，实际类型 `EditorEx`，`ui/SqlPreviewPanel.kt:2549-2551`），平台 API 在 243 构建平台上**实测可用**（`EditorSettings.isWhitespacesShown()/setWhitespacesShown(boolean)` 及 leading/inner/trailing/selection 全套，来自 243 发行版 `lib/app-client.jar` 的 javap 实测）。**但「内嵌控制台」不是我们的编辑器**——`embedConsole`（`:1334-1338`）收的是平台 Database 控制台组件，其空白符显示**不受我们控制**（可达路径未找到） | 范围**收窄为「我们自建的编辑器」**；内嵌控制台排除（或列入待验证前提） |
| 2 | 「照 DBX 做纯显示层装饰」 | **平台原生能力已覆盖**：`setWhitespacesShown` 是渲染层能力，**天然不改文档文本**，无需自建装饰 | 实现成本远低于 DBX 的 CodeMirror 自建方案；本任务的真实工作量在**设置项接线**（7 处落点 + 4 类守卫） |

### 1.1 我方编辑器构造点全清单（实施前实测复核，**计划原表低估**）

**覆盖面定案（实施时确定，验收据此判定）**：开关定位＝**「插件自建编辑器」全部 9 处**（不写窄成「预览类」）——理由是「预览类」边界对 `LogToSqlPanel` 输入区 / `SqlInputEditor` 这类**输入向**编辑器无法自洽划分，且用户勾了开关却在另一个插件编辑器里看不到空白符属可感知的不一致。收口后 `EditorEx.apply` 内一行即接入，覆盖面本身零额外成本。

| # | 构造点（实际行号，实施前实测） | 用途 | 计划原表 |
|---|---|---|---|
| 1 | `ui/SqlPreviewPanel.kt:2578`/`:2580`（createEditor）+ `:2591-2602`（`EditorEx.apply`） | **预览面板 SQL 区**（主目标） | 已列 |
| 2 | `log/LogToSqlPanel.kt:457`（输入区）、`:473`（结果区） | 日志转 SQL 面板（两处） | 已列 |
| 3 | `log/SqlClickPreview.kt:213` | 日志点击预览 | 已列 |
| 4 | `ui/SqlInputEditor.kt:67`/`:77` | SQL 输入编辑器 | **计划漏**（实测补） |
| 5 | `ui/RollbackSqlDialog.kt:125`/`:127` | 回滚 SQL 弹窗（**全文件零外观设置**，唯一不落在 `EditorEx.apply` 块内的接线点） | **计划漏** |
| 6 | `ui/ParamImportDialog.kt:102` | JSON 参数导入编辑器 | **计划漏** |
| 7 | `log/SqlToJavaPanel.kt:1341` | SQL→Java 结果区 | **计划漏** |
| 8 | `ui/JavaCodePreviewDialog.kt:51`（createViewer） | Java 只读预览弹窗 | **计划漏** |

计划 §八 待验证前提 3 猜「是否有第五处」——实测是**九处**（原表 4 处 + 补 5 处）。逐处证据 = `grep -rn "createEditor(\|createViewer(" src/main/kotlin` 全命中清单（**12 个调用点 / 9 个编辑器实例**，同一函数内 createEditor 双分支算 1 处）。**计数订正（独立复核实测）**：本行原写「11 个调用点 / 9 个编辑器实例」——「9 个编辑器实例」成立，调用点数少算 1：上表 8 行自列的调用点数为 2+2+1+2+2+1+1+1 = **12**（`SqlPreviewPanel` 2 / `LogToSqlPanel` 2 / `SqlClickPreview` 1 / `SqlInputEditor` 2 / `RollbackSqlDialog` 2 / `ParamImportDialog` 1 / `SqlToJavaPanel` 1 / `JavaCodePreviewDialog` 1）。

**现状**：`grep -rn "whitespaces|setWhitespacesShown|WhitespacesShown|isWhitespacesShown" src` **结果为空**——仓内零空白符开关；九处构造点全部只设行号/折叠/缩进导轨/页底留白四项（`RollbackSqlDialog` 连这四项都没有）。

**三级降级（对本任务是好事）**：预览编辑器有三级降级链（临时 PSI 文件 → 内存 PSI `:2628 createInMemoryPreviewPsi` → 裸 Document `:2601 fallbackPreviewDocument`），但 `setWhitespacesShown` 是**渲染层**能力，**三级都在**（这与 O-44b 的 inlay 不同，后者依赖 PSI、第三级不渲染）。

### 1.2 新增布尔开关的标准落点（照抄 `onboardingAnchorTipEnabled` 全链，**面板实为 5 处**）

| # | 落点（实施后实际行号） | 证据 |
|---|---|---|
| 1 | 设置类字段 `config/PluginSettings.kt:108`（`var editorShowWhitespaces: Boolean = false`，KDoc 在 `:92-107`） | 对齐 `joinDiagramNative`（`:85-90`） |
| 2 | 面板控件声明 `config/PluginSettingsPanel.kt:322-323`（`JBCheckBox`） | 对齐 `:319-320` |
| 3 | 面板行注册 + tooltip `PluginSettingsPanel.kt:916-919`（`addRow(...)` + `text` + `toolTipText`） | 对齐 `:911-915` |
| 4 | isModified 链 `PluginSettingsPanel.kt:1470`（位于 `isModifiedUi`） | 对齐 `:1468` |
| 5 | apply 写回 `PluginSettingsPanel.kt:1583` | 对齐 `:1581` |
| 6 | reset 回读 `PluginSettingsPanel.kt:1671` | 对齐 `:1669` |
| 7 | 双语 bundle 两键 `messages/MpSqlVisualizerBundle.properties:325-326`（en）、`MpSqlVisualizerBundle_zh.properties:307-308`（zh） | 对齐 `:322-323` / `:304-305` |
| — | 消费点（非 config 包） | 新增 `ui/EditorWhitespaceSettings.kt`——收口本体即消费点（读 `state.editorShowWhitespaces`），**否则被 `SettingsRealityGuardTest` 断言一判「写而不读」** |
| — | 面板测试入口 | `PluginSettingsPanel.setEditorShowWhitespacesCheck(checked)`（`internal`，`:1278-1280`，对齐 `:1273-1275`） |

**子页归属**：归入既有子页「界面与操作」（`config/SectionConfigurable.kt:141-144` 的 `UiActionsConfigurable(setOf(SECTION_UI), "settings.section.ui")`，注册 `plugin.xml:140-145`）——**不新增子页**（新增子页会触 `PluginXmlStructureGuardTest.kt:149-176` 的 `children.size == 7` 硬断言）。

**标准作业书**：`docs/架构设计/03-开发与测试指南.md` §4.3「新增设置项（必须检查全部调用点接线）」7 步（`:184-193` 附近），含第 7 步「假配置四断言自检」。

**会触发的守卫（4 类）**：

| 守卫 | 断言 | 证据 |
|---|---|---|
| `config/SettingsRealityGuardTest.kt` | 断言一（State 每个 var 在非 config 包有消费）`:104`；断言二（面板每个可编辑控件须在 apply 与 reset 各出现）`:159`；断言三（项目级 `effective*`/`isEnabled` 须有带接收者限定调用）`:228`；断言四（bundle 设置类键须被引用或列白名单）`:278` | 同左 |
| `config/SettingsTruthTest.kt:20` | 假开关防回潮；**纯动作开关不得持久化** | 同左 |
| `BundleParityGuardTest` | 中英 key 集合全等 | 见 `SettingsRealityGuardTest` KDoc 分工段 |
| `config/PluginXmlStructureGuardTest.kt:149-176` | 子设置页注册数硬断言 `children.size == 7` + groupWeight 严格递减 | **仅在新增子页时触发**（本任务不新增子页，故不动） |

## 二、目标与非目标

**目标**（仅在 D1 选「做」时）：让用户能对**插件自建编辑器**开启空白符可视化，用于排查动态拼接 SQL 的多余空格/Tab。

**非目标**：不改内嵌控制台（平台 Database 组件，不受我们控制）；不改 SQL 内容或渲染结果（纯显示层）；不新建设置子页；不做项目级三态（本开关是**本机显示偏好**，与项目无关——**须在 D1 中确认此定位**，若定位为项目级则落点翻倍）。

## 三、方案

### 决策点 D1（**须定案，实施前必须定**）

| 选项 | 内容 | 代价 | 推荐度 |
|---|---|---|---|
| **A（做，全局设置）** | 一个全局布尔开关，作用于三处/四处自建编辑器 | 3~4 文件 + bundle 2 键 + 用例 | ★★ |
| **B（不做）** | 只登记结论：IDEA 全局设置已提供同一能力（用户可自行开 Show whitespaces），插件做开关的差异化价值有限 | 0 | ★★★（**默认推荐**，见下） |
| **C（做，项目级三态）** | 项目级可空覆盖 + 全局默认 | 落点翻倍（`ProjectPluginSettings` + `ProjectPluginSettingsConfigurable` + `plugin.xml`），收益不明确 | ★ |

**D1 定案（2026-09-20）**：**选 A**。原「倾向 B」的理由是「IDEA 全局已有同一能力」；核实后该理由被**第②个使用场景**抵消——全局开着空白符时，用户仍可能希望预览面板干净，而插件开关是**只对该编辑器对象的覆盖**、不动用户全局偏好（口径：**默认关 = 不设置该属性**，继承全局；不是设 false）。差异化点在变更登记里写明。

> **实施期口径订正（2026-09-20，T-PWT-02 实测后）**：上述**场景②（「全局开时只让预览不显示」）在「默认关＝不设置该属性」的语义下不可达**——关闭态即继承，无从反向压制全局。本开关实为**单向**：开启 = 对这些编辑器强制显示；关闭 = 继承全局。真实差异化点因此收敛为**场景①**（不改用户全局偏好即可只在插件编辑器里看空白符）。要做到场景② 必须做三态（继承/显示/隐藏）或把「关」定义为 `setWhitespacesShown(false)`，后者直接违反验收标准 1。**本处为计划自相矛盾点，按验收标准 1 的口径实施**（用户已定案 A + 默认关不硬设 false）。

### 任务分解（D1=A 时）

**T-PWT-01（设置字段与面板接线）**：按 §1.2 的落点接线——`PluginSettings.State` 加 `var editorShowWhitespaces: Boolean = false`（**默认 false = 完全不动用户偏好**）；面板 `JBCheckBox` + 行注册 + tooltip；`isModified`/`apply`/`reset` 三处；双语 bundle 两键。

> 字段名由计划原拟的 `previewShowWhitespaces` 改为 **`editorShowWhitespaces`**：覆盖面定案为「插件自建编辑器」全部九处（含 Java 编辑器与参数导入编辑器），`preview*` 前缀名实不符。

**T-PWT-02（编辑器应用点收口）**：九处构造点统一走新增的 `ui/EditorWhitespaceSettings.applyTo(editor)` 收口。**未**采用计划建议的 `applyPreviewEditorSettings` 名（同上：覆盖面超出「预览」）；既有四项外观设置（行号/折叠/缩进导轨/页底留白）**不动**——各点取值本就不同（点击预览与回滚弹窗刻意隐藏行号），合并会改变既有观感，属本任务范围外。

**T-PWT-03（消费点登记）**：收口本体 `ui/EditorWhitespaceSettings.kt` 即非 config 包的真实消费点（读 `state.editorShowWhitespaces` 驱动行为），满足 `SettingsRealityGuardTest` 断言一；无需另建消费点。

**T-PWT-04（测试与文档）**：用例——①`EditorWhitespaceSettingsTest`（关态「不改」判据、开态显示 + 文本逐字节不变、预览面板真实构造链接线、平台继承机制实证）；②`EditorShowWhitespacesSettingBindingTest`（isModified/apply/reset 接线，对齐 `OnboardingAnchorTipSettingBindingTest` 形态）；③既有计数断言随新增复选框同步（`PluginSettingsPersistenceTest` 28 → 29）。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第九轮报告 §8 #44 落地备注（收口阶段统一处理）。

## 四、验收标准

1. 开关**默认关**时，编辑器 `isWhitespacesShown` 的取值**完全不动用户全局偏好**（不得硬设 false——须「不改」而非「设 false」，两者的用户观感不同：前者继承全局，后者强制关闭）。
   - **可判据化**（实施期定案）：headless 可验的等价判据 = ①关态调用后编辑器原有取值**原样保留**（`EditorWhitespaceSettingsTest` 人为置 true 后调用仍为 true）；②关态**零写入**（实现里 `if (!showWhitespaces) return`，静态可读）；③平台继承机制**实测**成立（`EditorWhitespaceSettingsTest.test未显式设置时编辑器取平台全局空白符值`：改 `EditorSettingsExternalizable` 全局值 → 新建编辑器取值随之变化）。
2. 开关开启时，预览面板编辑器显示空白符；SQL 文本内容与渲染结果**逐字节不变**（纯显示层）。
   - **判据订正**（首跑实证）：不能拿输入 SQL 当期望值——预览面板本就把 SQL 按显示模式重排后渲染（输入 `select  id,…` → 编辑器内 `SELECT\n    id,…`）。正确判据 = **同一输入在开/关两态下产出的文档文本逐字节相等**。
3. `SettingsRealityGuardTest` 四断言全绿（无「写而不读」「摆而不绑」「幽灵键」）。
4. 未新增设置子页（`PluginXmlStructureGuardTest.kt:149-176` 的 `children.size == 7` 不动）。
5. 全量 testShards + testDatabase + agent 绿。

## 五、真机走查清单（留人工）

**本任务的验收主体是真机**（编辑器外观 headless 测不到）：

1. 真机预览面板开/关开关：空白符显示随之变化，SQL 文本未被改动（复制出来的 SQL 无多余字符）。
2. 真机验证「默认关 = 继承全局」：先在 IDE 全局开 Show whitespaces，再确认预览面板**也显示**空白符（若被硬设 false 则不会显示——这正是验收 1 的判据）。
3. 真机日志转 SQL 面板与点击预览：行为与预览面板一致（若 T-PWT-02 收口则自动一致，须确认）。
4. 真机内嵌控制台：确认空白符显示**不受本开关影响**（登记为已知边界，非缺陷）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **差异化价值不足**（本任务最大风险） | IDEA 全局已有同一能力，做出来可能是重复开关 | D1 默认推荐 B；选 A 时须在变更登记里写清「只作用于插件自建编辑器、不影响用户其他编辑器」的差异化点 |
| 「默认关」语义歧义 | 「不动用户偏好」与「强制关闭」是两回事 | 验收 1 明确：默认关 = **不设置**该属性（继承全局），而非设 false |
| 内嵌控制台被误纳 | 平台组件不受我们控制，纳入会做出无效开关 | 非目标显式排除；§五 第 4 条作为边界登记 |
| 四处构造点漂移 | 若只改预览面板，日志侧两处行为不一致 | T-PWT-02 建议收口；§五 第 3 条确认一致性 |
| 项目级三态诱惑 | 一旦做项目级，落点与守卫面翻倍 | D1 的 C 选项明确标为「收益不明确」，不默认做 |

## 七、实施顺序

```
[D1 定案：A / B（推荐）]
  D1=A: T-PWT-01（设置字段与面板接线）→ T-PWT-02（编辑器应用点收口）→ T-PWT-03（消费点登记）
        → T-PWT-04（测试与文档）→ 真机走查 §五
  D1=B: 登记结论收口（含「全局已有同能力」的价值判断与内嵌控制台边界）
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| D1 定案 | 本次更新 | **A（做全局开关）**；默认关＝不设置该属性（继承全局）。**实施期订正**：理由中的「全局开时只让预览不显示」（场景②）在默认关语义下不可达，本开关为**单向**（开=强制显示 / 关=继承），真实差异化点收敛为场景①（不改全局偏好即可只在插件编辑器里看空白符） |
| T-PWT-01 | 见下方提交 | 落点实为**面板 5 处**（控件声明 `:322-323` / addRow+tooltip `:916-919` / isModified `:1470` / apply `:1583` / reset `:1671`）+ State 字段 `PluginSettings.kt:108` + bundle 双语 4 行（en `:325-326` / zh `:307-308`）+ 面板测试入口 `:1278-1280`。**字段名改为 `editorShowWhitespaces`**（覆盖面超出「预览」）；默认值 `false` = 不设置该属性 |
| T-PWT-02 | 见下方提交 | 收口为 **`ui/EditorWhitespaceSettings.applyTo(editor)`**（未用计划拟名 `applyPreviewEditorSettings`，理由同覆盖面）；**覆盖 9 处构造点**（计划原表 4 处 + 实测补 5 处，见 §1.1）；既有四项外观设置**未合并**（各点取值本就不同：点击预览/回滚弹窗刻意隐藏行号，合并会改变既有观感） |
| T-PWT-03 | 见下方提交 | 消费点 = 收口本体 `ui/EditorWhitespaceSettings.kt`（读 `state.editorShowWhitespaces` 驱动行为）；未另建消费点 |
| T-PWT-04 | 见下方提交 | `EditorWhitespaceSettingsTest` 8/0/0/0、`EditorShowWhitespacesSettingBindingTest` 2/0/0/0；`SettingsRealityGuardTest` 4/0/0/0（四断言）；`PluginXmlStructureGuardTest` 14/0/0/0；定向回归 22 类 246/0/0/0；全量 testShards 读数见下 |
| 存量断言同步 | 见下方提交 | `PluginSettingsPersistenceTest` 复选框总数硬断言 28 → 29（新增控件必然触发；首跑实证） |
| 计划自相矛盾订正 | 本次更新 | `20260916-1730-待完成开发计划全景盘点.md:94` 原写「D1 默认推荐 B」，同文件 `:109` 决策表已定 A——**以 A 为准**（用户定案），已就地订正该行并注明冲突 |

**与 T-InsertColumnInlay（`20260920-1156-…`）的同文件串行（交叉登记）**：两件计划都改 `ui/SqlPreviewPanel.kt`（本件的 9 处编辑器构造点收口 + 面板 5 处控件改动；T-ICI 的语句类型载体接线 `:1986-1987` + 两条渲染路径）。**落地顺序：本件先（`9b37f73` 主体 + `9fee344` 搜索索引），T-ICI 后（`100d03c` 主体 + `138b42d` 用例与守卫）**——两件的改动落在同一文件的编辑器构造/刷新链**同一批函数**上，故**同文件串行、不可并行**；后续读者若把两件当可并行任务分派，会在同一区域产生冲突（本轮即按「1154 先、1156 后」串行落地）。

**首跑即红的两处（实施期实证，非猜测）**：

1. **「文本逐字节不变」不能拿输入 SQL 当期望值**——预览面板本就把 SQL 按显示模式重排后渲染（输入 `select  id,…` → 编辑器内 `SELECT\n    id,…`），`SqlPreviewPanelTest` 既有用例的期望值也是重排后的形态。判据改为「同一输入在开/关两态下产出的文档文本逐字节相等」。
2. **同面板二次 `showResult` 读不到新开关值**——`showSql` 在 `editor != null` 时**复用既有编辑器**而非重建（`:2456-2470`），故两态对照必须先 `panel.clear()`。另：建两个面板各 `dispose()` 一次会撞 `SqlExecutor.releaseForPanel()` 单例的重复释放（`TraceableDisposable$DisposalException`），故全程单面板 + 收尾 `clear()`（照 `SqlPreviewPanelTest` 既有形态）。

**待验证前提（实施后逐条结清）**：

1. 「内嵌控制台不可达」来自静态分析（`embedConsole` 收平台组件）——**仍未实测**（无真机），本轮维持「排除 + 登记边界」，见 §五 第 4 条。
2. 「`setWhitespacesShown` 不设置即继承全局」——**已实证成立（headless，非推断）**。计划原登记「无法从 jar 侧佐证」的原因定位到：全局侧类在 243 发行版里叫 `com.intellij.openapi.editor.ex.EditorSettingsExternalizable`（**`editor.ex` 包，非计划查的 `editor` 包**，故此前 javap 报「找不到类」），在 `lib/app-client.jar` 内实测存在且 `isWhitespacesShown`/`setWhitespacesShown` 齐全；平台 `EditorSettingsState` 每个属性都是带 `SyncDefaultValueCalculator` 的 `StateProperty`（javap 实证），即「未显式设置 → 取全局默认」。`EditorWhitespaceSettingsTest.test未显式设置时编辑器取平台全局空白符值` 直接改全局值再新建编辑器读数：全局 true → 编辑器 true；全局 false → 编辑器 false。**验收 1 的机制面由此从「推断」升级为「实测」**。
3. 构造点数量——**实测 9 处**（原表 4 处 + 补 `SqlInputEditor`/`RollbackSqlDialog`/`ParamImportDialog`/`SqlToJavaPanel`/`JavaCodePreviewDialog`），详见 §1.1。
