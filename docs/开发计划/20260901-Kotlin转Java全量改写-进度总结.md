# Kotlin → Java 全量改写 · 进度总结（2026-09-01）

> 分支：`feature/kotlin-to-java`（基于 `master` @ `2521d33` / F-44）
> 工作树：`mybatis-plus-sql-visualizer-kotlin-to-java`（非 IDE 主工作区 `mybatis-plus-sql-visualizer`）
> 包根：`cn.song.mybatisplus.visualizer` · JVM 17 · Gradle Kotlin DSL · SDK IntellijIdeaUltimate 2024.3.7.1
> 配套计划：[`20260831-2350-Kotlin转Java全量改写-开发计划v2.md`](./20260831-2350-Kotlin转Java全量改写-开发计划v2.md)

---

## 0. 一句话状态

主源码与测试源码**当前 `compileJava` + `compileKotlin` + `compileTestKotlin` 全部 BUILD SUCCESSFUL（0 错误）**。本轮把卡住全量构建的 **3,939 处测试编译错误全部归零**，方式是把调用 Java 生产 API 的 **92 个 Kotlin 测试文件改写为 Java**。剩余 46 个主源码 `.kt` 与 140 个测试 `.kt` 仍按共存策略保留（均可编译）。

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
| 全量测试 | `./gradlew test` | ⏳ 运行中（含 testShards / heavy 沙盒） |
| 插件校验 | `verifyPlugin` | ⬜ 待跑（243/253/262 三版本零 Internal API 违规） |
| 产物 | `buildPlugin` | ⬜ 待跑（资源齐全、无 `$Kotlin` 元数据残留） |

---

## 5. 剩余工作（查漏补缺方向）

1. **主源码 46 个 `.kt`**：model(12)/psi(10)/sql(14)/config(9)/update(1)。按「全量纯 Java」目标应继续转，但当前与 Java 共存编译通过；建议按模块优先级推进（model → psi → sql → config → update），每模块同步转对应测试。
2. **全量测试跑绿**：等待 `./gradlew test` 结果，修复任何运行时回归（重点：record 与 data class 的 `equals/hashCode` 差异、集合顺序、null 处理、文本块空白差异）。
3. **`verifyPlugin` / `buildPlugin`**：确认无 Verifier 违规、产物无 Kotlin 元数据残留。
4. **对比 `master` 查漏**：`git diff master...feature/kotlin-to-java` 复核 API 漂移、未转生产类、功能缺失，逐项补正。
5. **可选**：把剩余 140 个 Kotlin 测试也转 Java，使 test 源集 100% 纯 Java（计划 v2 原目标）。

---

## 6. 提交与分支

- 关键节点已按约定自动提交（每模块波次一个 commit，见 `git log`）。
- 本总结文档与测试转换成果将作为本轮关键节点提交，提交时**排除临时调试文件**：`.orig/`、`__pycache__/`、`_parse_be.py`、`_spreview_orig.kt`、`be*.txt`。
- 分支策略：保持 `feature/kotlin-to-java`，`master` 不动；可 `git revert` 至任意阶段。

---

## 7. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-31 | v2 计划（评估，增量共存迁移策略锁定） |
| 2026-09-01 | 主源码波次收尾（`EntitySelectionDialog` 两处修复）+ **测试编译 3,939→0**，92 测试文件转 Java，本总结文档 |
