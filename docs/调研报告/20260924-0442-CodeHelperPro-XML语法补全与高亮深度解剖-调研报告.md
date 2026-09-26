# CodeHelperPro XML 语法补全与高亮能力深度解剖-调研报告

- 时间：2026-09-24 04:42
- 触发：用户需求「目前我的插件在 xml 场景下，不支持 xml 中的语法高亮等，希望深度分析 CodeHelperPro 插件的 xml 相关能力，重点是 xml 中的语法补全和高亮」
- 样本：本机 IDEA 2026.2 安装版 `MyBatisCodeHelper-Pro/lib/instrumented-MyBatisCodeHelper-Pro241-3.5.9+2321.jar`（构建戳 2026-08-08，1708 个 class）；Marketplace 在售最新为 3.6.7+2321（2026-09-24 API 实抓）
- 配套计划：`docs/开发计划/20260924-0442-T-XmlSynHi-XML编辑器语法高亮与表达式注入-开发计划.md`

## §0 摘要：五个核心结论

1. **它的 XML 高亮是「双轨架构」，且 Community 轨不亮 SQL 关键字。** Community（无 Database 插件）下靠自研注入：`#{}` 区间注入 Param 语言、`${}` 内部与 `if/when test`、`bind value`、`foreach collection` 属性值注入 MybatisOgnl 语言；statement 里的普通 SQL 文本（SELECT/WHERE 等关键字）**没有任何着色**——全 jar 对 `com.intellij.sql` 零依赖，旧的高亮路线（annotator、linePainter）在 plugin.xml 里全部注释弃用。Ultimate（Database 插件在场）下 SQL 着色来自**平台原生的 mapper XML SQL 注入**，插件只在其上叠加 SQL 语言补全与检查。这意味着「Community 下 mapper XML 里 SQL 关键字高亮」是它没占住的差异化空位。
2. **补全是双数据源轨**：参数类补全（`#{}` 参数名、方法名、foreach collection、OGNL 变量、include property）全部只依赖 Java mapper 接口 PSI，零配置可用；表/列名补全才依赖数据源（插件自管 JDBC 连接或对接 IDE 数据源），无源时静默返回空候选，由单字母关键字捷径（s→select、w→where 等约 30 组）兜底。
3. **resultMap 列名补全不走 JDBC**：解析 resultMap 关联 SQL 文本抽列名，按「列名与 property 的 Levenshtein 编辑距离」排序，附下划线转驼峰建议——免连接且对多表 join 友好，这个设计值得借鉴。
4. **性能三板斧是它在大 mapper 文件上不卡的根因**：四处 PSI CachedValue 键控缓存（isMybatisFile、tag→类+方法、列补全上下文、OGNL 变量表）、fileBasedIndex 反查（namespace/sqlId/resultMapId 全项目 O(1)）、注入解析的文件长度熔断＋reformat 时间窗冷却。
5. **对我方的口径修正**：我方 M27「XML 与注解 SQL 代码补全」已于 2026-08-15 交付（4 个 completion contributor），补全面与它的 Community 轨已相当接近；**真正的空白是编辑器内语法高亮注入**（全仓无 multiHostInjector/自定义语言/syntaxHighlighterFactory 注册）。它的 10 个 inspection 构成的「校验器形态」按第十二轮红线（不做校验器形态回应 CodeHelperPro）继续不做。它的 3.6.x 近期重心全在 MCP/AI 线，XML 编辑能力无新增，是竞争窗口。

## §1 调研范围、方法与证据边界

方法：jar 解包 + IDEA 自带 FernFlower 定向反编译（主控精读 291 个文件，三路只读子代理并行深挖补全链/我方盘点/文档市场面）+ Kotlin `@Metadata` 注解 d2 数组还原原始类名（混淆只改了类名，Kotlin 元数据里的原始名完整保留，本次分析还原出 60+ 个原始类名）+ 官网文档站（docsify markdown 直抓成功）与 Marketplace API 核对用户视角功能。

证据分级：

