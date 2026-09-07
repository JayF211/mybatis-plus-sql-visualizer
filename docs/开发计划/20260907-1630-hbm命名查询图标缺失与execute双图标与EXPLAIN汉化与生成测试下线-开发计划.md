# hbm 命名查询图标缺失 / execute 双图标 / EXPLAIN 汉化 / 生成测试下线 开发计划

> 现象（用户真机反馈，demo 项目 `mybatis-plus-sql-visualizer-demo/jpa-hibernate-demo`）：
> 1. `src/main/resources/hbm/LegacyItem.hbm.xml` 的 `<query>`/`<sql-query>` 命名查询无 SQL 预览图标；
> 2. `HibernateTemplateDemoServiceImpl.updateStatusById` / `deleteAsnById` 两个方法各出现两个预览图标；
> 3. 预览面板 EXPLAIN 按钮显示英文（插件语言设置为中文）；
> 4. 「生成测试」按钮作用性不强，评估后直接去掉。
> 用户要求：先出开发计划文件，再实施。
> 工作目录：dev worktree（`mybatis-plus-sql-visualizer-dev`）。⚠️ 当前 dev 工作区含未提交的 20260907-1500 popup 焦点告警修复（SqlPreviewPanel.kt / SqlGutterLineMarkerProvider.kt / SqlPreviewPanelTest.kt），实施前先提交该批改动，避免与本批混在一起。

## 一、根因定位

### 问题一：hbm.xml 命名查询无预览图标

`LegacyItem.hbm.xml` 含 `<query name="LegacyItem.findLowStockHql">`（HQL）与 `<sql-query name="LegacyItem.countByDeprecatedNative">`（原生 SQL）两个命名查询。两条链路均未覆盖：

1. **文件侧 gutter**：XML 语言只注册了 `XmlSqlGutterLineMarkerProvider`（只认父标签为 `mapper` 的 `select/insert/update/delete`）与 `PagesXmlGutterLineMarkerProvider`（只认 `maintainPage`）。`<query>`/`<sql-query>` 挂在 `<hibernate-mapping>` 下，无任何 provider 认领。
2. **调用点回查**：`JpaFullCoverageServiceImpl.queryByNamedQuery` 调 `entityManager.createNamedQuery("LegacyItem.findLowStockHql", LegacyItem.class)`。`SqlPreviewService.previewHql` 的 createNamedQuery 分支经 `NamedQueryLocator.findByQueryName` 回查——该方法只按 `<实体>.<查询名>` 前缀 `JavaPsiFacade.findClasses` 找 **Java @NamedQuery 注解**；LegacyItem 是纯 POJO（无注解，映射全在 hbm.xml），回查必然落空，随后走「把名字当 HQL 处理」降级，`"LegacyItem.findLowStockHql"` 不匹配任何 HQL 形态，预览为垃圾/空。
3. **hbm 解析器缺口**：`HbmXmlEntityResolver` 只解析 `<class>/<id>/<property>/<many-to-one>/<one-to-one>`（供 `resolveHqlTable` 路径 3 建实体→表映射），**不解析 `<query>`/`<sql-query>` 元素**——命名查询文本在插件视角不存在。

### 问题二：两个方法各出现两个预览图标

`commonDao.execute("update WmsDockBooking ...")` 同一条语句出现两个图标，来自两个互不感知的 provider 各锚一行：

| 图标位置 | provider | 认领理由 |
|----------|----------|----------|
| 调用行 `execute` 方法名 | `CustomDaoHqlGutterLineMarkerProvider` | qualifier `commonDao` 命中 DAO 后缀（CUSTOM_DAO 家族）+ 首参匹配 `UPDATE...SET` / `DELETE...FROM` 内容正则 |
| 方法定义行 `updateStatusById` | `StringSqlMethodGutterLineMarkerProvider`（T-DaoSql-04） | 方法体扫描用 `JdbcTemplateMethodDetector.isStringSqlMethodCandidate` 判定，`execute` 在 `JDBC_TEMPLATE_METHODS` 白名单内 + 存在字符串形态实参 |

