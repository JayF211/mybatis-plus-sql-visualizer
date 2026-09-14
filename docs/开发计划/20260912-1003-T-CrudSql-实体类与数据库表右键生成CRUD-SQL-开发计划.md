# T-CrudSql 开发计划：实体类与数据库表右键生成 CRUD SQL

> 立项时间：2026-09-12 10:03
> 执行分支：master（当前工作树）
> 需求来源：用户 2026-09-12 口头需求——实体类右键、IDEA Database 工具窗口表节点右键，生成该表的 CRUD SQL 语句；只生成 SQL，不做 Mapper/Entity 等代码生成。

## 一、需求背景与边界

竞品 MyBatisCodeHelperPro 在数据库表右键菜单提供"生成代码"系列功能。本插件只取其中一个轻量切口：右键拿到表结构后直接生成四条 SQL（SELECT / INSERT / UPDATE / DELETE），供复制到控制台或代码中使用，不生成任何 Java/XML 代码，不写文件。

边界定案：

- 入口一：Java 实体类右键（编辑器内右键 + 项目视图右键），复用现有 EntityResolver 解析表名与字段。
- 入口二：Database 工具窗口表节点右键，复用数据库插件自省元数据（列/类型/主键）。
- 产物：先经现有 SqlSelectionDialog 选择语句，再以现有 SQL 预览窗口展示单条（格式化/执行/复制按钮原样可用），INSERT/UPDATE 支持面板列勾选重建；多选表时候选列表合并展示。
- 明确不做：代码生成、SQL 文件落盘、DDL、批量执行。

## 二、调研结论（本地 jar 实证为唯一权威）

### 2.1 Database 插件的右键注册机制

对本地 `ideaIU-2024.3.7.1-win/plugins/DatabaseTools/lib/database-plugin.jar` 解包核实（`META-INF/plugin.xml`）：

- DatabaseTools **没有**提供专门的"视图 action"扩展点（extensionPoints 段 70-147 行逐一核对，无 viewActionProvider 类 EP）。
- 右键菜单组为普通 action 组 `DatabaseViewPopupMenu`，声明于该 plugin.xml 第 713 行，组内全是 reference 引用。
- 第三方插件注册方式即标准写法：`<add-to-group group-id="DatabaseViewPopupMenu" anchor="last"/>`。JetBrains 官方支持论坛确认此组对第三方开放（备选组 `Console.TableResult.PopupGroup` 用于数据网格区域，本需求不需要）。
- MyBatisCodeHelperPro 等竞品即用此法在表节点上挂菜单，方案与竞品同路。

该组属于 DatabaseTools 内部组，无公开 API 稳定性承诺。缓解：本项目 plugin.xml 已声明可选依赖 `com.intellij.database`（plugin.xml:76、build.gradle.kts:79 bundledPlugin），注册放 `database-optional.xml`——database 插件不在场时整个配置文件不加载，add-to-group 引用不存在的组不会导致插件崩溃；verifyPlugin 三版本（243/253/262）门禁覆盖组名跨版本存在性。

### 2.2 表节点数据获取（javap 实证，2024.3.7.1）

- Database 视图树选中项以 PSI 形式暴露：`com.intellij.database.psi.DbElement extends PsiFileSystemItem`（即 PsiElement），action 内经 `CommonDataKeys.PSI_ELEMENT_ARRAY` 拿到后 `filterIsInstance<DbTable>()`。
- `DbTable.getDasObject()` → `com.intellij.database.model.DasTable`。
- 列清单：`DasObject.getDbChildren(DasColumn.class, ObjectKind.COLUMN)`（`DasObject` 接口 javap 实证）；`DasColumn` 经 `DasNamed.getName()`、`DasTypedObject.getDataType().getSpecification()`、`isNotNull()`、`getDefault()`、`getComment()`（继承自 `DasObject.getComment()`）取齐。
- 主键：`getDbChildren(DasTableKey.class, ObjectKind.KEY)` → `DasTableKey.isPrimary()` → `getColumnsRef()`（`MultiRef<? extends DasTypedObject>`）解出主键列名。注意模型类是 `DasTableKey`（jar 实证存在），无 `DasKey` 类。
- 逻辑删除在数据库侧无元数据，仅实体侧可识别（见 2.3）。

### 2.3 实体类侧可复用的现有能力