- **已证实**：本文所有反编译结论均带反编译文件路径，主控对承重结论（根标签守卫、JDBC 类型硬编码表、单字母映射表、注入区间算法、程序化高亮器注册）做了二遍抽查，全部命中。
- **推断（标注）**：`i.b.*` 布尔门控的实现类因 Windows 解包大小写折叠丢失（`i/` 与 `I/` 合并），语义从 18 处调用点反推为「平台 Database 能力可用性检测」；`plugin_database.xml` 引用的 `paramLanguage.ParamMysqlParserDefinition`、`reference.IntellijSqlParameterReferenceContributor` 在本机 3.5.9 jar 中**无对应类（死引用）**，属 3.6.x 新版能力，本文按注册名与 Marketplace changelog 标注为「3.6.x 前瞻，未实证」。
- **未获取**：GitHub repo 元数据/issues/releases（匿名 API 额度被共享出口 IP 耗尽）；官网 settings 页仅截图无文字。

## §2 EP 注册全景

主 descriptor（plugin.xml）与 XML 编辑相关的注册面（混淆名 → @Metadata 还原名）：

| EP | 混淆类 | 还原名 | 作用 |
|---|---|---|---|
| multiHostInjector ×3 | Q.g / Q.c / al.f | OgnlMultiHostInjector / OgnlDollarInjector / ParamInjector | OGNL 与 Param 注入（§3.2） |
| multiHostInjector（database 可选） | al.b | CommentTableNameInjector | `<!--@Table 表名-->` 注释注入 SQL |
| lang.parserDefinition | R.q / com.intellij.lang.a.e | — | Param 与 MybatisOgnl 两个自定义语言 |
| lang.syntaxHighlighterFactory | R.m | — | Param 高亮器工厂（OGNL 的高亮器在 Language 构造器里程序化注册，plugin.xml 对应行被注释） |
| completion.contributor language=XML ×3 | view.c.d / view.c.e / view.c.k | SqlText 补全 / 属性值补全 / 配置文件补全 | §4 |
| completion.contributor language=Param | R.o | — | `#{}` 内 jdbcType 等结构属性补全 |
| completion.contributor language=MybatisOgnl ×2 | a.a.a / Q.a | — / MybatisOgnlIncludePropertyCompletionContributor | OGNL 语法补全 / include property 补全 |
| typedHandler ×2 | view.c.j（first） / a.f | — | `#{` `{` 自动闭合+自动弹窗；OGNL 里 `@`、`%{` 触发 |
| weigher key=completion ×2 | view.c.b.a / （database）view.c.b.b | MyJavaMethodNameWeigther | 方法名生成候选置顶 |
| lang.foldingBuilder language=XML | v.a | — | 动态标签折叠（v/V 包因大小写折叠合并，实现未精读） |
| localInspection ×10 | h.p/h.B/h.A/h.z/Q.j/a.d.b/R.a.b… | OgnlReferenceCheck、OgnlCompareStringCheck、ParamInspection、ResultMapColumnCheck、ResultMapColumnDuplicateCheck、MybatisResultTypeCheck、unUsedXml、methodNotInXml 等 | §5（红线外，本文不展开实现） |
| lang.inspectionSuppressor / spellchecker.support language=XML | q.a / F | — | 压制平台 XML 检查 / SQL 文本豁免拼写检查 |
| daemon.changeLocalityDetector | view.I | — | 编辑局部性扩大，加速注入重算 |
| dom.fileMetaData ×2 | dom.description.MapperDescription / ConfigurationDescription | — | DOM 模型：平台免费获得标签名/属性名/类引用补全 |
| psi.referenceContributor language=JAVA ×3 | view.H / view.m / view.N | — | Java 侧引用 |
| fileBasedIndex | B.b | MybatisCodeHelperIndexer6 | namespace/sqlId/resultMapId 全项目反查（键：`MybatisMapperNameSpace@ns`、`MybatisSqlId@ns.id` 等） |

一个值得点名的手法：OGNL 语言实现全部放在 `com.intellij.lang.a.*` 包名下（伪装成平台包），推测为规避 ClassLoader/包可见性问题或混淆副作用，无功能必要，我方**不应模仿**（平台包名伪装在 Marketplace 审核中有兼容审查风险）。

