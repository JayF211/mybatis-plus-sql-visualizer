# 高保真求值模式评估报告（T-FidelityEval）

**创建时间**：2026-09-18
**任务**：T-FidelityEval（第七轮建议 N-27，P2，评估先行）
**来源**：第七轮报告 §7 zMyBatis 精华清单 Z-1（真 MyBatis 引擎求值）+ Z-10（classloader 隔离防全局污染）
**性质**：评估型——本文是结论与建议，不是实施。四问各有量化结论，最终 go/no-go 交用户定案。
**基线**：分支 `t-fidelity-eval`，基线提交 8a3a6ff（dev 合流后）。
**方法**：源码精读（xml 求值管线 16 文件 / 2284 行 + 三处先例文件）+ git 全分支历史归因 + 变更记录与开发计划回溯 + headless 隔离 classloader spike 实录。

---

## 0. 结论先行

**建议 go-替代：采纳方案 C（只建语义对照测试集），并行推进方案 B（自研求值器按 O-31 补预检层与 if test 静默点修复）；方案 A（真引擎生产旁路）建议 no-go。**

理由一句话：历史偏差账里标签语义只占 20%，而真引擎并不解决占 80% 的 mock 值语义与面板回填问题；更关键的是真引擎「按参数值判定分支」与现行面板「按 test 表达式开关判定分支」在模型层面不兼容，旁路要付出 7~17 人天的同时还会丢掉 M24 建立的核心交互。而方案 C 用 1~2 人天就能把真引擎的语义背书变成回归资产，且零生产风险。

spike 结果是跑通的（隔离 `URLClassLoader` + 纯反射驱动 `getBoundSql`，宿主进程零 mybatis 依赖，OGNL 全局状态不跨 loader），所以 no-go 不是「做不到」，而是「不值得做成生产路径」。

---

## 1. 收益量化（问一）

### 1.1 统计口径

范围：自研求值管线相关的缺陷修复条目，即解析 / 求值 / 渲染 / 参数回填四条链，时间跨度 M24（2026-08-14 引擎骨架落地）至 T-DollarInjectMark（2026-09-15）。来源三处交叉：`git log --all --oneline -- src/main/kotlin/cn/song/mybatisplus/visualizer/xml/`、`docs/变更记录.md` 的 M24~M29 与 T-M25.x 段、`docs/memory-archive/` 中相关条目（`project-pages-xml-preview.md`、`project-kotlin-to-java-port-sync.md` 等，后者仅记录 xml 层数据结构约定，无求值语义缺陷条目）。

纯面板布局类（T-M26.1 分隔条、T-M26.2 按钮定位、T-M26.3 分区标题）与补全类（T-M27.x）不计入求值账；格式化类（T-M29.2/29.3）与求值无关，另列备注。

### 1.2 缺陷账清单（15 条）

