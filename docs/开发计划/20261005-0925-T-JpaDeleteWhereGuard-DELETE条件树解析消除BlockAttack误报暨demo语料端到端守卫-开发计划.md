# T-JpaDeleteWhereGuard：JPA DELETE 条件树解析消除 BlockAttack 误报 + demo 语料端到端产物守卫 —— 开发计划

> 用户 2026-10-05 双指令：①「为什么这次的问题没被单元测试覆盖/发现」这个问题需要解决掉（测试缺口结构性收口）；②「有 WHERE 却报无 WHERE 条件」生成开发计划文件并实施（截图：形态 18 `deleteByEmbeddedCreatedTimeBefore` 预览产物 `DELETE FROM wms_dock_booking booking WHERE booking.created_time < '1'` 明带 WHERE，仍报 ⚠ BlockAttack 全表删除）。
> 状态：根因已实证，**按指令实施**。

## 一、现状与根因（已实证）

### 1.1 Q2 根因：DELETE 语句模型的 conditions 恒为空列表

- 警示判定在 `SqlPreviewService.buildJpaWarning`（`:1293`）：`is DeleteStatement -> if (statement.conditions.isEmpty()) "⚠ [BlockAttack] ..."`——看的是**模型条件树**，不是 SQL 文本；
- JPA 链两个转换器构造 DELETE 模型时无条件写空：`HqlConverter.kt:171`、`JpqlConverter.kt:132` 均为 `DeleteStatement(table, conditions = emptyList())`。J2 只给 UPDATE 接了 `JpaUpdateParser`（SET/WHERE 解析成条件树），**DELETE 的 WHERE 从未接进模型**；
- 结论：JPA/HQL 通道下**任何**带 WHERE 的 DELETE 都误报，形态 18 只是首个被用户看到的实例。MP/Wrapper 链不受影响（那边的条件树来自 wrapper 解析）。

### 1.2 Q1 根因（为什么上次没被测试发现）与收口形态

三层叠加：J2 测试全在 converter 层直传手工 TableInfo（上游断链不可见）；locator 层测试只钉 SELECT 形态；demo 语料自动化断言只到「gutter 识别」未到「产物文本」。加上全链静默降级（TableInfo 落空无任何警告），问题活到真机。

**收口形态**：`DemoRecognitionTest` 已有「直接加载真实 demo 工程文件」的基建（`demoRoot = ../mybatis-plus-sql-visualizer-demo/...`，BasePlatformTestCase fixture），但只做图标计数。新增 **demo 语料端到端产物守卫**：同一基建加载形态 17/18/19 宿主方法 → `HqlQueryLocator.locate` → 实体解析 → `HqlConverter.convert` → 渲染产物**逐字断言**。该测试若在上轮就存在，形态 17 断链当场红（表名未映射），形态 18 误报也会红（conditions 恒空）——这就是「同类问题必须被测试拦住」的落点。

## 二、方案

### B：DELETE WHERE 条件树解析（Q2 主修复）

1. `JpaUpdateParser` 新增 `parseDelete(sql: String): ParsedDelete?`：
   - 头部正则 `DELETE\s+(?:FROM\s+)?实体(?:\s+(?:AS\s+)?别名)?`（JPQL 标准带 FROM；Hibernate 宽松形态无 FROM 也认；`FROM` 单词不落实体名——先试带 FROM 版）；
   - 复用既有顶层 WHERE 分界 + `parseWhere`（简单比较 / IS [NOT] NULL / IN 白名单，OR/LIKE/BETWEEN 等整体 `Condition.Raw` 兜底——**Raw 也是非空 conditions，警示即不再误报**）；
   - 列引用经既有 `parseColumnPath` 剥别名前缀（与 S2 口径一致）；
   - 无 WHERE → conditions 空列表（BlockAttack 警示对无 WHERE DELETE 保持正确触发）。
2. `HqlConverter` / `JpqlConverter` DELETE 分支接入：`parseDelete` 非 null → `DeleteStatement(conditions = parsed.conditions)`；解析返回 null（头部结构异常）维持既有空透传，不抛异常。
3. `buildJpaWarning` 零改动——修的是数据不是判定。