T-DaoSql-04 的设计意图是覆盖「SQL 拼接后经自研 DAO 委托链传递、调用行本身无锚点」的场景（`beanCruder`/`beanQuery` 属 UNKNOWN 家族）。但方法体扫描**未做家族守卫**：CUSTOM_DAO/HIBERNATE_TEMPLATE/JPA_SESSION 家族的调用本已在调用行被各自专属 provider 认领，方法定义行再亮一个即成重复。`GutterIconDedupTest` 断言「每处调用恰一图标」时 provider 清单只含三个调用行 provider（Hql/CustomDaoHql/Jdbc），未把方法定义锚点纳入计数，测试盲区。

### 问题三：EXPLAIN 按钮英文

`MpSqlVisualizerBundle_zh.properties:77` 即 `panel.explain=EXPLAIN`——zh 与 en 同值，按钮文案从未汉化（该词在 M37.2 落地时按 SQL 术语原样写死）。不是语言加载链路问题（其余面板按钮「复制 SQL/格式化/刷新」均正常中文）。不支持的降级提示 `panel.explain.unsupported` zh 侧已是中文，无需动。

### 问题四：「生成测试」功能评估（结论：下线）

功能现状（M37.4 / P-立项-06 N-4）：
- 入口：预览面板按钮 → popup 两项——「SQL 回归脚本」（把方言渲染后的 SQL + 断言骨架落盘 `src/test/resources/sql-visualizer/`）与「JUnit 用例骨架」（纯骨架字符串弹窗预览复制）。
- 实现：`testgen/TestAssetGenerator.kt`（107 行纯函数）+ `SqlPreviewPanel` 内按钮、`generateTestAsset()`、`testAssetStepForTest()`、`timestampLabel()`、`saveSqlScriptAsset()` + bundle `panel.genTest`/`genTest.*` 共 14 键 + 测试 8 个（TestAssetGeneratorTest 7 + SqlPreviewPanelTest 1）。
- 价值判断：产物是静态 SQL 文本 + 占位断言骨架，不感知项目测试栈（无依赖检测、无真实执行链），对标 MybatisCodeHelperPro testcase 卖点但落地单薄；9 月 6 日已有「测试资产真实断言增强」计划未实施，说明骨架距离可用尚有距离。
- 成本判断：按钮占用面板工具行；其 popup 是 20260907-1500 焦点告警修复的直接来源（doFinalStep 改造 + 可测性 step 工厂 + 专项测试均为它而设）；后续每次面板/弹窗规范调整都要连带维护。
- 下线风险：`testgen` 包无其他消费方（grep 全仓仅 SqlPreviewPanel 与测试引用）；`JavaCodePreviewDialog` 仍被 `ConvertSqlToJavaAction` 使用须保留；plugin.xml 无注册项；设置页无开关。git 历史可随时找回。

结论：整体下线，CHANGELOG Unreleased 记 Removed。若未来要做，方向应是接入项目真实测试框架的可执行用例生成，而非复活骨架输出。

## 二、方案决策

### 问题一（T-H 系列）：补全 hbm 命名查询链路

- **HbmXmlEntityResolver 扩展**：新增 `<query>`/`<sql-query>` 解析——提取 `name` 属性 + 文本内容（`<sql-query>` 内 `<return-scalar>` 等子标签须排除，只取直属 XmlText；CDATA 正常展开）；按查询名建索引（键=完整 name），带与既有 ClassMapping 同款的负命中缓存与封顶清空；`clearCache` 一并清新索引（PluginCacheManager 已调用 clearCache，无需改接线）。
- **新 gutter provider** `HbmNamedQueryGutterLineMarkerProvider`（language="XML"）：leaf 锚点为 `<query>`/`<sql-query>` 标签名 XML_NAME token（对齐 XmlSql provider 的 leaf 策略），O(1) 判定 `tag.name in {"query","sql-query"} && parentTag?.name == "hibernate-mapping"`，零 resolve；点击走 previewAsync 后台转换。与 XmlSql provider（要求父标签 mapper）天然去重。
- **调用点回查 fallback**：`NamedQueryLocator.findByQueryName` 保持 Java 注解优先，未命中时回落 `HbmXmlEntityResolver.findNamedQuery(project, name)`；`SqlPreviewService.previewHql` 的 createNamedQuery 分支按查询种类分派——`<query>`（HQL）→ `HqlConverter.convert`（实体表从查询名前缀或 FROM 子句实体经 `resolveHqlTable` 路径 3 hbm 映射取得）；`<sql-query>`（原生）→ 复用 `previewNativeSql` 同款参数替换路径。`previewCustomDaoHql` 的 NAMED_QUERY 回查分支同样接入 fallback（一行改动复用同一入口）。

