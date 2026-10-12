# T-XmlGap mapper XML 高亮/补全/导航差距收敛-开发计划

- 编号：**O-144**（20261012 实测占用边界 O-143；引用前再实测，若与并行会话撞号以盘点台账裁定）
- 来源：`docs/调研报告/20261012-1552-CodeHelperPro-XML高亮与补全再深挖-调研报告.md`（公开仓为占位指针，全文含竞品逆向细节，在内部资料库；下称「调研报告」，引用只写节号）
- 前置：`T-XmlSynHi`（O-74，D1~D5 已交付）、`T-XmlCmpl2`/`T-XmlCmpl3`/`T-FCA-03`/`T-PerfSweep16`（历次 XML 补全修复）
- 状态：**✅ 定案（2026-10-12 用户「可以按照推荐 A」）**——§四 六项决策点**全按推荐项 A**；本计划立项即解锁，实施启动=用户另令（W1 仍受 §五 前提 1 真机读数约束）
- 用户需求原话（2026-10-12）：「xml 高亮 代码补全 之前吸取 MyBatisCodeHelperPro 精华 但是吸取的不是很到位，很多功能和 MyBatisCodeHelperPro 还差得多，现在再次重点分析」

## §一 立项依据与口径修正

| # | 立项时的口径 | 实测修正（调研报告节号） | 影响 |
|---|---|---|---|
| 1 | 「已吸取竞品精华」 | 吸的是**骨架**，粒度与覆盖面差一档：`#{}` 与 `${}` 同 token 同色、表达式内部零分色、表名列名运算符无色、高亮与补全各用一套关键字集（41 词 vs 66 词）（§4.1） | 本轮主体是**提粒度 + 补覆盖面**，不是加新骨架 |
| 2 | 「高亮已做，共存也没问题」 | 让位门控是**宿主级、区间盲**（只记对方有没有 `startInjecting`，不看 `addPlace` 区间），命中即整宿主放弃（§4.3）。**但**竞品三个社区轨注入器在 Ultimate 下自我让位（`i.b.c()` 探测到 Database 插件即跳过），故用户真机（IU Ultimate）上我方大概率**没有被让位**；该缺陷真实命中面是 Community 版用户 + 装了竞品 | W1 从「第一优先」降级为**潜在缺陷修复 + 为区间互补铺路**；优先级取决于前提 1 的真机读数 |
| 2 | 「高亮已做」 | 做的是**整段 token 级**：`#{}` 与 `${}` 同色不可分、表达式内部无细着色、表名/列名/运算符无色（§4.1） | 粒度差一档，观感差距的主因之一 |
| 3 | 「补全已对齐竞品 Community 轨」 | 属性值补全只覆盖 `test`/`collection`（其余属性值直接 return）；`#{}` 内无结构属性（竞品有 7 项属性键 + 16 项 jdbcType + mode 三值）；语句标签属性名无补全；无引用导航；另缺 OGNL 智能片段、反引号列名、`<properties>` key 聚合、`_databaseId`（§4.2、§5.2） | 补全侧缺口比上轮估计的大；**注意 `arg0..N` 与 `#{expression=}` 竞品也没有，不要当差距写** |
| 4 | 「竞品的 paramLanguage 属 3.6.x 新能力」 | 3.6.7 实测仍无对应类，且那两行 EP 在 `plugin_database.xml` 里**本身就是注释**（§0.10） | 上轮口径勘误，不影响我方方案 |
| 5 | 「竞品补全退化差评 = 时机窗口」 | 100 条评论实抓未复现该负面证据，正面 XML 评价一条（§2.1） | 该论据失效，不再作为立项理由；立项理由改为「自查发现的真实差距」 |
| 6 | 「竞品 XML 面在演进」 | 3.5.9→3.6.7 的 XML 编辑面只有两条（SQL 行注释移除、if test 意图动作），无高亮/注入新增，无 3.7.x（§2.1） | 竞争窗口仍在，节奏不必激进 |

## §二 范围与非目标

做（按调研报告 §5 差距矩阵排序）：

1. **共存让位改为区间级互补**——我方只放弃竞品真正占用的着色区间，其余照常着色（§4.3 的根因修复）。
2. **高亮粒度与覆盖面**——`#{}` 与 `${}` 分色；`${}` 内部与属性值内 OGNL 细着色；表名/列名/运算符着色；关键字集统一并接方言感知。
3. **补全扩面**——`#{}` 结构属性（jdbcType/javaType/typeHandler/mode…）；属性值全覆盖（id/refid/resultMap/resultType/parameterType/type/ofType/property/jdbcType/keyProperty/keyColumn/column…）；语句标签属性名；OGNL 变量来源补全（`<bind>`/foreach item·index/include property/`_databaseId`/`@类@静态成员`/运算符）；select 投影位按 resultType 类属性；SQL 函数；单字母关键字捷径。
4. **编辑辅助**——颜色可配置（colorSettingsPage）、动态标签折叠（foldingBuilder）、live template。
5. **配置文件补全**——mybatis-config.xml（`<setting name>` 等）。
6. **引用导航**（**待 §四 决策 5 拍板**）——跳转与查找用法，不含错误标注。

