# Kotlin → Java 全量改写 · 进度总结（2026-09-01）

> 分支：`feature/kotlin-to-java`（基于 `master` @ `2521d33` / F-44）
> 工作树：`mybatis-plus-sql-visualizer-kotlin-to-java`（非 IDE 主工作区 `mybatis-plus-sql-visualizer`）
> 包根：`cn.song.mybatisplus.visualizer` · JVM 17 · Gradle Kotlin DSL · SDK IntellijIdeaUltimate 2024.3.7.1
> 配套计划：[`20260831-2350-Kotlin转Java全量改写-开发计划v2.md`](./20260831-2350-Kotlin转Java全量改写-开发计划v2.md)

---

## 0. 一句话状态

主源码与测试源码**当前 `compileJava` + `compileKotlin` + `compileTestKotlin` 全部 BUILD SUCCESSFUL（0 错误）**。本轮把卡住全量构建的 **3,939 处测试编译错误全部归零**，方式是把调用 Java 生产 API 的 **92 个 Kotlin 测试文件改写为 Java**。剩余 46 个主源码 `.kt` 与 140 个测试 `.kt` 仍按共存策略保留（均可编译）。**全量测试已跑绿（1421 测试 / 139 套件，0 失败，6 跳过）**；`verifyPlugin` 的 2 个兼容性 problem（缺失资源键）已修复，复验中。

---

## 1. 累计成果（实测 inventory）

| 维度 | 数量 | 说明 |
|------|------|------|
| 主源码 Java 文件 | **264** | 迁移产物（含 3 个既有手写 Java 样板） |
| 主源码 Kotlin 文件（仍保留） | **46** | config 9 / model 12 / psi 10 / sql 14 / update 1 |
| 测试 Java 文件 | **93** | 本轮新增 92 个转换 + 既有 1 个（`JavaCodeGeneratorTest`，前序会话） |
| 测试 Kotlin 文件（仍保留） | **140** | 均编译通过，且只调用 Kotlin 友好 API（model 等仍是 Kotlin），无需转换 |
| 测试编译错误 | **3,939 → 0** | 本轮收敛结果 |

> 注：计划 v2 目标「233 测试文件全量转 Java」。实际为**达到编译绿灯所需的最小转换集 92 个**（其余 140 个 Kotlin 测试本身不触碰已转 Java 的生产类、且 model 等包仍保留 Kotlin，因此保留 Kotlin 完全可编译可运行）。若后续要把 test 源集也 100% 纯 Java，再补转这 140 个即可，不影响功能。

---

## 2. 关键技术决策与踩坑（本次最重要的经验沉淀）

以下结论均经**实证**，非猜测，应作为后续同类迁移的硬规则。

### 2.1 测试必须转 Java，不能靠「加 `-parameters`」或「还原 object 回 Kotlin」
- 探查实验：在 `build.gradle.kts` 已有 `-parameters` 的情况下，Java 生产类被 Kotlin 测试用具名参数调用仍报 `Named arguments are prohibited for non-Kotlin functions`。**`-parameters` 只保留形参名供 Java 侧反射，并不能让 Kotlin 对 Java 函数用具名参数。**
- 还原实验：曾尝试把 `MpjQueryConverter`/`MpjLambdaConverter` 等 5 个由 `object` 误转的 Java 类 `git checkout master` 还原回 Kotlin `object`，但 worktree 的 Java 生产已与 master Kotlin 发生 **API 漂移**（`asValue` 改名、psi 包 Java 用 `.INSTANCE` 等），还原后 `compileKotlin` 失败。**已 `git checkout HEAD --` 回退**。结论：worktree 生产代码已是事实上的 Java 基线，测试只能对齐它。

