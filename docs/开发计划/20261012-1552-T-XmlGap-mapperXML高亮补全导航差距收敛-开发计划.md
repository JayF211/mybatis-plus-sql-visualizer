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
| XML 折叠 | **已做**（`ui/inject/SqlMapperFoldingBuilder.kt` + `<lang.foldingBuilder language="XML" order="first">`） | 平台自带 `XmlFoldingBuilder`（基类 `XmlCodeFoldingBuilder`）**已**为 XML 标签建 fold 区、占位为标签名本身（javap 实证；`isCollapseXmlTags` 只控制默认折叠与否）。故本类的增量＝**把占位换成 `标签名#id`**（`select#findById`，长 mapper 折叠后一眼认出是哪个语句），作用面刻意收窄到：仅 mapper 文件、仅语句标签与带 id 的 `<sql>`、仅跨多行标签、不自带默认折叠。与平台区同范围时的占位优先级属渲染期行为 → 列入真机走查项 |

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
- **主控复核「既有断言是否被削弱」**（仓库红线）：`XmlSqlCompletionContributorTest` 两处随扩面更新——`collection` 那条仍以 `take(8)` **精确钉住**新增变量后的前八项顺序（未削弱，反而把新顺序写进了契约）；`test` 那条由「全量等于 3 项」放宽为「前 3 项 + 含 `_databaseId`」，**尾部不再钉全量**属轻微放宽，已由新增 `XmlSqlCompletionW4W5Test`（29 例）覆盖全量面，接受。

### 颜色设置页（W6 收尾，主控）

- `ui/inject/SqlMapperColorSettingsPage.kt` + `<colorSettingsPage>` EP：暴露 W2/W3 的 **14 个键**（关键字/标识符/字符串/注释/数字/运算符/括号族/逗号/点/静态引用/`#{}`/`${}`）；预览样例覆盖全部键；带「描述符覆盖全部高亮键」守卫（反射枚举 `SqlMapperTT` 字段——未来新增键漏描述符必红）。

### W1 反向验证读数（仓库纪律：两次读数）

| 轮次 | 操作 | 读数 |
|---|---|---|
| **红** | 把 `shouldYield` 改回「占用非空即让位」、注入器改回宿主级 `return`（`RED PROBE` 留痕） | `XmlSynHiGateRangeTest` 10 例 **1 红**（`testW1_部分占用_不让位`）；`SqlMapperInjectorWiringTest` 7 例 **1 红**（`testW1_外来只占参数区间_我方仍注入余下区间`）；其余 15 例仍绿 ⇒ 探针**精准**（非成片失败） |
| **绿** | `git checkout` 还原两文件（`grep "RED PROBE"` 命中 0） | 两类 **17 例 0 失败**（`EXIT=0`） |

### 折叠 builder（W6 补齐，主控）

- `ui/inject/SqlMapperFoldingBuilder.kt` + `<lang.foldingBuilder language="XML" order="first">` EP；测试 `SqlMapperFoldingBuilderTest` 8 例 0 失败（多行语句建区且占位 `select#findById`、单行/无 id/非 mapper/resultMap 族零区、不自动折叠、EP 接线守卫）。
- 设计前提已写进类 KDoc：平台 `XmlCodeFoldingBuilder` **已**为 XML 标签建 fold 区（占位＝标签名），本类的增量是**带 id 的占位**，不是「让标签可折叠」；作用面收窄以免与平台抢 fold 区。
- 首跑编译红一次：`FoldingBuilder.getPlaceholderText(ASTNode)` 是接口抽象成员（`FoldingBuilderEx` 只提供二参重载）→ 补实现后绿。

### 全量门禁与兼容校验读数（收口）

| 项 | 读数 |
|---|---|
| `testShards` 首轮（只读收口代理，未清测试产物） | **FAILED 5m 2s**；5 片 1439/1585/1225/1401/1297 = **6947 例 / 2 失败 / 0 错误 / 8 skipped / 629 类** |
| ├ 失败 1（shard2，**真红**） | `GuardSelfTestCoverageMetaGuardTest.磁盘守卫类必须全部登记_防新守卫类整体脱离扫描面`：W3 新增 `ui/inject/SqlMapperKeywordCoverageGuardTest.kt` 未登记进 `GUARD_CLASSES`（整体脱离自证覆盖率扫描面）→ **已修 `7a97a77`**（按登记表口径补登，判定内核内联于用例故 `decisionFns` 留空 + 写明依据与灵敏度实测） |
| └ 失败 2（shard4，**陈旧测试字节码**） | `SqlTextAnalysisTest.strip后where内裸列补全产出主表列` → `NoSuchMethodError`（旧 3 参 `sqlCompletions` 重载）：W4/W5 给该公开方法加了 2 个默认参数，旧 3 参 JVM 重载随之消失，而 `compileTestKotlin` 判 UP-TO-DATE 未重编 → 测试类停在旧 ABI。**主控独立复核**：源码侧 3 参调用对新签名合法（`SqlTextAnalysisTest.kt:266`），属本仓已登记的假失败形态（改带默认参数后旧测试类 NoSuchMethodError） |
| `testShards` 复跑（清 `build/classes/kotlin/test` 强制重编，**非 `clean`**） | **BUILD SUCCESSFUL 5m 15s，EXIT=0**；5 片 1520/1272/1392/1364/1399 = **6947 例 0 失败 0 错误 8 skipped** |
| `verifyPlugin`（IPGP，收口代理） | 任务非零退出，但三目标 verdict 逐字 `Compatible.`（243：4 removal/29 deprecated/98 experimental；253：5/36/96；262：7/97/96）。非零退出源自项目既有 `failureLevel` 口径（`FailureLevel.entries - COMPATIBILITY_PROBLEMS`，把 DEPRECATED/EXPERIMENTAL/MISSING_DEPENDENCIES 也算失败），**非兼容性问题**；`MISSING_DEPENDENCIES` 两条（`com.intellij.ml.llm`/`com.intellij.mcpServer`）均为既有 `depends optional` |
| `verifyGate`（**权威**兼容门禁：直驱 verifier-cli，`-offline` + `-ignored-problems`，判定只看退出码） | **BUILD SUCCESSFUL 2m 8s，EXIT=0**；三目标 verdict 同上（主控**独立读 `build/reports/pluginVerifierGate/` 产物**复核，非采信自述） |
| 本批次新增 API 面 | `com.intellij.lexer.RestartableLexer`（2 处覆写）在三目标均记 **EXPERIMENTAL 使用提示**、verdict 仍 `Compatible`；全文无 `override-only`、无未知 EP 告警 |
| 喂入税性能守卫 | `XmlSynHiGateYieldCostTest`（900+ 喂入元素、断言整 pass 零 app 级服务查找 + 反向验证位）在定向批次内绿 ⇒ W1 的区间探针**未破坏**喂入税纪律（设置快照仍在最前短路） |
| W8 熔断阈值测试（本轮补齐） | 新增 `ui/inject/XmlSynHiFuseTest`（4 例 0 失败）：语句文本 100_000 边界两侧（恰等放行 / 超一熔断）、属性值 4096 边界、非 mapper 根守卫（语句侧经 `allowStatementText`、属性值侧经 `inMapperFile`——`allowAttributeValue` 本身不含文件上下文，测试首跑抓出该判据归属写错并已修正） |