不做（非目标，留痕防范围蠕动）：

- **任何 inspection/annotator 错误标注**——第十二轮红线「不做校验器形态回应 CodeHelperPro」继续有效。
- 竞品的**引导注释体系**（`<!--@Table-->`/`<!--@sql-->`/`<!--@ignoreSql-->`）——那是它让用户手工喂平台注入的补丁，我方不做校验器即无此负担。
- 竞品把语言实现塞进 `com.intellij.lang.*` 包名的伪装手法——Marketplace 兼容审查风险，不模仿。
- Java 注解字符串内高亮（`@Select` 场景）——平台已有注解字符串 SQL 注入，不重叠。
- 重命名重构（rename）——与引用导航同源但风险面更大（跨文件改写），本轮不碰，落地后按反馈另立项。

## §三 现状与可复用基建

- 注入链现状：`ui/inject/` 12 个文件（lexer-only 语言 + 注入器 + 门控 + typedHandler + resultMap 列补全）。
- 补全链现状：`ui/highlight/XmlSqlCompletionContributor.kt`（双语言注册 + 注入片段宿主坐标还原）+ `SourceSqlCompleter`（纯函数，关键字/表/列/别名列/`#{}` 参数）+ `SourceSqlSuggestionFactory`（Lookup 工厂）。
- 可直接复用（本轮不需要新建基建）：
  - `xml/TokenParser.kt`——`#{}`/`${}` 区间扫描（含 `\#` 转义、未闭合容错），**区间级让位与表达式细着色都建在它上面**；
  - `ui/XmlParamTypeResolver.kt` + `AnnotationSqlPreviewService.paramTypes`——参数名与类型推断（`@Param`/`param1..N`/集合别名），补全与高亮共用；
  - `sql/Dialect.kt`（11 方言关键字集）——关键字统一与方言感知的数据源；
  - `EntityResolver` + `EntityCache`、`DatabaseSchemaResolver`——表列元数据（列补全已有）；
  - `SqlLexer`（预览面板专用）——`SqlMapperLanguage.KEYWORDS` 与它统一时的对齐基准。
- 守卫与测试参照：`config/PluginXmlStructureGuardTest`、`ui/highlight/XmlSqlCompletionContributorTest`、`ui/inject/` 既有单测（门控三形态 stub 探针）。

## §四 决策点（用户定案前不动工）

### 决策 1：共存让位粒度（潜在缺陷，非当前症状）

**先说清事实**：调研报告 §4.3 的证据链显示，用户真机（IU Ultimate + CHP 已激活 + zMyBatis 在位）上我方**大概率并没有被让位**——竞品三个社区轨注入器都带「探测到 Database 插件就跳过」的门，在 Ultimate 上不运行。所以这一项**不是**「高亮看不见」的根因，而是：①Community 版用户 + 装了竞品的组合会真实中招；②任何宿主类型与我方重叠的第三方注入器都会触发整段让位。**是否要现在修，取决于前提 1 的真机读数**。

| 选项 | 方案 | 利 | 弊 |
|---|---|---|---|
| **A（推荐）** | 改为**区间级互补**：先用 `TokenParser` 算出本宿主的 `#{}`/`${}` 区间表，再让探针 registrar 记录外来注入器 `addPlace` 的 range（而非只记 `startInjecting`），我方只注入**未被占用的区间**；属性值侧按属性名判（竞品只占 test/value/collection） | 彻底消除「整段让位」；共存时我方着色不再消失，且不双写同一区间；也是 W2/W3 细着色能安全落地的地基 | 探针要读 `addPlace` 的 range，复杂度上升；平台对 `addPlace` 的调用时序需实测（前提 2） |
| B | 加设置项「共存时始终使用本插件高亮」（默认关），勾选即无视外来注入器 | 实现最简，用户可自选 | 与竞品双写同一区间 → 叠色/竞态；默认关等于默认仍可能整段让位 |
| C | 维持现状，只把 KDoc 与文档口径订正 | 零改动、零风险 | Community + 竞品组合的整段让位留着；W2/W3 细着色落地时会与让位逻辑打架 |
| D | 先只做「前提 1 真机探针」再定 | 用事实决定要不要花这份成本 | 多一轮往返 |

### 决策 2：表达式着色粒度

| 选项 | 方案 | 利 | 弊 |
|---|---|---|---|
| **A（推荐）** | 升级为**词法级细着色**：`#{}` 与 `${}` 用两个 token 类型（先解决「一眼可分」），再为 `${}` 内部与属性值加 OGNL-like 细 token（关键字/布尔/字符串/数字/`@类@方法`/运算符） | 观感对齐竞品；`#{}` vs `${}` 可分是本轮成本最低、感知最强的一项 | 需要在自研 lexer 里加表达式子状态机（不新建第二个 Language，避免注入区间翻倍） |
| B | 只做 `#{}`/`${}` 分色，表达式内部维持整段 | 成本最小 | 表达式内部仍无色，与竞品差距保留 |
| C | 新建第二个 Language（OGNL-like）独立注入 `${}` 内部与四个属性值（竞品同构） | 与竞品架构一致，未来接引用导航更顺 | 两个语言 + 两套注入区间，成本约 +60%；本轮导航若不做则收益不足 |