| # | 提交 | 条目 | 归因面 | 真引擎能否自动避免 |
|---|------|------|--------|--------------------|
| 1 | `d5dcd55` | T-M25.1 官方 DOCTYPE 被自建 DOM 解析器拒绝（`disallow-doctype-decl`），改三重 XXE 防护 | 解析器自建 | 可避免——真引擎 `XMLMapperEntityResolver` 从 jar 内 DTD 本地解析，spike 实证无联网 |
| 2 | `18fadbe` | T-M25.2 OGNL 静态字段引用 `@class@field`（用户实测） | 表达式语义 | 可避免——真 OGNL 原生支持，spike 实证 `@demo.Const@FLAG` 直接取到值 |
| 3 | `0e2eb2c` | T-M25.2 两套判真（ExpressionEvaluator vs OgnlOps）+ or/and 返回值语义对齐 + `\|\|X\|\|` 剥壳 | 表达式语义 | 部分——判真与 or/and 真引擎天然正确；但 `\|\|X\|\|` 真引擎抛 `ExpressionSyntaxException: Malformed OGNL expression`（spike 实证），我方宽容是超集，换成真引擎反而少一个能力 |
| 4 | `b17d691` | T-M25.2 预览入口漏传 `project` 致静态常量解析跳过 | 插件接线 | 不可避免（真引擎同样需要 classpath 上下文） |
| 5 | `a19b3bd` | T-M25.4 foreach 参数回填（`__frch_` 名不过滤） | 面板回填 | 不可避免 |
| 6 | `a60bf4a` | T-M25.4 foreach 集合值输入（`2,3` → `IN (2, 3)`） | 面板 UX | 不可避免 |
| 7 | `06ebb9a` | T-M25.4 逐字符输入时集合回填漏填 | 面板 UX | 不可避免 |
| 8 | `3a35139` | T-M25.5 foreach 迭代内容 trim（IN 单行）+ bind 自动求值回显 | 显示形态 / mock 回显 | 不可避免——真引擎原样保留换行缩进（spike 嵌套 foreach 输出空白噪声实证），trim 是我方产品偏好；bind 回显真引擎不提供 |
| 9 | `347d162` | T-M26.4 删 bind 分区 + T-M26.5 foreach 行精简 + LIKE 参数引号（数字启发式误判） | 面板 + mock 值语义 | 引号部分不可避免（mock 策略两套引擎都要自己定） |
| 10 | `70f8383` / `80f1be9` | IN 子句参数标记 `inList`（面板逗号分割回填） | 面板回填 | 不可避免 |
| 11 | `c322f32` | T-M29.4 XML 参数回填默认引号（用户实测日期列比较失效） | mock 值语义 | 不可避免——真引擎按参数 Java 类型绑定，但「用什么值」仍由我方决定 |
| 12 | `9b6af09` | T-M29.5 参数类型感知（`XmlParamTypeResolver`） | mock 值语义 | 不可避免——真引擎要真实类型对象，同一工程量；且拿 mock 字符串喂真引擎，分支判定照样与真实运行时分叉（spike §1.3 实证） |
| 13 | `c3ee1bd` | T-Perf-04 击键防抖 + OGNL 解析缓存 | 性能 | 不可避免——真引擎每次重建 Configuration / 重解析 mapper 更重 |
| 14 | `aa607c4` | T-DollarInjectMark `${}` 直替计数在 foreach/trim 过滤上下文丢失 | 自研上下文结构 | 半——真引擎无此计数功能（需自建），但其直替语义天然正确 |
| 15 | `3959a8e` | M26-B `${ew.customSqlSegment}` MP 占位展开 | MP 生态 | 不可避免——真引擎需要 wrapper 对象，非引擎能力 |

按计划口径二分：

| 面 | 条数 | 占比 |
|---|---:|---:|
| 标签/表达式/解析语义（真引擎可自动避免） | 3（#1 #2 #3） | 20% |
| mock 值语义 | 3（#9 引号部分 #11 #12） | 20% |
| 面板回填 / UX / 接线 / 性能 / 显示 / MP 生态（与引擎选型无关） | 9（#4 #5 #6 #7 #8 #10 #13 #15 + #3 的 `\|\|` 半条） | 60% |

结论口径与计划预判一致：**历史偏差的大头不在标签语义**。真引擎能覆盖的只有 20%，且这 3 条里还有 1 条（`||X||` 剥壳）是我方比真引擎更宽容的超集能力——换引擎等于主动放弃。

### 1.3 两条补充证据

其一，foreach / trim / choose 的**标签语义本身零缺陷修复**。`ForeachSqlNode.kt` 自 M24 落地后只被三类提交改过：静态字段支持（`18fadbe`）、显示 trim（`3a35139`）、计数转发（`aa607c4`），加一次 Kotlin→Java 移植（`d32b3d1`）——没有一条是嵌套遮蔽、separator 惰性插入、Map 迭代 index=键 这些最容易出错的地方。`TrimSqlNode.kt` 同样零语义修复。M24 交付时这些算法就是照 MyBatis 3.5.19 逐条复刻并带测试落地的，两年下来没有回修。这直接反驳了「标签语义维护税高」这个立项前提。

其二，mock 值语义的分叉是真引擎**无法**解决的问题，spike 有实录：