**门禁时效性说明**：`testShards` 复跑与 `verifyGate` 的读数对应提交 `7a97a77`；其后仅新增**测试类** `XmlSynHiFuseTest`（零生产代码改动），已定向验证绿——如需严格同树对账，下次门禁即覆盖。

### W8 沙盒走查读数（IU-2024.3.7.1 = 243，最低支持版本）

环境：`./gradlew runIde --args="<demo 项目>" -Pkotlin.compiler.execution.strategy=in-process`；沙盒为 **IU-2024.3.7.1**（正好是最低支持版，比默认 262 更有说服力）；pyautogui 驱动，截图存外层 `mpsv-walk/xmlgap-*.png`（不进仓）。
目标文件：`mybatis-plus-demo/src/main/resources/mapper/OrderMapper.xml`（同文件含 4 个 `#{}` 与 4 个 `${}`）。

| 走查项 | 读数 | 证据 |
|---|---|---|
| 插件在 243 沙盒加载并工作 | ✅ 预览面板正常渲染、`执行 SQL` 真跑出 2 行结果（面板显示 `2 rows`） | `xmlgap-06/13-*.png` |
| **SQL 关键字着色** | ✅ `SELECT`/`FROM`/`LEFT JOIN`/`ON` 呈 KEYWORD 橙，与正文明显区分 | `xmlgap-07-zoom-sql.png`（放大 2×） |
| **标识符着色**（W3 新增能力） | ✅ 列名/表名（`o.id`/`t_order`/`user`/`u.id`）呈 IDENTIFIER 浅蓝，肉眼可辨 | 同上 |
| **`${}` 着色**（W2 分色） | ✅ 定界符 `${`/`}` 呈 KEYWORD 橙、内部标识符另色——与 `#{}` 一眼可分的设计目标达成 | 同上 |
| 语句标签折叠标记 | ✅ 第 6/11/17 行（含 id 的语句标签）gutter 有折叠标记 | `xmlgap-06-editor-clean2.png` |
| **我方注入在场**（判别性证据） | ✅ `Alt+Enter` 意图列表含 **`Edit MpSqlMapper Fragment`**——该意图只对**注入片段**生成，且语言名正是本插件的 `MpSqlMapper` ⇒ 我方注入未被让位 | `xmlgap-31-altenter-zoom.png` |
| **拼写豁免** | ✅ **无缺陷**（勘误，见下）：SQL 文本上的波浪线经 tooltip 实证为**平台 SQL 方言弱警告**，与拼写检查无关 | `xmlgap-33-tooltip-zoom.png` |
| 折叠**占位文本**（`select#id`） | ❓ 未隔离观察到：平台标签折叠与我方区**范围相同**，`Ctrl+Shift+Minus` 折叠呈现的是平台区（`....>` 形态）；我方占位优先级属渲染期行为，如实留作未证 | `xmlgap-35/39-*.png` |

**一次误诊与勘误（留痕，勿重犯）**：走查首轮看到 SQL 文本上的波浪线，我按「绿/黄波浪＝typo」的经验判为拼写检查未豁免，并据此新增了 `SqlMapperXmlSpellcheckingStrategy`（XML 级注册、继承平台策略）。复验时悬停取到 **tooltip 原文：「SQL dialect is not configured.」**——波浪线实为**平台 SQL 方言弱警告**（IU 下平台给 mapper XML 语句文本挂了 SQL 方言语义），与拼写检查无关。
**处置**：该误诊方案（类 + EP + 用例）**已整批回退**（`plugin.xml` 保留一条勘误注记防重犯）；`spellchecker.support language="MpSqlMapper"` 按计划原样保留。
**教训**：波浪线判色不足以定性 inspection——**必须取 tooltip 原文**（或 Alt+Enter 意图名）再动手；本轮若不复验，就会把一个「平台既有行为」当成我方缺陷修，还留下一条无效注册。

**顺带观测（非本批次引入）**：IU 下 mapper 语句文本带「SQL dialect is not configured」弱警告属平台既有行为——竞品对此的解法是让用户手写引导注释（内部调研报告 §6），我方不做校验器、不引入注释负担，故该警告保持原样（用户可在 IDE 里为项目配置 SQL 方言消除）。