### 决策 3：高亮覆盖面

| 选项 | 方案 | 利 | 弊 |
|---|---|---|---|
| **A（推荐）** | 关键字集统一为 `SqlLexer.KEYWORDS` 的超集并按 `Dialect` 方言扩展；IDENTIFIER/OPERATOR 接平台语义槽位着色；保留 41 词平集作为兜底 | 修掉「补全能提示但不着色」的不一致；方言感知是竞品免费档没有的差异点 | 方言关键字集需按 11 方言核对（数据源已在 `Dialect.kt`） |
| B | 只统一关键字集，不做方言感知 | 成本更低 | 差异化点白放着 |
| C | 维持平集 | 零改动 | 冷门方言关键字不着色 |

### 决策 4：补全扩面的批次

| 选项 | 方案 | 利 | 弊 |
|---|---|---|---|
| **A（推荐）** | 一批全做：`#{}` 结构属性（7 属性键 + 16 jdbcType + mode 三值）+ 属性值全覆盖（id/keyProperty/refid/resultMap/extends/resultType 族/property/jdbcType/keyColumn/column/columnPrefix）+ 语句标签属性名 + OGNL 变量来源（`<bind>`/foreach item·index/include property/`<properties>` key/`_databaseId`）+ OGNL 智能片段 + select 投影按 resultType + 反引号列名 + SQL 函数 + 单字母捷径 + mybatis-config.xml | 一次把「差得多」的感知补齐 | 批次大，收口周期长 |
| B | 拆两批：先做 `#{}` 结构属性 + 属性值全覆盖 + 语句标签属性名（纯硬编码表 + 既有 PSI 轨，感知最强）；OGNL 变量来源/智能片段/config 文件另立项 | 收口快、风险小 | 另一半缺口继续裸奔 |
| C | 只做 `#{}` 结构属性 | 最小 | 属性值缺口是用户最容易撞上的（`refid`/`resultType` 空候选） |

注：`arg0..N` 与 `#{expression=}` 竞品也不支持，不列入范围。

### 决策 5：引用导航是否纳入本轮

| 选项 | 方案 | 利 | 弊 |
|---|---|---|---|
| **A（推荐）** | **本轮纳入「跳转 + 查找用法」**（`include refid`→`<sql id>`、`resultMap`/`extends` refid→`<resultMap id>`、`namespace`→接口、`resultType`/`parameterType`/`type`→类、`#{}` 参数→Java 方法参数、`test` 属性→实体字段），**不做报红、不做重命名** | 这是与竞品差距最大的一整块；平台自身就把「解析跳转」与「错误标注」分开，不触红线 | 工作量最大（引用类 + resolve 缓存 + 跨文件），需单独评估收口周期 |
| B | 本轮不做，另立专项计划（本轮只做高亮 + 补全） | 本轮聚焦、收口可控 | 用户感知差距最大的那块继续缺 |
| C | 纳入全部（含重命名重构） | 一次到位 | 跨文件改写风险高，且与本轮其余波次无耦合，不建议捆一起 |

### 决策 6：编辑辅助与体验杂项

| 选项 | 方案 | 利 | 弊 |
|---|---|---|---|
| **A（推荐）** | 全做：colorSettingsPage（`#{}`/`${}`/关键字/注释/字符串分项可调）+ XML foldingBuilder（动态标签折叠）+ live template（`cd`/`co` 两个骨架，**不含**竞品的 `@Table`/`@sql` 引导注释模板）+ 注入 SQL 文本豁免拼写检查 + `daemon.changeLocalityDetector` 放大编辑脏区 + backspace 成对删 `#}`/`$}` | 六项都是声明式 EP 或小改，成本低、感知直接；拼写豁免与脏区放大是「不修就一直被用户看到的瑕疵」 | EP 面扩大（`liveTemplateContext`/`colorSettingsPage`/`foldingBuilder`/`spellchecker`），走构建校验；脏区放大有性能影响需实测 |
| B | 只做颜色页 + 拼写豁免 | 解决最直接的两条抱怨 | 折叠/模板/成对删/脏区缺口保留 |
| C | 本轮不做 | 收口最轻 | 六项都是竞品有而我方无的显性差距 |

## §五 待验证前提（实施者先证伪再动手，逐条给判据）

