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

（未实施）