### 2.2 Kotlin → Java 互操作铁律（转换测试时逐条遵守）
1. **具名参数禁用**：对 Java 函数一律用**位置参数**。Kotlin data class 的具名参数/`copy` 在 Java 侧不可用。
2. **`object` → `final class X { public static final X INSTANCE = new X(); }`**：Kotlin 的 `X.method()` 在 Java 必须写 `X.INSTANCE.method(...)`。本轮大量测试因 `SqlGenerator`/`ConverterRegistry` 各 Converter/`VersionComparator`/`NamingStrategy`/`PluginUpdateRunner`/`XmlParamTypeResolver`/`HqlConverter`/`WrapperLocator`/`CriteriaQueryLocator` 等 former `object` 而修正。
3. **Java `record` 访问器是 `x()`（无 get 前缀）**；普通 Java 类用 `getX()`；Kotlin data class 仍生成 `getX()`/`componentN()`。本轮 `SqlReverseResult`(12 组件)、`LogSqlEntry`、`ReverseJoin`、`ReverseSet`、`XmlPreviewState` 等 record 全部按 `x()` 访问，且**无默认值** → 构造时 12 参全补。
4. **`copy(...)` 不可用** → 用全参 `new X(...)` 位置构造（必要时写小 helper 工厂复用默认值）。
5. **受检异常**：`ReadAction.compute(new ThrowableComputable<T, Throwable>())` 的 `compute() throws Throwable` 在 Java 非法，改为 `ThrowableComputable<T, RuntimeException>` + `@SuppressWarnings("deprecation")`（对齐 Kotlin `@Suppress("DEPRECATION")`）。
6. **文本块**：Kotlin `"""…""".trimIndent()` → Java `"""…"""` + `.stripIndent()`；`${'$'}`/`\$` 转义需逐字复刻（如 `XmlPreviewModelTest`/`HeavyXmlParamTypeResolverTest`）。
7. **跨包不可见成员**：测试与被测类不同包时，若生产成员为包级私有（如 `SqlPreviewPanel.paramFieldForTest`、`SqlLogPanelKt.*`），经反射访问，**不改生产**。
8. **密封类/接口**：`PluginUpdateRunner.CheckResult`/`InstallResult` 在 Java 侧按 `new …Available(manifest)` / `UpToDate.INSTANCE` / `InstallResult.Failed("…")` / `Installed.INSTANCE` 建模；`UpdateSectionTest` 的 `Function0`/`Function2` 用 SAM lambda，`restartAction` 返回 `kotlin.Unit.INSTANCE`。
9. **集合**：`List.of`/`Map.of`(≤5 对)/`Set.of`；有类型见证用 `List.<T>of(...)`；空集合用 `List.of()`。
10. **JUnit**：`org.junit.Test` + `org.junit.Assert`；`assertEquals(expected, actual)` 顺序与 Kotlin TestNG 风格一致，保持不变。

### 2.3 SDK 真实签名反查（避免猜重载）
`EntitySelectionDialog.java` 的 `ChooseByNamePopup.createPopup(project, new GotoClassModel2(project), null)` 曾在 Java 侧误用 `(Consumer<Language>) null`。经 `javap` 反查 `app-client.jar` 确认真实重载仅 `(Project, ChooseByNameModel, PsiElement)` / `(Project, ChooseByNameModel, ChooseByNameItemProvider)`，改为 `(PsiElement) null` 并清理孤儿 import（`Language`/`Consumer`），编译通过。

### 2.4 唯一一处生产可见性改动（ benign，已复核）
前序子代理将 `reverse/JavaCodeGenerator.javaEscape` 由 `private` 改为 `public static`（测试需直接调用做转义断言）。diff 确认**仅此一处可见性变化、逻辑未动**，属合理的测试可用化改动，保留。其余生产文件本轮未被测试转换子代理触碰。

---

## 3. 本轮推进明细（测试编译归零）

| 步骤 | 动作 | 结果 |
|------|------|------|
| 复核子代理产物 | 确认 `JavaCodeGeneratorTest.java` 正确、`JavaCodeGenerator.javaEscape` 仅可见性变化 | 该文件 0 错误，验证「测试转 Java」路线可行 |
| 中央编译基线 | `compileTestKotlin` | 3,939 错误 / 91 文件 |
| 第一波（并行 8 子代理） | 转换最高错误量的 59 个测试文件（按包聚类） | 错误 3,939 → 108 / 32 文件 |
| 第二波（并行 4 子代理） | 转换剩余 32 个低错误量文件（含 1 次 agent 瞬时失败重投 log 集群 18 文件） | 错误 108 → **0** |
| 终验 | `compileTestKotlin` | **BUILD SUCCESSFUL，0 错误，24 警告** |

