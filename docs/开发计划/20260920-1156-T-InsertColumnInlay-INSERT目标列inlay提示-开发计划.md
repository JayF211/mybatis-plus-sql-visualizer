# T-InsertColumnInlay INSERT 目标列 inlay 提示 — 开发计划

- 日期：2026-09-20 11:56
- 任务编号：T-InsertColumnInlay（第九轮报告 §9.4 **O-44b**，P3，**含决策点 D1**）
- 来源：第九轮报告 §8 DBX 增量清单 **#45**——DBX `3cb1faf`：INSERT 目标列 inlay 提示（`insertValueHints.ts:23` `InsertValueSource` 带 alias 字段、`:286` `selectProjectionAlias` 去重）——INSERT VALUES / INSERT…SELECT 逐表达式挂目标列名 inlay；**本轮新增规则**：投影项已自带同名列别名（尾随 `AS` 或隐式别名，点名不算）时丢弃提示避免重复
- 性质：**编辑器提示增量**——不改 SQL、不改渲染；让多列动态 INSERT 的「列-值错位」一眼可见
- 实施分支：自 master 拉任务分支 t-insert-column-inlay
- 验证基线：master `22d7c49`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 一、背景与依据（现状核实，含三处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「预览面板对 INSERT 挂 IDEA `InlayHintsProvider`」 | **可行，但仓内零先例（口径修正）**：`grep -rni "inlay" src/main src/test` 仅命中 `ui/diagram/DiagramRenderer.kt:239/248/253` 的局部变量 `joinLayouts`（假阳性），**无任何 `InlayHintsProvider`/`InlayHints` 使用**。平台 API 在 243 可用（实测 `lib/app-client.jar`：`com.intellij.codeInsight.hints.InlayHintsProvider<T>` 含 `getCollectorFor(PsiFile, Editor, T, InlayHintsSink)`/`createSettings`/`getName`/`getKey`/`getPreviewText`/`createConfigurable`/`isLanguageSupported`；`InlayHintsSink` 含 `addInlineElement`/`addBlockElement`） | **EP 名与属性须实施时核实**（仓内无注册先例） |
| 2 | 「挂在本面板预览编辑器上」 | **有副作用须处理（口径修正）**：inlay 的 EP 是 **language 级**（须 `language="SQL"`，参考既有 `plugin.xml:206-212` 的 lineMarkerProvider 形态）——注册即对 IDE 内**所有** SQL 编辑器生效（**含用户自己的 `.sql` 文件**）。必须在 provider 内做「只认本面板预览编辑器」的判定 | 落点须含面板判定（见 §三 T-ICI-02） |
| 3 | 「`statementKind` 可辨识」 | **成立但零消费者**：`xml/XmlMapperParser.kt:7` `enum class XmlStatementKind { SELECT, INSERT, UPDATE, DELETE }`（判定 `:196-200 kindOf(tagName)`，载体 `:12 val kind`）→ 模型暴露 `xml/XmlPreviewModel.kt:59 val kind`（`from()` 注入 `:220`）→ 面板可达 `model/PreviewResult.kt:49 xmlModel`。**但 `xmlModel.kind` 当前零读点**（`ui/SqlPreviewPanel.kt:3223` 的 `kindFor(source)` 返回的是 `ParamKind` 参数类别，不是语句类型） | 本任务会**新开第一个 `xmlModel.kind` 读点**，须一并加用例 |

### 1.1 关键差异：inlay 受降级链影响

预览编辑器有三级降级链：临时 PSI 文件 → 内存 PSI（`ui/SqlPreviewPanel.kt:2628 createInMemoryPreviewPsi`）→ 裸 Document（`:2601 fallbackPreviewDocument`）。**inlay provider 的 `getCollectorFor` 首参是 `PsiFile`**，故**第三级（裸 Document）无 PSI ⇒ 该级不渲染 inlay**（与 O-44a 的空白符开关不同，后者是渲染层、三级都在）。

### 1.2 面板判定可用的锚点（实测）