## §3 高亮与注入体系深度解剖

### 3.1 双轨架构

```
                     ┌─ Community（无 Database 插件）─────────────┐
                     │ Q.g: if/when@test、bind@value、            │
mapper XML ──自研──▶ │      foreach@collection 属性 → MybatisOgnl │
                     │ Q.c: ${} 内部 → MybatisOgnl（前缀合成 %{}) │
                     │ al.f: #{} 整段 → Param                     │
                     │ 普通 SQL 文本 → 无着色                     │
                     └────────────────────────────────────────────┘
                     ┌─ Ultimate（Database 插件在场，plugin_database.xml）┐
mapper XML ──平台──▶ │ 平台原生 SQL 注入 statement 文本（平台行为）       │
          ──叠加──▶ │ completion.contributor language=SQL ×2            │
                     │ localInspection checkSelectColumnExistInResultmap │
                     │ al.b: <!--@Table x--> → SQL（合成前缀 select * from）
                     │ gotoDeclarationHandler / referencesSearch ×2      │
                     │ lang.refactoringSupport language=SQL              │
                     │ （3.6.x 前瞻：ParamMysqlParserDefinition 让注入 SQL │
                     │   内 #{} 走自定义 MySQL 解析器不报红+参数引用）    │
                     └───────────────────────────────────────────────────┘
```

平台原生注入是 Ultimate 用户「mapper XML 里 SQL 有着色」的真正来源（官网 sqlred 页「IDEA 原生注入无法解析导致断供」的表述反证了这一点）；插件的双轨门控（`i.b.*`，推断为 Database 能力检测）避免与平台双写。

### 3.2 四个注入器

| 注入器 | 宿主元素 | 注入语言 | 区间与前缀 | 出处 |
|---|---|---|---|---|
| OgnlMultiHostInjector（Q.g） | XmlAttributeValueImpl | MybatisOgnl | `if@test`、`when@test`、`bind@value`、`foreach@collection` 四个属性值整段 | Q/g.java |
| OgnlDollarInjector（Q.c） | XmlText | MybatisOgnl | `${}` 内部区间（start+2 起），**合成前缀 `%{`**——OGNL 语言声明 `InjectableLanguage`，表达式包装符就是 `%{`/`}`（语言类字段直证） | Q/c.java |
| ParamInjector（al.f） | XmlText | Param | `#{}` 整段（含 `#{` 与 `}` 一起注入），宿主标签必须 ∈ statement 标签集（select/insert/update/delete/sql） | al/f.java |
| CommentTableNameInjector（al.b，Ultimate 轨） | PsiComment | 平台 SqlLanguage | `<!--@Table 表名-->` 注释，合成前缀 `select * from `——让表名注释获得 SQL 上下文（补全/解析），配套 live template `table` 生成该注释 | al/b.java |

区间扫描算法（al/j.java）值得抄：对文本分别扫 `#{...}` 与 `${...}`（处理 `\#{` 转义、未闭合容错——未闭合片段照样产出区间，只是标记 b=false），两组合并按 start 排序、重叠去重，产出交替区间表。注入器只做区间→语言映射，扫描与注入解耦。

### 3.3 两个自定义语言的重量差