- `psi/EntityResolver.kt` 已完整解析 `@TableName` 显式值优先（否则 snake_case 推断）、`@TableField(exist=false)` 跳过、递归父类收集字段、`@TableId`/自增、`@TableLogic`、字段注释——直接产出实体侧字段清单，无需新写解析。
- `psi/EntityClassFinder.kt` 已有表名 → PsiClass 反查（本次用不到，仅说明实体解析链路成熟）。
- action 惯例：i18n 走 `action.<id>.text/.description` 双 bundle；图标复用 `/icons/sqlGutter.svg`；现有两个 action 均挂 `EditorPopupMenu` anchor=last（plugin.xml:243-272），本次实体类入口与之同区。
- SQL 拼装约定：关键字换行格式化管线（BasicHTML 空格折叠须 `&nbsp;`）、标识符引用设置（20260814-1015 交付）直接沿用其配置读取方式。
- 测试沙盒刻意排除 database 插件（build.gradle.kts:163-175），database 相关仅编译期引用、运行期反射降级——已有 `DatabaseConsoleLauncher.kt` 的反射隔离先例可仿。

### 2.4 平台陷阱对照

- 262 平台 EDT 读 PSI 硬抛：action 的 `update()` 必须 `getActionUpdateThread() = BGT`，PsiClass/DbTable 元数据读取包 ReadAction。
- 新增反射入口（若有）必登记 `PlatformSignatureCrossVersionTest`。
- DialogWrapper cancelAction 坑、Disposer 树注册（弹窗子面板挂到 dialog disposable）沿既有记忆。

## 三、总体设计

四层结构，核心生成器与两个入口解耦，展示层完全复用现有 SQL 预览窗口：

```
入口层（2 个 AnAction，update 只做可见性/可用性判定）
  ├─ GenerateCrudSqlFromEntity（plugin.xml 主文件）
  └─ GenerateCrudSqlFromDbTable（database-optional.xml）
适配层（各自把来源转成统一表描述）
  ├─ PsiClass --EntityResolver--> CrudTableSpec
  └─ DbTable  --DbTableSpecAdapter--> CrudTableSpec（薄适配，反射隔离可测）
核心层 CrudSqlGenerator（纯 Kotlin，不依赖 database/psi，直接单测）
  └─ 入参 CrudTableSpec(tableName, columns[CrudColumn], 主键列名集, 方言/引号/换行配置)
     出参 CrudSqlSet：每条语句一个 PreviewResult 组装描述
展示层（全部复用，零新弹窗）
  └─ SqlSelectionDialog（多语句候选选择）→ SqlPreviewService.showResult
     → 现有 SqlPreviewDialog/SqlPreviewPanel：渲染、格式化、执行 SQL、复制全套按钮原样复用
```

### 3.0 展示层复用定案（2026-09-12 用户确认）

不新写任何弹窗/tab。CRUD 生成结果直接喂给现有预览窗口：

- **语句切换**：右键后先弹现有 `SqlSelectionDialog`（与 ConvertToSqlAction 多注解时同一路径），候选列表为该表的各语句（"SELECT（全表）""SELECT（按主键）""INSERT""UPDATE""DELETE"，逻辑删除变体单列一条）；选中哪条就用 `SqlPreviewService.showResult` 打开对应 `PreviewResult`。多选表时候选标签带表名前缀，同一列表内选择。
- **INSERT/UPDATE 复用 BatchModel**：组装 `PreviewResult(batchModel=...)`，面板列级勾选重建（自增主键默认不勾、UPDATE 主键进 fixedColumns 参与 WHERE、方言保留字引号）全部继承既有行为；`BatchModel.renderSql` 是唯一渲染源，初始 SQL 由它产出，保证逐字符一致。
- **SELECT/DELETE**：普通 `PreviewResult`（`?` 占位符进 unresolvedParams → 面板参数行 → 执行按钮可用）。
- **格式化/执行 SQL/复制按钮**：SqlPreviewPanel 既有能力，零改动复用。

### 3.1 SQL 生成规则定案

| 语句 | 形态 | 预览窗口承载方式 |
|---|---|---|
| SELECT 全表 | `SELECT col1, col2 FROM t;` | 普通 PreviewResult，无参数行 |
| SELECT 按主键 | `SELECT ... FROM t WHERE pk = ?;` | 普通 PreviewResult，`?` 进参数行 |
| INSERT | `INSERT INTO t (cols) VALUES (?, ...);` | batchModel(kind=INSERT)：自增主键列默认不勾，勾选重建走 BatchModel.renderSql |
| UPDATE | `UPDATE t SET col = ? ... WHERE pk = ?;` | batchModel(kind=UPDATE)：SET 列可勾选，主键列进 fixedColumns 恒参与 WHERE |
| DELETE | `DELETE FROM t WHERE pk = ?;` | 普通 PreviewResult |
| DELETE（逻辑删除变体，可选） | `UPDATE t SET del_flag = 1 WHERE pk = ?;` | 实体侧识别 `@TableLogic` 时作为独立候选进选择列表 |

