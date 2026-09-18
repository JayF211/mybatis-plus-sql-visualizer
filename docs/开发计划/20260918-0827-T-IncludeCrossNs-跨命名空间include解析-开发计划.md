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

（未实施）
