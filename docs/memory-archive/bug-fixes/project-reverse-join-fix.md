---
name: project-reverse-join-fix
description: SQL→Java 反向联表（JOIN）转换修复——反引号列/换行转义/lambda 联表容忍逻辑删除/selectAs 引用 VO/副表列归属/未映射误报消除，1218 全绿
metadata:
  node_type: memory
  type: project
  originSessionId: sess_265db880-45c6-4317-8db4-4afe70065e95
---

**SQL→Java 反向联表转换修复（2026-08-17~18，reverse 包，v2026.8.17）**。用户实测联表 SQL（`biz_bike_device_info t LEFT JOIN ...`，ON 带 MPJ 自动追加的 `deleted=0`）反向转换出现的问题，六轮修复（提交 9e1a214 + 3d9bf04 + 0fd6b1f + 55177b8 + 7463ad0 + 5cc8659 + 64bd1c3）：

① **反引号列紧跟表别名丢列**（生成 `.select("t.")`）：根因=SqlTokenizer 把 `t.` 并成单个 WORD token（续读循环里 `.` 无条件并入），反引号列 `` `signal` `` 被 `consumeSelectAlias` 误当 select 别名吞掉。修复=点号仅在「后一字符不是反引号」时并入 WORD（`sql[j]=='.' && j+1<len && sql[j+1]!='`'`），`` t.`signal` `` 拆成 t、.、`signal` 三 token 由 consumeQualified 合并。**只影响「点号后紧跟反引号」这一当前已损坏路径**。

② **raw join 字符串多行换行未转义**（`leftJoin("...\n AND ...")` Java 字面量不合法、不编译）：renderJoins 字符串形态拼 rawOn 未 escape。修复=新增 `joinString()`：`rawOn.replace(Regex("\\s+")," ").trim()` 归一空白 + `javaEscape`。

③ **lambda 形态联表被 ON 逻辑删除条件拦截**：canLambdaJoin 要求 onConditions 恰 1 个 ColumnComparison，MPJ 自动追加的 `deleted=0` 使其变「多条件」→ 降级丑字符串 join。修复=新增 `joinKeyComparison()`（滤除副表逻辑删除条件后取唯一 ColumnComparison）+ `isSubLogicalDelete()`。**关键约束：isSubLogicalDelete 必须限制逻辑删除列名集合 `LOGICAL_DELETE_COLUMNS`（deleted/is_deleted/is_delete/del_flag/deleted_flag/del/is_del）+ =0/1 字面量 + 列是副表真实列——否则 `type=1`/`status=1` 真过滤条件被误吞（实证：测试 `多表JOIN_ON多条件_回落raw join字符串` 用 `t1.type=1` 若不限列名会误 lambda 化破坏**。

④ **SELECT 列 AS 别名全部丢弃**：parseSelectColumns 里 consumeSelectAlias 消费后丢弃。修复=新增模型 `SelectColumn(expr, alias)`，`SqlReverseResult.selectColumns` 类型由 `List<String>?` 改 `List<SelectColumn>?`（**改动穿透测试：JavaCodeGeneratorTest 约 7 处非 null listOf + SqlReverseParserTest 4 处断言 + SqlToJavaConverterTest 1 处 + SqlToJavaRoundTripTest 1 处 it.expr**）。consumeSelectAlias 改为返回 String?（AS 形态读 advance 后 token、直接别名形态返回当前 token，trim('`')）。

⑤ **主表别名下副表列归属**（`t.model` 降级字符串）：提取共享 `resolveColumn(columnName, entity, alias)` 归属解析（主表→对应副表→唯一副实体兜底，singleOrNull 歧义回落 null）。`renderColumnRef` 改用 resolveColumn。

⑥ **selectAs 选中 VO 时第二参引用 VO 属性方法**（第二轮，用户反馈「选了 VO 后 as 没引用 VO」）：此前生成 `.selectAs(BikeDeviceInfoEntity::getName, "productName")`（字符串别名），正向代码实为 `.selectAs(BikeDeviceProductInfoEntity::getName, BikeDeviceInfoVO::getProductName)`。修复=`generateSelect` 计算 `selectAsVoClass = if (voQuery) invocation?.voClass else null`（voQuery=invocation.mpj && voClass!=null && voClass!=entityName），`renderSelectCall(sel, entity, voClass)` 带别名且 voClass 非空时输出 `selectAs($ref, Vo::getAlias)`（未选 VO 保持字符串别名）。

⑦ **消除副表列未映射误报**（第二轮，`t.model` 已归属副实体却仍报「未在实体中找到对应字段」）：`unmappedColumns` 此前只查主实体 columnToField。修复=改用 `resolveColumn` 判定（条件/排序列改存 `qualifiedName` 保留别名前缀，新增 `columnAliasOf(expr)` 拆别名），与渲染一致。