1. **（先做，决定决策 1 去留）用户真机上我方高亮到底有没有被让位**：在用户 IU 2026.2（装 CHP 已激活 + zMyBatis）打开含 `#{}` 的 mapper 语句，看 SQL 关键字是否着色。着色 → 我方未被让位（预期结果，竞品社区轨在 Ultimate 自我让位），决策 1 降级为「潜在缺陷修复」；不着色 → 需用 `InjectedLanguageManager.getInjectedPsiFiles` 探针确认占用方是谁（竞品平台轨 `ak.e` 注入的是 `XmlTag`、我方喂入的是 `XmlText`/`XmlAttributeValue`，宿主不同型，理论上不互斥，需实测澄清）。**这一步不做完，W1 不启动。**
2. **外来注入器的区间可观测性**：探针 registrar 记录 `addPlace` range 后，外来注入器是否会正常调 `addPlace` 并给出真实区间。反编译侧看到 `P/c.java:47-53`、`ak/f.java:27-34` 都是「先算区间再 `startInjecting`+`addPlace`」，但**必须真机确认区间值可用**。不可用则决策 1-A 退回「按属性名 + 按宿主是否含参数占位」的两级粗粒度互补。
3. **平台注入时序**：`addPlace` 是否稳定发生在注入器回调内（而非延迟到 `doneInjecting` 之后）——决定探针能否同步取到区间。
4. **竞品激活门与平台门的两态组合**：竞品 `#{}` 注入器无激活门、`${}`/属性值注入器有 `Profile.getValid()` 门，三者共同受 `!i.b.c()`（Ultimate/DBN 在场即跳过）约束。真机需覆盖「Ultimate / Community」×「CHP 已激活 / 未激活 / 未安装」四态，观察我方着色差异是否符合预期（本机 CHP `valid=true` 可作 Ultimate×已激活样本）。
5. **`MpSqlMapper` 语言下补全断供复验**：T-PerfSweep16/T-XmlCmplDispatch 已修过一次宿主坐标还原，本轮新增 token 类型后需复验注入区间内补全与 `#{}` 参数补全仍被咨询。
6. **两套关键字集统一后的回归面**：`SqlLexer.KEYWORDS` 被补全与预览面板共用，统一到方言超集时不得改变预览面板既有着色断言（对照 `SqlSyntaxHighlighterTest` 一族）。
7. **`<setting name>` 等硬编码表的权威性**：mybatis-config.xml 的 28 项需以 MyBatis 3.5.19 源码 `Configuration`/`Settings` 逐项核对，禁止凭记忆列（用户铁律：不调用不存在的 API）。
8. **jdbcType 枚举来源**：`#{}` 结构属性表须以 `java.sql.JDBCType.values()` 或 MyBatis `JdbcType` 枚举**实际反编译核对**，并确认大小写形态（MyBatis `JdbcType` 是大写枚举名，竞品用的是哪个形态要照抄一致）。
9. **live template 自定义 context 的注册面**：`liveTemplateContext` EP 新增属大改动判据（plugin.xml 新增 EP），需 `verifyPlugin` 构建校验。
10. **卸载安全**：本轮若新增编程式注册（如新的 ParserDefinition/语言扩展），须按动态卸载屏障记忆核查 Disposer 树与 `BarrierRegistry` 登记。
11. **区间级互补的性能**：注入器回调频率不变，但每宿主多一次区间计算与探针调用——万行级 mapper 实测输入延迟；竞品阈值（`maxParsingXmlTextLength=300000`、`parsingMaxLevel=1200`、`reformatLoadingTime=2000ms`）可作参照，我方现行 100_000/4096 是否随之上调需实测后定。

## §六 实施波次

> 前提：§四 决策按推荐项 A 写；用户改判时本表相应波次重写。实施顺序按依赖：W1 独立可先行，W2→W3 同包强依赖串行，W4~W6 写集互斥可并行，W7 独立。