### A：demo 语料端到端产物守卫（Q1 收口）

新增 `converter/jpa/DemoJpaSqlPipelineTest`（BasePlatformTestCase，照 DemoRecognitionTest 的 demoRoot 相对路径基建）：

- 加载 demo `HibernateTemplateDemoServiceImpl.java` + `WmsDockBooking.java` + `UpdateInfo.java`（实体经 fixture 装载，`PsiShortNamesCache` 可解析）；
- **形态 17 守卫**（原始报障形态，红过才叫守卫）：`updateEmbeddedCreatedTime` → locate → entityClassName=WmsDockBooking → 实体解析 → convert → 断言管道文本含 `wms_dock_booking` 与 `created_time`、渲染产物 `UPDATE wms_dock_booking SET created_time = ? WHERE id = ?`（空白归一）；
- **形态 18 守卫**（与 B 联动）：`deleteByEmbeddedCreatedTimeBefore` → convert → 断言 conditions 非空（无 BlockAttack 误报）+ 首条件列 `created_time`；
- **形态 19 守卫**（回归保护）：`findByEmbeddedCreatedTimeAndWarehouse` → 断言管道文本含 `booking.created_time` 与 `warehouse_id`（SELECT 链嵌入段映射不回归）。

### C：DELETE 条件解析测试（converter 层）

`JpaUpdateConverterTest` 补：带 WHERE 双链解析为 Simple 条件（列剥别名、值占位）；无 WHERE conditions 空；OR 兜底为 Raw（非空）；DELETE 管道文本零变化。

## 三、红线

- src/main 改动收敛在 `JpaUpdateParser.kt` / `HqlConverter.kt` / `JpqlConverter.kt`，零删例，UPDATE 路径行为逐字不变；
- 反向验证必做：临时把 HqlConverter DELETE 分支改回 `conditions = emptyList()` → 形态 18 守卫必红，两次读数落实施记录；
- 守卫测试用**真实 demo 文件**做样本（合成样本只能证「规则看得见我造的形态」）；
- demo 仓零改动（形态 18/19 已在 `a0b827b`）。

## 四、验证

1. 定向：`DemoJpaSqlPipelineTest` + `JpaUpdateConverterTest` + `HqlEntityNameExtractorTest`（testFast）；
2. 全量门禁：testShards 5 片单路（本改动触 src/main 转换链）；
3. 面板语义核对随下轮沙盒走查批（形态 18 预览不再出红警示、条件树/参数回填激活）。

## 五、待验证前提（实施者先证伪再动手）

1. fixture 内 demo 实体解析：`WmsDockBooking`/`UpdateInfo` 以 `configureByText` 装载后 `JpaEntityResolver().resolve` 可产出展平列（javax.persistence 注解在 fixture 下走短名兜底轨——DemoRecognitionTest 同环境已识别 demo 文件，间接佐证）；
2. `parseDelete` 头部正则对 `DELETE FROM wms_dock_booking booking WHERE`（实体名已被表名替换后的小写蛇形）命中——实体名正则须含下划线开头小写（`[A-Z]` 类首字符配 IGNORE_CASE 即可，需实测）；
3. DELETE 分支接入后 previewModel/参数回填链路行为与 UPDATE 同构（`collectConditionViews` 对 DeleteStatement 的支持是既有能力，MP 链在用）；
4. `JpqlConverter` DELETE 分支同样接条件树后，`deletePath_unchanged_afterUpdateSupport` 既有断言（statement 类型与 SQL 文本）不破坏——该断言未触 conditions 字段。

## 六、实施记录（20261005，master）

**实施提交**：B 主修复 `fa416e0`（src/main 3 文件 +51/−2：JpaUpdateParser 新增 `ParsedDelete`/`parseDelete`/DELETE_HEAD 正则〔别名前瞻排除 WHERE〕，HqlConverter/JpqlConverter DELETE 分支接线）；A 车道 `7320723`（`DemoJpaSqlPipelineTest`，HeavyPlatformTestCase）；C 车道 `b557f51`（JpaUpdateConverterTest 补 8 用例）。

