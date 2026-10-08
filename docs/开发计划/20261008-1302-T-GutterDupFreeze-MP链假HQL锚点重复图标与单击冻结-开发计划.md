# T-GutterDupFreeze：MP 链上假 HQL 锚点重复图标与单击冻结——开发计划

> 编号占用：O-124 / R70 / D108~D110 / V132~V134（20261008 grep 实测：O 最高 O-123、R 最高 R69、D 最高 D107、V 最高 V131，本组均未占用）
> 来源：用户指令（2026-10-08）——①「插件 demo 项目中 `cn.song.mybatisplusdemo.service.impl.WrapperFactoryCoverageServiceImpl` 中的方法出现了很多 sql 预览图标，这应该是重复了，是不正常的，现在分析问题原因生成开发计划文件」；②追加「点击 `listByBareFactoryChainExists` 方法的 `result.put("bareFactoryChain", userMapper.selectList` 行的第一个预览图标，弹框直接卡住了，也关闭不了」（随消息附四份 IDE 线程转储，含 `Freeze in EDT for 70 seconds`）
> 前序：`bc5a7be`（2026-08-21，内容启发与共享字符串提取器引入）、`480dacb`（T-GutterDedup，图标重复/缺失第一轮）、T-M23.11（链上撞名批量方法去重）、T-DaoSql-03（字符串 SQL 让位）、T-ExistsChainTerminal（20261007，本 demo 文件即该轮复现用例，见下方「非回归」一节）
> 状态：计划待确认（用户点名的为「分析问题原因 + 生成计划文件」，未授权动工）

## 一、现象与实测证据

### 1.1 图标归属：21 枚左栏标记里只有 10 枚是本插件的

用户贴图（`WrapperFactoryCoverageServiceImpl.listByBareFactoryChainExists`，行 186~222）左栏共 21 个标记。按像素配色分类（本插件 gutter 图标是 `icons/sqlGutter.svg`：绿色双尖括号 `#4FB477` + 紫色放大镜 `#B48EFF`；另一类红圈箭头图标不含本插件任何配色，属第三方插件）：

| 代码行 | 内容 | 本插件图标 | 第三方图标 |
|---|---|---|---|
| 186 | `public Map<...> listByBareFactoryChainExists(String username) {` | 0 | 1 |
| 190 | `result.put("bareFactoryChain", userMapper.selectList(` | **2** | 0 |
| 191 | `Wrappers.<User>lambdaQuery(User.class)` | 1 | 0 |
| 192 | `.like(StringUtils.hasText(username), User::getUsername, username)` | 0 | 1 |
| 193 | `.exists("select 1 from t_order o where o.user_id = user.id")` | 0 | 0 |
| 194 | `.eq(User::getStatus, 1)` | 0 | 1 |
| 195 | `.orderByDesc(User::getId)));` | 0 | 1 |
| 198 | `result.put("bareFactoryChainPaged", userMapper.selectPage(new Page<>(1, 10),` | 0 | 0 |
| 199 | `Wrappers.<User>lambdaQuery(User.class)` | 1 | 0 |
| 200 / 202 / 203 | `.like(...)` / `.eq(...)` / `.orderByDesc(...)` | 0 | 各 1 |
| 206 | `LambdaQueryWrapper<User> variableWrapper = Wrappers.<User>lambdaQuery(User.class)` | 1 | 0 |
| 207 / 209 / 210 | `.like(...)` / `.eq(...)` / `.orderByDesc(...)` | 0 | 各 1 |
| 211 | `result.put("variableDeclared", userMapper.selectList(variableWrapper));` | **2** | 0 |
| 214 | `result.put("bareFactoryChainNotExists", userMapper.selectList(` | **2** | 0 |
| 215 | `Wrappers.<User>lambdaQuery(User.class)` | 1 | 0 |
| 216 / 218 / 219 | `.like(...)` / `.notExists(...)` / `.eq(...)` | 0 | 各 1 |