| 波次 | 内容 | 写集（互斥） | 验收判据 | 定向测试 |
|---|---|---|---|---|
| W1 让位区间化（**前提 1 读数确认需要后再启动**） | 探针 registrar 记录 `addPlace` range；`XmlSynHiGate` 增加「区间级互补」判定；注入器按未占用区间注入；属性值侧按属性名互补；顺带订正 `ParamBraceTypedHandler` KDoc 与实现不符处 | `ui/inject/XmlSynHiGate.kt`、`SqlMapperInjector.kt`、`ParamBraceTypedHandler.kt`（注释） | 模拟竞品占用 `#{}` 区间时：语句 SQL 关键字**仍着色**、被占区间不重复注入（反向验证：摘掉互补判定 → 必须回到整段无色） | 门控三形态 stub 探针单测扩展（区间版）+ 反向验证两条读数 |
| W2 `#{}`/`${}` 分色 | 拆两个 token 类型 + 键表映射；typedHandler 语义不变 | `ui/inject/SqlMapperTT.kt`、`SqlMapperHighlightingLexer.kt`、`SqlMapperSyntaxHighlighter.kt` | `#{}` 与 `${}` 显示不同色；词法单测覆盖嵌套/未闭合/转义 | 词法 token 单测 |
| W3 表达式细着色 + 高亮覆盖面 | lexer 加 OGNL 子状态机（关键字/布尔/字符串/数字/`@类@方法`/运算符）；IDENTIFIER/OPERATOR 着色；关键字集统一 + `Dialect` 方言扩展 | 同上 + `SqlMapperLanguage.kt`、`SqlMapperSyntaxHighlighter.kt` | `test="page != null"` 内 `!=`/`null` 分色；方言关键字（如 `MERGE`/`RETURNING`）按方言集着色 | 词法单测 + 方言关键字集单测 |
| W4 补全扩面 A（`#{}` 与属性值） | `#{}` 结构属性表（7 属性键 `jdbcType/javaType/typeHandler/resultMap/mode/numericScale/jdbcTypeName` + 16 项 jdbcType + mode 三值，**逗号后切换**）；属性值按属性名分流补全（id→接口方法名、keyProperty→方法参数名、refid→`<sql id>`（含跨文件 `ns.id`）、resultMap/extends→`<resultMap id>`、resultType/type/ofType/parameterType/typeHandler→类名、property→bean 属性链、jdbcType→`org.apache.ibatis.type.JdbcType` 枚举、column/keyColumn/columnPrefix→列）；语句标签属性名表；resultMap `column` 候选来源改为「解析引用该 resultMap 的 select」（现为 mapper 全文 SELECT） | `ui/highlight/XmlSqlCompletionContributor.kt`、`SourceSqlCompleter.kt`、`SourceSqlSuggestionFactory.kt`、`ui/inject/ResultMapColumns.kt` | 逐个属性给出候选；空候选属性（如 `timeout`）给布尔/枚举而非空；`refid` 跨文件可跳 | 属性值分流单测（逐属性一例）+ 结构属性表单测 + resultMap 引用解析单测 |
| W5 补全扩面 B（OGNL 与 SQL） | OGNL 变量来源补全（`<bind>` name、foreach item·index、include property（ReferencesSearch 反查）、`<properties>` key 聚合、`_databaseId`）；OGNL 智能片段（`x != null` / `.size != 0` / `!= ''` / `'%'+x+'%'` 按类型给）；`@类@静态成员`、运算符、`in`/`nin`；select 投影位按 resultType 类属性；反引号内补本文件 resultMap 的 column+property；SQL 函数表；单字母关键字捷径（27 键形态） | 同 W4（串行接续）+ `ui/XmlParamTypeResolver.kt` | `<foreach item="x">` 后 `#{x.` 出字段候选；`SELECT <caret>` 出 resultType 属性；`<if test="x` 出 `!= null` 片段 | OGNL 变量来源单测 + 智能片段单测 + 捷径表单测 |
| W6 配置文件与编辑辅助 | mybatis-config.xml 补全（28 项 `<setting name>` + 12 项布尔集，表以 MyBatis 源码核对）；colorSettingsPage；XML foldingBuilder；live template（`cd`/`co`）；注入 SQL 文本**豁免拼写检查**；`daemon.changeLocalityDetector` 放大编辑脏区；backspace 成对删 `#}`/`$}` | 新文件 + `plugin.xml` 新增段 | 颜色页可改 `#{}` 底色并即时生效；动态标签可折叠；`cd`/`co` 展开正确；注入文本不再被拼写标红 | 颜色页键表单测 + 折叠区间单测 + 拼写豁免判定单测 + EP 接线守卫 |
| W7 引用导航（决策 5-A 时） | 引用类 + `psi.referenceContributor` 注册 + resolve 缓存：`include refid`、`resultMap`/`extends`、`namespace`、`resultType` 族、`#{}` 参数、`test` 属性字段 | 新目录 `ui/ref/` + `plugin.xml` | 六类目标 Ctrl+B 全部跳转到位；跨文件 refid 可跳；无目标时零报红（不触红线） | 引用 resolve 单测（逐类）+ 无目标负路径单测 |
| W8 守卫与性能验收 | 区间互补反向验证；关键字集一致性守卫（高亮集 ⊇ 补全集）；熔断阈值复测；真机双机走查（用户 IU 2026.2 装 CHP+zMyBatis / runIde 沙盒） | 测试目录 | 用户真机上「语句里有 `#{}` 时 SQL 关键字仍着色」肉眼可见；万行级 mapper 无输入卡顿 | 守卫反向验证（摘修复必红）+ 性能读数 |

## §七 测试与反向验证纪律

- 守卫/让位类改动必做反向验证：摘掉区间互补判定 → 在「模拟竞品占用区间」夹具下必须回到整段无色（两次读数落验收文档）。
- 「非 mapper 文件零注入」不构成精度证据，必须配真实 mapper 语料命中用例（防空绿）。
- 关键字集一致性守卫要能抓住「补全有、高亮无」的差集——合成样本只能证规则看得见我造的形态，须用真实差集（当前 25 个词）做判别位。
- 全量门禁三档分层：实施子代理只跑定向；收口单派只读代理跑全量；**大改动判定命中**（plugin.xml 新增 EP：`liveTemplateContext`/`psi.referenceContributor`/`colorSettingsPage`/`foldingBuilder`）→ 加 `verifyPlugin` 构建校验，发版前再 `buildPlugin`。
- 真机走查双机：用户日常 IU 2026.2（装 CHP 已激活 + zMyBatis，**这是本轮最关键的验收环境**，因为缺陷只在该环境暴露）+ Community runIde 沙盒（反向：无竞品时全量着色）。
- 收口期间主控不得碰 `src/main`；文档并行车道不受影响。

## §八 风险