```
q2 count=Integer 0        → SQL = SELECT * FROM t                  （if 判假）
q2 count=Integer 1        → SQL = SELECT * FROM t AND c = ?        （if 判真）
q2 count=String "1"（我方 mock 形态）→ SQL = SELECT * FROM t AND c = ?  （if 判真）
```

真引擎按真实值判定，我方 mock 是统一字符串 `"1"`。把 mock 值原样喂给真引擎，`count != 0` 依旧判真、与真实运行时（Integer 0 判假）分叉——问题只是从「自研 OGNL 的 mock 语义」平移到「真 OGNL 的 mock 语义」，收益为零。要真解决，必须构造真实类型对象（第 2 阶段成本），而那条路与引擎选型无关，自研路径同样可以走（方案 B/C 覆盖）。

### 1.4 在册未修的标签语义缺口（唯一的实质反向证据）

`IfSqlNode.kt:20-23`：test 表达式求值失败时静默按 true 展开且不记 errors，用户看不到「这个条件没算出来」。真引擎不会静默——spike 实证非法表达式直接抛 `BuilderException: Error evaluating expression ... Malformed OGNL expression`，失败可见。

这是自研管线在标签语义面上的真实正确性缺口，且已经立项（O-31 / T-ExprAstPrecheck 的 T-EP-02）。把它算进账，标签语义面为「3 条已修 + 1 条在册」，占比 25%。这是支持真引擎的最强论据，但它的修复成本是 1~2 人天（方案 B），不需要真引擎。

---

## 2. 成本核算（问二）

### 2.1 分阶段估算

| 阶段 | 工作内容 | 关键风险 / 隐藏成本 | 人天 |
|---|---|---|---:|
| P1 配置输入最小方案（真引擎启动） | 反射桥（`Class.forName` + Method 从声明类取，零依赖）；隔离 `URLClassLoader` 生命周期；内存流 + 资源名注册 mapper XML；databaseId 维持 mock null；include/refid 复用既有 `XmlIncludeExpander` 预展开 | **mapper 解析期 resolve resultType/parameterType/别名**——指向隔离 classpath 不存在的类即整体解析失败（spike §3.1 实录：`Could not resolve type alias 'demo.Missing'`），需要兜底清洗（剥离这些属性再解析）或要求用户 classpath 完整 | 2~3 |
| P2 参数真实类型对象构造 | 复用 `ui/XmlParamTypeResolver` 读取链（namespace → `findClass` → 语句方法 → `@Param` 值/参数名 → 参数类型），升级为类型类别 + 实例构造器（标量/日期/枚举/集合元素/DTO 字段递归） | 与 T-ParamTypeContract（N-26）的 T-PT-01 `ParamKind` 是同一基础件，可共建；**本阶段与真引擎不强绑定**，可延后——最小方案先跳过 | 3~4（其中 2 与 N-26 重叠） |
| P3 用户库 URL 收集 + child-first 隔离 classloader | 全仓零先例（实测无 `LibraryUtil`/`OrderRootType` 使用，`ModuleRootManager` 仅源根探测）：需新增 `OrderEnumerator` / `OrderRootType.CLASSES` 枚举（模块 + SDK + 依赖库 + 传递依赖）、读权限包裹、项目模型变更失效重建；`org.apache.ibatis.` 前缀 child-first、其余委派 parent；loader 缓存与项目关闭释放 | loader 泄漏与重建成本；跨 classloader 反射纪律要在每个调用点落实（`DatabaseSchemaResolver.kt:37-40` 教训：实现类在非导出包时 `IllegalAccessException`）；收集不全即 P1 的硬失败 | 3~4 |
| P4 e2e 测试基建 | 测试 classpath 挂 mybatis（`testImplementation`，不违反生产红线）；测试内构造隔离 loader + fixture mapper + 期望 SQL 快照 | 与方案 C 完全重叠 | 1~2 |
| P5 对照结果展示 | 双引擎产物 diff（显示模式切换先例可参照）；差异分类（标签语义 / mock 语义） | **模型层冲突**：现行面板条件开关的 id 是 test 表达式原文、开关是分支唯一真值源（`XmlPreviewModel.kt:96`），真引擎只能按参数值判定——开关模型无法保留，要么换成「输入真实参数值」的新交互，要么双模式并存（复杂度上升） | 2~4 |