- 本插件图标合计 **10 枚**；按本仓既有口径（`DemoRecognitionTest` 的「每处调用恰 1 图标」）应为 **4 枚**——四条链各 1 枚，落在链根行（191 / 199 / 206 / 215）。多出的 6 枚全部落在三行 `userMapper.selectList(...)` 上，每行 2 枚。
- 那 13 枚红圈图标**不是本插件产出**（哈希比对配色即可判死：本插件图标必含 `#4FB477` 与 `#B48EFF` 两色，红圈图标两色皆无）。用户看到的「很多」里过半是第三方插件的标记，本插件的问题集中在 190 / 211 / 214 三行的重复。

### 1.2 单击第一枚图标 → EDT 冻结 70 秒（四份转储同栈）

用户附的四份转储一致指向同一条链（帧是**调用点**而非函数入口，行号与 master 源码自洽）：

```
Freeze in EDT for 70 seconds / Sampled time: 37700ms
"AWT-EventQueue-0"  ... NestedLocksThreadingSupport.runReadAction → smartAcquireReadPermit
                        → RunSuspend.await → SuvorovProgress.showNiceOverlay
                        → EventStealer.waitForPing      ← EDT 在「等读许可」

"ApplicationImpl pooled thread 5x" RUNNABLE
  ReadAction.computeBlocking / ReadAction.compute
  ← SqlPreviewService$Companion.previewAsync(SqlPreviewService.kt:2229)
  ← CustomDaoHqlGutterLineMarkerProvider$...navigate(SqlPreviewService...:111)
  ← SqlPreviewService.previewCustomDaoHql(SqlPreviewService.kt:1103)
  ← SqlPreviewService.resolveHqlTable(SqlPreviewService.kt:1939)
  ← SqlPreviewService.findClassByEntityName(SqlPreviewService.kt:1994)
  ← PsiShortNamesCacheImpl.getClassesByName → StubIndex → 索引文件随机读（持久化 IO）
```

`SqlPreviewService.kt:1994` 正是 `findClassByEntityName` 慢路径的内层循环体，对应 master 源码：

```kotlin
// SqlPreviewService.kt:1990-1994
// 慢路径：全量扫项目类短名，匹配 @Entity(name=...) 注解值（jakarta/javax/短名三轨）
val namesCache = PsiShortNamesCache.getInstance(project)
for (shortName in namesCache.getAllClassNames()) {
    for (clazz in namesCache.getClassesByName(shortName, scope)) {   // ← 1994
```

即：**全项目类短名逐个索引查询**，在池化线程的 `ReadAction.compute`（不可取消）里连续跑了 70 秒，期间读锁一直被持有；EDT 侧同刻在等读许可，平台画 `SuvorovProgress` 覆盖层并接管事件（`EventStealer`），于是「空白窗 + 关不掉」。点击栈里的 provider 是 `CustomDaoHql`——这正是 190 行两枚图标中至少一枚的来源。

### 1.3 非回归

本 demo 文件 `38e6361`（2026-10-07，T-ExistsChainTerminal 复现用例）才加入 demo 仓；触发缺陷的两条判据均早于此：`matchesHqlContent` 的共享提取器与 `else -> expression.text` 兜底同为 `bc5a7be`（2026-08-21）引入，此后被 T-Perf-02 等重构按语义保留。**本次不是 T-ExistsChainTerminal 的回归**，只是它的复现语料第一次撞上这条老缺口。

## 二、根因

### 2.1 根因 A：内容启发把「整条链的源码文本」当成 HQL 首参

判定三段门（`CustomDaoHqlLocator.kt:293 isCustomDaoHqlCall`）：`(DAO qualifier 名后缀 OR 查询方法名关键字) AND 内容启发`。

- 第一段在本 case 成立：`userMapper` 以 `Mapper` 结尾（`CustomDaoHqlLocator.kt:123 DAO_QUALIFIER_SUFFIXES`）。
- 第二段被这条链骗过：`matchesHqlContent`（`:372`）取**首参**后交给 `extractArgumentText`（`:452`）→ `extractHqlString`（`:407`）；首参是 wrapper 链（`PsiMethodCallExpression`），既不是字面量、不是拼接、也不是引用，于是落到兜底分支：

