# T-HqlInhCols HQL 继承/抽象类实体列信息未解析降级 — 开发计划

- 日期：2026-09-18 08:23
- 任务编号：T-HqlInhCols（用户报障直派，P1）
- 来源：用户实测——HQL 场景实体类继承父类且中间有抽象类，预览 SQL 出现「列信息未解析，降级为 position.*」降级注释
- 性质：HQL 预览链实体解析健壮性修复——继承/抽象类层级下列清单解析失败面收口
- 实施分支：git 工作树新目录 + 任务分支 t-hqlinh-cols（自 master 拉出）
- 验证基线：master 全量 test 绿（跑前按铁律并行登记 + 独立 sandboxContainer）

---

## 一、背景与依据

用户实测：HQL 预览出现 `列信息未解析，降级为 x.*`，怀疑与「实体类继承了父类并且还有抽象类」有关。立项后对 master（锚点 6e280f2）全链排查，降级注释唯一出处是 `SelectClauseExpander.kt:103`（`renderColumns`），触发条件是 `aliasColumns` 中该别名的列清单为 null——而列清单为 null 的唯一判据是 `table.columns.isEmpty()`（`parseAliasColumns`，SelectClauseExpander.kt:128/134）。因此**一切上游实体解析失败都汇聚到这一行注释**，本次把上游四个缺口逐一收口。

## 二、现状核实（master 实测，2026-09-18）

| 面 | 现状 | 位置 |
|---|---|---|
| 降级注释 | 列空 → `alias.* /* 列信息未解析，降级为 alias.* */`，不区分实体不可解析/列空两种情形 | `converter/jpa/SelectClauseExpander.kt:100-106, 113-148` |
| 实体解析空结果 | `resolve()` 异常路径返回 `TableInfo("", "", emptyList())`，**从不返回 null** | `psi/jpa/JpaEntityResolver.kt:57-58, 78-79` |
| 空表短路 bug | `resolveHqlTable` 路径 1/2 `if (table != null) return table`——非 null 但列空的结果直接短路 hbm 路径 3，无列空回退 | `ui/SqlPreviewService.kt:1669-1696` |
| 实体别名 | HQL `FROM xxx` 的 xxx 按 JPA 语义可为 `@Entity(name=...)` 实体名；`extractEntityNameFromHql` 只提标识符、`findClassByShortName` 只按类名查找，`@Entity(name)` 属性全仓无任何读取点 | `SqlPreviewService.kt:1698-1703, 1744-1752`；grep 证实 |
| 继承链收集 | `collectFieldsIncludingSuper` 沿 `clazz.superClass` 递归、seen 防环，`@MappedSuperclass` 直接父类有测试；多级抽象父类链、父类在库/jar、`@Inheritance(SINGLE_TABLE/JOINED)` 抽象父类均无测试钉住 | `JpaEntityResolver.kt:151-158`；`JpaEntityResolverTest.kt:310-384` |
| 别名替换 | `mapEntityToTable` 按 `table.entityClassName` 文本命中替换实体名→表名；HQL 用 `@Entity(name)` 别名时 entityClassName 不命中，实体名不被替换 | `converter/jpa/HqlConverter.kt:267-292` |
| fallback | 实体不可解析 → `createFallbackTable()`（tableName=unknown_table、列空）→ 照样进展开器 → 必出降级注释 | `HqlConverter.kt:74-108, 356-365` |

## 三、方案

**T-HIC-01（空列回退收口，主修）**：`resolveHqlTable` 三条路径的返回判据从「非 null」改为「非 null 且列非空」——列空结果不直接 return，继续走后续路径；三路径全部落空才返回空列 TableInfo/null。对路径 1（PsiClass 直解）保留非空即回的快路径语义，仅对「解析成功但列空」追加路径 3 回退。改动面仅 `SqlPreviewService.resolveHqlTable` 一处私有方法。

**T-HIC-02（@Entity(name) 实体别名解析）**：
- `JpaMetadataReader` 新增 `entityNameOf(clazz)`：读 `@Entity(name=...)`（jakarta/javax/短名三轨，复用 findFlex），未指定返回 null。
- `resolveHqlTable` 路径 2 查找失败时追加实体别名反查：`PsiShortNamesCache` 拿不到类名时，用 `JavaPsiFacade.findClass` 按全限定名无果后，走「项目内 @Entity(name=className) 注解值匹配」慢路径（复用 EntityClassFinder 的 @TableName 反查同款形态：全量扫 @Entity 注解类、匹配 entityNameOf，结果挂 Project userData 以 modificationCount 失效——照抄既有缓存惯例，不自创）。
- `HqlConverter.mapEntityToTable` 同步：替换判据从「HQL 标识符 == entityClassName」扩为「== entityClassName 或 == entityName 别名」，别名命中同样替换为表名（TableInfo 无 entityName 字段，走 resolve 时把别名记入——最小改法：`mapEntityToTable` 增加 `entityAlias: String?` 参数，由调用链从 `entityNameOf` 传入；纯函数测试形态保持）。