> 子代理模式：读取真实生产 `.java` 签名 → 同目录/同包写 `.java` → 删原 `.kt` → 不碰 `src/main` → 不跑 gradle（规避 daemon 锁冲突，由主控统一编译）。参考样板 `reverse/JavaCodeGeneratorTest.java`。

---

## 4. 验证链状态

| 验证项 | 命令 | 状态 |
|--------|------|------|
| 主源码编译 | `compileJava compileKotlin` | ✅ BUILD SUCCESSFUL（前序会话已稳） |
| 测试编译 | `compileTestKotlin` | ✅ BUILD SUCCESSFUL（本轮） |
| 全量测试 | `./gradlew test` | ✅ BUILD SUCCESSFUL（**0 失败** / 1421 测试 / 139 套件，6 跳过） |
| 插件校验 | `verifyPlugin` | 🟡 2 compatibility problems（缺失资源键 `log.panel.jumpToSource.*`）已修复；复验因沙盒网络 SSL 受限（Remote host terminated handshake）无法连接 JetBrains 仓库，环境性阻断（非代码）。首次完整验证（IU-243/253/262）确认仅此 2 处且已修复 |
| 产物 | `buildPlugin` | ✅ BUILD SUCCESSFUL（资源齐全、无 `$Kotlin` 元数据残留） |

---

## 5. 运行时回归修复（全量测试 6 → 0）+ 资源键缺口

### 5.1 全量测试结果
- 初跑（`testrun4`，沙盒重定向 + 2g 堆）：**1421 tests, 6 failed, 6 skipped**。
- 复测（`testrun8`）：**0 failed**（139 套件全部 `failures="0"`）。
- 6 失败全部为 Kotlin→Java 转换语义差异（非功能缺失），均为单行 / 局部修复。

### 5.2 六处失败根因与修复
| # | 失败用例 | 根因（Java vs Kotlin 语义差异） | 修复 |
|---|---------|-------------------------------|------|
| 1–3 | `PluginSettingsConfigurableTest` / `PluginSettingsPanelBindingTest` → `PluginException: Light service class ...SqlLogManager must be final` | IntelliJ light service 容器强制 `@Service` 类 `final`；Kotlin 类默认 final，Java 类默认非 final | `log/SqlLogManager.java:32` `public class` → `public final class` |
| 4–5 | `SelectStarDetectorTest` `detect("SELECT u.* FROM t_user u")` / `detect("\`u\`.*")` 误判 false | 正则 `^[\w]+\*$` 不含 `\.`，无法匹配 `u.*`（`u` 后 `.` 再 `*`） | `sql/extract/SelectStarDetector.java:19` `^[\w]+\*$` → `^[\w]+\.\*$` |
| 6 | `OgnlEvaluatorTest` `assertEquals(42L, eval("42"))` → `expected:<42> but was:<42.0>` | Java 三元 `cond ? Double.parseDouble : Long.parseLong` 触发**二进制数值提升**（long→double），整数归一成 Double 42.0；Kotlin `if/else` 分别装箱保 Long | `xml/OgnlEvaluator.java` `number()` 改 `if/else` 语句分别赋值 `Object` |
| 附 | `OgnlEvaluatorTest` `eval("'%' + null + '%')` 原 NPE | `null.toString()` 在 Java 抛 NPE；Kotlin `Any?.toString()` 对 null 返回 `"null"` | `xml/OgnlEvaluator.java` `plus()` 加 `toStr()` 空安全辅助 |

> ⚠️ **团队级陷阱（已沉淀）**：JLS 15.25 规定 `boolean ? doubleExpr : longExpr` 的结果为 `double`（二进制数值提升），与 Kotlin `if/else`「分别装箱」语义不同。凡「数字字面量需保住 Long/Integer 原始类型」的场景，**必须用 `if/else` 语句分别赋值给 `Object`，绝不能用三元表达式**。

