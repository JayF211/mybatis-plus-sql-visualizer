# T-InsertColumnInlay INSERT 目标列 inlay 提示 — 开发计划

- 日期：2026-09-20 11:56
- 任务编号：T-InsertColumnInlay（第九轮报告 §9.4 **O-44b**，P3，**含决策点 D1**）
- 来源：第九轮报告 §8 DBX 增量清单 **#45**——DBX `3cb1faf`：INSERT 目标列 inlay 提示（`insertValueHints.ts:23` `InsertValueSource` 带 alias 字段、`:286` `selectProjectionAlias` 去重）——INSERT VALUES / INSERT…SELECT 逐表达式挂目标列名 inlay；**本轮新增规则**：投影项已自带同名列别名（尾随 `AS` 或隐式别名，点名不算）时丢弃提示避免重复
- 性质：**编辑器提示增量**——不改 SQL、不改渲染；让多列动态 INSERT 的「列-值错位」一眼可见
- 实施分支：自 master 拉任务分支 t-insert-column-inlay
- 验证基线：master `22d7c49`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 决策定案（2026-09-20，用户认可本会话推荐）

| 决策点 | 定案 | 一句话理由 |
|---|---|---|
| **D1** | **A——EP + 面板判定** | 本轮已把 EP 形态**核实清楚**（§1.4：`com.intellij.codeInsight.inlayProvider` 存在、带 `language` 属性、接口方法齐备），原「仓内零先例」的技术不确定性已消除；成本 2~3 文件，价值真实（多列动态 INSERT 的列-值错位是 MyBatis 常见坑） |
| **必做防护** | provider 内**必须**做「只认本面板预览编辑器」判定（userData + 文件名双锚点） | EP 是 language 级，注册即对**用户自己的 `.sql` 文件**也生效——这是本任务唯一的真实风险 |

---

## 一、背景与依据（现状核实，含四处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「预览面板对 INSERT 挂 IDEA `InlayHintsProvider`」 | **可行，但仓内零先例（口径修正）**：`grep -rni "inlay" src/main src/test` 仅命中 `ui/diagram/DiagramRenderer.kt:239/248/253` 的局部变量 `joinLayouts`（假阳性），**无任何 `InlayHintsProvider`/`InlayHints` 使用**。平台 API 在 243 可用（实测 `lib/app-client.jar`：`com.intellij.codeInsight.hints.InlayHintsProvider<T>` 含 `getCollectorFor(PsiFile, Editor, T, InlayHintsSink)`/`createSettings`/`getName`/`getKey`/`getPreviewText`/`createConfigurable`/`isLanguageSupported`；`InlayHintsSink` 含 `addInlineElement`/`addBlockElement`） | **EP 名与属性须实施时核实**（仓内无注册先例） |
| 2 | 「挂在本面板预览编辑器上」 | **有副作用须处理（口径修正）**：inlay 的 EP 是 **language 级**（须 `language="SQL"`，参考既有 `plugin.xml:206-212` 的 lineMarkerProvider 形态）——注册即对 IDE 内**所有** SQL 编辑器生效（**含用户自己的 `.sql` 文件**）。必须在 provider 内做「只认本面板预览编辑器」的判定 | 落点须含面板判定（见 §三 T-ICI-02） |
| 3 | 「`statementKind` 可辨识」 | **成立但零消费者**：`xml/XmlMapperParser.kt:7` `enum class XmlStatementKind { SELECT, INSERT, UPDATE, DELETE }`（判定 `:196-200 kindOf(tagName)`，载体 `:12 val kind`）→ 模型暴露 `xml/XmlPreviewModel.kt:59 val kind`（`from()` 注入 `:220`）→ 面板可达 `model/PreviewResult.kt:49 xmlModel`。**但 `xmlModel.kind` 当前零读点**（`ui/SqlPreviewPanel.kt:3223` 的 `kindFor(source)` 返回的是 `ParamKind` 参数类别，不是语句类型） | 本任务会**新开第一个 `xmlModel.kind` 读点**，须一并加用例 |

### 1.1 关键差异：inlay 受降级链影响

