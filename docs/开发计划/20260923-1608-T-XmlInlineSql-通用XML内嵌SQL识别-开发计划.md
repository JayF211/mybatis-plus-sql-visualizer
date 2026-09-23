# T-XmlInlineSql 通用 XML 内嵌 SQL 识别（不依赖根元素）— 开发计划

> 任务编号：**O-72**（立案时暂定 O-71；同日并行会话 T-HqlNoAliasProp 已将 O-71 落在 master `69a4028`，合并 dev 前订正为本号，实现与文档中编号已同步替换）
> 立案日期：2026-09-23
> 状态：**待确认**（写完停下等用户拍板，未动任何 src/main）

## 一、现象

用户公司项目（gousen-wms，RF 手持端自研框架）中的 `rfQcSampleListPage.xml` 无法被插件识别：

```xml
<RFListPage id="rfAsnListPage" title="rfAsnListPage" mainEntrance="true">
    <RFSearchUI id="searchCode" text="searchCode" onFoucs="true"/>
    <RFTable id="list" rowAction="wmsAsnRfManager.selectCustomerAsn">
        <RFTh id="id" title="id" width="50" enumType="" hidden="true"/>
        <DataSource type="HQL">
            <statement><![CDATA[
                SELECT qc.id as id, ... FROM WmsQuality qc
                LEFT JOIN WmsAsn asn ON asn.id = qc.asnId ...
            ]]></statement>
        </DataSource>
    </RFTable>
</RFListPage>
```

表现：无 gutter 预览图标；右键/Alt+Shift+Q「转 SQL 预览」弹「未找到」轻通知；SQL 全文（含 HQL）在 CDATA 里。

**用户明确补充：RFListPage 根元素是不固定的**——不同框架/页面根元素名各异。因此「再硬编码一套 RFListPage 白名单」的路线直接出局，识别不能依赖根元素名与具体标签名。

## 二、根因

插件对 XML 的识别是**点级白名单**（三道闸，全部硬编码「标签名 + 父标签名 / 根元素名」），该文件不属于任何一套：

| 闸门 | 白名单 | 出处 |
|---|---|---|
| gutter 图标 | `{select,insert,update,delete}` 且父标签 `mapper`；`maintainPage` 且父 `pages`；`{query,sql-query}` 且父 `hibernate-mapping` | `XmlSqlGutterLineMarkerProvider.kt:41-42`、`PagesXmlGutterLineMarkerProvider.kt:39-40`、`HbmNamedQueryGutterLineMarkerProvider.kt:37-42` |
| 右键动作 | 根 `pages` → pages 分支；否则 mapper `STATEMENT_TAGS` 光标上爬 + 全文件收集，均为空 → `showResult(null)`「未找到」 | `ConvertToSqlAction.kt:78-96`、`455-479` |
| 解析器 | 根元素断言（`<mapper>` / `<pages>`） | `XmlMapperParser.kt:57-59`、`PagesXmlParser.kt:45-47` |

补两条背景：

1. **不存在通用 CDATA 文本扫描机制**，也不存在 LanguageInjection——XML 里的 SQL 只在已认领的标签结构内被读取。`<DataSource>/<statement>` 在解析期根本不会被读到。
2. **HQL 能力已存在但没接线到这条数据**：`HqlConverter` + O-57 `JpaAliasTableResolver` 只接受两个入口——Java 字面量（`createQuery`/自定义 DAO）与 hbm `<query>`（`SqlPreviewService.previewHbmNamedQuery`，`SqlPreviewService.kt:1070-1119`）。该 XML 里的 HQL 对它们不可见。

**最相近的先例**是 F-45 的 `<pages>`/`<datasource>` 支持（`PagesXmlParser.kt:59-61` 直取 CDATA），但它仍是「根元素+标签名」硬编码白名单，不能照抄——用户已声明根元素不固定。

## 三、方案：结构特征识别，锚定内容而非标签名

新增第四条识别通道「**XML 内嵌 SQL 通用检测**」，作为既有三形态（mapper / pages / hbm）之后的**兜底**：

