# T-FidelityEval 高保真求值模式立项评估 — 开发计划

- 日期：2026-09-18 08:25
- 任务编号：T-FidelityEval（第七轮新建议 **N-27**，P2，评估先行）
- 来源：第七轮报告 §7 zMyBatis 精华清单 **Z-1+Z-10**——真 MyBatis 引擎求值（MyBatisEvaluator.kt:109-126）+ classloader 隔离防全局污染（IsolatedDynamicMyBatisPreparation.kt）
- 性质：**评估型任务**——产出是评估报告与 go/no-go 建议，不是直接实施。第七轮报告原话：「先做 1~2 天立项评估再定」
- 实施分支：主工作树即可（只产出 docs，不碰源码）
- 验证基线：评估报告经用户过目定案

---

## 一、背景与依据

zMyBatis 用约 500 行把 XML/`<script>` 丢给真 Apache MyBatis 跑 `getBoundSql`，全标签语义免维护；每次求值用一次性 `URLClassLoader` 隔离（MyBatis 会向 shaded OGNL 全局注册 accessor 污染宿主进程）。本插件的 XML 动态标签求值是**完全自研**：对齐 MyBatis 3.5.19 的 SqlNode 体系复刻（sealed interface 16 文件）+ 手写递归下降 OGNL 解释器（Lexer/Parser/AST 四段，803 行），含 parse 缓存、判真规则对齐、`||X||` 剥壳兼容、静态字段 PSI 常量注入——立项锚点核实（master 实测）确认其质量不低，**真引擎通道是「加语义对照旁路」而非「补缺」**。

评估要回答的核心问题：自研求值管线的边角维护税（foreach 嵌套/OGNL 复杂表达式的语义偏差），是否值得用「真引擎旁路」的工程量去换。

## 二、现状核实（master 实测，2026-09-18）——工程量因素盘点

| 因素 | 现状 | 缺口评级 |
|---|---|---|
| mapper XML 文本输入 | 已有（`XmlPreviewService.preview(fileText, statementId, project)` 纯文本入口） | 无缺口 |
| mybatis 配置 | **完全没有**：databaseId mock null、mybatis-config.xml/SqlSessionFactory/别名注册表均未解析——真引擎最小需要一个 `Configuration` + mapper 资源注册 | **最大缺口** |
| 参数真实类型对象 | 面板输入是 `Map<String,String>` 全字符串；真引擎 OGNL 对 `Integer 1` 判假、字符串 `"1"` 判真，行为不同——需按类型构造真实对象（`XmlParamTypeResolver` 类型读取是现成基础，可与 T-ParamTypeContract 共建） | 大 |
| 用户库 URL 收集 | **零先例**：全仓无 `LibraryUtil`/`OrderRootType` 使用，`ModuleRootManager` 仅源根探测；需新增 orderEntries 枚举 + child-first 隔离 classloader（跨 classloader 教训已有沉淀：`DatabaseSchemaResolver.kt:37-40` Method 一律从声明接口取） | 大 |
| mybatis 本体来源 | 插件运行时**零 mybatis 依赖是既定红线**（implementation 仅 jsqlparser）——真引擎类必须从用户项目 classpath 经隔离 classloader 反射驱动，不能打 jar 进插件 | 约束明确 |
| 探测用户项目 mybatis | 有先例：`ProjectTypeDetector.kt:226` `findClass("org.apache.ibatis.session.SqlSessionFactory")` | 无缺口 |
| e2e 验证基建 | Heavy 测试的 mp-core 仅测试 classpath，真引擎 e2e 需新建测试基建 | 中 |
| 对照结果展示 | 两套渲染产物 diff UI——预览面板已有双模式先例（显示模式切换）可参照 | 中 |

## 三、评估方法（T-FE-01）

产出 `docs/调研报告/2026MMDD-高保真求值模式评估报告.md`，按以下四问给结论：

1. **收益量化**：翻自研求值的历史 bug 账（git log + memory-archive 里 foreach/OGNL/bind 相关修复），统计「真引擎能否自动避免」的占比——若历史偏差大多在「mock 值语义」而非「标签语义」，真引擎收益有限（mock 语义两套引擎同样要自己定）。
2. **成本核算**：按 §二 缺口盘点给分阶段工程量估算（配置输入最小方案=要求用户指定 mybatis-config 或用最小 Configuration；参数对象构造复用 T-ParamTypeContract 类别；隔离 classloader 照 zMyBatis 模式），给出「最小可用旁路」的天数级估算。
3. **架构红线核对**：零依赖红线下的反射驱动形态（从用户 classloader 加载 mybatis + ognl + 实体类 + 别名类）在 262 平台的可行性试钻（一个 spike：headless 里构造隔离 classloader 跑通 `getBoundSql` 最小样例）。
4. **替代方案对照**：同工程量投给自研求值器（按 O-31 预检层+补测试）的收益对比；以及「不做旁路、只建语义对照测试集」（把 zMyBatis 旧轨思路反过来用：测试基建里用真引擎批量生成期望产物钉住自研行为，不进生产路径）——第三条可能是性价比最高的中间形态，评估必须覆盖。

## 四、结论形态与验收标准

1. 评估报告落盘，四问各有量化结论（bug 账统计数字 / 天数估算 / spike 跑通或失败实录 / 三方案对比表）。
2. 明确 go/no-go/go-替代建议，交用户定案；no-go 或 go-替代时本任务终结，Z-1 从吸收清单改记「评估后定案」。
3. spike 代码只进测试沙盒或临时目录，不进主源码树。

## 五、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| spike 沉没成本 | 隔离 classloader 试钻可能超预期 | spike 限时 0.5 天，超时即记录失败形态转 no-go 证据 |
| 评估被误当实施 | 本计划产出是报告不是代码 | 任务名与文档定位双重标注「评估型」 |