- **MybatisOgnl**（`com.intellij.lang.a.*`，Language 实例 `a.a`，名 `MybatisOgnl`，`InjectableLanguage`）：完整语言全家桶——FlexAdapter 词法 + 语法 parser、formatter、braceMatcher、quoteHandler、typedHandler（`@` 触发静态成员补全、`%{` 自动补 `}`）、两个 completion contributor、两个 inspection（OgnlReferenceCheck / OgnlCompareStringCheck）、live template（in/nin）、colorSettingsPage、spellchecker 豁免、自扩展点 `mybatisognl.variableReferencesContributor`。变量补全的数据底座是 `MybatisOgnlVariableContributor`（Q.b，Kotlin object + CachedValue 缓存）：mapper 方法参数 + bean 属性递归展开 + `<bind>` name + foreach item/index + include property，可选加 `_databaseId`、可关 `param1..N`；resolve 走 `ResolveCache`。
- **Param**（`R.*`，Language 名 `Param`）：刻意轻量——词法只有 `#{`、`}`、ID、NUMBER、逗号、点、括号、注释十几类 token；语法只有 ATTRIBUTE/EXPRESSION/PROPERTY_NAME/JDBC_TYPE 等；补全只管 `#{name,jdbcType=…}` 里的结构属性（七个属性名 + IN/OUT/INOUT）。参数名本身不归它管——Param PSI 基类给名字元素挂 `OgnlOrParamReference`（Q.h，OGNL 与 Param 共用一个引用类，类名即来源），补全与解析回到 OGNL 变量那套。

高亮器键表（两语言同构，全部锚定 `DefaultLanguageHighlighterColors` 语义槽位，用户主题自动适配）：

| Key | 底层槽位 | 效果 |
|---|---|---|
| `MybatisParam.BACKGROUND` / `OGNL.BACKGROUND` | TEMPLATE_LANGUAGE_COLOR | 注入区间整段淡底色（视觉上把 `#{}`/表达式从 SQL 文本里「垫」出来） |
| `*.KEYWORD` | KEYWORD | `#{`、`}`（Param）；表达式关键字（OGNL） |
| `*.IDENTIFIER` / `NUMBER` / `COMMA` / `DOT` / `BRACKETS` | 对应槽位 | 表达式成分着色 |
| `*.BAD_CHARACTER` | BAD_CHARACTER | 非法字符 |

关键实现细节：OGNL 高亮器不在 plugin.xml 注册，而是在 **Language 构造器里 `SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(this, …)` 程序化注册**（`com/intellij/lang/a/a.java` 直证，plugin.xml 的 `<syntaxHighlighter key="MybatisOgnl">` 注释行是历史痕迹）。对插件加载时序更稳，我方采用 XML 声明式注册即可，不必模仿。

### 3.4 门控与商业化

所有注入与列补全的第一道门是 `Profile.getValid()`（激活状态）——**未激活连注入高亮都不工作**；第二道是 `i.b.*` 平台检测（推断），Ultimate 下关闭自研 Param/OGNL 注入链路把编辑器让给平台注入（自研补全仍在）。免费版功能清单（README 对比表）：xml param 提示、resultMap property 提示；付费才有：识别 trim/where/include 标签的全自动 sql 补全、`#{}` 参数检测、OGNL 全套、resultMap column 提示与检测、param 重构。高亮注入在付费墙内。

### 3.5 性能设计（直接决定我方方案的验收线）

1. PSI CachedValue 键控缓存四处：isMybatisFile（Key `isMybatisFile`）、tag→(PsiClass,PsiMethod)（Key `mybatisXmlTagClassAndMethod`）、列补全上下文按 XmlAttributeValue（Key `mybatisColumnsComplete`）、OGNL 变量表（Key `mybatisPropertiesKey`）。
2. OGNL 引用 resolve 走 `ResolveCache.resolveWithCaching`。
3. fileBasedIndex 反查替代 PSI 暴力扫描（5 类键 + 哨兵键）。
4. **注入链路熔断**：`al.e` 有 `getMaxParsingXmlTextLength()` 上限（超长 XmlText 跳过解析），reformat 后有时间窗冷却（VirtualFile userData 存时间戳）——注入器每个 XmlText 都会被平台回调，没有熔断的大文件体验是灾难。这四条是我方 D 波次的验收硬线。
5. JDBC 登录超时 5s、DatabaseInfo null 全路安全（null 返回空 list 不抛错）。

## §4 补全体系

三个 XML contributor 按「位置分流、命中即接管」（view.c.d 覆写 `fillCompletionVariants` 且不调 super，order=first 抢在平台 XML 补全前屏蔽噪音）：