**沙盒清理核验**：两轮走查均以 `Alt+F4` 逐级关闭（模态 → 项目信任框 → 欢迎页 → Confirm Exit）后 `runIde` 任务正常结束（exit 0）；`Get-CimInstance` 按命令行过滤 `xmlgap`/`idea-sandbox`/`MPSV` **零残留**（记忆里「强停 gradle 不带走进程 ⇒ 下次 runIde 目录锁失败」的坑两轮均避开）。

### 收口发现的覆盖缺口：配置缓存 × 分片清单（**空绿**形态，已处置）

**现象**：本轮先后跑了两轮全量，各片计数**逐字相同**（shard1 1520 / shard2 1272 / shard3 1392 / shard4 1364 / shard5 1399 = 6947），但两轮之间本批次**新增了 12 个用例**（`SqlMapperFoldingBuilderTest` 8 + `XmlSynHiFuseTest` 4，另 `SqlMapperXmlSpellcheckingStrategyTest` 5 加后回退）——总数应当变化却没变。

**根因（读 `buildSrc/src/main/kotlin/mpv-testing.gradle.kts` 定位）**：`testShards` 的分片清单在**配置期**由 `testFileEntries` 源码扫描算出（`discoveredTestClasses`），而本仓开启 `org.gradle.configuration-cache=true` ⇒ **新增测试类不会进入旧的分片清单**，全量门禁静默漏跑它们。权重表（`dev-support/test-shard-weights.tsv`）只影响均衡，不决定清单——所以「补权重」也救不了这个缺口。

**影响面判定**：本批次**全部**新增测试类都不在清单内（逐类核对 `SqlMapperKeywordCoverageGuardTest`/`InjectRangePlanTest`/`XmlSynHiGateRangeTest`/`XmlGapEditingAssistTest`/`SqlMapperColorSettingsPageTest`/`SqlMapperFoldingBuilderTest`/`XmlSynHiFuseTest`/`MybatisConfigCompletionContributorTest`/`MybatisXmlReferenceTest`/`XmlSqlCompletionW4W5Test`/`MyBatisXmlCompletionDataTest`/`ResultMapColumnsGapTest` 等 14 个 = 权重表命中 0）。即：**不带处置的全量绿不构成本批次测试的覆盖证据**。

**处置**：收口门禁带 `--no-configuration-cache` 重跑（清单重算，新增类入场）；此后读数才是本批次的有效全量证据。
**处置后读数（最终树，`b9a472b`）**：`testShards --no-configuration-cache` → **6959 例 / 0 失败 / 0 错误 / 8 skipped**，5 片 1378/1386/1467/1413/1315（= 原 6947 **+ 本轮净增 12 例**，逐类核对 `SqlMapperFoldingBuilderTest`/`XmlSynHiFuseTest`/`InjectRangePlanTest`/`MybatisXmlReferenceTest`/`XmlSqlCompletionW4W5Test`/`SqlMapperKeywordCoverageGuardTest` 等结果文件均已在各片产出）；`verifyGate --no-configuration-cache` → **BUILD SUCCESSFUL 1m54s，EXIT=0**，三目标 verdict 仍全 `Compatible.`。
**给后人的判据**：见到「新增测试类后全量各片计数不变」即为本缺口形态；`testShards` 读数与「新增用例数」对不上账时，先查 configuration cache。

### W8 走查（二）：Ultimate 262 + CHP + zMyBatis **共存环境**（本批次最关键的一站）

**环境复刻方式与理由**：计划原写「用户日常 IU 2026.2」，但直接往用户日常 IDE 装开发包会**覆盖其在用的发行版**（不可逆的用户侧副作用，且属用户环境改动）——故改为**等价复刻**：`./gradlew runIde -PgifRecord=true` 起 **IU 2026.2.1 Ultimate** 沙盒（`build/MPSV-GifFresh`），并把用户机在装的 `MyBatisCodeHelper-Pro`（37M，含 `valid=true` 的 `MyBatisCodeHelper.xml`）与 `zMyBatis`（1.9M）连同激活配置一并拷入沙盒 → **Ultimate + 竞品已激活 + zMyBatis 在位**，与用户真机的关键条件一致（版本 2026.2.1 vs 用户 2026.2.3，同一 minor）。
**注**：竞品插件在沙盒内仅供本机验证，不涉及任何再分发。

| 走查项 | 读数 | 证据 |
|---|---|---|
| **共存下我方注入是否在场**（W1 前提 1 的现场实证） | ✅ **在场**：`Alt+Enter` 意图列表含 `Edit MpSqlMapper Fragment`（该意图只对注入片段生成）⇒ **未被 CHP/zMyBatis/平台让位** | `xmlgap-75-altenter-zoom.png` |
| **语句含 `#{}` 时 SQL 关键字着色可见**（W8 头条验收） | ✅ `SELECT`/`FROM`/`LEFT JOIN`/`ON`/`AND`/`LIKE` 橙（KEYWORD），与正文分明 | `xmlgap-73-zoom262.png`、`xmlgap-81-top-zoom.png` |
| 标识符着色（W3） | ✅ 列名/表名（`o.id`/`t_order`/`user`/`u.id`）浅蓝 | 同上 |
| `#{}` / `${}` 占位渲染 | ✅ 两者都从正文里「跳出来」（定界符暖色 + 内部标识符蓝） | 同上 |
| **Ctrl+B 跳转**（W7 头条） | ✅ 在 `<include refid="Base_Column_List"/>` 上 Ctrl+B → **跳到第 20 行 `<sql id="Base_Column_List">`**（光标落在 id 值上） | `xmlgap-89-ctrlb-zoom.png` |
| 折叠标记 | ✅ 语句标签与 `<sql>` 行 gutter 有折叠标记 | `xmlgap-81/85-*.png` |
| 折叠**占位文本**（`select#id` vs 平台标签名） | ❓ **未取到**：`Ctrl+Minus` 多次点击落点漂移（视图在截图与点击间滚动），未命中语句标签；**问题仍未答** | `xmlgap-90/91-*.png` |
| `#{}` 与 `${}` **互相**可分（W2 验收「一眼可分」） | ⚠️ **未确证**：截图分辨率下像素取证不结论（采样混入抗锯齿邻色）；**代码级已证**——两键不同且 fallback 不同（`PARAM_HASH`→KEYWORD / `PARAM_DOLLAR`→INSTANCE_FIELD），有 `SqlMapperSyntaxHighlighterKeyTest` 钉住 | 代码 + 单测 |
| 属性值补全 / live template `cd`·`co` 展开 / `#{}` 成对删 / 颜色页可调 | ❓ 未取到：本轮 GUI 驱动（pyautogui + 中文输入法切换 + 窗口置前）单步成本高、落点漂移，四项各需多轮点击；**代码 + 单测已覆盖，视觉确认待用户侧 2 分钟核对** | — |