### 问题二（T-D 系列）：方法定义锚点补家族守卫

`StringSqlMethodGutterLineMarkerProvider.hasStringSqlCall` 内对每个白名单命中调用补 `PreviewReceiverResolver.classify(call)` 守卫：CUSTOM_DAO / HIBERNATE_TEMPLATE / JPA_SESSION 家族跳过（这些调用已在调用行被专属 provider 认领），仅 JDBC_TEMPLATE / UNKNOWN 计入——与 `JdbcTemplateSqlGutterLineMarkerProvider` 的家族排除制镜像，恢复「方法锚点 = JDBC/UNKNOWN 家族聚合入口」的设计不变量。性能：classify 为 O(1) 名字启发 + per-file 缓存（T-Perf-02 既有设施），且只对已过白名单 + 字符串实参双筛的小集合执行，daemon 热路径无回退。
已知边界（对齐「宁可漏标不重复标」口径）：CUSTOM_DAO 家族但 HQL 内容不匹配（CustomDaoHql 不认领）的调用，修复后调用行与方法行均无图标——此类字符串大概率非 SQL，且快捷键/右键 action 路径仍可预览。

### 问题三（T-E1）：EXPLAIN 文案汉化

zh bundle `panel.explain=执行计划`；en 保持 `EXPLAIN`。同步核查该键的复用点：`explainSql` 降级弹窗标题复用此键，改后标题同步变中文，语义成立。顺带全量审计 zh bundle 非 SQL 术语英文残留——现有审计仅 `settings.language.en=English`（语言选项专名，保留）。

### 问题四（T-R 系列）：生成测试整体下线

删除面清单见 §四。不保留开关或隐藏入口（价值不足，留着即是维护成本）。

## 三、实施顺序

1. **前置**：提交 dev 工作区未提交的 20260907-1500 修复（独立提交，属上一计划验收产物）。
2. 本批四组任务互相独立，可并行子智能体分派：T-H（hbm 链路，改动面最大）/ T-D（守卫一行 + 测试）/ T-E（bundle 一行）/ T-R（删除 + 文档同步）。T-R 与 T-D/T-H 都触碰 `SqlPreviewPanel.kt`/`SqlPreviewPanelTest.kt`，若并行须先做 T-R（删除收敛行号）再做其余，或串行执行。
3. 收尾：bundle 键增删后跑 bundle 一致性核查（zh/en 键集合对称），全量 test。

## 四、实施清单