预览编辑器有三级降级链：临时 PSI 文件 → 内存 PSI（`ui/SqlPreviewPanel.kt:2628 createInMemoryPreviewPsi`）→ 裸 Document（`:2601 fallbackPreviewDocument`）。**inlay provider 的 `getCollectorFor` 首参是 `PsiFile`**，故**第三级（裸 Document）无 PSI ⇒ 该级不渲染 inlay**（与 O-44a 的空白符开关不同，后者是渲染层、三级都在）。

### 1.2 面板判定可用的锚点（实测；**文件名口径已订正**）

| 锚点 | 证据 |
|---|---|
| 预览编辑器已挂 `SqlCompletionContext.KEY` userData | `ui/SqlPreviewPanel.kt:2556-2557` |
| 预览文件名 = **逐个列举的保留名**（不是 `mpsql-preview-*.sql` 通配） | 见下方三形态表 |

**口径订正（原计划缺陷，实施时实测推翻）**：原表第二行写「固定命名 `mpsql-preview-*.sql`」——**实测有三个不同形态，主路径那个不匹配该通配**：

| 层级 | 真实文件名 | 建文件处 | 匹配 `mpsql-preview-*.sql`？ |
|---|---|---|---|
| **一级：项目内临时 VFS 文件（inlay 真正会渲染的那一级）** | **`mpsql-preview.sql`** | `ui/SqlPreviewPanel.kt:664`（原计划引的 `:639/653` 是同一函数的注释/旧行号） | **不匹配**（少一个连字符） |
| 二级：内存 PSI（降级链第二级） | `mpsql-preview-memory.sql` | `ui/SqlPreviewPanel.kt:2666` | 匹配 |
| 测试缝（`createPreviewFileForTest`） | `preview.sql` | `ui/SqlPreviewPanel.kt:2696` | 不匹配 |

后果：兜底锚点若按通配写，**主路径上失效**——双锚点退化成只靠 userData 的单锚点，表现为「真预览编辑器上 inlay 静默不出现」而非泄漏。**修正**：保留名逐个列举，且常量与面板建文件处**同源**（`PreviewInlayScope.PREVIEW_FILE_NAME` / `MEMORY_PREVIEW_FILE_NAME`，面板 `projectPreviewFile` / `createInMemoryPreviewPsi` 直接引用），杜绝锚点与真实文件名再次漂移；测试缝名 `preview.sql` **有意不列入**保留名（它是最像用户文件的普通名，列入即打穿「用户自己的 `.sql` 文件」硬判据）。

### 1.3 最贴近的既有范式（照抄对象）

| 范式 | 形态 | 证据 |
|---|---|---|
| 13 个 gutter `LineMarkerProvider` | `ui/gutter/XmlSqlGutterLineMarkerProvider.kt:31-56`（`getLineMarkerInfo` O(1) 语法判定）+ `:60-63`（`gutterEnabled()` 读设置） | 同左 |
| 注册形态 | `plugin.xml:196-245`（`<codeInsight.lineMarkerProvider language="JAVA|XML" …/>`；`:206-212` 是 language 属性样例） | 同左 |
| 编辑器级装饰先例（替代路径） | `ui/highlight/SqlAutoFolding.kt:36 fun applyTo(editor, text)`，在预览编辑器上调用 `ui/SqlPreviewPanel.kt:2592`（另 `:1253`/`:2422`/`:2439`） | 同左 |

### 1.4 EP 形态核实（**本轮新增，原为待验证前提 1**）

对 243 平台发行版（`ideaIU-2024.3.7.1-win/lib/app-client.jar`）实测：

| 项 | 实测值 |
|---|---|
| 扩展点名 | **`com.intellij.codeInsight.inlayProvider`**（另有较新的 `com.intellij.codeInsight.declarativeInlayProvider`，以及 `codeInsight.inlayActionHandler`、`config.inlayGroupSettingProvider`、`config.inlaySettingsProvider`） |
| bean | `com.intellij.codeInsight.hints.InlayHintsProviderExtensionBean`，`dynamic="true"`，`attribute="implementationClass"`，`implements="com.intellij.codeInsight.hints.InlayHintsProvider"` |
| **`language` 属性** | **存在**（bean 含 `getLanguage`/`setLanguage`）→ `<codeInsight.inlayProvider language="SQL" implementation="…"/>` 形态成立 |
| 接口方法 | `InlayHintsProvider` 含 `getCollectorFor`、`isLanguageSupported(Language)`、`createSettings`、`createConfigurable`、`getName`、`getKey`、`getPreviewText` |

