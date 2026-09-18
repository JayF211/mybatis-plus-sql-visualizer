# T-IncludeCrossNs 跨命名空间 include 解析 — 开发计划

- 日期：2026-09-18 08:27
- 任务编号：T-IncludeCrossNs（第七轮报告 §9.4 **O-30**；原拟定名「include 展开现状核实与补强」——立项锚点核实后改名收窄）
- 来源：第七轮报告 §7 zMyBatis 精华清单 **Z-8**——跨文件 refid 依赖图解析（namespace 限定、歧义检测、环检测，XmlStatementSourceGraphResolver.kt:86-125）
- 性质：预览准确性补强——同文件 include 已完整支持（既有能力），本任务只补跨命名空间引用
- 实施分支：合流后自 master 拉任务分支 t-include-cross-ns（前置项 T-MergeDev2Master 策略决策）
- 验证基线：合流后 master 全量 testShards 绿（以合流时钉死计数为准）

---

## 一、背景与依据

第七轮报告立项时的假设是「XML 预览未解析跨文件 include，是预览准确性直接补强」。锚点核实的结论要好得多：**同文件 include 展开已完整实现并有测试**（T-M24-03，复刻 MyBatis 3.5.19 `XMLIncludeTransformer.applyIncludes`）——`<sql id>` 片段收集、嵌套 include、property 静态替换、动态 refid（`refid="${c}"`）、环检测+深度上限全部在位（`XmlIncludeExpander.kt`）。唯一缺口是**跨命名空间 refid（`namespace.id`）**：查表失败 → errors 记录 + 移除 include 节点 + 预览不中断（`XmlIncludeExpander.kt:50-53`），即当前是「显式降级」而非「崩溃」，但用户看到的预览少了片段内容。本任务把这条降级路径升级为真实解析。

## 二、现状核实（master 实测，2026-09-18）

| 面 | 现状 | 位置 |
|---|---|---|
| 同文件展开 | 完整支持：fragments 表收集、就地展开、property 继承/覆盖、环检测 chain+MAX_DEPTH=20 | `xml/XmlIncludeExpander.kt:16-53`；`xml/XmlMapperParser.kt:51-58` |
| 跨命名空间 refid | 未支持：查表失败 → errors + 移除节点（有测试钉住降级行为） | `XmlIncludeExpander.kt:50-53`；`XmlIncludeExpanderTest.kt:83-93`；CHANGELOG.md:678 |
| 展开时机 | `XmlScriptBuilder.parse` 之前就地展开 DOM，include 不进脚本构建器 | `XmlMapperParser.kt:55-58` |
| 注解 `<script>` include | 不展开（报 Unknown element），与 MyBatis 运行时一致，已文档化——维持现状 | `xml/AnnotationSqlParser.kt:10-11,37-43` |
| 跨文件定位先例 | 文件发现用 FileTypeIndex+后缀过滤（`FilenameIndex.getFilesByName` 通配符平台失效有注释）；namespace → Java 定位用 `JavaPsiFacade.findClass(namespace, allScope)` | `psi/jpa/HbmXmlEntityResolver.kt:157,26,47`；`ui/XmlEwResolver.kt:50-56` |
| 架构约束 | `XmlMapperParser` 纯文本输入零 PSI 依赖；但 `parse` 会就地 expand 语句 DOM——建片段索引需「只收集不展开」的轻量入口 | `XmlPreviewModel.kt:41`；`XmlMapperParser.kt:58` |

## 三、方案

**T-IC-01（轻量片段收集入口）**：`XmlMapperParser` 新增 `collectFragments(fileText): Map<String, String>`（只扫 `<sql id>` 顶层片段、不 expand 语句 DOM、零 PSI 依赖，复用既有 DOM 解析代码路径）。

**T-IC-02（跨文件解析器，psi 层）**：新增 `XmlCrossNsFragmentResolver`（psi 层，允许 PSI）：入参当前文件文本 + project；流程：①从当前 mapper 根取本文件 namespace；②对 `refid` 含 `.` 且本文件查表失败的 include，按最后一个 `.` 切 targetNamespace/fragmentId（对齐 zMyBatis 语义）；③用 FileTypeIndex 找同后缀 mapper 文件、`collectFragments` 收集、按其根元素 namespace 匹配 targetNamespace 建跨文件片段表；④结果交 `XmlIncludeExpander` 合并进 fragments 表后再展开。歧义（多文件同 namespace/同 id 冲突）与缺失 → 沿用现有 errors 降级链（文案注明「跨命名空间片段解析失败：歧义/未找到」），不新增 UI 通道。环检测天然由既有 chain+深度上限覆盖（合并后的展开流程不变）。

