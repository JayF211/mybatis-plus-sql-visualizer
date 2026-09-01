# Kotlin → Java 转换规则手册（本轮大波次统一基准）

本手册是所有转换 Agent 的唯一权威。目标：**100% 功能等价**、可评审、可编译、测试全绿。
项目：IntelliJ 插件，`jvmToolchain(17)`（可用 `record`/`sealed`/`switch` 表达式/模式匹配/`var`/文本块）。
Kotlin/Java 同 source set 共存——转换时**删 .kt、加同包 .java**，跨语言互操作天然成立。

## 0. 铁律（违反即回退）
1. **只翻译实现，绝不改公开 API**：类名、方法名、参数名与类型、返回类型、包名、注解、可见性
   （public/protected/internal→public、private 保持）必须与原 Kotlin **逐字一致**。内部私有名可重命名。
   - `internal` → `public`（Kotlin internal=模块内可见，Java 无对应，public 是其超集，安全）。
2. **保留全部行为分支与边界**：`when` 的 else、`?.` 的 null 分支、异常的 catch、early return 一律保留。
3. **中文注释 / 文档整体保留**，转为 Java 注释（`//` 或 `/** */`）。不得丢失业务注释。
4. **不得引入新第三方依赖**，不得改变序列化/字节码对外契约（plugin.xml、META-INF 不变）。
5. 转换完**删除原 .kt 文件**，写入 .java（同目录同包）。

## 1. 文件级结构
- Kotlin 文件 `Xxx.kt` 含：类/对象/顶层函数/顶层 val。
- 若文件只有一个 public 类 `Xxx` → Java `Xxx.java`（同名）。
- 若有**顶层函数 / 顶层 val / 扩展函数** → 额外生成 `XxxKt.java`，其中：
  - 顶层 `fun foo(...)` → `public static R foo(...)`
  - 顶层 `val/const FOO` → `public static final R FOO`（const → `static final` 字面量）
  - 扩展 `fun Type.foo(...)` → `public static R foo(Type receiver, ...)`；其余参数紧随。
  - 调用这些函数时一律写 `XxxKt.foo(...)`（含跨包）。全量转换后由中央 fix 阶段统一收口。
- 伴生对象 `companion object { ... }`：成员直接提升为 enclosing 类的 `static`。
  - `const val`/`val` → `private static final` 或 `public static final`（看可见性）。
  - `fun` → `public static`。
  - 若伴生含复杂初始化/方法且被 `Xxx.Companion` 显式引用，则保留 `public static final class Companion` 内部类。

## 2. 数据类型
- `data class X(val a: String, val b: Int)`：
  - 全 `val`、无方法体、无继承 → **`public record X(String a, int b)`**；
  - **强制**：每个组件上方写 `/** @param a 说明 */` 或行内 `///` 注释（用户硬规则：record 必须带字段注释）。
  - 含 `var` 或 1+ 方法 → 用 `@lombok.Value`/`@Data`（允许）或手写 POJO（private final 字段 + 构造 + getter +
    equals/hashCode/toString）。手写时 equals/hashCode 需覆盖全部字段。
- `enum class` → Java `enum`（保留 entries 与 `companion`/方法）。
- `sealed class/interface X` → Java `sealed interface/abstract class X permits A,B,C`（Java 17 sealed）。
  `when(x)` 对 sealed 做穷尽 → `switch (x) { case A a -> ...; case B b -> ...; }`（模式匹配）。
- `object X` 单例 → `public final class X { public static final X INSTANCE = new X(); private X(){} ... }`
  （或若仅静态方法则直接全 static）。调用处 `X.INSTANCE.method()`。