### 5.3 测试运行环境踩坑（Windows）
- 全量 `./gradlew test` 默认堆下 `OutOfMemoryError` → init script 提 `maxHeapSize=2g` + G1GC。
- `prepareTestSandbox` 复制 `jsqlparser-5.3.jar` 时若被外部进程（IDE/索引器）内存映射锁定，报「请求的操作无法在使用用户映射区域打开的文件上执行」；且 `rm -rf` 沙盒会挂死（Windows 无法删 memory-mapped 文件）。
- **规避**：init script 把 `test`/`prepareTestSandbox` 的 `sandboxDirectory` 重定向到 `build/test-sandbox/default`（绕开被锁 `.intellijPlatform/sandbox`）。脚本位于 `/tmp/redirectsandbox.gradle`（沙盒重定向 + `maxHeapSize=2g`）。

### 5.4 verifyPlugin 兼容性缺口（缺失资源键，pre-existing master 缺陷）
`verifyPlugin`（对 IU-243 / 253 / 262 三版本）报 **2 compatibility problems**：
```
#Reference to a missing property log.panel.jumpToSource.notFound  of resource bundle messages.MpSqlVisualizerBundle
#Reference to a missing property log.panel.jumpToSource.noSource of resource bundle messages.MpSqlVisualizerBundle
    Method cn.song.mybatisplus.visualizer.log.SqlLogPanel.jumpToSource(LogSqlEntry)
```
- 根因：`SqlLogPanel` 引用 `log.panel.jumpToSource.noSource` / `notFound`，但资源包仅在 `log.history.*` 前缀下定义了这两个键，`log.panel.*` 前缀缺失 → 运行期点「跳转源码」会抛 `MissingResourceException`。
- **已确认非本分支回归**：master 的 `SqlLogPanel.kt`（L846 / L852）引用完全相同的键，master 资源包同样缺失 → 这是 master 既有缺陷，转换被忠实保留。
- 修复：在 `MpSqlVisualizerBundle.properties` 与 `_zh.properties` 的 `log.panel.jumpToSource.*` 段补齐 `noSource` / `notFound` 两条键（英文 / 中文各一），文本对齐 `log.history.*` 对应键。
- 复验状态：首次完整验证（IU-243/253/262 三版本）确认仅此 2 处 compatibility problems 且已修复；后续 `verifyPlugin` 重跑因沙盒网络 SSL 受限（Remote host terminated handshake，无法连接 JetBrains 仓库下载 IDE）环境性失败，**非代码问题**。修复逻辑确定（补的正是 verifier 报缺的 2 个键）。

### 5.5 对比 master 查漏补缺结论（零功能丢失）
- 对比 master `src/main/kotlin`（213 类）与分支联合（java 263 + kotlin 47 = 309 类），仅 4 类「缺失」实为**重命名**：
  - `Ognl` → `OgnlEvaluator.java`
  - `WrapperFormat` → `WrapperFormatKt.java`（+ 新增 `ReverseWrapperFormat` 枚举）
  - `LogEntryPreviewRecognizer` → `LogEntryPreviewRecognizerKt.java`
  - `LogSqlModels` → `LogSqlModelsKt.java`
- `plugin.xml` 无指向已改名类的陈旧引用，干净。**无生产功能丢失**。

## 6. 剩余工作（查漏补缺方向）

1. **主源码 46 个 `.kt`**：model(12)/psi(10)/sql(14)/config(9)/update(1)。按「全量纯 Java」目标应继续转，但当前与 Java 共存编译通过；建议按模块优先级推进（model → psi → sql → config → update），每模块同步转对应测试。
2. **全量测试跑绿**：等待 `./gradlew test` 结果，修复任何运行时回归（重点：record 与 data class 的 `equals/hashCode` 差异、集合顺序、null 处理、文本块空白差异）。
3. **`verifyPlugin` / `buildPlugin`**：确认无 Verifier 违规、产物无 Kotlin 元数据残留。
4. **对比 `master` 查漏**：`git diff master...feature/kotlin-to-java` 复核 API 漂移、未转生产类、功能缺失，逐项补正。
5. **可选**：把剩余 140 个 Kotlin 测试也转 Java，使 test 源集 100% 纯 Java（计划 v2 原目标）。

---

## 7. 提交与分支