**沙盒清理核验**：`Alt+F4` → `Confirm Exit` → Enter 后 `runIde` 任务正常结束（exit 0）；`Get-CimInstance` 按 `MPSV-GifFresh` 过滤 **零残留**。
**走查工具坑（本轮实录，供后续复用）**：①沙盒 IDE 被 Chrome/WPS 覆盖时 `pygetwindow.activate()` 与 `SetForegroundWindow` 均被 Windows 拦（仓内 `record/force_front.py` 的 AttachThreadInput 也没抢到）——**正解是先把浏览器窗口 `minimize()`** 再点进 IDE；②沙盒内**中文输入法默认激活**，`typewrite` 会被拼音截走（表现＝乱码 + 候选条），**先按 Shift 切英文**；③IDE 的 Go to File 需**两次 Enter**（首次只选中、二次打开）；④每次点击前必须重新截图定位（视图会自行滚动）；⑤**折叠键位是 NumPad 专用**（`Ctrl+NumPad-` / `Ctrl+Shift+NumPad-`，普通 `-` 无效）——无小键盘或键位被占时改走 Find Action（`Ctrl+Shift+A`）；⑥**`press enter` 会落进编辑器**：Find Action 弹窗若已因故关闭，那次 Enter 会在光标处插入空行（本轮实录：`easy-code-demo` 的 mapper 文件被插了一个**首行空行**，走查后已逐字还原）——**GUI 走查结束后必须对目标文件跑一次 `git diff` 核对**，别假定「只点没改」。

### W8 走查（三）：**着色不生效**根因定位（用户 2026-10-12 报「SQL 文本整段白」）

**用户反馈**：看我的走查截图，mapper XML 里 SQL 文本**整段是白的**（我此前把 XML 标签的橙色误读成了 SQL 关键字色——5× 原像素放大后确认用户是对的）。

**诊断方法（判定点加临时日志，证明路径走到哪一步）**：在注入器入口、区间计算后、高亮器工厂、高亮器构造器四处埋 `System.err.println`，两个平台各跑一次 runIde 后读日志。

| 平台 | 注入器被调 | occupied | 我方注入片段 | **高亮器工厂被调** | **高亮器构造** |
|---|---|---|---|---|---|
| IU-2024.3.7.1（243） | ✅ 104+55 次 | 0 | ✅ 49 个 | ❌ **0** | ❌ **0** |
| IU-2026.2.1（262） | ✅ 1794+1143 次 | 0 | ✅ 358 个 | ❌ **0** | ❌ **0** |

**结论（与先前判断相反，逐条留痕）**：
1. 我方**注入确实发生**（片段存在，`Edit MpSqlMapper Fragment` 意图在场），**且未被让位**（`occupied=0`）。
2. 但**我方高亮器在 243 与 262 上都从未被实例化**（工厂/构造器探针双 0）⇒ **注入语言的自有着色从未真正渲染过**；此前在 243 沙盒看到的橙关键字/蓝标识符是**平台 SQL 注入**画的，不是本插件的 W2/W3 成果。
3. 262 上平台着色不出现（`<sql>` 片段体这类非语句标签平台本就不覆盖；语句体在 262 亦为白）⇒ 用户真机（Ultimate 2026.2 + 未配 SQL 方言）看到整段白 ✓ 与其反馈一致。
4. 用户机器上装的 **v2026.10.9 发行版根本不含 XML 注入**（jar 内无 `multiHostInjector`、无 `SqlMapperInjector`/`SqlMapperLanguage`），所以「白色」在该版本上是**功能尚未装到**，与本分支无关；但即便装了本分支构建，按第 2 条**着色仍不会出现**。

**两次未成功的试验（均已还原，留痕防重走）**：
- ① 给注入片段换成绑定语言的 `LanguageFileType`（原为 `PlainTextFileType`）→ 无效（探针仍 0）；
- ② 临时停用 `RestartableLexer`（A1 登记的风险项）→ 无效（探针仍 0）。
两次都只改了「高亮器被调用之后」的环节，而问题在**平台根本不来取我们的高亮器**，故均无法生效；两者都是未被证实的改动，已整批回退（工作树回到 `be7d222`）。

**原待定问题**：为何平台不为该注入片段取 `SyntaxHighlighterFactory`（两版皆然）。候选方向（①平台 Database 插件同区间注入抢占渲染；②注入片段语言在高亮 pass 侧未被识别为可着色语言）**两个都错**——真因见下节。

### 着色真根因定位与修复（收口，20261012 当轮闭环）

**真根因：`plugin.xml` 把高亮器工厂的 EP 名写成了裸 `<syntaxHighlighterFactory>`，而平台声明的名字是 `lang.syntaxHighlighterFactory`（限定名 `com.intellij.lang.syntaxHighlighterFactory`）。** 裸名解析到不存在的 `com.intellij.syntaxHighlighterFactory`，被平台**静默忽略**（不报错、不阻断加载）⇒ 该注册段是死代码 ⇒ `SyntaxHighlighterFactory.getSyntaxHighlighter(…)` 走 `SyntaxHighlighterFactoryEP` 的默认实现 `PlainSyntaxHighlighterFactory` ⇒ 注入片段**整段无色**。