**SqlText 补全（view.c.d，order=first）**——只处理语句文本位置，根标签守卫 `mapper`，按优先级串行：
1. `#{` 未闭合 → 参数名补全（namespace→PsiClass→id→PsiMethod→参数名列表）；文本以 `,` 结尾改补 `jdbcType=` + 16 个硬编码 JDBC 类型。
2. 反引号内 → 本文件全部 resultMap 的 column/property。
3. SQL 语法位 → SqlParser 状态机（`sqlparse.SqlParser`）：select 列表位优先补 **resultType 类的 bean 属性**（无数据库！）；from/join 后补表名；已解析出的表+别名在 where/on/order/group/having 位补 `别名.列`；外加 SQL 函数候选。数据源为 null 整路静默空。
4. 已输入长度=1 → 单字母关键字捷径（s→select、i→insert into、j→join、l→left join、o→on、w→where、g→group by、m→max/min、c→count 等，大小写各一组，multimap 实证）。

**属性值补全（view.c.e）**——按属性名分流：`id`→接口全部方法名；`keyProperty`→方法参数名；`jdbcType`→`java.sql.JDBCType.values()`；`column/columnPrefix`→解析关联 SQL 抽列（PSI 缓存 + Levenshtein 权重 `1000-编辑距离` + 下划线驼峰建议）；`foreach@collection`→ResolveParamUtils 解析方法参数后 `stopHere()`。

**配置文件补全（view.c.k）**——`<setting name>` 28 个硬编码名 + 布尔值。

**Param 内补全（R.o）**——`#{}` 结构属性 + mode 值。**OGNL 补全**——语言级语法补全（运算符 shl/shr/eq/lt…、`@` 前缀、true/false/null）+ include property（ReferencesSearch 反查 `<include>` 的 `<property>`）+ 平台 `PsiReferenceCompletionProvider` 消费 OgnlOrParamReference.getVariants()（变量+bean 属性递归+bind/foreach/include 变量）。

汇总表：

| 补全能力 | 触发位置 | 候选来源 | 依赖 DB |
|---|---|---|---|
| SQL 关键字/函数/表/列/别名.列 | 语句文本 | SqlParser 状态机 | 是（resultType 字段路除外） |
| `#{param}` 参数名 | `#{` 后 | mapper 方法签名 | 否 |
| jdbcType 等结构属性 | `,` 后 | 硬编码 | 否 |
| 反引号列名 | 反引号后 | 本文件 resultMap PSI | 否 |
| resultMap column/columnPrefix | 属性值 | SQL 文本解析+Levenshtein | 否 |
| statement id / keyProperty | 属性值 | 接口方法名 / 方法参数 | 否 |
| foreach collection | 属性值 | 方法参数解析 | 否 |
| 标签名/属性名/类引用 | 任意 | 平台 DOM 框架（推断） | 否 |
| OGNL 变量/属性/静态成员 | test/value/collection/`${}` | 方法参数+bind+foreach+include | 否 |
| live template | 全文件 | cd/CD、co/CO（foreach 骨架，collection 变量动态弹参数）、table（@Table 注释） | 否 |

## §5 检查与重构体系（概览，红线外）

10 个 localInspection（OGNL 引用、OGNL 字符串比较、Param、resultMap 列、重复列、resultType、未用 xml、方法未在 xml、mapper xml、select 列在 resultMap）+ param 重命名重构 + ExtractSql 片段提取（Ctrl+Alt+C）+ 方法名生成 SQL（find…By 组合爆炸生成器 + weigher 置顶）。**这一整层是「校验器形态」**，按竞品第十二轮红线（`20260924-0120` 报告 §红线：不做校验器形态回应 CodeHelperPro——它做校验、我们做渲染）本次不立项不模仿，仅登记为战略边界确认。

## §6 官网文档印证的用户视角痛点