- **认领条件**（对 XmlTag）：叶子标签（无子 XmlTag）+ 文本（CDATA 或普通 text）trim 非空 + 首词命中 SQL 关键字集 `{SELECT, WITH, INSERT, UPDATE, DELETE, REPLACE}`（大小写不敏感）。完全不读根元素、不读标签名语义。
- **排除既有认领**（防双图标/抢占）：mapper 语句标签、`hibernate-mapping` 下的 `query/sql-query`、pages 的 `datasource`（其图标在 `maintainPage` 上）。排除口径收敛到检测器一处，gutter / Action / 预览共用。
- **渲染分派**：载体标签自身或其父链属性 `type` ∈ `{hql, jpql}`（大小写不敏感）→ 复用 hbm HQL 管道（`previewHbmNamedQuery`，name 传空即走 `extractEntityNameFromHql` 从 FROM 子句取实体，O-57 别名列名映射自动生效）；**转换失败或实体不可解析 → 回退原文直显 + warning，不抛错**。无 `type` 或其他值 → 原生 SQL 直显（pages 同款）。
- **优先级**：预览链 pages → mapper → 通用兜底，保证既有形态零回归。
- **设置开关**：新增「通用 XML 内嵌 SQL 识别」，默认开，可关。
- **性能红线**（沿用 PagesXmlGutterLineMarkerProvider 注释里的红线）：gutter 快速路径只做 O(1) 语法判定 + 载体标签局部文本的前缀判定（截断取前 64 字符），零 resolve、零全文件遍历、零转换。

### 实施切分

| # | 级别 | 内容 |
|---|---|---|
| T-01 | P0 | 检测核心 `XmlInlineSqlDetector`：`isSqlCarrierTag(tag)` + 排除集合 + `looksLikeSql` 文本抽取（CDATA/text 统一口径）。单测含用户真实 RF 样例夹具 + 误报样本（非 SQL 叶子标签）+ 既有三形态排除样本 |
| T-02 | P0 | 新 gutter provider（锚载体标签 name token）+ GutterIconDedupTest 扩展（RF 文件恰 1 图标、mapper/hbm/pages 文件不新增图标） |
| T-03 | P0 | 预览服务 `XmlInlineSqlPreviewService`（SQL 直显 / HQL 分派 / 失败回退原文）+ `ConvertToSqlAction` XML 分支兜底接线（光标所在载体优先 → 全文件候选 `SqlSelectionDialog` 0/1/多分发；选择区分支同步纳入） |
| T-04 | P1 | 设置开关 + i18n bundle（中英）+ 设置页接线 |
| T-05 | P2 | `OnboardingAnchorTip` 引导词补充（可选，一行枚举） |
| T-06 | P1 | 测试收口：定向测试 + 反向验证 + runIde 真机走查（用户样例文件） |

派活纪律按惯例：子代理只跑定向测试、达标即提交、实施记录回填各自工作树副本；全部完成后单派只读收口代理跑全量门禁。

### 反向验证清单（守卫类改动纪律）

- 摘掉 T-02 通用 provider → RF 样例 gutter 图标数从 1 变 0（贴两次读数）。
- 摘掉 T-01 排除集合 → mapper 样例文件语句行图标数从 1 变 2（贴两次读数）。
- 摘掉 T-03 HQL 回退分支 → 构造不可解析实体的 HQL 样例，预览从「原文 + warning」变异常/空（贴两次读数）。

## 四、待验证前提（实施者先证伪再动手）

1. **PSI 文本抽取口径**：CDATA 内容经 XmlTag 叶子节点读取可展开取得——hbm 链用的是直属 `XmlText` join（`SqlPreviewService.kt:1116-1117`），通用检测需在夹具上实证 CDATA 与普通 text 两形态走同一抽取函数结果一致。
2. **gutter 性能**：getLineMarkerInfo 逐 token 高频调用，叶子标签文本读取（`tag.getValue().getText()`）在大 XML 上成本未知；缓解=先判「无子标签」再读文本、前缀截断。若实测有回退，改为「首词判定前移到 token 级文本」。
3. **HqlConverter 对该 HQL 形态的转换能力**：样例含驼峰属性路径（`qc.asnId`）、混下划线（`qc.related_bill`）、ad hoc `LEFT JOIN ... ON`——O-57 的别名副表解析应有基础，但成功率未知；**验收底线是回退原文直显不抛错**，转换完美度不作为验收门槛。
4. **type 属性位置**：按用户样例在父标签 `DataSource` 上（载体标签自身也探测）；其他框架属性名可能不同，首版只认 `type`，不做玄学探测。
5. **误报面**：首词命中即认领是宽口径（如 `<title>SELECT your plan</title>` 会被认领）；真实项目发生率未知，决策点 D2 备选收紧口径=首词命中且含 `FROM/INTO/SET/VALUES` 之一。先按宽口径实施 + 开关兜底，实测误报再调。