| # | 改动 | 文件 | 说明 |
|---|------|------|------|
| T-H1 | `HbmXmlEntityResolver` 新增命名查询解析：`HbmNamedQuery(name, kind, queryText)` 模型（kind: HQL/NATIVE）、`findNamedQuery(project, name)` 查询接口、正负命中缓存（沿用 NEGATIVE_CACHE_MAX 封顶口径）、`clearCache` 扩展 | `psi/jpa/HbmXmlEntityResolver.kt` | `<sql-query>` 文本提取只取直属 XmlText，排除 `<return-scalar>`/`<return>` 子标签 |
| T-H2 | 新建 `HbmNamedQueryGutterLineMarkerProvider`：XML_NAME leaf、`query`/`sql-query` + 父标签 `hibernate-mapping` O(1) 判定、tooltip/accessibleName bundle 键、点击 previewAsync | `ui/gutter/HbmNamedQueryGutterLineMarkerProvider.kt`（新）+ `plugin.xml`（XML 语言块注册） | 复用 `/icons/sqlGutter.svg` |
| T-H3 | `SqlPreviewService` 新增 `previewHbmNamedQuery(tag)`：kind 分派 HQL→HqlConverter（table 走 resolveHqlTable hbm 路径）/ NATIVE→原生参数替换；`previewHql` createNamedQuery 分支与 `previewCustomDaoHql` NAMED_QUERY 分支接入 `NamedQueryLocator.findByQueryName` 未命中 → hbm fallback | `ui/SqlPreviewService.kt` + `psi/jpa/NamedQueryLocator.kt` | fallback 入口收敛为一个 internal 函数，两分支共用 |
| T-H4 | 测试：`HbmXmlEntityResolverTest` 补命名查询解析用例（含 sql-query 子标签排除、负缓存）；`HbmNamedQueryGutterLineMarkerProviderTest`（图标命中/不命中 `<class>` 行/去重）；调用点回查 fixture 测试（addFileToProject hbm 文件 + createNamedQuery 预览，setUp 清 resolver 缓存） | `src/test/.../psi/jpa/` + `src/test/.../ui/gutter/` | hbm 文件 fixture 直接用 demo 的 LegacyItem 内容 |
| T-D1 | `hasStringSqlCall` 补家族守卫：classify ∈ {CUSTOM_DAO, HIBERNATE_TEMPLATE, JPA_SESSION} → 跳过 | `ui/gutter/StringSqlMethodGutterLineMarkerProvider.kt` | 镜像 Jdbc provider 家族排除制 |
| T-D2 | 测试：`GutterIconDedupTest` provider 清单加入 StringSqlMethodGutterLineMarkerProvider，补 demo 同款 fixture（commonDao.execute UPDATE/DELETE HQL）断言：调用行恰 1（CustomDaoHql）、方法定义行 0；保留 `beanCruder.selectList`（UNKNOWN 家族）方法行仍亮图标回归 | `src/test/.../ui/gutter/GutterIconDedupTest.kt` + `StringSqlMethodGutterLineMarkerProviderTest.kt` | 修复 T-GutterDedup 时代未纳入方法锚点的盲区 |
| T-E1 | zh bundle `panel.explain=执行计划`；全量核查 en/zh 键值对齐（只改 zh） | `messages/MpSqlVisualizerBundle_zh.properties` | en 侧 EXPLAIN 为术语保留 |
| T-R1 | 删除 `testgen/TestAssetGenerator.kt`（整个 testgen 包）；`SqlPreviewPanel` 删按钮（L406~407）、`generateTestAsset()`、`testAssetStepForTest()`、`timestampLabel()`、`saveSqlScriptAsset()` 及相关 import | `src/main/kotlin/.../testgen/` + `ui/SqlPreviewPanel.kt` | `JavaCodePreviewDialog` 保留（ConvertSqlToJavaAction 在用） |
| T-R2 | 删除 `TestAssetGeneratorTest.kt` 与 `SqlPreviewPanelTest.testTestAssetOnChosen走DoFinalStep不出同步弹窗` | `src/test/kotlin/.../testgen/` + `ui/SqlPreviewPanelTest.kt` | 20260907-1500 的 doFinalStep 修复本体（gutter 二级列表分支）保留不动 |
| T-R3 | bundle 删 `panel.genTest` + `genTest.*` 共 7 键 ×2 文件；`docs/架构设计/M37M38竞品融合新增功能-说明文档.md` 生成测试节标注已下线；CHANGELOG Unreleased 新增 Removed 条目；`docs/MEMORY.md` 变更记录同步 | bundle ×2 + docs | 键删除后 grep 确认无残留引用 |

## 五、验证标准

- 跑任何 `./gradlew` 前先 `python "C:/Users/USER/.zcode/cli/parallel_tasks.py" check`（并行铁律，Git Bash 路径写法）。
- 全量 `clean test` 0 失败：基线 3531 − 下线 8 + 新增约 11 ≈ 3534 绿。
- `verifyPlugin -PverifyIdes=minimal`（243+262）Compatible 不回退。
- runIde 真机（走 [[runide-demo-verify-workflow]]，demo 项目 jpa-hibernate-demo）：
  - LegacyItem.hbm.xml 两个命名查询行出现图标，点击分别得到 HQL 转 SQL（表名 legacy_item、列映射正确）与原生 SQL 预览；
  - `JpaFullCoverageServiceImpl.queryByNamedQuery` 内 `createNamedQuery("LegacyItem.findLowStockHql", ...)` 调用可预览真实 HQL 对应 SQL（不再垃圾降级）；
  - `updateStatusById` / `deleteAsnById` 各只剩调用行一个图标，方法定义行无图标；`beanCruder.selectList` 场景方法行图标不回归；
  - 预览面板 EXPLAIN 按钮显示「执行计划」，功能行为不变；
  - 预览面板无「生成测试」按钮，其余按钮完好。

## 六、回滚

四组改动彼此独立提交（T-H / T-D / T-E / T-R 各一笔，T-R 含 docs），任一出问题单独 `git revert` 即可，互不牵连。