```kotlin
// CustomDaoHqlLocator.kt:437
else -> expression.text to false     // ← 把「整条链的源码文本」当字符串返回
```

链文本里嵌着 `.exists("select 1 from t_order o where o.user_id = user.id")`，`HQL_CONTENT_PATTERNS` 的 `SELECT ... FROM` / `FROM <标识符>` 遂命中（`CustomDaoHqlLocator.kt:194-199`）→ **纯 MyBatis-Plus 文件被认成「自定义 DAO HQL 调用」**。

同一兜底分支还让 `locateFromCall`（`:246`）把 `hql` 填成整条链源码、`isLiteralHql=false`，交给预览链去解析——这正是 1.2 里 70 秒扫描的输入（实体名由 `HqlEntityNameExtractor` 的 `FROM_ENTITY` 正则从链文本里抓出，预期为 `t_order`）。

命中面不止 `exists`：链内任何带 SQL 文本的条件方法（`.apply` / `.inSql` / `.last` / `.exists` / `.notExists`）落在以 `*Mapper`/`*Dao`/`*Repository` 结尾的接收者调用上，都会复现。demo 该文件里这类形态计 8 处。

### 2.2 根因 B：同一行第二枚图标来自「批量操作锚点把查询当批量」

`WrapperAnchorDetector.kt:116` 场景 D 只要方法名在 `BatchOperationConverter.batchOperationMethodNames` 里就认锚点，而 `selectList` 正收录于该注册表（`BatchOperationConverter.kt:103`）。`userMapper.selectList(...)` 链首是 `userMapper`（不是 wrapper 构造/工厂），`initializerHeadIsWrapper`（`:176`）判否 → 不触发既有的「链上去重」，于是 `selectList` 这个标识符上也挂了一枚图标。

由此得出的**可判别指征**（全部与实测行一一吻合，是根因 A+B 的直接指纹）：

- 三行 `userMapper.selectList(<wrapper 链或 wrapper 变量>)` → 2 枚（假 HQL 锚点 + 批量锚点）；
- 四行 `Wrappers.<User>lambdaQuery(...)` → 1 枚（场景 C 工厂锚点，正确）；
- 一行 `userMapper.selectPage(new Page<>(1, 10), ...)` → **0 枚**：`selectPage` 不在批量注册表，且首参是 `new Page<>(...)`，`expression.text` 里没有 `SELECT/FROM` → 两条误判都不成立。这一行的「干净」反过来证实了根因 A 的判据就是「首参文本」而不是「链里有没有 SQL 串」；
- 两行 `.exists(...)` / `.notExists(...)` → 0 枚（无 provider 认领，与既有口径一致）。

### 2.3 根因 C：冻结是「无界索引扫描 + 不可取消读锁」叠加平台锁语义

- 直接成因：`findClassByEntityName`（`SqlPreviewService.kt:1967`）的慢路径没有任何上限与中断点；对不存在的名字（`t_order`）必然扫完全部短名。项目越大越慢，实测 70s。
- 放大成因：`previewAsync`（`SqlPreviewService.kt:2223`）用 `ReadAction.compute`（不可取消）包住整条转换链，读锁在整个扫描期间不放；同时不判 `DumbService`，索引尚未就绪时也照跑。
- 平台侧：转储只证「本插件线程持读锁 70s」+「EDT 在等读许可」，EDT 那次读请求的发起方是第三方插件（`dev.coolrequest...RunMethodEditorMouseMotionListener.mouseMoved` 在 EDT 上直接 `ApplicationImpl.runReadAction`）。两者之间的因果顺序列入待验证前提（§五.1）。**但无论 EDT 的等待由谁触发，「预览链里持读锁做无界索引扫描」都必须修**——它本身就是一个 70 秒无反馈、无法取消的点击。

### 2.4 影响面判断