| 风险 | 缓解 |
|---|---|
| 探针取不到真实区间（前提 2/3 证伪） | 决策 1-A 退回粗粒度互补（按属性名 + 按「宿主是否含 `#{}`/`${}`」两级），保底让「无参语句」恢复着色 |
| 前提 1 实测发现我方真被让位（与预期相反） | W1 立即升为第一优先；先跑探针确认占用方，再按决策 1-A 改区间互补（届时需评估是否要给 `ak.e` 的平台轨留位） |
| 区间互补后仍与竞品叠色 | 只注入未占用区间是硬约束；真机肉眼 + 双机对照各一条读数 |
| 关键字集统一改坏预览面板着色 | 预览面板断言先跑（前提 5），统一只做「并入」不删词 |
| 补全扩面引入 dumb 态回归 | 新候选来源若触索引，必须 `DumbAware` + `RELIABLE_DATA_ONLY` 兜底（T-XmlCmpl2 教训） |
| 引用导航跨文件 resolve 拖慢编辑 | resolve 走 `ResolveCache`；无目标即返回 null（不报红），失败面最小 |
| live template 自定义 context 注册面扩大 | 归入大改动判据，走 `verifyPlugin` |
| 与 zMyBatis 的共存未验证 | 前提 1 的探针天然覆盖「任一外来注入器」；真机走查把 zMyBatis 也纳入观察项 |
| 批次过大导致收口周期长 | 决策 4 可拆两批；W7 可独立立项 |

## §九 变更记录（单文件）

| 日期 | 记录 |
|---|---|
| 2026-10-12 | **六项决策点定案（用户「可以按照推荐 A」）**：决策 1 让位区间化＝A（探针记录 `addPlace` range，我方只注入未占用区间；W1 受前提 1 真机读数约束）、决策 2 着色粒度＝A（词法级细着色，不新建第二语言）、决策 3 覆盖面＝A（关键字集统一为 `SqlLexer.KEYWORDS` 超集 + `Dialect` 方言扩展 + IDENTIFIER/OPERATOR 着色）、决策 4 补全批次＝A（一批全做）、决策 5 引用导航＝A（本轮纳入跳转与查找用法，不做报红与重命名）、决策 6 编辑辅助＝A（颜色页/折叠/live template `cd`·`co`/拼写豁免/脏区放大/成对删六项全做）。实施启动=用户另令。 |
| 2026-10-12 | 立项：O-144 / T-XmlGap 计划生成，依据同日调研报告（第二轮，样本 CHP 3.6.7）；六项决策点待用户定案，未动码 |

## §十 实施记录

### 实施组织（用户 2026-10-12 明令：合并由主控做、减少全量测试次数）

| 项 | 落法 |
|---|---|
| 工作树/分支 | `xmlgap`（主控自持：W1/W6/W7）、`xmlgap-hl`（子代理 A1：W2/W3）、`xmlgap-cmpl`（子代理 A2：W4/W5），三者均自 master `c9518e8` 派生 |
| 内部仓读取 | 按用户指定方式——**绝对路径直读主树** `…/mybatis-plus-sql-visualizer/docs/内部/…`（新工作树不含该嵌套私有仓） |
| 合并 | 主控执行；A2 一支并入 `4dcb2ef`（**零冲突**，写集不相交） |
| 测试次数控制 | 各波次只跑定向 `--tests`；全量门禁**全流程只跑一次**（收口阶段）；合并点只做 `compileTestKotlin` 校验，不重复跑测试 |

### 前提 1 结论（W1 解锁依据，静态证据闭环，未打扰用户 IDE）

| 注入器 | 喂入类型 | 用户 IU Ultimate 上活跃？ | 触发我方让位？ |
|---|---|---|---|
| 竞品 `P/g` 属性 OGNL | `XmlAttributeValueImpl` | 否（`Profile.getValid()` + `!i.b.c()` 双门） | 否 |
| 竞品 `P/c` `${}` | `XmlText` | 否（同上双门） | 否 |
| 竞品 `ak/f` `#{}` | `XmlTextImpl` | 否（`!i.b.c()` 平台门） | 否 |
| 竞品 `ak/e` 平台轨整标签 | **`XmlTag`**（`Arrays.asList(XmlTag.class)` 实测） | 可能 | **否**（宿主不在我方探测面） |
| zMyBatis（用户真机同在装） | **无注入器**（右键执行插件，plugin.xml 实测无 multiHostInjector） | — | 否 |
| 平台 SQL 注入器 | SQL PSI | — | 否 |

竞品社区轨自让位链：`plugin.xml:484` `<depends optional="true" config-file="plugin_database.xml">com.intellij.database</depends>` ⇒ IU 自带 Database 插件 ⇒ `plugin_database.xml:110` 注册 `ultimateVersionChecker` ⇒ `i.b.c()` 为真 ⇒ 三个社区轨注入器整段跳过。

**结论**：用户真机上我方**未被让位**；W1 的价值面是 **Community 版（社区轨注入器活跃）+ 任何未来第三方注入器**，不是当前症状修复。

### W1 让位区间化（主控）