合计：

- 完整旁路（P1+P2+P3+P4+P5）：**11~17 人天**
- 最小可用旁路（P1+P3+P4+文本对照 1 天，条件开关降级为真实值输入，不做类型对象与 diff UI）：**7~10 人天**
- 仅方案 C（对照测试集，即 P4）：**1~2 人天**

### 2.2 可复用的既有件（压成本的部分）

字面量化这一层可以基本复用。真引擎 `BoundSql.getParameterMappings()` 产出的 property 名序列与现行 `RenderedXmlSql.mappings`（`ParameterMapping.property`，foreach 内为 `__frch_item_0.name` 形态）结构同形；面板消费链是 `rendered.mappings.map { it.property }` → `ParamHint` → `backfillParamsBySource`（`SqlPreviewPanel.kt:1741-1749`），换源只需一个适配器。spike 实测 q1 的 `?` 数为 4、q6 嵌套 foreach 为 6，队列顺序与 SQL 文本顺序一致，`detectInClauseParams`、`quoteFor` 这些下游逻辑无需改动。

MCP 侧 `MapperCheckTool.kt:94-103` 也消费 `rendered.mappings`，同样走适配器即可，但它意味着改造面比预览面板多一个出口。

### 2.3 长期成本（不进人天估算但必须计）

用户项目依赖升级（mybatis 3.5.x → 3.6/4.x）时的反射签名漂移、OGNL 版本差异、`mybatis-spring`/`mybatis-plus` 对 `Configuration` 的包装、多模块项目 classpath 收集不全、真引擎抛异常导致预览从「可用的近似」退化为「不可用」——这些是自研管线不存在的维护面。自研路径的失败形态是降级（`IfSqlNode` 默认展开、`${}` 空串 + 警示行），真引擎的失败形态是硬失败（spike 里两次 `BuilderException`）。

---

## 3. 架构红线核对与 spike（问三）

### 3.1 红线核对

`build.gradle.kts:103` 的 `implementation` 仅 `com.github.jsqlparser:jsqlparser:5.3`，全仓无 mybatis 依赖——**零 mybatis 依赖红线成立**。真引擎类必须从用户项目 classpath 经隔离 classloader 反射驱动。

探测先例可用：`ProjectTypeDetector.kt:226` 已有 `facade.findClass("org.apache.ibatis.session.SqlSessionFactory", moduleWithDependenciesScope)`，可直接复用来判定「用户项目是否具备真引擎条件」。

### 3.2 spike 设计与实录

限时 0.5 天预算内完成，实际在本会话内跑通。代码在系统临时目录 `C:\Users\USER\AppData\Local\Temp\mpsv-fidelity-spike\`（未进主源码树，`git status` 干净），用 JDK8 + `javac` 手工编译运行，mybatis jar 取自 `~/.gradle/caches/modules-2/files-2.1/org.mybatis/mybatis/3.5.19/`，未给插件工程加任何依赖、未跑 gradle。

关键设计：`SpikeMain.java` **编译期 classpath 不含 mybatis**（`javac -d classes src/SpikeMain.java` 即通过），全部通过 `Class.forName(name, true, isolated)` + `Method/Constructor` 从声明类获取的方式驱动——这既验证了红线可行，也顺带验证了 `DatabaseSchemaResolver.kt:37-40` 的跨 classloader 反射纪律。

实录（原样摘录运行输出）：

```
== 0. 宿主 classloader 零 mybatis 依赖核验 ==
[OK] 宿主 Class.forName(org.apache.ibatis.session.Configuration) -> ClassNotFoundException
[OK] 宿主可见 ognl? NO

== 1. 构造隔离 URLClassLoader（parent = 宿主，模拟插件 classloader）==
[OK] OgnlRuntime 由隔离 loader 载入：true
[OK] 用户实体 demo.User 由隔离 loader 载入：true