## 五、验收

1. 用户的 `rfQcSampleListPage.xml`（真实文件）在 runIde：`<statement>` 行出现 gutter 图标，点击弹出预览（HQL 转换结果或原文回退 + warning）；右键/Alt+Shift+Q 光标在 statement 内直接预览；文件含多个 `DataSource` 时弹选择栏。
2. 根元素改名（`RFListPage` → 任意名）行为不变（复跑同一夹具断言）。
3. 既有三形态零回归：mapper 动态 SQL、pages、hbm 命名查询的既有定向测试全绿。
4. 三条反向验证读数达标（见上）。

## 六、不做的事

- **不做 LanguageInjection**（编辑器内 SQL 高亮/补全注入）——体量大且与本诉求无关。
- **不做用户自定义「标签路径」配置**——通用内容判定已覆盖「根元素不固定」；若未来误报失控再立项。
- **不解析 RFTh 列元数据**成预览列面板（pages 的 pagesColumns 能力）——首版只做 SQL 预览，有需求另起。
- **MCP 工具不扩展**（`render_sql`/`crud_sql` 仍 mapper 专属）。

## 七、涉及文件清单

- 新增：`src/main/kotlin/cn/song/mybatisplus/visualizer/xml/XmlInlineSqlDetector.kt`、`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/XmlInlineSqlPreviewService.kt`、`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/XmlInlineSqlGutterLineMarkerProvider.kt`
- 修改：`actions/ConvertToSqlAction.kt`（XML 分支兜底）、`config/PluginSettings.kt` + 设置页 + bundle（中英）、`onboarding/OnboardingAnchorTip.kt`（可选）、`plugin.xml`（lineMarkerProvider 注册）
- 测试：`src/test/.../xml/XmlInlineSqlDetectorTest.kt`、`.../ui/XmlInlineSqlPreviewServiceTest.kt`、`.../ui/gutter/XmlInlineSqlGutterLineMarkerProviderTest.kt`、`GutterIconDedupTest.kt` 扩展

## 实施记录

### 交付方式

用户确认「按推荐方案」后，主会话在独立工作树 `mpsv-xml-inline-sql`（分支 `t-xml-inline-sql`，基于 master `1f0b9fd`）单车道实施；parallel_tasks 登记 `T-XmlInlineSql / sandbox=MPSV-XmlInlineSql`。

### 任务落地（对照 §三）

- **T-01/T-02/T-03/T-04**：提交 `395b933`（13 文件 +704）——`XmlInlineSqlDetector`（认领/排除/type 探测/总开关）、`XmlInlineSqlGutterLineMarkerProvider`（plugin.xml 注册，`gutter.xmlInlineSql.*` 文案）、`XmlInlineSqlPreviewService`（原生直显 + HQL 复用 `previewHbmNamedQuery` + 异常回退原文）、`ConvertToSqlAction`（光标上爬/全文件收集/选择区候选三处兜底 + `previewXmlTag` 统一分发）、`PluginSettings.xmlInlineSqlEnabled`（默认开）+ 设置面板 5 处接线 + FolderConfigurable 搜索索引 + 中英 bundle 6 键。
- **T-05**：**决策不实施**——`OnboardingAnchorTip` 的 XML 锚点词若加 `<statement`/`<DataSource` 这类通用词，任意含同名标签的 XML 都会触发首用引导，与该类「误报面最小」原则冲突；通用检测本身已让引导通知自然覆盖新场景。
- **CDATA 修复**：提交 `f943b41`（见下「关键实测发现」）。