**口径修正 4**：原计划把「EP 名与属性」列为第一个待证伪前提（仓内零 inlay 注册先例）。现已坐实——**EP 存在、带 `language` 属性、接口方法齐备**。仍待实测的是 **253/262 三平台可用性**与**自定义 highlighter 是否影响 language 判定**（见 §八 待验证前提）。

## 二、目标与非目标

**目标**（仅在 D1 选「做」时）：在预览面板的 INSERT 语句上，对每个 VALUES 表达式 / SELECT 投影挂**目标列名**提示，使「列-值错位」一眼可见；投影项已自带同名列别名时不重复提示。

**非目标**：不影响用户自己的 `.sql` 文件（EP 是 language 级的，必须在 provider 内做面板判定）；不在裸 Document 降级级渲染（无 PSI）；不改 SQL 文本或渲染结果；不做非 INSERT 语句的列提示（SELECT/UPDATE 的目标列语义不同，另议）；不追 DBX 的 `insertValueHints.ts` 的 CodeMirror 专属解析形态（我方走 PSI）。

## 三、方案

### 决策点 D1（**须定案，实施前必须定**）

| 选项 | 内容 | 代价 | 推荐度 |
|---|---|---|---|
| **A（做，EP + 面板判定）** | 新增 `InlayHintsProvider<NoSettings>` + `plugin.xml` 注册 `language="SQL"` + provider 内以 userData/文件名判定只作用于预览编辑器 | 2~3 文件（provider + `plugin.xml` + 面板接线/模型读点）+ 用例 | ★★ |
| **B（做，替代路径：编辑器自定义元素）** | 不走 language 级 EP，改用编辑器级装饰（参照 `SqlAutoFolding.kt:36` + `SqlPreviewPanel.kt:2592` 的先例） | 成本显著更高（须自管渲染与生命周期） | ★ |
| **C（不做）** | 只登记「EP 名与副作用未核实」结论 | 0 | ★★ |

**D1 定案（2026-09-20）**：**选 A**。EP 形态已核实（§1.4），原「先核实再定」的步骤已完成大半——剩余的三平台可用性核实并入 T-ICI-01 的收尾。**若 253/262 实测发现 EP 形态不符或作用域无法可靠限制，仍按原预案退 C**（如实登记，不硬做）。

### 任务分解（D1=A 时）

**T-ICI-01（EP 核实，**243 已完成，253/262 待收尾**）**：243 平台实测结论见 §1.4（EP 名、bean、`language` 属性、接口方法齐备）。**剩余**：253/262 两平台的 EP 可用性实测；`EditorEx` 上是否需要 `InlayModel` 显式启用；自定义 `SqlSyntaxHighlighter` 是否影响 language 判定。**判据**：能否可靠地把作用域限制在预览编辑器。

**T-ICI-02（面板判定）**：provider 内用 §1.2 的两个锚点判定「是本面板的预览编辑器」；非本面板直接返回空。**同时**：语句类型不是 INSERT 时返回空。**实施定案**：锚点取**合取**（文件名 ∈ 保留名 **且** userData 载体存在），载体**另开专用 Key**（不复用 `SqlCompletionContext.KEY`）——两点理由：① `SqlCompletionContext.KEY` 不是「预览编辑器」专属（`refreshCompletionContext` 在 `:1959-1962` 给原生控制台编辑器也挂了同一个 Key，复用即把「是不是预览编辑器」的判定让给文件名单独承担）；② 载体要携带语句类型，`SqlCompletionContext` 无该字段。降级链第三级（裸 Document）无 PSI ⇒ provider 不被调用（验收 4 的机制面）。

**T-ICI-03（列名来源）**：从预览模型取 INSERT 的目标列与值表达式——**落点须实施时定位**（`XmlStatementKind.INSERT` 的语句节点里列名列表与 VALUES/SELECT 投影的对应关系）。**照抄 DBX 的去重规则**：投影项已自带同名列别名（尾随 `AS` 或隐式别名，点名不算）时丢弃提示。