== 2. 反射构建 Configuration + 注册 mapper XML（含官方 DOCTYPE）==
[OK] mapper 解析完成（DTD 由 jar 内 mybatis-3-mapper.dtd 本地解析，全程无联网）

-- q1 if+foreach（Map 参数，真实 List<Integer>）
   SQL  = SELECT * FROM t WHERE name = ? AND id IN (?,?,?)
   ? 数 = 4
-- q2 count=Integer 0 → if 判假            SQL = SELECT * FROM t
-- q2 count=Integer 1 → if 判真            SQL = SELECT * FROM t AND c = ?
-- q3 静态字段 @demo.Const@FLAG            SQL = ... WHERE a = CONST_FROM_USER_CLASSPATH
-- q4 ${} 原始直替（含注入形态）            SQL = SELECT * FROM orders WHERE x = x' OR '1'='1
-- q5 ||X|| 非标准包裹                     抛异常: BuilderException ... Malformed OGNL expression
-- q6 foreach 嵌套（外层 OR / 内层 IN）     ? 数 = 6
-- q7 用户实体对象作 _parameter（非 Map）    SQL = SELECT * FROM t WHERE name = ?
== 3.1 resultType 指向隔离 classpath 不存在的类
[OK] mapper 解析期直接失败：BuilderException ... Could not resolve type alias 'demo.Missing'

== 4. OGNL 全局状态隔离核验（两个一次性 loader）==
[OK] loader1 OgnlRuntime == loader2 OgnlRuntime ? false（静态状态互不可见，宿主进程零污染）

SPIKE RESULT: PASS
```

结论：

1. 零依赖反射驱动形态**成立**——编译期无 mybatis、运行期靠隔离 loader 加载，`getBoundSql` 全链路跑通。
2. classloader 隔离**有效**——两个一次性 loader 的 `OgnlRuntime` 是不同的 Class 对象，MyBatis 向 shaded OGNL 注册的全局 accessor 静态表落在各自 loader 内，宿主进程零污染（Z-10 的强制配套在机制层已确认可行）。
3. 官方 DOCTYPE 由 jar 内 DTD 本地解析，无联网需求。
4. `if` / `foreach` / 嵌套 `foreach` / `${}` / 静态字段引用 / 用户实体对象作 `_parameter` 全部按预期工作；`?` 队列（parameterMappings）顺序与 SQL 一致，可作字面量化输入。

失败形态（同样重要，作为旁路脆弱性证据）：

- `||X||` 非标准包裹真引擎直接抛 `Malformed OGNL expression`，我方现有宽容能力会丢失。
- mapper 解析期 `resultType` 解析失败即硬失败（`Could not resolve type alias`）——用户 classpath 收集不全、别名未注册、多模块依赖遗漏都会让整条旁路不可用，而不是降级。

### 3.3 未验证项（诚实口径）

本次 spike 是 **headless JDK8 + 手工 javac/java**，**没有 IDE 平台参与**，因此以下均属「环境受限未验证」：

1. 262 平台上的 `OrderEnumerator` / `OrderRootType.CLASSES` 库 URL 收集（全仓零先例，是 P3 的最大未知量）；
2. 读权限（`ReadAction`）包裹下的项目模型访问、`ModuleRootManager` 在 EDT/后台线程的行为；
3. 真实 IDE 插件 classloader 作为 parent 时的委派行为（spike 用 `SpikeMain` 的 app loader 模拟，形态等价但未在 262 验证）；
4. loader 生命周期与项目关闭释放、依赖变更失效重建；
5. 真实用户项目（多模块 / mybatis-plus 包装 / 别名注册表）的 mapper 解析成功率。

这五项不改变 spike 的结论（机制可行），但它们决定了 P3 的真实成本区间，也是「no-go 不是因为做不到」这一判断的依据。

---

## 4. 替代方案对照（问四）

### 4.1 三方案

**方案 A：真引擎生产旁路（Z-1 原方案）**
用户项目 classpath 存在 mybatis 时走真引擎求值，自研降级为 fallback；配 Z-10 隔离 classloader。成本 7~17 人天。

**方案 B：同工程量投给自研求值器**
按 O-31（T-ExprAstPrecheck）补 AST 三档预检层 + T-EP-02 修 `IfSqlNode` 静默 true 缺口 + 补测试；叠加 T-ParamTypeContract（N-26）把 mock 值从「统一字符串 1」升级为按类型分派。成本 1.5~2 人天（O-31）+ 2~3 人天（N-26 中与 mock 语义相关的部分）。

**方案 C：不做旁路，只建语义对照测试集**
把 zMyBatis 旧轨思路反过来用：在**测试基建**里用真引擎批量生成期望产物，钉住自研行为，不进生产路径。成本 1~2 人天。

方案 C 的对照协议（技术上可行，已核实）：自研侧 `XmlSqlRenderer().render(node, bindings, ...)` 接受任意 `Map<String, Any?>` bindings，因此可以**绕开 mock 层**，把同一批真实值（`Integer 0`、`List<Integer>`、DTO 实例）分别喂给真引擎与自研渲染器，两套产物直接对照。断言只覆盖标签语义面（if/choose/trim/foreach/bind/`${}`/`#{}` 队列），mock 值语义差异登记为「已知差异清单」而非失败。真引擎侧用与 spike 相同的隔离 loader 形态，mybatis jar 从测试 classpath 取。