**三重交叉核实（平台源码 + 描述符 + 反编译，逐条给判据）**：
1. **平台声明处**：`intellij.platform.ide.impl.jar` 内 `META-INF/PlatformExtensionPoints.xml` 的 `<extensionPoint name="lang.syntaxHighlighterFactory" beanClass="…SyntaxHighlighterFactoryEP" dynamic="true">`（本机 IU 安装目录逐 jar 扫描，全平台 1243 个 EP 名里 `syntaxHighlighterFactory` 仅此一条、**无裸名形态**；`intellij.idea.ultimate.customization.jar` 内重复声明同名）。
2. **我方描述符**：`plugin.xml` 写的是裸名 → 限定名 `com.intellij.syntaxHighlighterFactory`，**不在**平台声明集内。同批 23 个 EP 标签里只有这一个解析不到（`runConfigurationExtension` 等经 `qualifiedName=` 形态核实均正常）。
3. **反编译链路**（`InjectionRegistrarImpl` 与 `SyntaxHighlighterFactory`，FernFlower + JBR 25 实产）：注入时 `parseFile` 末尾 `obtainHighlightTokensFromLexer` 第 966 行 ——
   `LanguageFileType fileType = language.getAssociatedFileType();`（我方语言无关联 FileType → null，已核 `Language.getAssociatedFileType()` = `FileTypeRegistry.findFileTypeByLanguage`）→ `provider = null` → 回退分支 `new LexerEditorHighlighter(SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, file), scheme)`；而 `SyntaxHighlighterFactory.getSyntaxHighlighter` = `getLanguageFactory().forLanguage(language)`，`forLanguage` 按 `keyToString(key)=key.getID()`（即 `"MpSqlMapper"`）查 EP，查不到即返回默认实现 `PlainSyntaxHighlighterFactory`。**四步全部对上探针读数**（注入器被调、片段存在、工厂与构造器双 0）。

**修复**：`plugin.xml` 改为 `<lang.syntaxHighlighterFactory language="MpSqlMapper" implementationClass="…SqlMapperSyntaxHighlighterFactory"/>`（一行），并在注册处与 `SqlMapperSyntaxHighlighterFactory` KDoc 写明「必须用限定名」及其后果。修复后链路即通：EP 命中我方工厂 → `SqlMapperSyntaxHighlighter`（`SyntaxHighlighterBase`，`getTokenHighlights` 已全表映射）→ 15 个 token 全有 `TextAttributesKey`，且键都以 `createTextAttributesKey(id, 平台键)` 建、**自带平台 fallback**，未配颜色方案也能借主题色渲染。

**反向验证（仓库纪律：摘掉修复必红，两次读数）**：新建守卫 `config/PluginXmlExtensionPointResolutionTest`（解析每份描述文件 `<extensions>` 的 `defaultExtensionNs` + 子标签还原限定名，要求落在平台声明 EP 全集内；全集两路并集＝classpath 扫描 + `PluginManagerCore` 反射，附合成样本自证）：
- 修复态：**绿**（`tests=2 failures=0`）；
- 把 plugin.xml 临时改回裸名：**红**，报 `plugin.xml: <syntaxHighlighterFactory> → com.intellij.syntaxHighlighterFactory`；
- 还原修复态后再跑：绿。（同一守卫在首次试跑时还抓出并核实了一处**假阳性**：`database-optional.xml` 的 `com.intellij.database.modelRelationProvider` 是 `database-plugin.jar` 真实声明的 EP，仅测试沙盒未加载 Database 插件——已按「逐条注明来源」的口径列入豁免表。）

**为什么三道既有门禁都没拦住（值得记住）**：这条死注册穿过了 ①定向单测、②`testShards` 全量分片、③`verifyGate` 三目标（243/253/262）——①②的扫描面是 Kotlin 逻辑与行为，EP 名可解析性不在其中；③的 Plugin Verifier 读的是 API/类引用与兼容性，**不校验 `plugin.xml` 里 EP 名能否解析**（该裸名自 T-XmlSynHi D1 起就在册，本仓历轮 `verifyGate` 均 `Compatible.`、且明记「无未知 EP 告警」即证）。故本轮新增的 `config/PluginXmlExtensionPointResolutionTest` 是这条链上**唯一**能自动复核的护栏——**同族坑第二次踩到才补上机器判据**，这也说明「EP 名对不对」此前完全靠人眼。

**影响面与结论改写**：本条与「平台 Database 插件抢占」无关，也**不是**方言配置问题——上一节「用户修法」实测到的上色是**平台 SQL 注入**画的（Ultimate 下平台本就给 mapper 语句体注入 SQL，但需配 SQL 方言），与我方链路互不相干；我方链路此前从未通过。故：
- **「编辑器内着色」的对外承诺成立**（此前记的「承诺不成立」作废），且**不依赖用户配 SQL 方言**；
- 上一节「残留缺口：`<sql id=…>` 片段体平台不覆盖」**仍然成立，但成因变了**：我方注入器的作用面是「mapper 文件内任意标签的直属文本」（`XmlSynHiGate.allowStatementText` 只查 mapper 根与长度熔断，**不按标签名收窄**），故 `<sql>`/`<if>`/`<foreach>` 体内的文本**本就由我方注入**——修复后这些位也会着色，不再需要平台覆盖；
- 同族坑在本仓是**第二次**（第一次＝`typedHandlerDelegate` 应为 `typedHandler`，已在 plugin.xml 注释留痕）——现由上述守卫把「EP 名可解析性」变成可自动复核的红线。