## 3. 空安全（最高频，务必精确）
- `String?` → `@Nullable String`；非 null `String` → `@NotNull String`（用 `org.jetbrains.annotations`）。
- `a?.length` → `a != null ? a.length : null`（或先 `if (a == null) return null;` 再 `a.length`）。
- `a?.foo()` → `a != null ? a.foo() : null`。
- `a ?: b` → `a != null ? a : b`。`a ?: throw ...` → `if (a == null) throw ...;`（随后用 a）。
- `a!!` → `Objects.requireNonNull(a)` 或 `if (a == null) throw ...`。
- `x?.let { it -> ...use it... }` → `if (x != null) { ...use x... }`（注意返回值：let 返回 lambda 结果，
  若要保留返回值用 `var res = x != null ? (...) : null;`）。
- `x?.also { it -> sideEffect(it); }` → `if (x != null) { sideEffect(x); }` 返回 x。
- `x.apply { field = v }` → `x.field = v; return x;`（apply 返回 receiver）。
- `x.run { ... }` → 内联，将 `this` 替换为 x。
- `with(x) { ... }` → 内联，this 替换为 x。
- `foo ?: return` / `?: continue` 等控制流 → 对应 `if (foo == null) return;`（之后用非空 foo）。

## 4. 集合（java.util，语义等价）
- `listOf(a,b)` → `List.of(a,b)`；`mutableListOf()` → `new ArrayList<>()`；`ArrayList()` → `new ArrayList<>()`。
- `mapOf(k,v,...)` → `Map.of(...)`（≤10 项）或 `new LinkedHashMap<>(){{put(...);}}`/builder；`mutableMapOf` → `new HashMap<>()`。
- `setOf(...)` → `Set.of(...)`；`mutableSetOf` → `new HashSet<>()`。
- `emptyList()` → `List.of()`；`emptyMap()` → `Map.of()`。
- `arrayOf(...)` → `new T[]{...}`。
- `list.filter { }` → 用循环或 `list.stream().filter(...).toList()`（Java 16+ `toList()` 可用）。
  优先保持原有求值顺序与短路语义；collect 到 `new ArrayList<>()` 亦可。
- `mapNotNull`/`filterNotNull`/`flatMap`/`groupBy`/`associateBy`/`fold` → 等价循环或 stream，注意空值处理。
- `sequence {}` / `buildList {}` / `buildMap {}` → 直接构造对应集合。

## 5. 控制流
- `when` 常量分支 → `switch` 表达式（`case A -> result;`），其余 → `if/else`。
- `if (c) a else b` 作表达式 → `c ? a : b` 或带 `yield` 的 `switch`。
- `for ((k,v) in map)` → `for (var e : map.entrySet()) { var k=e.getKey(); var v=e.getValue(); }`。
- `try { } catch (e: Ex) { }` → Java `try/catch (Ex e)`。
- `suspend fun`：本项目仅个别，且无协程调用——直接去掉 `suspend`，当普通方法（确认无 `callSuspend`/续体使用）。
- `by lazy { ... }` → `private static final X INSTANCE = ...` 急切实例化，或 `AtomicReference` 懒加载；
  若语义要求线程安全懒加载，用 `static class Holder { static final X V = ...; }` 或 `Suppliers.memoize`。
- `inline`/`reified`：去掉 `inline`；`reified T` 改为显式 `Class<T>` 参数（调用处传入实际类）。

## 6. 注解与导入
- 空注解：`@NotNull`/`@Nullable` 来自 `org.jetbrains.annotations`。
- 若原 Kotlin 使用了 `@Test`/`@NotNull`(kotlin) 等，映射到对应 Java 注解。
- 导入保持最小必要集合，删无用 import（编译会报错，fix 阶段亦会清理）。

## 7. 验证（各 Agent 自测口径）
- 转换后该文件的 Java 在**隔离层面**应自洽：公开签名与原 Kotlin 一致、无残留 Kotlin 语法
  （`val`/`fun`/`?.`/`?:`/`when`/`it`/`object`/`data class`/`is` 模式等）。
- 不得出现未定义符号；跨包符号用全限定或 `XxxKt.` 形式预留，由中央编译兜底。
- 不要求 Agent 单独跑 Gradle（由中央统一 `compileJava`/`compileKotlin` 把关）。