- 用户可见：同一语句两枚图标、点其中一枚长时间无响应且窗口关不掉（本次）；`selectList(wrapper)` 那枚若按批量模板出 SQL，还会漏 wrapper 条件（待验证，见 §五.3）。
- 非用户可见但同源：`locateFromCall` 把链源码当 hql 存进 `CustomDaoHqlInfo`，任何命中此形态的预览都基于垃圾输入。
- 与既有护城河的关系：本仓已经历四轮「同行双图标」修补（T-GutterDedup / T-M19.5 / T-M23.11 / T-DaoSql-03），全是**逐对打补丁**，没有一条覆盖全局的不变量。本次是第五类形态，按老办法补丁可以过关，但同类还会再来（R70 登记根治方向）。

## 三、方案

### S1（D108）内容启发只认「字符串承载」的首参，禁掉源码文本兜底

1. `CustomDaoHqlLocator.kt:437`：`else -> expression.text to false` 改为**返回 `null`**（即「无法静态取得字符串」= 不是 HQL 承载）。该分支同时是 `matchesHqlContent` 与 `extractHqlFromCall` 的公共上游，一处收口两头受益（识别不再误判、预览不再拿到整条链源码）。
2. `CustomDaoHqlLocator.kt:372 matchesHqlContent`：显式要求首参是「字符串承载式」——字面量 / 字符串拼接（`PsiBinaryExpression`、`PsiPolyadicExpression`）/ 解析到 `String` 的引用；其余形态（方法调用链、`new` 表达式、类字面量、`Page` 等）一律判否。仓内已有可对齐的既有形态：`PreviewReceiverResolver.isStringLikeFirstArg`（T-GutterDedup 落地），实施时优先复用它而不是新写一套判定。
3. 影响面闭合：`extractHqlString` / `extractArgumentText` 仅在 `CustomDaoHqlLocator` 内使用（全仓 grep 实测）；`HqlQueryLocator` 有同名私有函数但不同源，本次不动。

**反向验证**：把 `:437` 恢复成 `expression.text to false` → `DemoRecognitionTest` 新增的「`userMapper.selectList` 处 0 图标」断言必红，贴两次读数。

### S2（D109）批量锚点遇 wrapper 实参让位

`WrapperAnchorDetector.kt:116` 场景 D 分支补一条让位判定（与既有的 `initializerHeadIsWrapper`（`:176`）、`hasStringLikeArg`（`:128`）同族，纯 O(1) 语法级）：

- 实参中任一为 wrapper 链（复用 `initializerHeadIsWrapper(arg)`）或 wrapper 变量（复用 `WrapperLocator.isWrapperVariable`）→ `return null`，让位给链锚点。

效果：190 / 211 / 214 各剩 1 枚（链根行那枚）；`selectList(null)`（无 wrapper 实参）保留批量图标；`getById(id)` / `saveBatch(list)` 等形态不受影响。

注意不要与 `BATCH_CHAIN_COLLISION_NAMES`（`:47`，仅 `selectAll`，走变量 resolve）混写：那套判的是 **qualifier 形态**，本守卫判的是**实参形态**，语义不同、可并存。

**反向验证**：摘掉该守卫 → 190 / 211 / 214 回到 2 枚 → 必红。

### S3（D110）把无界索引扫描变成有界 + 可取消

1. `SqlPreviewService.kt:1967 findClassByEntityName` 慢路径（`:1990-2005`）加三道护栏：
   - 名字形态门控：`entityName` 不是 Java 类名形态（含 `_`、以数字开头等）直接返回 null，**不进**全项目扫描——本次的 `t_order` 即被这条挡住；
   - 扫描上限 + `ProgressManager.checkCanceled()`：进入慢路径后按短名数设上限（初值建议 2000），超限记账并降级为未命中；
   - 命中即止（现有逻辑已有 `break`，保留）。