- 新增 `ui/inject/InjectRangePlan.kt`（纯逻辑）：占用区间归一（裁剪 → 扩张到 `#{...}`/`${...}` 整段 → 合并）+ 互补区间计算；扩张是为了不让切割点落在参数定界符中间（竞品 `${}` 注入器只报 `${` 之后到 `}` 之前的区间）。
- `XmlSynHiGate`：探针 registrar 换成记录 `addPlace` 区间的 `RangeRegistrar`；新增生产入口 `occupiedRanges`（设置开关线 + 布尔缝兼容 + 异常 fail-safe 全覆盖哨兵）；`shouldYield(project, host)` 语义修订为「**整宿主**被占」。
- `SqlMapperInjector`：按未占用区间注入（单会话多 place），不再整宿主放弃。
- 顺带订正 `ParamBraceTypedHandler` KDoc（旧述「双让位」与实现不符，实现只查设置开关线）。
- **自测抓出的设计缺陷（留痕）**：首版 `shouldYield` 判「占用区间非空即让位」——区间形态下这会把「对方只占一个 `#{}`」也判成让位，正好抵消互补收益；由 `XmlSynHiGateRangeTest.testW1_部分占用_不让位` 抓出后修订。第二次由接线用例抓出「布尔缝被 setUp 钉成 false 会短路区间缝」，区间用例补清空布尔缝。

**读数**：`ui.inject + ui.highlight + ui.ref` 定向 **347 例 0 失败 0 错误**（`GRADLE_EXIT=0`）。反向验证位：`testW1_部分占用_不让位`（旧宿主级形态必红）、`testW1_外来占满整宿主_语句文本零着色`（保守线不回归）、`InjectRangePlanTest` 16 例纯逻辑判别位。

### W6 配置文件补全与编辑辅助（主控）

| 件 | 落点 | 硬编码表来源（全部实读 jar，禁凭记忆） |
|---|---|---|
| mybatis-config 补全 | `ui/highlight/MybatisConfigCompletionContributor.kt`（独立文件，不改 W4/W5 正在改的主贡献器） | 29 项 `<setting name>`（`XMLConfigBuilder` 常量池；`multipleResultSetsEnabled` 在 3.5.19 **已不存在**）、布尔 13 项、枚举 5 组（`javap` 枚举常量）、`jdbcTypeForNull` 41 项（`JdbcType` 常量）、配置子标签表 |
| live template | `ui/inject/MybatisSqlTemplateContextType.kt` + `resources/templates/mybatis-sql.xml` | 只做 `cd`（CDATA 骨架）/`co`（foreach 骨架）两个纯骨架；**不含**竞品引导注释类模板 |
| 编辑脏区放大 | `ui/inject/SqlMapperChangeLocalityDetector.kt` | 收窄到**所属语句标签**（竞品放大到整文件；取舍见 KDoc，防大 mapper 无谓重算） |
| `#{}`/`${}` 成对删 | `ui/inject/SqlMapperBackspaceHandler.kt` | `charDeleted` 阶段补删（不改平台原有删除语义，回归面最小） |
| 注入片段拼写豁免 | `ui/inject/SqlMapperSpellcheckingStrategy.kt` | **按注入语言注册**（非 XML 级）——XML 级注册会与平台既有 XML 拼写策略同台竞争，选错优先级即污染所有 XML |
| 颜色可配置 | **未做**（依赖 W2/W3 的键表） | 待 A1 合并后按新键表补 `colorSettingsPage` |
| XML 折叠 | **有意不做** | 平台 XML 折叠已覆盖标签折叠，自研 builder 只会与平台抢同一 fold 区、收益为零；若确需竞品式「`select#id` 折叠占位」需先做真机实验 |

### W7 引用导航（主控）

- 新增 `ui/ref/MybatisXmlReferenceContributor.kt` + `ui/ref/MybatisXmlTargets.kt`：九类属性引用——`refid`→`<sql id>`（含跨文件 `ns.id`）、`resultMap`/`extends`/`parameterMap`→对应 id、`namespace`→接口、statement `id`→接口方法、`resultType` 族→`PsiClass`、`jdbcType`→MyBatis `JdbcType` 常量（**按类名运行时解析**，生产依赖图零 mybatis——build.gradle.kts 红线）、`property`→实体字段链。
- 红线守法：**全部 soft 引用**（目标缺失零报红）+ 零 inspection/annotator（有专门守卫用例扫本包源码禁词）。
- 复用：mapper 文件清单单槽 TTL 缓存沿用 `log/MapperXmlLocator` 的成熟模式（禁引监听器）。

### W2+W3 高亮粒度与覆盖面（子代理 A1，`75491fd`）