### 4.2 对比表

| 维度 | A 真引擎生产旁路 | B 自研补强 | C 语义对照测试集 |
|---|---|---|---|
| 成本 | 7~17 人天 | 3.5~5 人天 | 1~2 人天 |
| 覆盖历史偏差面 | 标签语义 20% | 标签语义 20% + mock 值语义 20%（N-26 部分） | 不修缺陷，但把全部 15 条 + 未修缺口变成可观测清单 |
| 覆盖 if test 静默缺口 | 是（真引擎抛异常，失败可见） | 是（T-EP-02 直接修） | 否（只暴露，不修） |
| 新增生产能力 | 高保真模式（对标 Z-1） | 无新增生产能力，预览可解释性提升 | 无 |
| 生产风险 | 高：解析硬失败、依赖漂移、loader 生命周期、反射纪律 | 低：纯 xml 层逻辑，`testFast` 通道可覆盖 | 零：不碰生产路径 |
| 产品交互影响 | 大：条件开关模型无法保留，需新交互或双模式 | 无（标注是增量提示） | 无 |
| 与既有计划关系 | 依赖 P3（零先例的库收集） | O-31 / N-26 已立项，本就排在实施序列里 | 独立，可随时插入 |
| 失败形态 | 硬失败（预览不可用） | 不适用 | 不适用 |
| 长期维护面 | 用户依赖版本 / OGNL 差异 / mybatis-plus 包装 / 多模块 classpath | 仅支持面演进时同步分类清单 | 场景集维护 |

### 4.3 建议

**go-替代：以方案 C 为主，方案 B 并行，方案 A 建议 no-go。**

- 方案 C 用 1~2 人天买到方案 A 大部分的信息收益——真引擎的语义背书、已知差异清单、未来自研求值器改动的回归护栏——且零生产风险。它是「不做旁路」与「做旁路」之间性价比最高的中间形态，也是计划 §三 第 4 问点名要求覆盖的第三条路。
- 方案 B 是低风险的正确性修复，且 O-31 / N-26 本就已在实施序列（第七轮报告 §9 实施顺序：T-ParamTypeContract → ... → T-ExprAstPrecheck）。真引擎唯一能覆盖的实质缺口（if test 静默 true）在这里被直接修掉，不需要引擎。
- 方案 A 不建议进入实施：收益面 20%、成本 7~17 人天、且要在模型层放弃 M24 建立的条件开关交互；真引擎对 mock 值语义零帮助，而 mock 语义恰是另一半账。若用户仍希望保留「高保真模式」的产品叙事，建议的最小形态是**方案 C 的测试集 + 一个只读的「真引擎对照」诊断入口**（用户手动触发，不参与渲染主链），而非把真引擎接进预览渲染路径。
- Z-1 在吸收清单中的状态按计划 §四 第 2 条改为「评估后定案」。