**教训（select 渲染两分支约定）**：renderSelectColumn 不能返回整条 `select(...)`/`selectAs(...)`——非联表分支把列并成单个 `.select(a, b)`（joinToString ","），联表分支逐列 `.select`。故拆成 `renderSelectRef`（内层列引用，非联表分组用）+ `renderSelectCall`（整条调用，联表逐列用 + 非联表含别名时逐列）。非联表分支含别名时无法并入单个 .select，须逐列渲染。

**教训（isSubLogicalDelete 列名白名单）**：容忍 ON 逻辑删除条件必须用列名白名单（LOGICAL_DELETE_COLUMNS），不能用「任意副表列 =0/1」——否则真过滤条件（type=1/status=1）被误吞，破坏既有 raw join 回落行为。

⑧ **invocationHasMpj 接口继承误判**（第三轮，提交 0fd6b1f）：`BikeDeviceInfoService extends MPJBaseService<BikeDeviceInfoEntity>` 被误判无 MPJ 能力 → 多表 JOIN 报错。根因=`hasMpjInterface` 主路径 `getInterfaces()` 需完整解析泛型超类型，jar-loaded 跨模块时返空数组。修复=混合双路径（保留 getInterfaces+superClass 主路径 + superTypes+resolve() 回退，resolve 失败从 canonicalText 取 raw 全命名匹配）。详见 [[project-reverse-invocation-has-mpj-interface-bug]]。

⑨ **selectAs 字符串联接形态修复（T-M48）**（第四轮，提交 55177b8，用户反馈 `.selectAs("t.name", VO::getProductName)` 非法）：**MPJQueryWrapper 无 selectAs(String, SFunction) 重载**（只有 selectAs(SFunction,SFunction) 和 selectAs(SFunction,String)）。当 ON 含额外条件（无法 lambda 化）回落 MPJQueryWrapper 时，列是字符串，selectAs(String, SFunction) 编译报错。修复=`generateSelect` 计算 `rawMode = (effectiveKind == MPJ_QUERY)`，`renderSelectCall` 在 rawMode 下取原始列表达式字符串、别名列渲染为 `.select("t.name AS productName")`；lambda 形态（MPJLambdaWrapper）保持 `.selectAs(Entity::getX, VO::getY)`。**关键：isSubLogicalDelete 过滤 deleted=0 后若可 lambda 化则生成更优 lambda 形态（对齐正向代码）；仅当无法 lambda 化（如 ON 含 type=1 非逻辑删除）才回落字符串形态。**

⑩ **实体选择弹窗唯一推断跳过（T-M49）**（第五轮，提交 7463ad0，用户反馈多表联查每次都弹框）：`reverseManualEntitySelection` 默认 ON 时 JOIN 查询强制弹 EntitySelectionDialog。提取 `canAutoInferEntitySelection(mainCandidates, subCandidates)` 纯函数（主表 1 候选 + 每副表各 1 候选 → true），唯一可推断时跳过弹窗直接渲染；任一表 0/多候选仍弹。测试 +5。

⑪ **VO-only selectAs 引用 VO 方法（T-M50）**（第六轮，提交 5cc8659，用户反馈只选 VO 不选调用类时 as 没方法引用）：`buildInvocation()` 无调用类返回 null → selectAsVoClass=null → 字符串别名。修复=(1) `buildInvocation()` 无调用类但选 VO 时返回 VO-only `InvocationTarget(callerClass="", voClass=vo, mpj=true)`；(2) `appendWrapperAndInvocation()` callerClass 为空时跳过调用行生成。VO 方法引用不依赖调用类 MPJ 能力。测试 +1。

⑫ **invocationHasMpj jar-loaded 泛型超类型修复（T-M48.5）**（第七轮，提交 64bd1c3，沙盒模式 CarDeviceInfoService 仍误判）：上一版 canonicalText 回退在 jar-loaded 未解析场景返简单名不匹配。重写双路回退（direct FQN match + PsiShortNamesCache.allScope lookup）。详见 [[project-reverse-invocation-has-mpj-interface-bug]]。

**教训（select 渲染两分支约定）**：renderSelectColumn 不能返回整条 `select(...)`/`selectAs(...)`——非联表分支把列并成单个 `.select(a, b)`（joinToString ","），联表分支逐列 `.select`。故拆成 `renderSelectRef`（内层列引用，非联表分组用）+ `renderSelectCall`（整条调用，联表逐列用 + 非联表含别名时逐列）。非联表分支含别名时无法并入单个 .select，须逐列渲染。

**教训（MPJ wrapper 形态与 selectAs 重载）**：MPJQueryWrapper（字符串联接）列必须是字符串，无 selectAs(String, SFunction)；MPJLambdaWrapper（lambda 联接）才支持 selectAs(SFunction, SFunction) 引用 VO 属性。生成器必须按 effectiveKind 分支渲染，不可混用。

验证：`clean test` 全量 1220 tests 0 failures（reverse 包 103 + Heavy MPJ 4 全绿）。三处文档同步已完成（docs/变更记录.md 三行 + build.gradle.kts patchPluginXml changeNotes v2026.8.17 三条；README 能力列表为泛化描述未改）。