| 锚点 | 证据 |
|---|---|
| 预览编辑器已挂 `SqlCompletionContext.KEY` userData | `ui/SqlPreviewPanel.kt:2556-2557` |
| 预览文件固定命名 `mpsql-preview-*.sql` | `ui/SqlPreviewPanel.kt:2628-2636` |

### 1.3 最贴近的既有范式（照抄对象）

| 范式 | 形态 | 证据 |
|---|---|---|
| 13 个 gutter `LineMarkerProvider` | `ui/gutter/XmlSqlGutterLineMarkerProvider.kt:31-56`（`getLineMarkerInfo` O(1) 语法判定）+ `:60-63`（`gutterEnabled()` 读设置） | 同左 |
| 注册形态 | `plugin.xml:196-245`（`<codeInsight.lineMarkerProvider language="JAVA|XML" …/>`；`:206-212` 是 language 属性样例） | 同左 |
| 编辑器级装饰先例（替代路径） | `ui/highlight/SqlAutoFolding.kt:36 fun applyTo(editor, text)`，在预览编辑器上调用 `ui/SqlPreviewPanel.kt:2592`（另 `:1253`/`:2422`/`:2439`） | 同左 |

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

**D1 建议**：选 **A**，但**先做 T-ICI-01（EP 核实）**——EP 名与属性在 243/253/262 三平台的形态未在仓内找到先例，若 EP 形态与预期不符（如属性名变化、需额外 settings 注册），须重新评估；若核实发现副作用不可控（无法可靠判定「只作用于本面板」），退 **C**。

### 任务分解（D1=A 时）

**T-ICI-01（EP 核实，先做）**：核实 `com.intellij.codeInsight.inlayProvider`（或实际 EP 名）的注册形态与属性、三平台可用性、是否需要 `createSettings`/`createConfigurable` 的最小实现。**判据**：能否可靠地把作用域限制在预览编辑器。

**T-ICI-02（面板判定）**：provider 内用 §1.2 的两个锚点（`SqlCompletionContext.KEY` userData 优先，文件名 `mpsql-preview-*.sql` 兜底）判定「是本面板的预览编辑器」；非本面板直接返回空。**同时**：`xmlModel.kind != INSERT` 时返回空。

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
|------|------|------|
| T-ICI-01 | （实施后填） | EP 名/属性/三平台可用性结论须回填 |
| D1 定案 | （实施后填） | A/B/C 选择与理由须回填 |
| T-ICI-02 | （实施后填） | 面板判定锚点的最终形态与可靠性须回填 |
| T-ICI-03 | （实施后填） | 列名来源落点、静态不可判的边界清单须回填 |
| T-ICI-04 | （实施后填） | `xmlModel.kind` 首读点落位与四条用例读数须回填 |
| T-ICI-05 | （实施后填） | 文档落位与门禁读数须回填 |

**待验证前提（实施者先证伪再动手）**：

1. **inlay EP 的名字与属性未在仓内找到先例**（`plugin.xml` 无 inlay 注册）——须对 243/253/262 发行版核实，这是本任务第一个待证伪点。
2. `EditorEx` 上 inlay 是否需要 `InlayModel` 显式启用、以及预览编辑器用的自定义 `SqlSyntaxHighlighter`（`ui/SqlPreviewPanel.kt:2564-2572`）是否影响 language 判定（预览文件由 `getStdFileType("SQL")` 建，语言**预期**为 SQL）——未实测。
3. 「`SqlCompletionContext.KEY` userData 可作判定锚点」来自 `ui/SqlPreviewPanel.kt:2556-2557` 的静态证据——须确认该 userData 在编辑器生命周期内**始终存在**（若被清理则判定失效，须改用文件名兜底为主）。
4. DBX 的「投影自带同名列别名时不提示」规则的**确切判据**（尾随 `AS` / 隐式别名 / 点名不算）来自 `insertValueHints.ts:286` 的转述，未逐行核对其解析实现——实施时须按我方 PSI 形态重新定义，不照抄。