- 官网 sqlred 页整页在教用户写**引导注释**（`@sql` 前后缀注释、`ignore` 注释、`${}` 真实 SQL 注释）来平息 Ultimate 平台注入的错误标红——即它的高亮检测体系大量依赖用户手工标注，不是开箱零注解体验；`@Table` 注释注入器（al.b）就是这条注释体系的实现落点。
- 官网 complete.md 明说动态标签（if/trim/where/set/include）之后的 SQL「IDEA 原生注入无法解析导致断供」，其卖点之一是让解析穿过动态标签继续。
- 3.6.x changelog（3.6.4→3.6.7，9 月连发）重心全在 MCP/AI 线（run_mybatis_sql、validate_mybatis_mapper、JSON Join、.mcp.json 一键生成），XML 编辑核心无新增。

市场面：直装版 downloads 1,415,277（id 9837）+ Marketplace Edition 227,593（id 14522），评分 4.96/197 票（第十二轮报告读数）。XML 编辑补全/高亮是它的付费护城河本体，也是我方与它重叠度最高的战场。

## §7 我方现状对照与差距矩阵

我方现状（只读盘点，374 个 Kotlin/Java 源文件逐一核对 EP 注册与实现）：

- **已有**：M27 四个 completion contributor（`ui/highlight/XmlSqlCompletionContributor.kt`：SQL 关键字/表/列/`别名.`列/`#{}` 参数/动态标签名/标签属性名/test·collection 内 OGNL 参数与实体字段；`SqlCompletionContributor` SQL 文件；`AnnotationSqlCompletionContributor` 注解字符串；`WrapperCompletionContributor`）——补全主面已覆盖竞品 Community 轨的大部分；XML gutter 行标 ×3；右键/快捷键预览入口；自研 SqlLexer+SqlSyntaxHighlighter（**只服务自家预览面板**，经 `LexerEditorHighlighter` 手工应用，未注册任何 factory）。
- **明确空白**（EP 未注册且无手写实现双重确认）：XML 编辑器内 SQL/表达式高亮注入（无 multiHostInjector/自定义 Language/syntaxHighlighterFactory）、typedHandler、引用导航（include refid→`<sql id>`、namespace→接口）、annotator/inspection（红线外不做）、foldingBuilder、live template、颜色设置页、自定义索引。
- **可复用基建**（这是立项底气，竞品对应物在括号内）：`SqlLexer.kt` 自研 SQL 词法（≈竞品 Param 高亮器 but SQL 域）、`TokenParser.kt` `#{}`/`${}` 扫描含转义复刻（≈al.j 区间扫描）、`Ognl.kt` 受限 OGNL 求值与 AST（≈MybatisOgnl 语义子集）、`XmlParamTypeResolver` 参数类型推断、`EntityResolver`+`EntityCache` 三维缓存（≈PSI CachedValue 体系）、`DatabaseSchemaResolver` 反射隔离 DBN 列元数据（≈DatabaseInfo null 安全）、`Dialect` 九方言推断（竞品 Community 轨没有方言概念）、`PluginCacheManager` 缓存工具。

差距矩阵（XML 编辑器编辑体验）：

| 能力 | CodeHelperPro Community | CodeHelperPro Ultimate | 我方现状 |
|---|---|---|---|
| `#{}` 表达式着色 | Param 语言注入（付费门控） | 平台注入+3.6.x 自定义解析 | **无** |
| `${}`/test/OGNL 着色 | MybatisOgnl 注入（付费门控） | 同左 | **无** |
| SQL 关键字着色 | **无** | 平台注入 | **无** |
| SQL 关键字/表/列补全 | SqlParser 状态机 | SQL 语言 contributor 叠加 | M27 已有（自带表列来源 DBN 反射轨） |
| `#{}` 参数名补全 | 方法签名轨 | 同 | M27 已有 |
| resultMap column 补全 | SQL 文本解析+Levenshtein | 同+inspection | **无** |
| 引用导航/重命名 | OgnlOrParamReference 全套 | 同+SQL 引用 | **无** |
| live template | cd/co/table/ognl in | 同 | **无** |
| 错误检查 | 10 inspection | +SQL 检查 | **无（红线外，不做）** |

## §8 可借鉴设计与差异化机会

可直接吸收的设计（按借鉴价值排序）：