**T-ICI-04（模型读点与用例）**：新开 `xmlModel.kind` 的第一个读点（`model/PreviewResult.kt:49` → 面板），并加用例：①`kind == INSERT` 时提示生成；②`kind != INSERT` 时不生成；③投影已带同名列别名时不重复；④非本面板编辑器不生成。

**T-ICI-05（文档）**：CHANGELOG `[Unreleased]` + 变更记录行 + 第九轮报告 §8 #45 落地备注；`docs/架构设计/03-开发与测试指南.md` 若有必要补 EP 注册说明。

## 四、验收标准

1. **作用域正确**：用户自己的 `.sql` 文件（即使语句是 INSERT）**不出现**本插件的 inlay 提示（用例 ④ + 真机确认）。
2. `kind == INSERT` 的预览语句出现目标列提示；非 INSERT 不出现（用例 ①②）。
3. 投影已带同名列别名时不重复提示（用例 ③）。
4. 裸 Document 降级级不渲染（如实登记为已知边界，不是缺陷）。
5. SQL 文本与渲染结果**逐字节不变**（inlay 是显示层）。
6. 守卫：`PluginXmlStructureGuardTest`（新 EP 注册结构）绿；若加 i18n 文案则 `SettingsRealityGuardTest` 断言四（幽灵键）绿；全量 testShards + testDatabase + agent 绿。

## 五、真机走查清单（留人工）

**本任务验收主体是真机**（inlay 渲染与 EP 生效 headless 测不到）：

1. 真机预览一条 INSERT mapper：每个值表达式上方/内联显示目标列名。
2. 真机打开用户自己的 `.sql` 文件写一条 INSERT：**不出现**本插件提示（作用域判定的关键判据）。
3. 真机多列 INSERT（如 10 列）：提示不互相遮挡、可读。
4. 真机 `INSERT … SELECT` 且投影已带同名列别名：不出现重复提示。
5. 真机在 243 / 253 / 262 三个平台各看一次（EP 可用性差异）。
6. 真机触发降级链第三级（裸 Document）的场景：确认无 inlay 且无异常（边界确认）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **作用域泄漏到用户文件**（本任务最大风险） | EP 是 language 级的，注册即对所有 SQL 编辑器生效；判定不可靠会污染用户自己的 `.sql` | T-ICI-02 双锚点判定；验收 1 与 §五 第 2 条为硬判据；核实不可靠则退 D1=C |
| EP 形态未核实 | 仓内无 inlay 注册先例，EP 名/属性/三平台可用性未验 | T-ICI-01 先行；核实失败则退 C |
| 降级链下行为不一致 | 第三级无 PSI 不渲染 | 如实登记为已知边界（验收 4），不假装全覆盖 |
| 列-值对应关系判定复杂 | 动态 INSERT（`<foreach>` 生成多值组、`<trim>` 拼列名）下列名与值的对应可能非静态可判 | 静态不可判时**不提示**（宁缺勿错，与 `ParamKind` 的 UNKNOWN 口径一致），登记为已知边界 |
| 与既有 gutter provider 混淆 | 两者都是「按语法挂提示」 | 分工写清：gutter 是行级标记，inlay 是行内提示；注册 EP 不同 |

## 七、实施顺序