**T-IC-03（接线与范围定界）**：`XmlPreviewService.preview` 纯文本链（无 project）维持现状降级；PSI 链（`XmlSqlGutterLineMarkerProvider` 等经 `file.text` 进来的调用点）注入 resolver 结果。v1 只做 `namespace.id` 全限定 refid，不做裸 id 全局搜索（歧义面大、与 MyBatis 运行时语义不符）；注解 `<script>` include 维持现状。

**T-IC-04（测试与文档）**：`XmlIncludeExpanderTest` 现有 8 用例是行为锚点不得破坏（尤其「跨命名空间refid_报错并移除」在**解析不到目标文件**场景的降级语义——无 project/无索引时必须仍走旧降级）；新增用例：跨文件命中展开、property 穿透、歧义降级、环跨文件、namespace 不匹配降级。CHANGELOG [Unreleased] + 变更记录行 + 第七轮报告 Z-8 落地备注回填。

## 四、验收标准

1. 既有 8 用例零改动全绿（降级语义保住）；新增用例全绿。
2. 纯文本链（无 project）行为逐字节不变；PSI 链跨文件 include 正确展开。
3. `collectFragments` 不触发语句展开（用例断言：含动态标签的 mapper 只收集片段不渲染语句）。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 两 mapper 文件跨命名空间 include 真实项目形态：预览含片段内容、无 `⚠` 降级行。
2. 目标文件关闭/删除：回退降级警示，预览不中断。
3. 大项目（多 mapper）预览耗时无明显回退（索引遍历有界）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 遍历开销 | 大项目全 mapper 扫描建片段表 | 仅在本文件查表失败且 refid 含 `.` 时才触发跨文件收集（惰性）；收集范围可先限定与当前文件同目录+同模块 |
| namespace 归属多文件 | 同 namespace 声明在多个文件（非法但存在） | 歧义走 errors 降级，与 MyBatis 运行时「后注册覆盖」的差异如实文档化 |
| 纯文本链覆盖缺口 | `XmlPreviewService.preview(fileText,…)` 无 project 拿不到索引 | v1 明确只覆盖 PSI 链；纯文本链降级现状保留（文档写明边界） |

## 七、实施顺序