- 关键节点已按约定自动提交（每模块波次一个 commit，见 `git log`）。
- 本总结文档与测试转换成果将作为本轮关键节点提交，提交时**排除临时调试文件**：`.orig/`、`__pycache__/`、`_parse_be.py`、`_spreview_orig.kt`、`be*.txt`。
- 分支策略：保持 `feature/kotlin-to-java`，`master` 不动；可 `git revert` 至任意阶段。

---

## 8. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-31 | v2 计划（评估，增量共存迁移策略锁定） |
| 2026-09-01 | 主源码波次收尾（`EntitySelectionDialog` 两处修复）+ **测试编译 3,939→0**，92 测试文件转 Java，本总结文档 |
| 2026-09-01 | **运行时回归修复（全量测试 6→0）**：`SqlLogManager` 加 `final`（消 3 PluginException）、`SelectStarDetector` 正则补 `\.`（消 2 断言）、`OgnlEvaluator` 三元数值提升陷阱改 `if/else`（消 `42.0` 类型）+ `plus()` null 空安全；补齐 `log.panel.jumpToSource.noSource/notFound` 资源键（消 verifyPlugin 2 compatibility problems，master 既有缺陷）；全量测试 0 失败、buildPlugin 通过 |
| 2026-09-02 | **Wave 1 + Wave 2（9/10）主源 Kotlin→Java**：model 12 文件 + psi 9 文件（PsiDialect/WrapperCache/ExpressionEvaluator/KtWrapperDetector/EntityClassFinder/WrapperAnchorDetector/EntityResolver/ArgResolver/BatchOperationConverter）转 Java，每批全量测试 1421/0/0/6 绿后提交（`ad34b26`→`e913f3c` 共 8 commit）；详见下方第 9 节 |
| 2026-09-02 | **Wave 2 完结（10/10，`868808c`）**：`WrapperLocator.kt`（1223 行）+ `LocatedWrapper` 转 Java，63 处调用点去 INSTANCE、internal 去 mangled；转换期修复 `expandHelperCalls` 越界差异（1 测试暴露）；全量 1421/0/0/6 绿。**psi 包主源 `.kt` 清零**，剩余 24（sql 14 / config 9 / update 1） |

---

## 9. Wave 1 / Wave 2 推进明细（2026-09-02）

### 9.1 波次状态

| 波次 | 范围 | 状态 |
|------|------|------|
| Wave 1 | model 12 `.kt` → Java | ✅ 完成（`ad34b26`，首轮子代理遗留 2 个测试失败已修复） |
| Wave 2 | psi 10 `.kt` → Java | ✅ **完成**（10/10，`868808c`；psi 包主源 `.kt` 清零） |
| Wave 3 | sql 14 + interceptor 3 | 待开始 |
| Wave 4 | config 9（含 `PluginSettingsPanel.kt` 1161 行） | 待开始 |
| Wave 5 | `update/StartupUpdateCheck.kt` + `buildPlugin`/`verifyPlugin` 终验 | 待开始 |

- 剩余主源 `.kt`：**24**（sql 14 / config 9 / update 1）；主源 Java 文件 **302**（含新增 `LocatedWrapper.java`）。
- 基线：全量测试 **1421 / 0 失败 / 0 错误 / 6 跳过**（每批提交前复验；`XmlPreviewPanelTest` 出现过 1 次 `cannot open system clipboard` 环境性偶发失败，重跑即绿，与本轮改动无关）。

### 9.2 Wave 1 回归根因（重要教训）

- 首轮子代理把 `return modelBuild(BatchModel(...)).copy(paramGroups = paramGroups)` 改写成丢弃 `.copy` 的形式 → `BatchBuild.paramGroups` 恒为 null → 批量路径 `paramTable` 为 null（2 个测试失败）。已恢复 3 处 `.copy`（buildUpdateBatch / buildInsertBatch / buildUpsertBatch）。
- **教训：子代理批量改写后必须逐处核对「链式调用尾部方法是否被静默丢弃」；同类型相邻位置参数逐一对照构造器签名。**

### 9.3 object→Java 转换模式与踩坑沉淀