- 交付：`PARAM` 拆为 `PARAM_HASH`/`PARAM_DOLLAR`；`#{}` 参数规格与 `${}` OGNL 双子状态机（含花括号深度、未闭合容错、`\#` 转义）；实现 `RestartableLexer` 支持增量续扫；`IDENTIFIER`/`OPERATOR`/标点族（`PAREN`/`BRACKET`/`BRACE`/`COMMA`/`DOT`）接平台语义槽；关键字集 **41 → 589 词**（原 41 ∪ `SqlLexer.KEYWORDS` ∪ 十一方言 `reservedWords`，只增不删）；新增 `BOOLEAN`、`STATIC_REF` token。
- **键位取舍（主控评审接受）**：计划 §四 决策 2-A 的三句参考取向互斥（一个 token 只能落一组属性），子代理按「分色优先」裁决并写明 KDoc——`#{`/`}` → `KEYWORD`、`${`/`}` → `INSTANCE_FIELD`；**代价＝原「`#{}` 整段淡底」不再存在**。颜色页（见下）已把 14 键全部暴露为可调项，用户可自行调回偏好配色。
- **反向验证（子代理自证，两次读数在案）**：关键字集临时改回 41 词平集 → 守卫 4 例红，含与调研报告 §0.9 逐词吻合的「差 25 词」**真实差集**（`TABLE/CREATE/DROP/…/COUNT/SUM/…`）；还原后 7+36 例绿。
- **主控补验（新增平台 API 面的兼容性）**：`com.intellij.lexer.RestartableLexer` 在 **243**（`ideaIU-2024.3.7.1-win/lib/util-8.jar`）与 **253**（`idea-2025.3.5-win/lib/util-8.jar`）签名**逐字一致**（`getStartState`/`isRestartableState`/5 参 `start`），262 亦在（`intellij.platform.core.jar`）→ 无最低版本兼容问题；因属「新平台 API 面」，收口仍加 `verifyPlugin` 三目标。
- 子代理登记的 8 条未做/不确定项中，最需用户知情的三条：布尔当前与关键字同色（颜色页可拆）；OGNL 关键字集只取 `Ognl.kt` 实际支持的 `and/or/not/in/null`（`eq/neq/lt/gt` 等该求值器无实现）；方言并集有**已知过度着色面**（顶层裸用 `key`/`size` 等保留字会着关键字色，精确收敛需 per-file 方言链，列为后续）。

### W4+W5 补全扩面（子代理 A2，`f8474b1`）

- 交付：`#{}` 结构属性（7 键 + 41 jdbcType + mode 三值，逗号后切换）、属性值逐属性分流（`id`/`keyProperty`/`refid`/`resultMap`/`extends`/`parameterMap`/`resultType` 族/`property`/`jdbcType`/`column` 族）、语句标签与 resultMap 族属性名（MyBatis DTD 逐条抄录）、OGNL 变量来源（`<bind>`/foreach item·index/include property/config properties/`_databaseId`·`_parameter`）、OGNL 智能片段与运算符、select 投影按 resultType、反引号列名、SQL 函数表、单字母捷径、resultMap「引用它的 select」列来源。
- 硬编码表全部实读（`javap` + `unzip -p … mybatis-3-mapper.dtd`）：7 键与调研报告 §3.2 逐项一致；jdbcType 按前提 8 用 **41 项全枚举**（非报告里的 16 项）。
- **未单测项（受 Light 沙箱索引限制，如实登记）**：跨文件 `refid`、`<properties>` key 聚合、foreach item 元素字段、`@类@静态成员`——由 W8 收口评估补测或真机复核。
- 口径差异（已登记）：计划 §六「空候选属性给布尔/枚举」与派活「`timeout` 等数值属性不产候选」冲突，按后者实现（数值属性无枚举语义）。

### 颜色设置页（W6 收尾，主控）

- `ui/inject/SqlMapperColorSettingsPage.kt` + `<colorSettingsPage>` EP：暴露 W2/W3 的 **14 个键**（关键字/标识符/字符串/注释/数字/运算符/括号族/逗号/点/静态引用/`#{}`/`${}`）；预览样例覆盖全部键；带「描述符覆盖全部高亮键」守卫（反射枚举 `SqlMapperTT` 字段——未来新增键漏描述符必红）。

### W1 反向验证读数（仓库纪律：两次读数）

| 轮次 | 操作 | 读数 |
|---|---|---|
| **红** | 把 `shouldYield` 改回「占用非空即让位」、注入器改回宿主级 `return`（`RED PROBE` 留痕） | `XmlSynHiGateRangeTest` 10 例 **1 红**（`testW1_部分占用_不让位`）；`SqlMapperInjectorWiringTest` 7 例 **1 红**（`testW1_外来只占参数区间_我方仍注入余下区间`）；其余 15 例仍绿 ⇒ 探针**精准**（非成片失败） |
| **绿** | `git checkout` 还原两文件（`grep "RED PROBE"` 命中 0） | 两类 **17 例 0 失败**（`EXIT=0`） |

### 遗留与待真机项（W8 收口）

1. **颜色页**：✅ 已补（见上节）。
2. **真机走查**（用户 IU 2026.2，装 CHP 已激活 + zMyBatis）：语句含 `#{}` 时 SQL 关键字着色可见；`#{}`/`${}` 分色；属性值补全；Ctrl+B 九类跳转；live template `cd`/`co` 展开；成对删；拼写不再误标；颜色页可调。
3. **W4/W5 子代理登记的未单测项**（受 Light 沙箱索引限制）：跨文件 `refid`、`<properties>` key 聚合、foreach item 元素字段、`@类@静态成员`——由 W8 收口时评估补测或真机复核。
4. **W2/W3 登记的两处待评审取舍**：布尔是否单独配色（现与关键字同色）；方言并集过度着色是否要 per-file 方言收敛。
5. **全量门禁**：一次（收口阶段，只读收口代理执行）；因新增平台 API 面（`RestartableLexer`）与 7 个新 EP，附加 `verifyPlugin` 三目标。
