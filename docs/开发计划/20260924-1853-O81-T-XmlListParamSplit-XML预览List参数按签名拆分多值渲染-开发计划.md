# O-81 T-XmlListParamSplit：XML 预览 List 参数按签名拆分多值渲染 — 开发计划

> 立项：2026-09-24 18:53，用户反馈 + 截图（SQL 预览对话框）。
> 用户原话：xml 预览 sql list 的泛型是 string，但是目前识别的明显有问题。
> 截图证据：`#taskTypeList` 输入 `PI,WP`，SQL 渲染为 `task.type IN ('PI,WP')`——整串被当成一个值；
> 期望形态 `IN ('PI', 'WP')`。

## 一、问题与链路定位

截图对应 **XML 语句 gutter 预览流**：`XmlSqlGutterLineMarkerProvider` → `XmlPreviewService.preview`
（`PreviewResult.tableName` = 「表：WMS_MOVE_DOC」元信息、参数 source 为 `taskTypeList` 等签名参数名）。

面板证据：左栏 6 行全部是 `#source` 形态的普通参数行（`buildParamRow`）——无「动态条件」区、
无「foreach 集合」区（foreach 行标签是 `columnLabel(collectionExpr)`，**不带 `#`**，且带 null 复选框）。
⇒ 用户 mapper XML 中**没有 `<foreach>`**，IN 列表是 `IN (#{taskTypeList})` 直写形态
（mapper 方法签名 `@Param("taskTypeList") List<String>`）。

## 二、根因分析

值渲染链（`SqlPreviewPanel`）：

1. 面板 XML 分支 `renderSql()`（~L2374）组装回填提示：
   `inList = index in detectInClauseParams(sql)` —— **只看 SQL 形态**；
2. `renderParamValue`（~L4235）只有 `hint.inList == true` 才按逗号拆多值（M19.6），
   否则单值渲染 → `'PI,WP'`；
3. `detectInClauseParams`（~L4185）从 `?` 回扫：跳空白 → 须紧贴 `(` → **只回退一个可选空格** → 须 `IN`。

**根因两层**：

- **R1（识别缺口，用户点名）**：方法签名已解析出 `taskTypeList` 是 COLLECTION 且元素类别 TEXT
  （`XmlParamTypeResolver` → `ParamTypeInfo(kind=COLLECTION, elementKind=TEXT)`，O-78 已建），
  但回填提示组装**完全没用这个信息**——集合参数的逗号输入是否拆分，全押在 SQL 形态探测上；
- **R2（形态探测缺口）**：`detectInClauseParams` 的回扫窗口只容忍 `IN (` **恰一个空格**；
  `IN` 与 `(` 之间是换行/制表/多空格（mapper 里极常见的排版）即漏判 → `inList=false`。

## 三、待验证前提（实施者先证伪再动手）

| # | 前提 | 验证方式 |
|---|------|----------|
| V1 | 用户 XML 是 `IN (#{taskTypeList})` 直写（无 foreach） | 截图面板形态推断；无法拿到用户 XML 原文，修复须对**任一形态**都生效 |
| V2 | R2 缺口可被最小用例实证：`IN\n(?)` / `IN  (` / `IN\t(` 漏判、`IN (` 命中 | 先红读数（新增 detectInClauseParams 用例在改前跑） |
| V3 | 面板经 `xml.paramTypeFor(source)` 能拿到 COLLECTION + elementKind（真机 mapper 接口可解析时） | XmlParamTypeResolverElementKindTest 已证（List<String>→TEXT）；端到端用例用手工 paramTypes 注入 |
| V4 | 类型驱动拆分不破坏 O-78 foreach 流 | foreach item 键（`__frch_*`）的 `paramTypeFor` 返回元素类别（非 COLLECTION），不会命中无条件拆分分支；O-78 既有用例回归 |

## 四、方案

**类型驱动为主（R1）+ 形态探测加固为辅（R2）**，两段独立生效、互不依赖。

### 4.1 类型驱动拆分（主修复）

面板 XML 分支的提示组装抽为**纯函数**（新文件 `ui/XmlParamBackfillHints.kt`，零平台依赖，可独立单测）：

```
xmlBackfillHints(xml, sources, shapeInListIndices, mappings): List<ParamHint>
```

规则：
- `paramTypeFor(source).kind == COLLECTION` **且** 该 `#{}` 映射未显式声明 `typeHandler` →
  `inList=true`（逗号输入无条件拆多值），`quote = elementKind?.quoteRequired ?: quoteFor(source) ?: true`
  （`List<String>`→引号、`List<Long>`→裸渲染，与 O-78 foreach item 派生同口径）；
- 其余 source 维持现状：`quote = quoteFor(source) ?: true`，`inList = 形态命中`。

### 4.2 detectInClauseParams 加固（辅修复）

`IN` 与 `(` 之间「一个可选空格」放宽为**任意空白串**（换行/制表/多空格）。
`?` 紧贴 `(` 的判定、多 `?` IN 列表仅首位命中、`IN(` 紧凑形态均维持不变（既有测试钉住）。