2. `SqlPreviewService.kt:2223 previewAsync`：`ReadAction.compute` 换成可取消读（`ReadAction.nonBlocking{}`），或在转换前判 `DumbService.isDumb(project)` 走「索引未就绪，稍后再点」轻提示（仓内先例：`execute/DataSourceSelector.kt:3245` 的 `isDumb`、`:457` 的 `runWhenSmart`）。
3. 长期根治方向登记 R70（不现做）：以 `@Entity(name=)` 注解值的专用索引替代「全项目短名 + 反射式注解扫描」；以及预览弹窗改为「先弹后填」（可取消、可关闭），把长任务从点击路径上摘下来。

**反向验证**：把形态门控摘掉 → 新增「非类名形态不触发全项目扫描」用例必红。

### S4 测试与反向验证

- `DemoRecognitionTest`（现仅覆盖 `jpa-hibernate-demo`，95 行）扩到 `mybatis-plus-demo`，对该文件断言：四行 `Wrappers.<User>lambdaQuery` 各 1 枚；三行 `userMapper.selectList(...)` **0 枚**；`userMapper.selectPage(...)` 0 枚；`exists`/`notExists` 0 枚；**全文件本插件图标总数 4**（现 10）。
- `GutterIconDedupTest`（271 行，14 个「恰 1 图标」用例）新增两例合成语料：①`*Mapper` 结尾接收者 + 首参 wrapper 链内嵌 SQL 串；②链内嵌 SQL 串 + `selectList(wrapper 变量)`。
- 新增 `CustomDaoHqlLocator` 用例：`locate(selectListCall) == null`（直击根因 A，无需经 provider）。
- 新增慢路径护栏用例：非类名形态不触发扫描（计数探针或把门控抽成纯函数单测）。
- 反向验证按 §S1/S2/S3 各摘一次，两次读数在册。

## 四、改动清单

| 文件 | 位置 | 改动 |
|---|---|---|
| `psi/jpa/CustomDaoHqlLocator.kt` | 437 | `else -> expression.text to false` → `null`，并改 KDoc 说明「非字符串承载 = 非本定位器家族」 |
| `psi/jpa/CustomDaoHqlLocator.kt` | 372-376 | `matchesHqlContent` 增「字符串承载式」判定（优先复用 `PreviewReceiverResolver.isStringLikeFirstArg`） |
| `psi/WrapperAnchorDetector.kt` | 116-139 | 场景 D 增「实参含 wrapper 链/变量 → 让位」判定（复用 `initializerHeadIsWrapper` / `WrapperLocator.isWrapperVariable`） |
| `ui/SqlPreviewService.kt` | 1967-2005 | `findClassByEntityName` 慢路径加名字形态门控 + 扫描上限 + `checkCanceled` |
| `ui/SqlPreviewService.kt` | 2223+ | `previewAsync` 改可取消读 / 或加 `DumbService.isDumb` 降级提示 |
| `src/test/.../gutter/DemoRecognitionTest.kt` | 全文 | 扩 `mybatis-plus-demo` 目标 + 上述图标数断言 |
| `src/test/.../gutter/GutterIconDedupTest.kt` | 尾部 | 两例新语料 |
| 新增测试类 | `psi/jpa/` 或 `ui/gutter/` | 定位器直判 + 慢路径护栏用例 |

不动：`CustomDaoHqlGutterLineMarkerProvider` 的锚点策略（锚定方法名 identifier 是有意设计）、`HqlEntityNameExtractor` 提取口径、`BatchOperationConverter` 模板、bundle 文案、`plugin.xml`。

## 五、待验证前提