```
T-ICI-01（EP 核实——决定做/不做）→ [D1 定案]
  D1=A: T-ICI-02（面板判定）→ T-ICI-03（列名来源与去重）→ T-ICI-04（模型读点与用例）
        → T-ICI-05（文档）→ 真机走查 §五
  D1=B: 改走编辑器级装饰路径（成本重估后另开计划）
  D1=C: 登记结论收口
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|-------------|
| T-ICI-01 | 本次 | **243 平台逐行核实**（不只 javap）：EP `com.intellij.codeInsight.inlayProvider`；bean `InlayHintsProviderExtensionBean` 的 `language` 与 `implementationClass` **都是 `@RequiredElement`**；`InlayHintsSettings.hintsShouldBeShown` 三道闸（全局 / 语言 / 单 provider）默认全开，且 EP **不注册 `settingsKeyId` 时 `computeIsEnabledByDefault` 走「无匹配 bean → true」**分支 ⇒ 本 provider 默认生效、无需设置页。**253/262 仍未实测**（未验证面，见下） |
| D1 定案 | 本次 | **A（EP + 面板判定）** 落地；必做防护 = 双锚点**合取**（文件名 ∈ 保留名 且 userData 载体存在），载体另开专用 Key（不复用 `SqlCompletionContext.KEY`，理由见 T-ICI-02） |
| T-ICI-02 | 本次 | 锚点形态见 §1.2 订正表；保留名两个常量与面板建文件处**同源**（`PreviewInlayScope.PREVIEW_FILE_NAME` / `MEMORY_PREVIEW_FILE_NAME`，面板 `projectPreviewFile` / `createInMemoryPreviewPsi` 直接引用）；测试缝名 `preview.sql` 不列入保留名 |
| T-ICI-03 | 本次 | 列名来源 = **预览文本自身**（`INSERT INTO t (列清单) VALUES/SELECT`），**不读 PSI 树**：本插件测试环境有意排除 database/sql 插件 jar（`buildSrc/src/main/kotlin/mpv-testing.gradle.kts:22-31`），SQL 语言与 SQL FileType 都不注册，PSI 结构不可依赖；且 XML INSERT 渲染后列名与值都在文本里 |
| T-ICI-04 | 本次 | `xmlModel.kind` 首读点 = 面板 `attachPreviewStatementKind`（`current?.xmlModel?.kind`），双载体写入（Editor + PSI 文件）；两条渲染路径（新建 `assemblePreviewEditor` / 复用 `refreshCompletionContext`）都写（漏一条即该路径静默不出现） |
| T-ICI-05 | 本次 | 文档落位：本文件 §1.2 订正 + §三 T-ICI-02 定案 + 本节；CHANGELOG 条目文本交收口阶段统一写入（本批并行约定不写 CHANGELOG / 变更记录 / 架构设计） |

**实施口径（与计划原文的偏离与取舍）**：

1. **多组 VALUES 元组逐组提示**（原文只写「每个 VALUES 表达式」）：`foreach` 生成的批量 INSERT 逐行标注，与 `SqlAutoFoldPlanner` 的「每组元组独立判定」口径一致；行内提示是只读显示层，重复列名不改语义。
2. **别名去重的「点名不算」读法（原文歧义，两种读法都实现）**：原文「尾随 `AS` 或隐式别名，点名不算」可读作 (a) 裸列名不是别名（`SELECT a` 照常提示）；(b) 限定名末段不算别名（`SELECT t.a` 照常提示）。两条互不冲突、都已落地：隐式别名要求「前驱 token 与末尾标识符之间有空白**且**前驱是值结尾」，故单 token 投影无别名、`t.a` 的 `.` 也不构成隐式别名。各有用例钉住（`test点名不算别名_裸列名照常提示` / `test点名不算别名_限定名末段不当别名`）。
3. **双锚点取合取，不是「优先 / 兜底」**：原文 §三 T-ICI-02 是「userData 优先，文件名兜底」（析取）。析取下「文件名命中」可单独放行，而 `mpsql-preview.sql` 这个名字用户完全可能自己造（`.idea/sql-visualizer/preview/` 之外的同名文件）；合取后两条同时成立才放行，用户文件被文件名锚点独立拦下（用例 `test用户文件即使被挂上语句类型载体也不产提示`）。
4. **非 XML 预览链不提示**：语句类型源为 `xmlModel.kind`，非 XML 链（Wrapper 批量 INSERT / `@Insert` 注解 / JdbcTemplate）无该模型 ⇒ 写 null ⇒ 不提示。本轮**不扩大**到文本级语句识别（`SqlTypeClassifier` 是另一条口径，扩面须另开计划）。
5. **不注册 `settingsKeyId`**（不新增设置页）：平台缺省即开启（见 T-ICI-01），用户可用平台 inlay 全局 / 语言级开关关闭。与 O-44a（空白符开关）不同——那条是面板渲染层、本插件自己的设置项；本条是 IDE 编辑器提示层，走平台既有开关。
6. **无 inlay 时返回 null（不产 collector）**而非「产出空列表」：平台 `getCollectorWrapperFor` 对 null 直接不建 collector，零条目零开销。
7. **collector 一次性闸门**：平台两处调用形态不同——真机 pass 按 Divider 分段元素逐个调用（返回 false 即移出调度），平台测试链 `collectTraversing`（`SyntaxTraverser.psiTraverser`）**忽略返回值**对每个 PSI 元素都调一次。无闸门会按元素数量重复投提示。

**与 T-M32 计划的 inlay 注册口径冲突（登记）**：`docs/开发计划/20260815-2130-T-M32-Wrapper实时提示-开发计划.md`（状态：立项、暂缓实施）也注册 inlay，口径是 **`InlayParameterHintsProvider`（`com.intellij.codeInsight.parameterNameHints` EP，Java 语言，作用于用户自己的代码，2025.3+ API 待核）**；本任务是 **`InlayHintsProvider<NoSettings>`（`com.intellij.codeInsight.inlayProvider` EP，SQL 语言，只作用于本插件预览编辑器）**。取舍：**provider 形态统一到新 EP**——平台对该接口的 javadoc 明写「新代码优先用 declarative 形态、`InlayParameterHintsProvider` 是参数名提示专用机制」，且本轮实测 `inlayProvider` EP 在 243 可用（T-M32 那条「243 兼容待核实」的顾虑对本 EP 不成立）。**作用域口径不统一且不应统一**：T-M32 的目标就是用户 Java 代码（必须命中用户文件），本任务的目标是插件自建预览编辑器（必须不命中用户文件）——两条计划的作用域判据方向相反，各自在计划内写清即可；共享的只是 EP 形态与「作用域判定必须显式、不能靠 language 注册面」这条纪律。

**待验证前提（实施后收口）**：

1. ~~inlay EP 名字与属性无仓内先例~~ → **243 已逐行核实**（EP 名、bean、两个 `@RequiredElement`、接口方法齐备、默认开启机制）。**253/262 未实测**（未验证面）。
2. `EditorEx` 是否需显式启用 `InlayModel`、自定义 `SqlSyntaxHighlighter` 是否影响 language 判定 → **headless 不可证**（测试环境不注册 SQL 语言/FileType，`.sql` 在测试里不是 SQL PSI），只能真机走查（§五）。**已收窄的部分**：provider 不读 PSI 结构、不读语言，判据只有「文件名 + userData + 文档文本」三样，故 highlighter 与 language 判定都不进入本 provider 的判据面；`InlayModel` 也无需显式启用——`InlayHintsPass.applyCollected` 直接对 `editor.inlayModel` 加条目（243 sources 实证），测试链已在真实 `InlayModel` 上取到提示文本。
3. `SqlCompletionContext.KEY` userData 稳定性 → **不再作为锚点**：`refreshCompletionContext` 给原生控制台编辑器也挂了同一个 Key（`ui/SqlPreviewPanel.kt:1959-1962`），复用会把「是不是预览编辑器」让给文件名单独承担；自建专用 Key 挂 Editor + PSI 文件双载体（Editor 侧随编辑器对象稳定，`:2553-2554` 既有注释口径）。
4. DBX 去重规则的确切判据 → 按我方词法形态重定义（`AS` 别名 + 隐式别名；`*` 投影不提示），**未逐行核对 DBX 实现**（其 CodeMirror 解析形态与我方词法不同）。
5. **本轮新暴露的未验证面（真机走查须优先确认）**：inlay pass 由 daemon 驱动，`InlayHintsPass.doCollectInformation` 首行是 `HighlightingLevelManager.shouldHighlight(myFile)` 门；一级预览文件位于 `<项目>/.idea/sql-visualizer/preview/mpsql-preview.sql`——**该路径是否被判为可高亮，headless 无法证实**（测试环境文件索引形态与真机不同，且 `ProjectFileIndex` 读法本身是慢操作、EDT 上直调会撞平台断言）。若真机上主路径 inlay 不出现，**先查这道门而非锚点**（锚点已由用例钉死）。