### 4.3 显式边界（不做 / 维持）

- 显式 `typeHandler` 的集合映射不拆（自定义序列化形态，拆了反而错）；
- 无签名类型信息的参数仍纯走形态探测（宁缺勿错，不按参数名猜集合）；
- 值内逗号转义不支持（与 M19.6 / ListParamRenderer 同口径，如实按逗号切）；
- `IN (?, ?, ?)` 多占位列表第 2+ 位仍不判 inList（foreach 已有逐项回填通道，形态探测扩面会双拆）；
- `JOIN (` 理论误判（`IN` 子串无词边界检查）为存量行为，本次不扩大不收窄。

## 五、实施步骤（先红后绿）

1. 新增 `ui/XmlParamBackfillHintsTest`（纯逻辑，testFast 桶）：COLLECTION+TEXT 拆分/引号、
   COLLECTION+NUMBER 裸渲染、typeHandler 不拆、无类型信息走形态、foreach item 键不命中；
2. `SqlPreviewPanelTest` 追加 `detectInClauseParams` 形态用例：`IN\n(`、`IN  (`、`IN\t(`、回归 `IN (`/`IN(`；
3. `XmlPreviewPanelTest` 追加端到端：`IN (#{taskTypeList})` + paramTypes 注入 COLLECTION/TEXT，
   输入 `PI,WP` → `IN ( 'PI' , 'WP' )`；List<Long> → `IN ( 1 , 2 )`；
4. **先跑新增用例拿失败读数**（红）；
5. 实施 4.1 + 4.2，复跑定向测试转绿；O-78 既有 foreach 用例 + 既有 detectInClauseParams 用例回归。

## 六、验证与收口

- 定向：`--tests` 四类（两新两旧）；纯逻辑新类走 testFast 复核分桶；
- 本改动不动 `build.gradle.kts`/`plugin.xml`/依赖版本，不属大改动，不跑构建校验；
- 全量门禁按测试三档分层由收口阶段统一安排。

## 实施记录

实施：2026-09-24，主分支直接实施（单 commit，同 O-80 惯例）。

### 先红读数（改前，`test` 任务定向，7 例 3 失败）

1. `SqlPreviewPanelTest.testDetectInClauseParams_IN与括号间任意空白命中` →
   `expected:<[0]> but was:<[]>` —— R2 实证：`IN\n(` 漏判；
2. `XmlPreviewPanelTest.testXmlListParamShapeDetectionToleratesNewline` →
   实际产物 `task.type IN ('PI,WP')` —— **端到端复现用户截图形态**（IN 与 ( 间换行的整串单值）；
3. `XmlPreviewPanelTest.testXmlListParamNumberElementsRenderBare` →
   实际产物 `IN ('1', '2')` —— R1 引号半边实证：单空格形态拆分已发生，但 `List<Long>`
   元素被集合恒引号档包引号（elementKind 未参与派生）。

注：`testXmlListParamSplitsCommaInputBySignature`（单空格 + List<String>）改前即通过——
该形态被旧探测命中；用户实踩的是换行排版形态（读数 2）。

### 修复落点

- 新增 `ui/XmlParamBackfillHints.kt`：`xmlBackfillHints(xml, sources, shapeInListIndices, mappings)`
  纯函数——签名 COLLECTION 无条件 inList + 引号按 elementKind 派生；显式 typeHandler 豁免；
  其余 source 维持 `quoteFor ?: true` + 形态命中（升级前行为）；
- `SqlPreviewPanel` XML 分支：提示组装改调 `xmlBackfillHints`（`xmlMappings` 同帧传入）；
- `SqlPreviewPanel.detectInClauseParams`：`IN` 与 `(` 之间「可选一个空格」→「任意空白串」，
  KDoc 登记多 `?` 列表仅首位命中的语义钉住不变。

### 转绿读数

- `testFast` 全桶 exit 0，新类 `XmlParamBackfillHintsTest` 7/0/0/0（零 com.intellij 引用，
  确认进纯逻辑快桶）；
- `test` 定向：`XmlPreviewPanelTest` 14/0/0/0（3 新端到端 + 11 既有 XML 分支回归）、
  `SqlPreviewPanelTest.testDetectInClauseParams*` 4/0/0/0；
- 相邻回归：`SqlPreviewPanelForeachValuesTest` + `SqlPreviewPanelParamKindTest`
  （COLLECTION 空输入在 inList 分支前短路，O-79 三档不受影响）+ `XmlPreviewServiceTest`。

### 边界与既知近似（登记）

- 值内逗号转义不支持（与 M19.6 / ListParamRenderer 同口径）；
- 无签名类型信息（接口不可解析 / Map 入参）时纯走形态通道——形态探测仍可能有未覆盖排版
  （如括号前有块注释），有签名即不依赖形态；
- `JOIN (` 子串误判为存量理论形态，本次不收窄不扩大；
- foreach item 键（`__frch_*`）`paramTypeFor` 返回元素类别（非 COLLECTION），类型拆分不介入，
  O-78 逐项回填语义不变（`foreachItem键不触发集合拆分` 用例钉住）。