## 六、实施顺序

```
bug 账统计 → 工程量核算 → spike 试钻（限时）→ 三方案对比 → 报告定稿交用户
```

## 七、实施记录（实施后回填）

**已完成（2026-09-18，分支 `t-fidelity-eval`，基线 8a3a6ff）。**

**产出**：`docs/调研报告/20260918-T-FidelityEval-高保真求值模式评估报告.md`；变更记录总册顶部表格已追加入库行；本计划 §七 回填。只产出 docs，未碰生产源码；spike 代码只在系统临时目录 `%TEMP%\mpsv-fidelity-spike\`，主源码树与工作树干净。评估型任务无产品变更，未写 CHANGELOG。

**四问结论摘要**：

1. **收益量化**：自研求值管线历史缺陷账 **15 条**（M24 至 T-DollarInjectMark，三源交叉：git 全分支 log + 变更记录 M24~M29 段 + memory-archive）。按计划口径二分——标签/表达式/解析语义 **3 条（20%）**、mock 值语义 **3 条（20%）**、面板回填/接线/性能/显示/MP 生态 **9 条（60%）**。提交号：`d5dcd55` `18fadbe` `0e2eb2c` `b17d691` `a19b3bd` `a60bf4a` `06ebb9a` `3a35139` `347d162` `70f8383` `80f1be9` `c322f32` `9b6af09` `c3ee1bd` `aa607c4` `3959a8e`。两条关键补充：foreach/trim/choose 标签语义自 M24 落地后**零缺陷回修**（`ForeachSqlNode.kt` 仅被静态字段、显示 trim、计数转发与 Kotlin→Java 移植改过），反驳「标签语义维护税高」的立项前提；另在册未修 1 条 `IfSqlNode.kt:19-23`（if test 求值失败静默 true 不记错，即 O-31 的 T-EP-02），是真引擎唯一能覆盖而自研尚缺的实质缺口。结论与计划预判一致：**历史偏差大头不在标签语义**。
2. **成本核算**：完整旁路 **11~17 人天**；最小可用旁路 **7~10 人天**。分阶段——P1 配置输入最小方案 2~3（隐藏成本：mapper 解析期 `resultType`/`parameterType`/别名解析失败即硬失败）；P2 参数真实类型对象构造 3~4（复用 `ui/XmlParamTypeResolver` 读取链，与 T-ParamTypeContract 的 T-PT-01/T-PT-03 共建，可延后）；P3 用户库 URL 收集 + child-first 隔离 classloader 3~4（全仓零 `LibraryUtil`/`OrderRootType` 先例，估算置信度最低）；P4 e2e 测试基建 1~2（与方案 C 完全重叠）；P5 对照结果展示 2~4（含「条件开关模型无法被真引擎保留」的交互冲突）。字面量化层可基本复用（真引擎 `parameterMappings.property` 序列与 `RenderedXmlSql.mappings` 同形，面板消费链 `SqlPreviewPanel.kt:1741-1749` 与 MCP `MapperCheckTool.kt:94-103` 走适配器即可）。
3. **架构红线核对 + spike**：零 mybatis 依赖红线成立（`build.gradle.kts:103` implementation 仅 jsqlparser）。限时 spike 在本会话内跑通（**SPIKE RESULT: PASS**）——headless JDK8 + 手工 javac/java，mybatis 3.5.19 jar 取自 gradle cache，未给插件加依赖、未跑 gradle；`SpikeMain.java` 编译期 classpath 不含 mybatis，全链路 `Class.forName(..., isolated)` + Method 从声明类取。实录要点：宿主 `Class.forName("org.apache.ibatis.session.Configuration")` → CNFE；隔离 loader 载入 mybatis 与用户实体；官方 DOCTYPE 由 jar 内 DTD 本地解析；`if`/`foreach`/嵌套 `foreach`/`${}`/`@demo.Const@FLAG`/用户实体对象作 `_parameter` 全部按预期工作，`?` 队列顺序与 SQL 一致；两个一次性 loader 的 `OgnlRuntime` 为不同 Class，OGNL 全局 accessor 静态表不跨 loader（宿主零污染，Z-10 配套机制层确认可行）。失败形态实录（旁路脆弱性证据）：`||X||` 真引擎抛 `ExpressionSyntaxException: Malformed OGNL expression`；mapper 解析期 `resultType` 指向隔离 classpath 不存在的类 → `BuilderException: Could not resolve type alias 'demo.Missing'`，即硬失败而非降级。**未验证项（环境受限未验证）**：262 平台库 URL 收集、读权限包裹、真实插件 classloader 委派、loader 生命周期与失效重建、真实用户项目（多模块 / mybatis-plus 包装 / 别名注册表）解析成功率五项均未在 IDE 平台验证。
4. **替代方案对照**：A 真引擎生产旁路（7~17 人天，收益面 20%，且模型层与现行「条件开关为分支唯一真值源」不兼容）／B 同工程量投自研（O-31 AST 预检层 + if test 静默点修复 + N-26 mock 分派，3.5~5 人天，零生产风险）／C 只建语义对照测试集（测试基建用真引擎生成期望产物钉住自研行为，不进生产路径，1~2 人天，对照协议已核实可行：自研 `XmlSqlRenderer.render` 接受任意 bindings，可绕开 mock 层喂同一批真实值）。

**建议**：**go-替代——方案 C 为主 + 方案 B 并行，方案 A 建议 no-go**（no-go 不是因为做不到，spike 已证明机制可行；而是收益 20%、成本 7~17 人天、且要放弃 M24 建立的条件开关交互）。交用户定案；Z-1 从吸收清单改记「评估后定案」，本任务终结。