### 关键实测发现（推翻计划前提 1）

**本平台 PSI 的 `XmlText.text` 对 CDATA 返回带 `<![CDATA[ ... ]]>` 包裹的字面全文，并非展开内容**——判别性读数：`expected:<[DELETE FROM t]> but was:<[<![CDATA[DELETE FROM t]]>]>`（首跑 11 例红）。修法＝`XmlInlineSqlDetector` 统一剥包裹（`unwrapCdata`）后再判首词/提取文本。计划前提 1 照抄 hbm 口径的假设被证伪：`HbmXmlEntityResolver.extractNamedQueryText` 用的就是裸 `XmlText.text` 拼接。

### 测试与反向验证

- 定向新增 26 例全绿（Detector 14 / gutter 6 / PreviewService 6，含 HQL JPA 实体夹具转换集成例与不可解析透传例）；既有回归 51 例绿（ConvertToSqlActionTest 3 / GutterIconDedupTest 14 / GutterProvidersNegativeTest 1 / XmlSql·Pages·Hbm gutter 16 / PagesXmlParserTest 17），**合计 77/0**。
- 反向验证三条（摘掉修复行 → 必红，均实测复绿）：
  1. gutter 认领判定摘掉 → `testRF样例_statement行有图标` 红（6 例 1 失败）；
  2. 排除集合摘掉 → `testmapper文件不双图标`、`testpages文件不双图标`、`test排除mapper语句标签`、`test排除pages数据源标签` 4 例红（hbm 例未红：其夹具文本 `FROM A a` 首词不在关键字集，本就不进入认领路径——该排除断言对此样本不构成判别，已如实记录）；
  3. HQL 管道摘掉（强制按失败处理）→ `testHQL_实体可解析时走转换管道` 红（回退路径被激活，转换丢失）。

### 待验证前提核销

| 前提 | 结论 |
|---|---|
| 1 PSI 文本抽取口径 | **实测推翻**（CDATA 不展开），已修（见上） |
| 2 gutter 大文件性能 | 按红线实现（叶子判定 + 64 字符截断前缀，type 探测不进快速路径）；未做大文件基准，**真机走查留待** |
| 3 HqlConverter 转换能力 | 集成实测：`@Entity @Table` 实体夹具下 `SELECT qc.code FROM WmsQuality qc` → `wms_quality` 表名映射生效；不可解析实体**透传原文不抛错**（`testHQL_实体不可解析时透传原文不抛错` 绿） |
| 4 type 属性位置 | 按计划实现（自身→祖先就近优先，仅认 `type`，值 hql/jpql 大小写不敏感） |
| 5 误报面（宽口径） | 首词命中即认领的行为已用 `test宽口径_首词命中即认领` 钉死；真实项目误报率待用户反馈，总开关兜底 |

### 顺带发现（未在本任务修复，登记待议）

**hbm 命名查询链的 CDATA 缺口（既有问题）**：`HbmXmlEntityResolver.extractNamedQueryText` 与 `SqlPreviewService.previewHbmNamedQueryTag` 对 CDATA 形态的 `<query>` 会把 `<![CDATA[` 包裹原样带进 HQL 转换（同口径裸拼接）。该缺口此前不可见的原因：`HbmNamedQueryResolverTest` 的断言「CDATA 文本应展开提取」是**非判别性断言**——`<![CDATA[FROM LegacyItem li …]]>.contains("FROM LegacyItem li")` 在展开与不展开两种情况下同为真。建议后续任务：两处提取改走 unwrap 口径 + 该断言改为 `assertFalse(queryText.contains("<![CDATA["))` 判别式。

### 遗留

- runIde 真机走查（用户真实 `rfQcSampleListPage.xml` 的 gutter/预览/选择栏三态）未做，验收 §五-1 留人工。
- 构建校验按「大改动判据」跑 `verifySmoke`：**BUILD SUCCESSFUL 45s**（plugin.xml 新增 lineMarkerProvider EP 实例，无新 API 面/since-build 变更，取 smoke 档平衡耗时）。