### 沙盒走查（四）：修复后端到端取证（IU-2024.3.7.1，2026-10-12 收口轮）

环境＝`./gradlew runIde`（默认 243 沙盒）+ 沙盒上次打开的 `easy-code-demo` 项目 + `src/main/resources/mapper/UserMapper.xml`（该项目的 `.idea/sqldialects.xml` 里有 `<file url="PROJECT" dialect="MySQL"/>`，即平台 SQL 注入**同时在场**，故单看「有颜色」不足以归属）。

| 观察点 | 读数 | 归属判据 |
|---|---|---|
| 语句体 `select` / `from` / `order by` / `like` / `concat` | 橙＝KEYWORD | 平台与我方都会画，**不可单独归因** |
| `user` / `username` / `id` | 浅蓝＝IDENTIFIER | 同上 |
| `'%'` | 绿＝STRING | 同上 |
| `#{likeUsername}` | **`#{` 与 `}` 橙、中间 `likeUsername` 浅蓝**（定界符与内容分色） | 我方 lexer 的 `PARAM_HASH` 只吃定界符、内容另判 IDENTIFIER——平台把 `#{…}` 当整体参数，**不会**产生这种分裂 |
| `test="likeUsername != null and likeUsername != ''"` 属性值 | 有 OGNL 分色 | 我方属性白名单注入位 |
| **`returning x`（临时键入后已撤销）** | **`returning` 呈 KEYWORD 橙** | **决定性**：`RETURNING` 是我方「全方言超集」才收的词（`SqlMapperLanguage` KDoc 自己登记过这个多着色副作用），MySQL 方言的平台高亮器**不认**它 |
| 状态栏面包屑 | 显示 **`<Injected MpSqlMapper file>`** | 光标确在我方注入片段内（该位即注入生效的编辑上下文） |

**结论**：修复后我方高亮器**确实被实例化并参与渲染**（`returning` 一条已排除「颜色全来自平台」的可能）。走查用的临时键入（`returning x` 一行）已 Ctrl+Z 撤销，磁盘文件与该轮开始前**逐字一致**（`git diff` 只剩该仓既有的、非本轮的 `<select>` 块改动）；沙盒 `Alt+F4` → `Confirm Exit` → Enter 正常退出，`runIde` 任务 exit 0，按沙盒名过滤进程**零残留**。截图存外层 `mpsv-walk/epfix-*.png`（不进仓）：`epfix-04-opened`（全窗）、`epfix-05-sqlzoom`（语句体 3×）、`epfix-06-sqlfrag`（`<sql>` 片段体 4×）、`epfix-09-hash`（`#{…}` 定界符/内容分色 8×）、`epfix-10-returning`（决定性判据 + 面包屑）、`epfix-12-exit`（退出确认）。

**✅ 用户修法已实测验证（2026-10-12 当轮）**：在 **项目级** `.idea/sqldialects.xml` 加一条 `<file url="PROJECT" dialect="MySQL" />`（等价于 Settings → Languages & Frameworks → SQL Dialects → 把项目/mapper 目录指到 MySQL）后重启 IDE，**mapper XML 语句体的 SQL 文本立刻上色**（实测 5× 原像素：`select`/`from`/`and`/`like`/`concat` 橙＝KEYWORD、`user`/`username` 浅蓝＝IDENTIFIER、`'%'` 绿＝STRING、`#{username}` 定界符有色）✓ —— 即：**用户真机的「整段白」根因是 mapper 文件没有 SQL 方言映射，配上即好**。
**关键坑（本轮踩到）**：`SqlDialectMappings` 是 **`projectSettings`**（Database 插件 plugin.xml 实证），存在**项目** `.idea/sqldialects.xml`，**不是**应用级 `options/sqlDialects.xml`——写错位置会静默无效（本轮先踩了这个坑）。
**⚠️ 本条结论已按上节修正（留原文防误导）**：配 SQL 方言上色**只能证明平台 SQL 注入链路是通的**，不能证明「用户『整段白』的根因是没配方言」——我方链路的真因是 EP 名写错（上节），且**不需要**用户配方言。两条链路互相独立，谁在渲染取决于环境（平台注入在位时会盖在我方之上，观感一致，故不能用「配了方言就上色」反推我方是否生效）。
**残留缺口（原判）**：`<sql id=...>` 片段体这类**非语句标签**平台不覆盖，配上方言也不上色；本应由此处由我方注入着色，但我方高亮器不被调用（上表）⇒ 该处仍是白。**⚠️ 该判据随上节修复作废**：我方注入器按「mapper 文件内任意标签的直属文本」注入（不按标签名收窄），修复后 `<sql>`/`<if>`/`<foreach>` 体内同样着色——原先「非平台可解」的表述不成立。

### 收口轮（EP 名修复）门禁读数（2026-10-12，对应提交 `8cd794a`）