1. 双轨门控架构：检测平台 SQL 注入在场（Ultimate）则让位，避免双写——我方门控判据用 Class.forName 探测 Database 插件（与 `ExecuteSqlSupport` 同型），实施前按待验证前提真机确认。
2. 区间扫描与注入解耦：扫描器产出 `区间表`（含转义、未闭合容错、重叠合并），注入器只做映射——我方 `TokenParser` 已是同型算法，PSI 化移植即可。
3. 注入性能熔断：文件长度上限 + reformat 时间窗冷却 + O(1) 守卫（根标签/语句标签判定先行）——照抄设计，验收线照抄读数形态。
4. resultMap 列名「SQL 文本解析 + Levenshtein 对齐 property」：免连接、join 友好，作为 D 补全波次的实现路线。
5. 高亮器键全部锚定 `DefaultLanguageHighlighterColors` 语义槽位 + 注入区间 TEMPLATE_LANGUAGE_COLOR 淡底色：主题自适应、实现成本最低。
6. 「参数名引用共用一个 Reference 类贯穿两语言」的收敛设计：我方若做引用导航（本次不做）可参考。
7. typedHandler 交互：`#`/`$` 后 `{` 自动补 `}` 并自动弹参数补全、`,`/`.`/`=` 触发属性补全——低成本高感知。
8. DOM fileMetaData 白嫖平台补全：标签名/属性名/namespace 类引用由平台 DOM 框架免费产出——低成本补齐「标签/属性名补全」缺口（M27 已有部分，评估增量后决定是否吸收）。

差异化机会（竞品没有或没做好的）：

1. **Community 轨 SQL 关键字高亮**：它明确没做（§0 结论 1），我方自研 SqlLexer 挂语言注入即可全 IDE（Community+Ultimate）可用，且可按 `Dialect` 方言调整关键字集——它 Community 轨无方言概念。
2. 与预览环联动：我方独有「免库 mock 预览」（开关式模拟参数）+ gutter 一键预览，高亮注入落地后「编辑器里看得清 → gutter 一键看渲染结果」形成闭环，它没有这一环（无 mock、付费门控）。
3. 零注释体验：它的标红体系要用户写 `@sql` 引导注释，我方不做校验器也就没有误导性标红，纯加色不减色，风险面小。

## §9 战略裁定与红线确认

- 第十二轮红线「不做校验器形态回应 CodeHelperPro」继续有效：本次范围=高亮注入+补全缺口，**不含任何 inspection/annotator 错误标注**。纯高亮与补全不构成校验器形态，与红线无冲突。
- 它的付费墙内能力（注入高亮、全自动 SQL 补全、OGNL 全套）我方以免费形态覆盖其中高亮与补全，是「以免费打击付费墙」的正面战场动作；校验器形态继续让给 zMyBatis/它去卷。

## §10 落地优先级建议（如果只做三件事）

1. **`#{}`/`${}`/OGNL 表达式注入高亮**（D1~D3 主线）——用户每一天都在看的体验差，竞品付费墙内核心项，我方基建复用度最高。
2. **Ultimate 门控 + Community SQL 关键字高亮**（D3）——差异化空位，一次门控两版通吃。
3. **resultMap column 补全 + typedHandler**（D5）——补全缺口里感知最强的两件，Levenshtein 路线免连接。

live template、引用导航、颜色设置页缓一批，等主线落地后按用户反馈排。

## §11 子代理分工与复核说明

三路并行只读子代理：①补全链路深挖（137 次工具调用，定向反编译 decomp-ad，产出 §4 全部与混淆名对照表）；②我方现状盘点（EP 注册与实现双重确认，产出 §7）；③官网/GitHub/Marketplace/既有档案（产出 §6 与编号冲突检查）。主控二遍实证：plugin.xml 全文精读、四注入器与区间扫描器逐行读、Param/OGNL 高亮器键表、语言类程序化注册、我方 plugin.xml:268-280 与 grep 反证、单字母映射表/JDBC 硬编码表/根标签守卫抽查——子代理报告与本报告结论无矛盾；`i.b.*` 语义与 3.6.x 前瞻项按 §1 证据分级标注为推断/未实证。