1. **EDT 70s 等待与本次点击的因果序**：转储证「本插件持读锁 70s」+「EDT 等读许可」，但 EDT 那次读请求来自第三方插件（CoolRequest 鼠标移动监听）。验证法：同一文件重复点击，分别在「S1 修复后（不再进慢路径）」与「临时把 gutter 图标总开关关掉」两态采样转储，看 EDT 是否仍卡；若仍卡，则 EDT 侧问题独立存在（本任务只负责把我们的读锁持有时间压到有界）。
2. **空白窗归属**：按现时序（先算后弹，`previewAsync` → `showResult`）本插件预览对话框在冻结期间不该存在；需真机复现确认是平台 `NiceOverlay` 还是「先前一次点击留下的对话框未绘制」，用 `[T-PreviewFast] request→dialog` 日志 + 截图 + 转储三方对齐。**验证前不要在文档里断言「本插件弹窗导致空白窗」。**
3. **第二枚图标（批量锚点）的产物**：`BatchOperationConverter.buildSelectBatch`（`:528-558`）对 `selectList` 走 `FULL_COLUMN_SELECT_METHODS` 分支，模板是「全列 SELECT + 逻辑删除条件」，**看起来不消费 wrapper 条件**；但 `preview()`（`SqlPreviewService.kt:212-236`）先走 `WrapperLocator`。需 headless 断言 `preview(selectListLeaf).sql` 是否含 `username LIKE ? AND EXISTS (...)`：含 → S2 只是去重；不含 → 该枚还会给出**漏 WHERE 的错误 SQL**，S2 升级为正确性修复（并把该结论写进变更记录）。
4. **`HqlEntityNameExtractor` 对本 case 的实际提取值**：按 `FROM_ENTITY`（`[A-Z]` + `IGNORE_CASE`）预期是 `t_order`，用单测钉死；它决定 S3 的名字形态门控是否正命中（若提取出的是别的形态，门控条件要跟着改）。
5. **其余 provider 的同族误判**：本次只实测到 CustomDaoHql 与场景 D 两处，需按「首参不是字符串就不该认」的口径各复核一遍（`JdbcTemplateSqlLocator`、`StringSqlMethodGutterLineMarkerProvider` 已有 `isStringLikeFirstArg` 形态，预期清白，但要读一遍而不是假定）。
6. **`ReadAction.nonBlocking` 在 243/262 双目标可用性**：需按平台源码/javap 核验（本仓既有先例只在 `DataSourceSelector`/`McpToolsetSupport` 用 `DumbService`，没有 `nonBlocking` 的现成站点），若签名/语义有版本差，S3.2 退回「`isDumb` 降级 + 扫描上限」这一条最小改动。

## 六、验收标准

1. demo 该文件：四行 `Wrappers.<User>lambdaQuery` 各 1 枚图标；三行 `userMapper.selectList(...)` 0 枚；`selectPage` / `exists` / `notExists` 行 0 枚；全文件本插件图标总数 4（用 `DemoRecognitionTest` 等值断言，不靠肉眼数图）。
2. 单击任意一枚图标：有结果或给出可读降级文案，等待时间有界（目标 1s 量级）；不出现空白窗、不出现「关不掉」；索引期点击走轻提示不阻塞。
3. 既有行为零回退：HQL / JPQL / JDBC / HibernateTemplate / 自研 DAO 的图标与预览用例全绿（`GutterIconDedupTest`、`DemoRecognitionTest`、`CustomDaoHql*Test` 及其余 gutter 用例）。
4. 定向读数 + 反向验证各两次读数 + `testFast` 冒烟在册；全量门禁按三档分层统一收口执行。

## 七、文档同步（实施后）

1. 本计划文档 §八实施记录回填（改动落点、定向读数、反向验证两次读数、实施偏差）。
2. `docs/变更记录.md` 顶部追加 2026-10-08 行（含占号 O-124/R70/D108~D110/V132~V134）。
3. `docs/MEMORY.md`「需求文档与开发计划」节加一行索引。
4. `docs/memory-archive/pitfalls/pitfall-custom-dao-gutter-dedup.md` 补第三类形态：「内容启发把整条链源码当 HQL 首参 → 以 `*Mapper` 结尾的接收者上凭空亮图标」。
5. 发行说明并入下次发行（本任务不改 `docs/发行说明/`）。

## 八、实施记录

**实施日期**：2026-10-08；分支 `t-gutterdupfreeze`（工作树 `mybatis-plus-sql-visualizer-gdf`）；实施提交 `99d2f36`（计划落库 `dd0bd26`）。

### 8.1 改动落点（7 文件）

