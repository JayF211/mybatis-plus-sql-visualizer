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

### 1.1 我方编辑器构造点全清单（实测）

| 构造点 | 用途 | 证据 |
|---|---|---|
| `ui/SqlPreviewPanel.kt:2549-2551`（`EditorFactory.createEditor`）+ `:2564-2572`（`EditorEx.apply`：`isLineNumbersShown=true`、`isFoldingOutlineShown=true`、`isIndentGuidesShown=false`、`isAdditionalPageAtBottom=false`、`setPlaceholder(...)`） | **预览面板 SQL 区**（主目标） | 同左 |
| `log/LogToSqlPanel.kt:459-462` 与 `:483-486` | 日志转 SQL 面板（两个构造点） | 同左 |
| `log/SqlClickPreview.kt:214-220` | 日志点击预览 | 同左 |

**现状**：`grep -rn "whitespaces|setWhitespacesShown|WhitespacesShown|isWhitespacesShown" src` **结果为空**——仓内零空白符开关；三处构造点全部只设上述四项。

**三级降级（对本任务是好事）**：预览编辑器有三级降级链（临时 PSI 文件 → 内存 PSI `:2628 createInMemoryPreviewPsi` → 裸 Document `:2601 fallbackPreviewDocument`），但 `setWhitespacesShown` 是**渲染层**能力，**三级都在**（这与 O-44b 的 inlay 不同，后者依赖 PSI、第三级不渲染）。

### 1.2 新增布尔开关的标准落点（照抄 `onboardingAnchorTipEnabled` 全链，7 处）

| # | 落点 | 证据 |
|---|---|---|
| 1 | 设置类字段 | `config/PluginSettings.kt:463-466`（`var onboardingAnchorTipEnabled: Boolean = true`，KDoc 在 `:463-465`） |
| 2 | 面板控件声明 | `config/PluginSettingsPanel.kt:320`（`JBCheckBox`） |
| 3 | 面板行注册 + tooltip | `PluginSettingsPanel.kt:908-911`（`addRow(...)` + `text` + `toolTipText`） |
| 4 | isModified 链 | `PluginSettingsPanel.kt:1455`（位于 `isModifiedUi`） |
| 5 | apply 写回 | `PluginSettingsPanel.kt:1566` |
| 6 | reset 回读 | `PluginSettingsPanel.kt:1652` |
| 7 | 双语 bundle 两键 | `messages/MpSqlVisualizerBundle.properties:322-323`（en）、`MpSqlVisualizerBundle_zh.properties:304-305`（zh） |
| — | 消费点（非 config 包） | 照 `onboarding/OnboardingAnchorTip.kt:75`/`:123` 的形态——**否则被 `SettingsRealityGuardTest` 断言一判「写而不读」** |

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

### 任务分解（D1=A 时）

**T-PWT-01（设置字段与面板接线）**：按 §1.2 的 7 处落点接线——`PluginSettings.State` 加 `var previewShowWhitespaces: Boolean = false`（**默认 false** = 完全不动用户偏好）；面板 `JBCheckBox` + 行注册 + tooltip；`isModified`/`apply`/`reset` 三处；双语 bundle 两键。

**T-PWT-02（编辑器应用点收口）**：三处/四处构造点各自 `EditorEx.apply { … }` 内加 `settings.isWhitespacesShown = <读设置>`。**建议抽一个 `applyPreviewEditorSettings(editor)` 收口**（现有四处各自内联四项设置，收口后新增项只需改一处）。

**T-PWT-03（消费点登记）**：在非 config 包留下真实消费点（照 `onboarding/OnboardingAnchorTip.kt:75`/`:123` 形态），满足 `SettingsRealityGuardTest` 断言一。

**T-PWT-04（测试与文档）**：用例——①设置读写往返（`PluginSettings` 既有测试形态）；②`applyPreviewEditorSettings` 在开关开/关两态下 `isWhitespacesShown` 的取值（纯逻辑可测部分）；③`isModified`/`apply`/`reset` 三处接线的守卫（`SettingsRealityGuardTest` 自动覆盖，确认全绿）。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第九轮报告 §8 #44 落地备注。

## 四、验收标准

1. 开关**默认关**时，编辑器 `isWhitespacesShown` 的取值**完全不动用户全局偏好**（不得硬设 false——须「不改」而非「设 false」，两者的用户观感不同：前者继承全局，后者强制关闭）。
2. 开关开启时，预览面板编辑器显示空白符；SQL 文本内容与渲染结果**逐字节不变**（纯显示层）。
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
| D1 定案 | 本次更新 | **A（做全局开关）**；差异化点=「全局开时只让预览不显示」；默认关＝不设置该属性（继承全局） |
| T-PWT-01 | （实施后填） | 7 处落点实际行号、默认值语义须回填 |
| T-PWT-02 | （实施后填） | 是否收口为 `applyPreviewEditorSettings`、覆盖了几处构造点须回填 |
| T-PWT-03 | （实施后填） | 消费点落位须回填 |
| T-PWT-04 | （实施后填） | 用例读数、四断言与门禁读数须回填 |

**待验证前提（实施者先证伪再动手）**：

1. 「内嵌控制台不可达」来自静态分析（`embedConsole` 收平台组件）——**未实测**其 EditorSettings 是否有可达路径；若可达则范围可扩大，须先证伪再决定。
2. 「`setWhitespacesShown` 不设置即继承全局」的行为**未实测**（本计划依据 API 语义推断：`EditorSettings` 是编辑器级覆盖）——实施时须在真机确认「不调用 setter」与「调用 setter(false)」的差异（这正是验收 1 的判据）。
3. 四处构造点的**实际数量**（预览 1 + 日志 2 + 点击预览 1 = 4）来自 grep `EditorEx`/`EditorSettings` 的命中——实施时须复核是否有第五处（如测试夹具里的编辑器构造）。