- `object Foo` → `final class Foo` + `INSTANCE` + static 转发器；**Java 不允许同签名 static+instance 并存**——`WrapperAnchorDetector` 采取全 static 方案，Java 调用点同步改为 `WrapperAnchorDetector.anchorElement(...)`。
- **测试字节码过期假失败**：Gradle 不检测测试字节码过期，object 转 Java 后测试仍按旧 Kotlin 实例方法调用编译 → `IncompatibleClassChangeError`。对策：每次转换后 `./gradlew compileTestKotlin --rerun-tasks` 强制重编译再跑 test。
- **Kotlin `internal` 成员从 Java 调用需 mangled 名**（`xxx$mybatis_plus_sql_visualizer`）；对应类自身转 Java 后须回头去 mangled（`BatchOperationConverter` 转 Java 时已同步修正 `WrapperAnchorDetector`/`SqlPreviewService` 调用点；`WrapperLocator` 转 Java 后还剩 `WrapperSearchService.java:111` 一处）。
- **Kotlin 侧调用 Java static getter 不做属性语法合成**：`WrapperLocator.kt` 中 `BatchOperationConverter.batchOperationMethodNames` 需改为 `BatchOperationConverter.getBatchOperationMethodNames()`。
- 其他：`reverseLookupCacheInfo` 返回 `kotlin.Pair<Integer,Integer>` 保 Kotlin 解构测试；`Map.of/Set.of` 迭代序未定义→ 首个命中即返回的场景用 `LinkedHashMap`；Java `String.regionMatches` 无 ignoreCase 重载（Kotlin 扩展）→ 手写比较；companion `const val` → 外层静态字段（`PluginSettings.INFERENCE_SNAKE`）。

### 9.3.1 WrapperLocator 转换（Wave 2 最难，1223 行，`868808c`）

- **全 static final 类方案**：63 处 `WrapperLocator.INSTANCE.` 调用点 sed 批量去 INSTANCE（5 主源 Java + 2 测试 Java）；Kotlin 测试 `WrapperLocator.locateWithTable(...)` 语法对静态方法天然兼容。
- **internal 成员去 mangled 并常量化**：`wrapperFactoryMethodNames`→`WRAPPER_FACTORY_METHOD_NAMES`、`wrapperFactoryClassNames`→`WRAPPER_FACTORY_CLASS_NAMES`、`serviceChainMethodNames`→`SERVICE_CHAIN_METHOD_NAMES`、`delete/count/pageConsumerMethodNames`→`DELETE/COUNT/PAGE_CONSUMER_METHOD_NAMES`（外部代码引用仅注释，零影响）；`isServiceChainStart`/`isWrapperVariable`/`isChainHeadCall`/`isSqlCandidate`/`detectWrapperType` 转 public static。
- **顶层 `LocatedWrapper` data class 拆独立文件**（外部以 `psi.LocatedWrapper` 引用；Java 一文件一 public 类）；`entityFileStamp` 构造时计算；测试访问 `getChain()/getTable()/entityFileValid()`。
- **转换期发现 2 个真差异**：① Kotlin 具名实参构造 `LocatedWrapper(chain=…, table=…)` 对 Java 构造器非法（测试改位置实参）；② **`getParameter(index)` 越界返回 null vs Java 数组下标抛 `ArrayIndexOutOfBoundsException`**（`expandHelperCalls` 实参数多于形参数场景，1 个测试失败暴露）——已加边界检查。
- 语义保真要点：`distinctBy{endOffset}`→LinkedHashSet；双键 `compareBy`→`Comparator.comparingInt().thenComparingInt()`；PSI 引用相等去重（`seenIfStatements`）用 IdentityHashMap-backed Set；扩展函数 `consumingCall()`→静态方法；解构返回值→私有嵌套 holder 类。

### 9.4 验证流程（每批固定）

```
./gradlew compileJava compileKotlin -I /tmp/redirectsandbox.gradle
./gradlew compileTestKotlin --rerun-tasks -I /tmp/redirectsandbox.gradle
./gradlew test -I /tmp/redirectsandbox.gradle   # 目标 1421/0/0/6，XML 计数核对
git add -A && git commit
```