**T-HIC-03（继承链健壮性 + 诊断注释）**：
- `collectFieldsIncludingSuper` 补三类守卫测试（多级抽象父类链、抽象父类 + 泛型基类、父类字段 @Transient 隔代排除）——代码逻辑现有实现已覆盖多级链（superClass 递归），本项以测试钉住为主，不动主逻辑。
- `SelectClauseExpander.renderColumns` 降级注释拆两档：主表别名列空且 `table.entityClassName` 为空 → `/* 实体不可解析，降级为 x.* */`；entityClassName 非空但列空 → `/* 实体 x 列信息未解析（检查继承链父类映射），降级为 x.* */`。文案走硬编码与既有同款（该注释已是硬编码中文，保持一致），测试同步改锚点。
- JOIN 副表别名降级维持现状（TableInfo 只含主表列，属已知边界，不误伤）。

**T-HIC-04（@Inheritance 边界标注，只注释不改行为）**：`resolveInternal` 在 `inheritanceStrategy` 为 JOINED/TABLE_PER_CLASS 时于 TableInfo 附注释说明「父类字段实际存于父表，列清单按单表口径合并，JOINED 语句须自行改写」——不做 JOINED 拆表改写（范围失控），仅防误读。SINGLE_TABLE 合并语义正确，无需处理。

**T-HIC-05（测试与文档）**：
- `JpaEntityResolverTest` 补：三级抽象链（Leaf → abstract Mid → abstract Base[+@MappedSuperclass]）字段收集顺序、隔代 @Transient 排除、泛型抽象基类字段收集、`@Entity(name)` 别名解析。
- `HqlConverterSelectExpandTest` 补：实体别名 FROM 的表名替换与列展开、实体不可解析/列空两档注释文案断言（改既有 136/186/195/236 锚点行）。
- `SqlPreviewService.resolveHqlTable` 为私有方法且依赖 IDE 全局——空列回退以 `HqlConverter` 层等效测试 + resolveHqlTable 抽取纯函数化（入参三路径判据抽出为可测 internal 函数）覆盖。
- CHANGELOG [Unreleased] + 变更记录行。

## 四、验收标准

1. 表驱动用例全绿：三级抽象继承链列清单完整且顺序正确（父类在前）；`@Entity(name)` 别名 HQL 表名替换正确、SELECT 展开不再降级。
2. 空列回退：实体注解解析失败但 hbm 映射存在时，路径 3 兜底生效（不再被空表短路）。
3. 降级注释两档可区分，既有锚点行同步更新，无回归。
4. JOIN 副表降级行为逐字节不变（防回归断言）。
5. 全量 test 绿；跑前 `parallel_tasks.py check` + register + 独立 sandboxContainer，跑完 done 注销。

## 五、真机走查清单（留人工）

1. 用户工程复现原场景：抽象父类 + @MappedSuperclass 继承实体的 HQL 预览，确认列清单完整、无降级注释。
2. `@Entity(name="Alias")` + `FROM Alias` 场景：表名替换与列展开正确。
3. 继承 + JOIN FETCH 混合场景：副表别名降级注释仍在（预期边界），主表展开正常。
4. hbm 项目（路径 3）：JPA 解析失败场景不再出现 unknown_table。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 空列回退改动触达三条预览链 | previewHql / previewNamedQuery / previewHbmNamedQuery 共用 resolveHqlTable | 判据收紧只影响「列空」分支，非空路径行为不变；三链各自补负例断言 |
| @Entity(name) 慢路径性能 | 全量扫注解类 | 照抄 EntityClassFinder 缓存惯例（Project userData + modificationCount 失效），仅快路径未命中才走 |
| 别名替换误伤 | HQL 中其他位置出现同名标识符 | mapEntityToTable 维持既有「词边界 + 全词命中」替换形态，仅扩判据不改替换机制 |
| JOINED 策略列口径 | 本次只加注释不改写 | 验收只验注释存在，不验拆表语义；文档写明边界 |
| 测试锚点漂移 | 注释文案改动波及既有 4 处断言 | 同一提交内同步改锚点，HqlConverterSelectExpandTest 全绿为准 |

---

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-18 | 实施交付（分支 t-hqlinh-cols，工作树 mpsv-thic）：T-HIC-01 空列回退以「更优候选替换」语义落地（resolveHqlTable 列空时先试路径 2/3，找不到才保留原结果——首版直接返回 null 被 PreviewEndToEndTest.testNamedParam_tableNameResolved 夹具局部类场景抓出回归后修正）；T-HIC-02 entityNameOf + findClassByEntityName 慢路径（EntityNameLookupCache，modificationCount 失效）+ mapEntityToTable 别名替换 + convert 新增 entityAlias 参数（previewHql/previewJpql 两链透传）；T-HIC-03 降级注释两档（实体不可解析 / 实体 X 列信息未解析（检查继承链父类映射））+ SelectExpand 测试锚点同步与 8 新用例；T-HIC-04 inheritanceBoundaryComment（JOINED/TABLE_PER_CLASS 边界标注）；T-HIC-05 JpaEntityResolverTest 3 新用例（三级抽象链/隔代 Transient/entityNameOf）+ CHANGELOG Unreleased。**门禁：全量 test 4343/0/0/6 绿**（含陈旧产物 NoSuchMethodError 假失败，clean 复核排除）；真机走查清单留人工。build.gradle.kts 沙盒行用后待还原 |