| 落点 | 改动 |
|---|---|
| `psi/jpa/CustomDaoHqlLocator.kt:372-386` | `matchesHqlContent` 增首参「字符串承载式」门控（复用 `PreviewReceiverResolver.isStringLikeFirstArg`，与 HIBERNATE_TEMPLATE 家族同口径） |
| `psi/jpa/CustomDaoHqlLocator.kt:427-437` | 拼接分支「全无字面量部分」由 `expression.text to false` 改 `null to false` |
| `psi/jpa/CustomDaoHqlLocator.kt:446-452` | `else` 兜底由 `expression.text to false` 改 `null to false`（「整链源码当 HQL」的最终落点） |
| `psi/WrapperAnchorDetector.kt:132-139` | 场景 D 增 `hasWrapperArgument` 让位（紧跟既有 `hasStringLikeArg` 让位之后） |
| `psi/WrapperAnchorDetector.kt:205-231` | 新增 `hasWrapperArgument`：链实参走零 resolve 的 `initializerHeadIsWrapper`；变量实参先看声明/推断类型，类型不可得才 `resolve` 兜底 |
| `psi/jpa/EntityAliasSweep.kt`（新增） | 有界扫描对象：上限 5000 + 每 64 个短名一次 `ProgressManager.checkCanceled` + 命中即止；判定逻辑与 PSI 取数解耦（可纯用例驱动） |
| `ui/SqlPreviewService.kt:1967-2016` | `findClassByEntityName`：索引构建期（dumb）按未命中降级且**不写负缓存**；慢路径改调 `EntityAliasSweep`，触顶同样不写负缓存 |
| 测试 | `DemoRecognitionTest` +83 行（真实 demo 文件断言）、`GutterIconDedupTest` +79 行（两个合成用例 + 按 provider 集计数的重载）、`EntityAliasSweepTest` 新增 4 例 |

### 8.2 定向读数

`./gradlew test`（六类过滤）→ **50 用例 / 0 失败 / 0 错误**：`DemoRecognitionTest` 3、`GutterIconDedupTest` 16、`EntityAliasSweepTest` 4、`CustomDaoHqlLocatorTest` 19、`CustomDaoHqlGutterLineMarkerProviderTest` 7、`GutterProvidersNegativeTest` 1（XML 时间戳 `2026-10-08T05:30Z`＝13:30 本地，实证真跑，非 up-to-date）。

过程记录两处非产品缺陷：①首轮**测试期望写错**——把方法体内 `lambdaQuery` 调用数写成 3，实为 4（变量声明形态那条也在 `PsiReferenceExpression` 父级下），改断言为分布 `[0,1,1,1]`（变量声明那条按既有去重规则让位给变量名锚点）；②**编译红一处**——`PsiShortNamesCache.getClassesByName` 返回 `Array`，`EntityAliasSweep.lookup` 签名随之为 `Array<PsiClass>`。

冒烟（改动触达所有 wrapper 锚点的 daemon 热路径与实体解析链，仅跑 gutter 类不够）：`./gradlew testFast --rerun` → **3406 用例 / 0 失败 / 0 错误（266 套件）**，XML 时间戳区间 `2026-10-08T05:36:54Z`~`05:37:30Z`＝13:36~13:37 本地；`--rerun` 强制真跑，避开「任务 UP-TO-DATE 未执行」的假绿形态。

### 8.3 反向验证（三轮，各自摘守卫 → 必红；标记行与读数同批落日志，读数自我说明）