```
T-IC-01（轻量收集入口）→ T-IC-02（跨文件解析器）→ T-IC-03（接线）→ T-IC-04（测试与文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

- 实施分支/工作树：`t-include-cross-ns`（工作树 `mpsv-icns`），基线 `8a3a6ff`（dev 合流后 master）
- 提交：`1ea06f7`（实施提交：代码 + 测试 + CHANGELOG + 变更记录总册；本 §八 回填为紧随的 docs 提交）
- 门禁：定向 `XmlIncludeExpanderTest` 16 例绿（8 既有**零改动** + 8 新增）、`XmlCrossNsFragmentResolverTest` 10 例绿（LightPlatform：真实 FileTypeIndex + PSI + 项目内真实 mapper 文件）、`testFast` 全量绿；全量 `testShards` **4965/0/0/6 绿**（432 套件，3 片并行 3m12s）
- 环境备注（诚实口径）：前两轮 testShards 分别撞 11 例 / 8 例失败，全部为机器级剪贴板争用（`cannot open system clipboard`，SqlPreviewPanelTest / XmlPreviewPanelTest / PluginSettingsPanelBindingTest 等面板类）与 `Container is already disposed`——同机另有三个工作树（mpsv-stfix/mpsv-eap/mpsv-sig）并行跑测试；两轮失败集不同（非确定性）、两处面板类隔离复跑即绿、与本任务改动零关联（不涉剪贴板/容器生命周期），第三轮全绿 4965/0/0/6

任务落点：

| 任务 | 落点 |
|---|---|
| T-IC-01 | `xml/XmlMapperParser.kt`：`collectFragments(fileText)`（公开，id → 片段内部 XML 文本）、`collectMapperFragments`（internal，附根 namespace）、手写片段序列化（保留子标签/属性/CDATA/注释，文本与属性值按 XML 规则转义） |
| T-IC-02 | 新增 `xml/CrossNamespaceFragments.kt`（片段表 + 失败原因表）；新增 `psi/XmlCrossNsFragmentResolver.kt`（FileTypeIndex 扫描 + PSI 预筛 + `collect` 纯逻辑归并）；`xml/XmlMapperParser.scanIncludes`（当前文件扫描：根 namespace / 本文件片段 id / 含 `.` 的 refid，property 已静态替换）；`xml/XmlIncludeExpander.expand(..., crossNamespace)` 合并展开（跨文件片段文本经 wrapper 解析 + `Document.importNode` 插入本文档） |
| T-IC-03 | `ui/XmlPreviewService.preview` 单点接线（`resolve(fileText, project)`；`project == null` 直接空表）；注解 `<script>` 链与 `MapperCheckTool`（纯文本、无 project）维持现状 |
| T-IC-04 | 测试两处（上述两文件）；文档三处（本 §八 + CHANGELOG [Unreleased] Added + 变更记录总册） |

实施偏离（对照 §三 方案与 §六 风险）：

1. **目标文件定位用 PSI 而非纯 VFS 文本**：`XmlFile.rootTag` 取 namespace 做预筛、`file.text` 交 `collectFragments`——与 `MapperXmlLocator`/`HbmXmlEntityResolver` 先例同款，且 `file.text` 是**内存文本**（未保存编辑参与解析，比磁盘版本更准）。代价是候选文件各一次 PSI 解析（平台缓存兜底）。
2. **§六「收集范围限定与当前文件同目录+同模块」未采用**：解析器入参只有文件文本（无 VirtualFile），拿不到当前文件路径；改为 projectScope + PSI 预筛（非 mapper / namespace 不匹配的 XML 不做 DOM 解析），并保持惰性触发（仅当本文件存在未解析的全限定 refid 才遍历）。
3. **片段文本序列化未用平台 `LSSerializer`**：实测其 `writeToString` 对独立 CDATA 节点不写闭合 `]]>`、对 Element 节点附带 XML 声明，无法直接往返；改手写序列化，CDATA/实体/属性换行按 XML 规则保真（用例钉住）。
4. `XmlIncludeExpander` 两个既有私有函数（`propertyMap`/`resolveRefid`）由 `private` 改 `internal`，供当前文件扫描复用（不复刻 property 替换语义）。
5. 旧降级文案在**无 project / 无失败原因**路径逐字节保留（`cross-namespace include is not supported.`），仅在解析器给出具体原因时改走新文案——对齐验收标准 2「纯文本链行为逐字节不变」。
6. **第七轮报告 §12 Z-8 落地备注未回填**：本批七份计划（Z-2/Z-3/Z-4/Z-6/Z-7/Z-8/#35）都要求回填同一报告文件，七个并行工作树同改一文件必成冲突；落地事实已写入 CHANGELOG 与变更记录总册，报告回填建议合流后由主调度统一批量做。

未验证项 / 真机待走查（§五 三项全部留人工）：

- 真实项目形态跨命名空间 include 预览（含片段内容、无降级行）、目标文件关闭/删除后回退降级、大项目多 mapper 预览耗时——本机未走查。
- 目标文件未落盘（新建未保存）不在 FileTypeIndex 内 → 该 refid 降级；同 namespace 声明在多文件按歧义降级（MyBatis 运行时为后注册覆盖，差异已入 CHANGELOG）。
- 跨文件片段内的**裸** refid 只查引用方本文件片段表（MyBatis 同语义）；该片段只存在于同 namespace 的**其它**文件时降级——v1 边界，补 `当前namespace.裸id` 候选需展开期持有当前 namespace。
- MCP `MapperCheckTool`（纯文本无 project）与注解 `<script>` include 维持现状。

现状核实表勘误（§二 行号锚点随 dev 合流漂移，行为描述与当前代码一致）：

- 跨命名空间边界行由 `CHANGELOG.md:678` → 现 758（M24 版本节，历史记录不改写）；`HbmXmlEntityResolver.kt` 的 FileTypeIndex 用法由 `:157` → 现 `:167-175`；`XmlMapperParser.kt:51-58`（片段收集 + 语句展开）→ 现 `:69-79`（本次改动前即 51-58）。
- 另核实到一处计划未列的调用点：`mcp/MapperCheckTool.kt:71` 直调 `XmlMapperParser().parse(text)`（纯文本、无 project），不参与跨命名空间解析（无 IDE 上下文可用），按 T-IC-03 边界维持现状。