- 逻辑删除列值以 `@TableLogic` 注解 delval 为准（EntityResolver 已解析）；DB 侧入口仅当列名与现有逻辑删除列名配置匹配时提供变体，否则只出物理 DELETE。
- 无主键降级：SELECT 按主键/DELETE 的 WHERE 生成 `WHERE 1 = 0 -- 未识别到主键，请补充条件`，UPDATE 的 fixedColumns 为空时同样降级并在候选标签上注明；不猜测首列。
- 方言与引号：BatchModel 自带 `dialect` 字段（MySQL/PG/SQLServer 保留字包裹）；SELECT/DELETE 文本的引号与关键字换行沿用现有设置读取，与 ConvertToSqlAction 输出风格一致。

### 3.2 action 可用性判定

- `GenerateCrudSqlFromEntity`：`PSI_ELEMENT`（或 PSI_ELEMENT_ARRAY）取 PsiClass——是 PsiClass 直接用；是字段/方法则 `PsiTreeUtil.getParentOfType` 上溯所在类。经 EntityResolver 解析成功（拿到表名）才 enable。挂 `EditorPopupMenu` 与 `ProjectViewPopupMenu`，均 anchor=last。
- `GenerateCrudSqlFromDbTable`：PSI_ELEMENT_ARRAY filter DbTable，非空 enable；多选全支持。挂 `DatabaseViewPopupMenu` anchor=last，注册进 database-optional.xml 新增 `<actions>` 段。

## 四、任务拆解

| 编号 | 内容 | 交付物 |
|---|---|---|
| T-CrudSql-01 | 核心生成器：CrudTableSpec/CrudColumn 模型 + CrudSqlGenerator（SELECT/DELETE 文本生成、主键/无主键降级、INSERT/UPDATE 组装 BatchModel） | `model/CrudTableSpec.kt`、`generator/CrudSqlGenerator.kt` + 纯单测 |
| T-CrudSql-02 | 实体类入口：GenerateCrudSqlFromEntity（BGT update + ReadAction）、EntityResolver→spec 适配 | `actions/GenerateCrudSqlFromEntityAction.kt` + plugin.xml 注册 + bundle 双语键 |
| T-CrudSql-03 | 数据库表入口：DbTableSpecAdapter（DbTable→spec）、database-optional.xml 注册、ActionManager 运行期组存在性防御 | `database/DbTableSpecAdapter.kt`、`actions/GenerateCrudSqlFromDbTableAction.kt` + 签名护栏登记 |
| T-CrudSql-04 | 预览接线（展示层零新弹窗）：CrudSqlSet → 候选列表（现有 SqlSelectionDialog）→ 选中项组装 PreviewResult → `SqlPreviewService.showResult`；INSERT/UPDATE 走 BatchModel 保证初始 SQL 与勾选重建逐字符一致 | `generator/CrudPreviewResultMapper.kt` + bundle 双语键 |
| T-CrudSql-05（可选增强，不阻塞） | 设置项：占位符风格（? / #{}）、逻辑删除开关 | 设置页子项 + 持久化 + 面板测试基线 +1 |
| T-CrudSql-06 | 收口：全量回归、verifyPlugin 三版本、runIde 真机走查（实体右键 + 数据源表右键各一）、文档三处同步 | 测试报告、变更记录、MEMORY 台账 |

依赖关系：01 先行；02/03 并行；04 可与 02/03 并行开发（接口先定）；05/06 收尾。

## 五、测试计划

- `CrudSqlGeneratorTest`：SELECT/DELETE 文本快照；复合主键；无主键降级；INSERT/UPDATE 的 BatchModel 组装（自增主键默认不勾、fixedColumns、方言引号、勾选重建与初始 SQL 逐字符一致——沿 BatchModel 既有约定）；逻辑删除变体。纯单测，不依赖平台。
- `CrudPreviewResultMapperTest`：候选标签文案（多表前缀）、无主键标注、PreviewResult 字段映射（unresolvedParams/columns/columnComments）。
- `GenerateCrudSqlFromEntityActionTest`：沿 actions 测试惯例——MP 实体 enable 并生成正确表名；非实体类（POJO 无注解且无匹配）disable；内部类/方法上右键上溯；Kotlin data class 实体（沿 Kotlin gutter 同等支持面）。
- DbTable 适配层：测试沙盒无 database 插件，适配器做成"接口注入 + 薄实现"，薄实现不做单测（仿 DatabaseConsoleLauncher 反射隔离先例），核心断言全部压在 spec→SQL 层；`PlatformSignatureCrossVersionTest` 登记 DbTable/DasTable/DasTableKey 三个签名点。
- 全量回归：跑前 `parallel_tasks.py check`，先清 build/classes 陈旧类（两类已知假失败先证伪再排逻辑）；基线 ≥ 现行 3788 用例 + 本次新增。
- verifyPlugin：243/253/262 三目标 Compatible。