## 8. 禁止
- 禁止"顺手重构"：不要重命名公开符号、不要改变算法、不要删除"看似无用"的分支/字段。
- 禁止引入 Kotlin 仍依赖的运行期行为差异（如 Kotlin `==` 是 `equals`，Java 对象 `==` 是引用——
  字符串/包装类型比较务必用 `.equals()`，集合比较用 `equals`）。
- 禁止把 `List.of` 用于**之后会被 mutate** 的场景（应用 `new ArrayList<>()`）。

## 9. 转换顺序（反向拓扑，自顶向下）—— 已踩坑修正
**错误做法（已回滚）**：先转叶子包（model/util）。后果：叶子类转 Java 后丢失 Kotlin 特有语义
（默认参数、命名参数、object 表达式），而仍大量为 Kotlin 的调用方成片断裂，逐个打补丁不可收敛。

**正确做法**：Kotlin↔Java 边界**自上而下**推进。每转一个包，必须保证**它的所有 Kotlin 调用方
都已在更上层转完**（即该包在"剩余 Kotlin 调用图"中是入度为 0 的顶层）。转完后它调用的下层
（仍 Kotlin）由 Java 按**全参**调用，不破坏。

**首批准许转换的 0 入边包（已用脚本计算，无其它 Kotlin 包 import 它们）**：
```
converter.jpa(5)  database(1)  psi.jpa(10)  share.format(3)  share.ui(1)
sql.extract(4)  sql.interceptor(3)  sql.perf(3)  sql.perf.rules(3)
ui.completion(4)  ui.components(1)  ui.diagram(3)  ui.diff(1)  ui.gutter(9)  ui.highlight(13)
```
随后按入度升序逐层下推（actions/config/sql/ui/xml/psi/model/util 留到最后）。
**`model`/`util` 必须最后转**：届时所有调用方已是 Java，可安全去掉默认参数（Java 调用方传全参）。

## 10. 默认参数硬规则（已踩坑，违反即编译断裂）
Kotlin `= xxx` 默认参数在 Java 中**无法表达**，且 Kotlin 调用方依赖它 → 转 Java 后两类断裂：
(a) `No value passed for parameter`；(b) `Named arguments are prohibited for non-Kotlin functions`。
**处置**：
1. 若该类仍有 Kotlin 调用方（未转完）→ **保留为 Kotlin**，待末波次与调用方一并转。
2. 若已无 Kotlin 调用方（全 Java）：用**构造器重载**保留默认值（等价 Kotlin `@JvmOverloads`）：
   ```java
   public Foo(int a, String b) { this(a, b, "默认"); }
   public Foo(int a, String b, String c) { ... }
   ```
   严禁裸 `record` 丢默认值。
3. `object` 单例且方法被 Kotlin 以 `X.method()` 对象表达式调用 → Java 必须 `public static final X INSTANCE`
   + 实例/静态方法；`@JvmStatic fun` → `public static` 方法（含默认参数重载）。调用方若仍 Kotlin 且用
   `val b = X` 捕获单例 → 同样**保留为 Kotlin** 至末波次。
4. **`data class.copy(field = ...)` 在 Java 中无等价物**：Java record 不生成 `copy`；即便保留 Kotlin data class，
   从 Java 调 `copy` 须传全部参数（默认参数对 Java 不可见），不可行。
   → **所有使用 `xxx.copy(...)` 的文件，必须与 model 包在末波次一并迁移**（届时 model 已是 Java，
   调用方改写为 `new Xxx(...)` 全参构造，或 model 提供 `withXxx(...)` 等价方法）。
   `sql.interceptor`（DataPermissionPlaceholder/DynamicTableNameProcessor/TenantLineEnhancer）即此例，已推迟。

## 11. 本分支进度基线
- 干净基线：212 主源 .kt + 4 .java（原 3 + `util/Nulls.java`），`compileKotlin` 绿。
- 已转（正确模式验证）：`database/DatabaseConsoleLauncher` 1 文件。
- 严禁在"自底向上"顺序下批量转包；一律按 §9 的反向拓扑序推进。