| 项 | 读数 |
|---|---|
| 定向（`ui.inject.*` + `ui.highlight.Xml*` + `ui.highlight.Mybatis*` + `config.PluginXml*` + `convention.GuardSelfTestCoverageMetaGuardTest`） | **28 类 / 250 例 / 0 失败 / 0 错误** |
| `testShards` 首轮 | **FAILED 3m 24s**；5 片 1248/1510/1429/1384/1390 = 6961 例 / **1 失败**——失败＝`SqlTextAnalysisTest` 的 `NoSuchMethodError`（旧 3 参 `sqlCompletions` 重载）＝本仓**已登记**的「陈旧测试字节码」假失败形态（W4/W5 加默认参数改了 JVM 签名，`compileTestKotlin` 判 UP-TO-DATE 未重编） |
| `testShards` 复跑（清 `build/classes/kotlin/test` 强制重编，**非 `clean`**；带 `--no-configuration-cache`） | **BUILD SUCCESSFUL 11m 11s，EXIT=0**；5 片 **1228/1525/1491/1227/1490 = 6985 例 / 0 失败 / 0 错误 / 9 skipped**（+ `testExternal` 24 例 1 skipped）；`parity: 计划类全集 632 个均有结果 XML` ✓；本轮新增的 `PluginXmlExtensionPointResolutionTest` 落在 shard3（2 例 0 失败，结果 XML 已产出） |
| 计数对账 | 上一轮权威读数（`b9a472b`）为 6959 例 / 629 类；本轮 6985 例 / 632 类（+26 例 / +3 类）——**差额未逐类归因**（分片清单在 `--no-configuration-cache` 下重算，且其间 master 有合并提交），仅确认本轮新增类**确在**清单内（parity 632 全覆盖 + shard3 结果 XML 在场）。如后续需严格对账，按「各片计数 vs 新增用例数」逐类核 |
| 门禁告警 | `testShards WARN: 临界片失衡 +19%（shard1 125.2s vs 均值 105.2s）——权重失准/新类缺权嫌疑`：属权重画像过期提示（新类零权重插值），非失败；如需消警跑 `dev-support/weight-refresh.ps1` |
| 未跑项 | `verifyGate`/`verifyPlugin` 本轮**未重跑**——理由：本轮无新增 EP 面（同一 EP 仅改正名字），且已实证 Plugin Verifier **不覆盖** EP 名可解析性（见「为什么三道既有门禁都没拦住」），重跑对本 bug 类零判别力 |

### W4/W5 四项补测读数（收口第二轮，`XmlSqlCompletionW4W5CrossFileTest`，7 例 0 失败）

原登记「受 Light 沙箱索引限制未单测」的四项，逐项给判据与读数。**「环境受限」这个前提被实测推翻**——`FileTypeIndex` 在 Light 沙箱可用（但**读的时机**敏感：`addFileToProject` 后**立即**读为 0，走一次 `configureByText` 后即就绪，见 `test05` 的双读数），故四项全部可在单测内取证，不必交真机。

| 项 | 用例 | 读数 |
|---|---|---|
| 跨文件 `refid` | `test01` | ✅ 另一 mapper 的 `<sql id="otherFrag">` 以 `com.example.OtherMapper.otherFrag`（`namespace.id`）形态进入候选，本文件 `localFrag` 同时在场 |
| `<properties>` key 聚合 | `test02` + `test06` | ⚠️ **首跑红 → 修后绿**：`propertyKeysOf` 取的是**子标签名**（恒为 `property`）而非 `name` 属性值 ⇒ 该能力对真实配置文件**完全无效**。已按 MyBatis 实现改正（`XMLConfigBuilder.propertiesElement` → `XNode.getChildrenAsProperties()`，mybatis **3.5.19 sources 实读**：key 取子标签 `name` 属性且要求 `value` 在场） |
| foreach item 元素字段 | `test03` | ✅ `#{it.` 给 `List<DeviceInfoEntity>` 的**元素类型**字段（`id`/`name`），非主实体兜底 |
| `@类@静态成员` | `test04` | ✅ `@com.example.Consts@` 给静态字段与方法（候选是**完整表达式** `@类@成员`），类可解析时不再只给语法骨架 |

**同一函数上顺带修掉的结构性缺陷**：`configPropertyKeys` 的缓存原先是**进程级 `@Volatile stamp/value` + 比当前 project 的 modificationCount**——多项目 IDE 里两个 project 的计数撞上会互相读到对方的缓存值。已改为**挂在 project 上**（`Key<Long>`/`Key<List<String>>` user data），随 project 回收、无泄漏。
**反向验证**：`test06` 就是该缺陷的反向位——把实现改回 `props.subTags.mapNotNull { it.name }` 必红（且 `test02` 同红）。

### 折叠优先级的结构取证（`SqlMapperFoldingPriorityTest`，3 例 0 失败）

原遗留项问「我方 `select#id` 占位与平台标签折叠谁胜出」。三条事实全部落在平台源码 + 实测上：

1. **顺序**：XML 语言的折叠 builder 由 `LanguageFolding.allForLanguageOrAny(XML)` 汇总；实测顺序 = `[SqlMapperFoldingBuilder（我方）, com.intellij.lang.xml.XmlFoldingBuilder（平台）, spring XmlPropertyPlaceholderFoldingBuilder, liquibase …]` ⇒ `order="first"` 生效。
2. **合并规则**：`CompositeFoldingBuilder.buildFoldRegions` 用 `Set<TextRange> rangesCoveredByDescriptors` 做**同范围先到先得**（`intellij.platform.core.jar` 反编译实读）——所以「我方区会不会被平台吞掉」只取决于范围是否**逐字相同**。
3. **范围**：实测我方 = **整个 `<select>` 标签**（`tag.textRange`，占位 `select#findById`）；平台 `XmlFoldingBuilder` = **内容区**（`[21..109]`＝`<mapper>` 内容、`[46..99]`＝`<select>` 内容，占位 `...`）——**范围不同 ⇒ 不互相吞，三个区共存**。
4. 故「谁胜出」是**渲染层**问题（光标在标签体内按 `Ctrl+Minus` 命中最内层区；gutter 上 `<select>` 那一行的标记对应我方整标签区）。Light 沙箱里编辑器 folding model 恒为空（实测 `allFoldRegions` = 0），单测观察不到渲染结果 ⇒ 交真机读数（下节）。

**反向验证（两次读数）**：把 `plugin.xml` 的 `order="first"` 去掉 → `排第一` 用例**红**，实测顺序变为 `[XmlFoldingBuilder, spring…, liquibase…, SqlMapperFoldingBuilder]`（我方落到**最后**）；恢复后绿。同一次反向跑里还顺手复验了高亮器链路（见下）。

### 着色链路的单测级取证（`SqlMapperHighlighterDispatchTest`，2 例 0 失败）