## 六、验收标准

1. 编辑器内实体类上右键、项目视图实体文件右键，均出现"生成 CRUD SQL"，点击出现语句选择列表，选中后在现有 SQL 预览窗口打开，表名与列与 EntityResolver 语义一致；非实体类不出现或置灰。
2. Database 工具窗口表节点右键出现入口，生成的 SQL 列名/类型/主键与该数据源自省结果一致；未连接数据源的表节点以离线元数据生成，缺列信息时候选标签注明。
3. 卸载/禁用 DatabaseTools 场景（如 Community 或裁剪版）插件正常加载，实体类入口不受影响。
4. 预览窗口内格式化、执行 SQL、复制按钮全部可用；INSERT/UPDATE 的列勾选重建生效（自增主键默认不勾）；多选两张表时候选列表带表名前缀可分别打开。
5. 中英文文案齐全（候选标签、选择弹窗标题），预览窗口随语言设置切换。
6. 真机 runIde 走查通过（实体右键 + MySQL 数据源表右键各一），全程无 UnhandledException。

## 七、风险与预案

| 风险 | 预案 |
|---|---|
| `DatabaseViewPopupMenu` 为内部组，262 及后续版本组名或结构变动 | database-optional.xml 隔离加载，最坏情形入口消失不崩；verifyPlugin 三版本门禁 + 签名护栏测试尽早暴露；真机走查覆盖 262 |
| 262 update 线程读 PSI 硬抛 | update 全部 BGT + ReadAction 包裹，真机 262 验证 |
| 测试沙盒无 database 插件，适配层不可测 | spec 抽象隔离，适配层压薄；签名护栏登记三个类 |
| 数据源离线/未连接时元数据不全 | 生成时以已自省内容为准，缺失时候选标签明示而非静默生成错误 SQL |
| 预览窗口单例化（关旧开新）与多语句选择的状态衔接 | 走 `SqlPreviewService.showResult` 既有入口（内部已处理关旧开新与弱引用登记），不绕过 |

## 八、实施记录

2026-09-12 交付（worktree mpsv-crudsql，分支 feature/t-crudsql）：

- T-CrudSql-01：`generator/CrudTableSpec.kt`（CrudColumn/CrudTableSpec/CrudKind/CrudStatement）+ `generator/CrudSqlGenerator.kt`（SELECT/DELETE 纯文本 + INSERT/UPDATE 组装 BatchModel，renderSql 唯一渲染源）。@Version 语义与 `BatchOperationConverter.buildUpdateBatch` 逐一对齐（version 不进实体 SET、SET 尾 `= version + 1`、WHERE 追加 `AND version = ?`）；无主键降级 `WHERE 1 = 0 /* */` 且降级 UPDATE 不挂 batchModel。
- T-CrudSql-02：`actions/GenerateCrudSqlFromEntityAction.kt`（BGT update + 后台 ReadAction 解析 + EDT showCandidates；MP 注解短名判定；PSI_ELEMENT/PSI_ELEMENT_ARRAY/caret 上溯三路收集）。Kotlin KtClass 留后续迭代（文档化边界）。
- T-CrudSql-03：`database/DbTableSpecAdapter.kt`（getDbChildren(DasColumn, COLUMN) / DasTableKey.isPrimary+getColumnsRef / Attribute.AUTO_GENERATED，全部直引编译期类型零反射）+ `generator/DbTypeQuoteHint.kt`。database-optional.xml 注册 DatabaseViewPopupMenu anchor=last。
- T-CrudSql-04：`actions/CrudSqlPreviewFlow.kt`（SqlSelectionDialog 候选选择 → SqlPreviewService.showResult；候选标签 `表名 · 语句种类`，无主键追加标注；0/1 条不弹选择框）。
- 实施 vs 计划偏差：① `PSI_ELEMENT_ARRAY` 实际在 `PlatformCoreDataKeys`（计划误写 CommonDataKeys，javap 实证 243 布局）；② 候选选择复用 SqlSelectionDialog 时 displayTexts 无 PSI 读取（纯 bundle + 已算 SQL 首行），不触发后台预计算要求；③ 逻辑删除变体值固化为 1（@TableLogic delval 自定义形态文档化边界，面板可编辑）。
- 测试：CrudSqlGeneratorTest 11 例 + DbTypeQuoteHintTest 3 例 + CrudSqlPreviewFlowTest 3 例（纯 JUnit），clean 全量 3339/0/6 绿（沙盒 MPSV-CrudSql 隔离，基线 3322 + 17）。
- 验证：编译 243 目标通过；verifyPlugin 三版本与 runIde 真机走查（实体右键 + MySQL 表右键）待合并前执行，留人工。