**并行实施**：主控做 B（src/main 耦合紧，先落库给子代理稳定底座）+ 双子代理并行 A/C（文件零交集：A 新建测试文件、C 只改 JpaUpdateConverterTest），各自 check→register→done 全流程登记，提交只 add 各自文件。

**A 车道成果**（demo 语料端到端产物守卫，Q1 收口落点）：3/3 绿。形态 17 端到端（locate→entityClassName 语句头提取→实体解析表名/展平列→convert→渲染产物归一全等 `UPDATE wms_dock_booking SET created_time = ? WHERE id = ?`）；形态 18 守卫（DeleteStatement conditions 非空 + Simple 列 created_time）；形态 19 回归（`booking.created_time`/`booking.warehouse_id` 映射不回归）。**fixture 六坑留档**：①testFast 快桶不收平台类须跑 `test` 任务；②Light fixture 跨文件 PSI 落空须 HeavyPlatformTestCase（M27 同源）；③Heavy 下 createChildDirectory 须 write-action；④source root 是临时根本身，文件须落 `cn/song/jpademo/...` 层级否则包名推导不一致、@Embedded 展平退化单列兜底；⑤findClass 短名轨不命中须 FQN/getClassesByName 兜底链（误删即 3 红）；⑥Kotlin 显式可空标注与 resolve() 非空契约冲突。

**C 车道成果**：33/33 绿（新增 8 用例：双链带 WHERE 解析 Simple〔列剥别名〕、嵌入段双重语义、无 WHERE 空 conditions〔裸全表警示语义保持〕、OR 兜底 Raw 非空、parser 直测无 FROM/畸形/SELECT 头三态、既有 deletePath 回归未动仍绿）。

**待验证前提实测回填**：前提 1 证实（Heavy fixture 下展平列三元组断言通过）；前提 2 证实（DELETE_HEAD 对小写蛇形表名命中）；前提 3 证实（条件树激活路径与 UPDATE 同构）；前提 4 证实（deletePath_unchanged_afterUpdateSupport 未改仍绿）。

**反向验证（两次读数）**：①摘除 HqlConverter DELETE 分支接线（RED-PROBE，`ParsedDelete? = null`）→ 恰 3 条失败（形态 18 端到端 + hqlDelete_withWhere + delete_embeddedPathWithWhere）；②恢复 → 36/36 全绿（BUILD SUCCESSFUL 26s exit 0；恢复后与 HEAD 零 diff）。过程坑：首版探针 `val parsedDelete = null` 无法推导成员访问致编译错，改显式类型后红跑成立。

**全量门禁读数**：testShards 5 片单路 **6156/0/0/9**（较上轮 6108 +48：本任务 A 车道 3 + C 车道 8，其余为并行会话同期新增〔T-FramePrefixResize 等〕；skip 9 仍为 docker 桶）+ testExternal 40/0/1 + agent 93/0/0 + parity 计划类 535 个均有结果 XML；BUILD SUCCESSFUL 3m16s exit 0。

**沙盒 UI 走查**：留待下轮沙盒走查批——形态 18 预览不再出红警示、DELETE 条件树/参数回填激活逐字核对。

## 七、同构普查批（20261005，用户令「其他功能类似问题一起修复」）

**扫描方式**：两路只读 Explore 子代理并行（B 路=模型文本脱节类：全量核对 buildWarning/buildJpaWarning 两警示判定点、11 个 buildJpaPreviewResult 消费链、20 处 DeleteStatement/UpdateStatement 生产点的模型-文本同源性；A 路=语句头/字面量假设类：全 src/main 的 FROM/UPDATE/DELETE 正则、startsWith 头部分派、裸词替换场景）+ 主控亲核 CRU 族逐方法警示对齐。