---

## 5. 未验证项与限制

1. spike 为 headless JDK8 环境，**未在 IDE 262 平台验证**——库 URL 收集、读权限、真实插件 classloader 委派、loader 生命周期、真实用户项目解析成功率五项均属「环境受限未验证」（§3.3）。
2. 人天估算基于源码精读与 spike 实测，未做真实试钻到可运行状态；P3 的零先例面（库收集）是估算中置信度最低的一项，实际可能超出区间。
3. 缺陷账的归因面分类由本评估人工判定，边界条目（`0e2eb2c` 的 `||` 剥壳、`347d162` 的引号部分）已在清单中标注拆分口径，便于复核。
4. 面板「条件开关 vs 真引擎按值判定」的模型冲突由源码推断（`XmlPreviewModel.kt:96` 开关是分支唯一真值源），未做真机交互验证。

---

## 6. 证据索引

| 项 | 位置 |
|---|---|
| 自研求值管线 | `src/main/kotlin/cn/song/mybatisplus/visualizer/xml/`（16 文件 2284 行；`Ognl.kt` 803 行） |
| 条件开关为分支唯一真值源 | `xml/XmlPreviewModel.kt:92-100` |
| if test 静默 true 缺口 | `xml/IfSqlNode.kt:19-23` |
| `${}` 计数转发 | `xml/DynamicContext.kt:38-44`、`xml/ForeachSqlNode.kt:122` |
| 参数映射结构（字面量化输入） | `xml/XmlSqlRenderer.kt:10-49` |
| 面板回填消费链 | `ui/SqlPreviewPanel.kt:1741-1749` |
| MCP 出口消费 | `mcp/MapperCheckTool.kt:94-103` |
| 类型读取链（可复用） | `ui/XmlParamTypeResolver.kt:32-65` |
| mybatis 探测先例 | `log/ProjectTypeDetector.kt:226` |
| 跨 classloader 反射纪律 | `ui/highlight/DatabaseSchemaResolver.kt:37-40` |
| 零依赖红线 | `build.gradle.kts:103` |
| spike 代码（临时目录，未进主树） | `%TEMP%\mpsv-fidelity-spike\`（`src/SpikeMain.java`、`resources/demo/M.xml`、`resources/demo/M2.xml`、`user-src/demo/{User,Const}.java`） |
| 缺陷账提交 | `d5dcd55` `18fadbe` `0e2eb2c` `b17d691` `a19b3bd` `a60bf4a` `06ebb9a` `3a35139` `347d162` `70f8383` `80f1be9` `c322f32` `9b6af09` `c3ee1bd` `aa607c4` `3959a8e` |

---

## 7. 交付说明

- 本报告只产出 docs，未改生产源码；spike 代码在系统临时目录，主源码树与工作树均干净。
- 无产品变更，不写 CHANGELOG。
- 结论交用户定案：采纳则按「方案 C 为主 + 方案 B 并行」安排后续任务（方案 C 可独立立项，方案 B 即既有 O-31 / N-26）。

---

## 8. 定案（2026-09-19）

- **用户 2026-09-19 定案：采纳「方案 C（语义对照测试集）」**；方案 A（真引擎生产旁路）确认 **no-go**。
- 方案 B 的实际剩余已归零：O-31（`T-ExprAstPrecheck` `6f3eb2f`）与 N-26（`T-ParamTypeContract` `4d86308`）已随第七轮七计划交付；仅 `foreach` 体内 `if` 求值失败不产 errors 这一条尾款单列（见下）。
- 落地计划：`docs/开发计划/20260919-0426-T-TailSweep2-二轮收口与真引擎语义对照-开发计划.md` —— G1 = T-FS-01~03（对照基建/协议/场景集），G2 = T-FS-04~10（遗留收口，含 `foreach` 尾款 T-FS-07）。
- Z-1（真 MyBatis 引擎求值）状态：**评估后定案 = 不进入生产路径**（转为本报告 §4.1 的测试基建形态）。