| 轮次 | 摘除内容（日志标记） | 结果 | 失败消息 |
|---|---|---|---|
| R1 | `CustomDaoHqlLocator.kt` 还原为修复前版本（`T-GutterDupFreeze` 命中 0、`isStringLikeFirstArg` 1） | 42 例 / **2 红** | demo 用例与合成「链实参」用例均 `selectList(...) 处应 0 图标 expected:<0> but was:<1>`；**变量实参用例保绿**（该形态只依赖 S2） |
| R2 | `WrapperAnchorDetector.kt` 还原为修复前版本（`hasWrapperArgument` 0） | 38 例 / **3 红** | demo 用例 + 链实参 + 变量实参三例同款 `expected:<0> but was:<1>`（R1 里保绿那例此轮转红＝两轮互证归属） |
| R3 | `EntityAliasSweep` 上限判定临时改 `scanned >= Int.MAX_VALUE`（不可触顶） | 23 例 / **1 红** | `testTruncatesAtLimit`：`触顶回调应报告已扫数＝上限 expected:<100> but was:<-1>`；同批 19 条定位器用例保绿（无连坐） |

三轮每轮跑前有还原/改写标记行，跑后 `git checkout HEAD -- <文件>` 恢复；末次核对 `git status` 干净、工作树与提交逐字节一致（即产出 50/0 读数的那棵树）。

诚实边界：R1/R2 的红由用例内**首条**断言触发，同一用例里其余断言（`selectPage` 0 枚、`exists/notExists` 0 枚、链根分布 `[0,1,1,1]`、方法体总数 4）只被绿跑覆盖「成立」，其「判别力」未逐条反向。

### 8.4 实施偏差（相对计划）

1. **计划 §S3 的「名字形态门控」未做**：`t_order` 是合法 Java 标识符形态，形态门控会误伤合法的 `@Entity(name = "t_order")` snake_case 别名（拿功能损失换不来必要收益）→ 改由「dumb 期降级 + 上限 + 取消检查」三件承担。
2. **S3.2 两个候选中选 dumb 降级**，且降级点由 `previewAsync` 收进 `findClassByEntityName` 内部——爆炸半径更小，与索引无关的预览路径不受影响；`previewAsync` 的 `ReadAction.compute` → 可取消读（`nonBlocking`）**未做**，挂 R70（需 243/262 API 核验 + 全预览入口回归）。
3. **新增 `EntityAliasSweep` 可测试抽离**（计划未点名，为反向验证服务）；重构后命中路径直接返回扫描中命中的 `PsiClass`，原实现是再按 `qualifiedName` 二次 `getClassesByName`——语义等价。
4. **上限取 5000（计划草案写 1500）+ 触顶不写负缓存**（计划原文是「照常缓存负结果」）：常规工程类短名在千级，上限功能上等于不设限，只钉住病态/超大工程的最坏代价；触顶是「没查到」而非「确认不存在」，写负缓存会让大工程里的合法别名被一次性截断后永久失效。

### 8.5 遗留与未闭合项

- **R70 三件**：①以 `@Entity(name=)` 注解值专用索引替代全项目短名扫描；②预览弹窗改「先弹后填」，把长任务从点击路径上摘下来；③`psi/EntityClassFinder.kt:94-115 scanAllClassesByTableName` 是**同类第二条无上限全项目短名扫描**（在预览反查表名链上；有缓存与 VO/DTO 快筛，但同样无界、无 dumb 判定）——本轮按计划范围未改，建议随 R70 一并处置。
- **待验证前提 #1/#2（需真机复验）**：EDT 那次读请求的发起方（第三方 CoolRequest 的鼠标移动监听在 EDT 取读权限）与本插件长读锁之间的因果序；以及用户所报空白窗的归属（平台 `NiceOverlay` vs 上一次点击残留的对话框）。本轮回填不作结论。
- **待验证前提 #3 风险归零**：第二枚图标（批量锚点）的点击产物是否漏 WHERE——S2 后该锚点不再产生，入口不可达。
- **待验证前提 #4**：`t_order` 的实际提取值经静态判定（`HqlEntityNameExtractor.FROM_ENTITY` 带 `IGNORE_CASE`，可匹配小写首字母）＝`t_order`；S1 后该输入不再进入该路径，故未补独立单测。
- **未跑**：全量门禁（`testShards`）与构建校验（`buildPlugin`/`verifyGate`）——按测试三档分层由统一收口执行；本任务触达 `src/main` 但未动构建脚本、`plugin.xml`、依赖版本。