此前「我方高亮器是否真的被平台取用」只有真机截图作证。本轮补成单测：`SyntaxHighlighterFactory.getSyntaxHighlighter(SqlMapperLanguage, …)` 走的正是注入时算 token 的同一条路（`getLanguageFactory().forLanguage(language)`，按语言 ID 查 EP），断言它返回**我方** `SqlMapperSyntaxHighlighter`（而非平台默认 `PlainSyntaxHighlighter`），并钉住 `KEYWORD`/`IDENTIFIER` 映射到自有键。
**顺带推翻一条仓内旧认知**：此前多处 KDoc 写「Light 测试环境不加载 plugin.xml 的 EP」——本轮实测**会加载**（`LanguageFolding.allForLanguageOrAny(XML)` 里能看到我方 builder；高亮器工厂按 EP 名解析成功）。故 EP 接线其实可在单测里直接断言，不必只靠「读 plugin.xml 文本 + 正则」的间接守卫（既有守卫保留，两者互补）。
**反向验证**：把 EP 名改回裸 `<syntaxHighlighterFactory>` → 两例**双红**，实际读数 `PlainSyntaxHighlighter` / 键 `[TEXT]`（即用户看到的「整段白」形态）；恢复后绿。

### 沙盒走查（五）：折叠占位真机读数（IU-2024.3.7.1，收口第二轮）

环境同走查（四）：默认 243 沙盒 + `easy-code-demo` 的 `src/main/resources/mapper/UserMapper.xml`（含多行 `<select id="selectAllByUsernameLikeAndPasswordOrderById">`）。

| 动作 | 读数 | 截图（`mpsv-walk/`，不进仓） |
|---|---|---|
| **Collapse All**（经 Find Action 调用） | 整文件折叠成 4 行，`<mapper>` 行显示**平台占位 `...`**——最外层区＝平台 `<mapper>` 内容区，其内嵌套区不渲染 | `fold-06-collapsed.png` |
| 光标置于 `<select>` 开标签内 + **Collapse**（`Ctrl+NumPad-`） | 第 19 行显示 **`select#selectAllByUsernameLikeAndPasswordOrderById`** ⇒ **我方占位胜出** | `fold-11-collapseregion.png`、`fold-12-ourplaceholder.png`（3× 放大） |
| Expand All 还原 | 回到未折叠态；文档内容零改动（`git diff` 与走查前逐字一致） | `fold-13-restored.png` |

**结论（回答原遗留项）**：**「谁胜出」这个提法本身不成立**——两区范围不同（我方＝整个标签，平台＝内容区），在折叠模型里**共存**；用户折叠语句标签时看到的是**我方 `select#id`**（该区在外层），平台 `...` 只出现在折叠 `<mapper>` 这类**我方不建区**的标签上。故 **W6 折叠占位能力对用户可见**，原备选方案（改折语句体文本以避让平台）**不需要**。

**顺带记两个工具/环境事实（供后续走查复用）**：①折叠键位是 **NumPad 专用**——`Collapse` = `Ctrl+NumPad-`、`Collapse All` = `Ctrl+Shift+NumPad-`，普通 `-` 无效（Find Action 列表里的键位提示即证），无小键盘的机器须走 Find Action；②**243 New UI 的折叠标记在 gutter 里不常驻显示**（逐行放大实证：`<select>` 行只有我方两枚 gutter 图标，`<mapper>` 行什么都没有），故「点 gutter 标记折叠」这条常规路在本环境走不通，须用 Find Action 驱动。

### 遗留与待真机项（W8 收口）

1. **颜色页**：✅ 已补（见上节）。
2. **真机走查**：**三站均已做**——①runIde **243**（最低支持版，见前节）；②**IU 2026.2.1 Ultimate + CHP 已激活 + zMyBatis 共存沙盒**（见上节，W1 前提 1 现场实证「未被让位」+ Ctrl+B 跳转实证）；③**收口第二轮**：同 243 沙盒补做「着色端到端」（走查四）与「折叠占位」（走查五）。**剩余 4 项视觉确认待用户侧**（各约 30 秒，代码与单测均已覆盖）：`#{}` 与 `${}` 是否**互相**一眼可分、属性值补全候选、live template `cd`/`co` 展开、`#{}` 成对删、颜色页（Settings → Editor → Color Scheme → MyBatis Mapper SQL）可调。
3. **折叠占位文本**（`select#id`）：✅ **已隔离观察 + 真机取证**（见「折叠优先级的结构取证」+「沙盒走查（五）」）——两区范围不同、模型里共存，**用户折叠语句标签时看到的是我方 `select#id`**；原备选方案（改折语句体文本）**不需要**。
4. **W4/W5 四项未单测项**：✅ **已补测，四项全绿**（见「W4/W5 四项补测读数」）；「受 Light 沙箱索引限制」的前提**被实测推翻**，且补测中**抓出一个真实缺陷**（`<properties>` key 取值口径错，已修）。
5. **W2/W3 两处待评审取舍**（仍待评审，非缺陷）：布尔是否单独配色（现与关键字同色）；方言并集过度着色是否要 per-file 方言收敛。
6. **编辑器内着色**：✅ **已修 + 单测级取证 + 沙盒取证**（见「着色真根因定位与修复」+「着色链路的单测级取证」+「沙盒走查（四）」）——此前「对外承诺不成立」的结论作废；`config/PluginXmlExtensionPointResolutionTest` 与 `SqlMapperHighlighterDispatchTest` 双守卫。
7. **全量门禁**：已跑两次——W1~W7 那轮 6959 例、EP 名修复那轮 6985 例（读数见前节）。**本轮改了生产代码**（`XmlCompletionSymbols` 的 `propertyKeysOf` 与缓存归属）⇒ 收口须**再跑一次**权威全量（读数见下节）。