**B 路结论**：修复后本仓绝大多数 converter 的 SQL 文本由语句模型经 SqlGenerator 生成，文本与模型天然同源，「文本带 WHERE、模型 conditions 空」错报路径未再出现；现存的是**警示通道不对称**（delete 全家齐备、update 集体缺位、无决策登记）。修复 F1~F5（`0261b44`）：
- F1 `buildJpaWarning` 补 UpdateStatement 分支（JPA 链无 WHERE bulk UPDATE 此前静默；conditions 空 ⟺ 文本无 WHERE，无错报面）；
- F2/F5 EasyQueryConverter `updatePreview` 两分支补全表更新警示 + updatable(entity) notes 无主键时失实修正（按实际 WHERE 语义三分支）；
- F3 MdsConverter warning 判定 when 化补 update 分支；
- F4 QuerydslConverter update 分支补警示+notes（对齐 delete 分支既有形态）。
- B 路另登记设计内边界两处（Hql/Jpql UPDATE 解析失败→SelectStatement 透传、SelectClauseExpander J19 短路，均有行内登记）不扩面。

**A 路结论**：确定缺陷三处+顺带一处，全部是「替换端裸词、字面量不设防」与已修缺陷同管道的残留，统一修法=新增 `JpaSqlLiteralMasking`（掩码→替换→还原；字面量口径沿用 JpaAliasPropertyMapper.STRING_LITERAL 仓内唯一份）：
- G1 `HqlConverter.mapFallbackEntityToTable` 裸 replaceFirst 无词边界无字面量保护（`OwnerInfo` 命中 `OwnerInfoHelper` 前缀 → `unknown_tableHelper`）；
- G2 HqlConverter/JpqlConverter `mapEntityToTable` 的 `\b实体名\b` 全局替换吃字面量（`like '%UserEntity%'` → `'%user%'`）；
- G3 `JpaFromEntityMapper` ENTITY_REFERENCE 扫描与替换正则均吃字面量（`set note = 'copy from OrderEntity x'` 字面量内的 FROM 假象驱动错表映射）；
- G5 `HqlConverter.mapAssociationPath` 三段路径正则命中字面量内容（掩码接入顺带覆盖）。
- A 路理论风险四处**登记不扩面**（触发面窄、修复收益低于回归风险，留作后续候选）：注释开头语句头部分派失效（JPQL 标准不支持注释）/JpqlConverter.expandSelectAliases 首个 FROM 假设/SqlPreviewService.detectMainAlias 子查询前置/JdbcTemplateSqlPreviewService.extractFirstTableName。

**测试**：`JpaSqlLiteralSafetyTest` 6 用例（HQL/JPQL/UPDATE SET 字面量保护、fallback 前缀子串、关联路径字面量、掩码往返）+ `PreviewEndToEndTest` 2 用例（JPA UPDATE 无 WHERE 警示/带 WHERE 无警示）+ 三链 converter 警示正反用例 7 个（并行子代理 `a4af61d`，EasyQuery 12/Mds 7/Querydsl 7 全绿）。

**反向验证（两次读数）**：摘 `mapEntityToTable` 掩码 → 恰 2 条字面量用例失败；恢复 → 全绿。首轮字面量测试 2 失败系用例自身 `select u` 触发既有裸别名展开语义（T-HQL-SELECT-01 正当行为），改具体列投影后绿——非实现缺陷。

**全量门禁读数**：首轮 testShards 出现 `SenderLoopBackoffTest`（退避时序）+`FatalPassthroughTest`（状态回调计数）两处失败，均在本轮未触碰的 agent 传输/日志包；串行复跑 BUILD SUCCESSFUL 判定为本轮并行负载下的时序抖动（与在册回归假失败清单同族；上一轮门禁同代码全绿佐证）。复跑 testShards 又出现 `SettingsTruthTest`（publishStatusChanged 计数 9→7）+`BundleParityGuardTest`（AgentManager bundle key 缺失）两处守卫失败——git 实测系**并行会话在途实施 T-FirstUsePremainConsent**（AgentManager.kt/PremainRunConfigurationExtension.kt/plugin.xml/bundle 正被其修改，计划 `2b38c5e`），属他人 WIP 被源码扫描守卫如实检出，非本任务回归。**两轮门禁中本任务触碰的全部包（converter/jpa、converter、ui、integration）零失败**；字面量掩码 6 用例+三链警示 26 例+JPA 族回归均定向全绿。权威全量门禁随占用工作树的会话收口统一复跑（并行纪律：记录证据并交回）。
