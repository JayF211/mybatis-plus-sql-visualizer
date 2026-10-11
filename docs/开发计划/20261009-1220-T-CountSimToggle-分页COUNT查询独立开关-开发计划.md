# T-CountSimToggle：分页 COUNT 查询独立开关（COUNT 与 LIMIT 解耦）

> **来源**：用户 2026-10-09 提问「分页拦截器模拟会自动生成一个 count 查询，做成配置项更合适，
> 用户不想要可以关掉」——经代码盘点确认合理（见 §二），立项。
> **生成日**：2026-10-09；写完停下等确认，未动手。
> **占号**：O-130/R76/D129~D130/V152~V153（引用前实测：本计划生成时 O 最高 O-129 / R 最高 R75 /
> D 最高 D128 / V 最高 V151，均已随 T-PremainReattach 消耗）。

## 一、现状（代码实证，dev-bak `65d486d` 态）

分页拦截器模拟（R12 T-PaginationSim）当前**一把总开关** `paginationSimEnabled`（默认开）同时
控制两类产出：

- **LIMIT 追加**：主 SQL 尾按方言渲染 `LIMIT ?, ?`（占位形态，面板回填后真实值）；
- **COUNT 包裹**：`SELECT COUNT(*) FROM (原SQL) TOTAL`（MP 3.5.17 `getOriginalCountSql`
  逐字节形态），预览附第二条语句（面板 `appendCountSection` 渲染）。

COUNT 生产点仅两处（改动面小）：

1. Wrapper 链：`SqlPreviewService.paginationCountSql`（已有闸门：总开关 / `Page.searchCount`
   可判定形态 [D3=A] / count·exists 消费不产 COUNT）；
2. XML/注解共享：`XmlPreviewService.applyPaginationShared`（LIMIT 行与 countSql 同一个 copy
   产出；调用方按 `paginationSimEnabled()` 决定是否传 pageParamName，注解路径同）。

面板显示侧消费点唯一：`SqlPreviewPanel.appendCountSection`（`result.countSql == null` 即不渲染）。

**关键缺口**：MP 运行时语义本就支持「分页不计数」（`Page searchCount=false` 时
PaginationInnerInterceptor 不执行 COUNT，反编译实证）——但该语义只有 Wrapper 链的**可判定
形态**能自动识别（构造器第 3/4 参布尔字面量）；XML/注解路径 searchCount 静态不可判恒按 true。
用户既改不了第三方代码的 `new Page<>()` 默认值，也没有任何手段表达「我要 LIMIT 但不要 COUNT」。

## 二、需求评估：合理，立项

1. **粒度对齐运行时语义**：COUNT 在 MP 里是 willDoQuery 的独立语句且受 searchCount 控制，
   LIMIT 是 beforeQuery 的改写——运行时本来就允许「分页不计数」，插件只有全有/全无，粒度粗。
2. **真实场景存在**：总数由独立统计接口/ES 提供；大表 `COUNT(*)` 代价高，分析 SQL 只关心主查询；
   导出可执行 SQL 到控制台时 COUNT 是要手动删的噪音。
3. **实现面小且单点**：两个生产点 + 一个设置字段 + 设置页一行 + 双语文案 + 守卫；
   面板显示侧零改动（countSql=null 自然不渲染）。
4. **不动既有语义**：默认值 true 保持现状，老用户零感知。

## 三、方案

### D129（推荐）：父开关下挂 COUNT 子开关，父优先

- `PluginSettings.State` 新增 `paginationCountEnabled: Boolean = true`（声明在
  `paginationSimEnabled` 之后，同 KDoc 风格注明「仅在 paginationSimEnabled 开启时生效；
  默认 true 保持现状」）。
- 两处 COUNT 生产点各加一道子开关闸门（`paginationCountEnabled() == false → countSql = null`，
  LIMIT 照旧）：
  - `SqlPreviewService.paginationCountSql` 在总开关闸门后追加；
  - `applyPaginationShared` 内 countSql 求值处加闸门（LIMIT 行不动）——该函数无 settings 参数，
    用 `XmlPreviewService.paginationCountEnabled()` 同款 runCatching 缝（测试环境未注册按 true）。
- 设置页 `paginationSimCheck` 下一行加子复选框（`addRow(p, g, "", …)` 同款），文案键：
  `settings.paginationCount`（「附分页 COUNT 查询预览」）+ tip 注明「关闭后分页模拟只追加 LIMIT，
  不再生成 COUNT 语句；总开关关闭时本项不生效」。双语言 bundle 同步。
- 面板/导出链零改动（生产端把关后 countSql 恒 null，显示与复制自然消失）。

### D130（放弃）：三态下拉（全部/仅 LIMIT/仅 COUNT）

设置页引入三态控件偏离仓内「复选框为主」的形态（feedback-ui-match-existing）；
「仅 COUNT 不要 LIMIT」无真实需求来源（手写 LIMIT 已有 hasExistingLimit 保守跳过）。
留档不作。

## 四、实施步骤

- **S1** `PluginSettings.State.paginationCountEnabled` 字段 + KDoc（含父开关优先级说明）。
- **S2** Wrapper 链 `paginationCountSql` 追加子开关闸门（总开关判定之后）。
- **S3** `applyPaginationShared` countSql 产出加闸门 + `paginationCountEnabled()` 测试缝
  （与 `paginationSimEnabled()` 同款形态）；核实 XML 路径调用方传入 pageParamName 处无需改动
  （子开关只影响 COUNT，pageParamName 语义不变）。
- **S4** 设置页子复选框 + 双语 bundle 键 + isModified 比较追加（`paginationSimCheck` 同款位置）。
- **S5** 守卫：`PaginationSimTest` +2——子开关关 → LIMIT 在而 countSql 为 null（Wrapper 链与
  applyPaginationShared 各一）；父关子开 → 全无（父优先）；**反向验证**：摘 S2/S3 闸门 → 对应用例
  必红；既有分页用例（默认 true）零漂移。
- **S6** 编号落账 + CHANGELOG + 官网功能页分页模拟描述核对（三处同步）。

## 五、验证方案

1. 定向：`PaginationSimTest` 新增用例 + 反向验证两刀（贴红绿读数）。
2. 回归：`PaginationSimTest` 全类 + `testFast` 冒烟（默认 true 路径零漂移）。
3. 真机（留走查清单）：设置页开关往返、预览面板 COUNT 段显隐、导出/复制产物无 COUNT 残留。

## 六、待验证前提（实施者先证伪再动手）

- **P1** `applyPaginationShared` 内部确实没有总开关判定（判定在调用方 pageParamName 三元）——
  已从代码读出该函数只看 `pageParamName == null`，实施时核实 XML 路径调用方行号并确认子开关
  加在函数内部即可同时覆盖 XML/注解两路径。
- **P2** countSql 的消费面唯一（`appendCountSection`）——grep 实证仅 `SqlPreviewPanel:3206` 一处
  消费 `PreviewResult.countSql`；实施时再核「导出可移植 SQL」「复制」链路取的是编辑器文本
  （COUNT 段随编辑器文本存在，生产端把关后自然消失），确认无独立拼接点。
- **P3** `PaginationSimTest` 现有断言对新字段默认值（true）零破坏——默认 true 等价现状，
  理论零漂移，跑全类确认。
- **P4** 设置持久化兼容：`PluginSettingsPersistenceTest` / `ProjectPluginSettingsTest` 对新增
  Boolean 字段的处理形态（XmlSerializer 缺省字段回落默认值 true，无迁移问题）——实施时跑该两
  类确认。

## 七、实施记录

2026-10-09 实施（工作树 countsimtoggle，分支 t-countsimtoggle，基线 b4fe6f9）。S1~S6 全部落地，无方案级偏差。

### 待验证前提证伪结论（§六 P1~P4）

- **P1 成立**：`applyPaginationShared` 内部确无总开关判定（XmlPreviewService.kt 原只看
  `pageParamName == null` / SELECT / hasExistingLimit）；调用方两处核实——XML 路径
  `XmlPreviewService.applyPagination`（`paginationSimEnabled()` 闸门在 :93）、注解路径
  `AnnotationSqlPreviewService.buildResult`（`paginationSimEnabled()` 三元在 :253）。
  子开关加在函数内 countSql 求值处即同时覆盖两路径，pageParamName 语义不变（LIMIT 照旧），
  调用方零改动。
- **P2 成立**：`PreviewResult.countSql` 消费面唯一——`SqlPreviewPanel.appendCountSection`
  （消费点 :3206，唯一调用点 `showSql` :3086）；RollbackSqlGenerator.kt 里的 `countSql`
  是回滚链本地变量（countCheckSql 语义），与本字段无关。「导出可移植 SQL」
  （`exportPortableSql` :1131）与「复制」（`copySql` :2906）均取编辑器文本
  （`currentEditorSql()` / document.text），COUNT 段唯一入口是 appendCountSection 写编辑器——
  生产端把关后编辑器无 COUNT 段，复制/导出自然无残留，确认无独立拼接点。
- **P3 成立**：默认 true 等价现状，定向首轮 PaginationSimTest 既有用例零失败（见下读数）。
- **P4 成立 + 一处预期内测试维护**：`ProjectPluginSettings.State` 是独立 data class
  （不含 paginationSim 字段），其往返测试拷贝显式字段清单——零影响；
  `PluginSettingsPersistenceTest` 的 before 快照只比对显式字段清单——新增字段无破坏形态；
  XmlSerializer 对持久化缺失字段回落默认 true。但 `testLanguageWriteThroughRefreshesCheckboxTexts`
  钉死设置页复选框总数 41，新增子复选框触发守卫红（首轮读数），按同步先例 ccd0f74 更新
  41→42 并补 T-CountSimToggle 双语文案正反两面断言（zh「附分页 COUNT 查询预览」/
  en「Attach pagination COUNT query preview」）——非产品缺陷，测试维护点。

### 读数

- **反向验证（两刀同轮，两用例路径独立互不污染）**：摘 S2 闸门（注释
  `paginationCountSql` 内子开关判定）→ `testCountSubSwitchOffKeepsLimitDropsCountOnWrapperChain`
  红（PaginationSimTest.kt:554 AssertionFailedError）；摘 S3 闸门（`applyPaginationShared`
  内 `if (true || paginationCountEnabled())`）→ `testCountSubSwitchOffKeepsLimitDropsCountOnSharedPath`
  红（:571）。读数＝`2 tests completed, 2 failed`。两刀还原（grep RED PROBE 清零）后复跑回绿。
- **绿轮（定向 6 类）**：PaginationSimTest 22（19 存量 + 3 新增）、
  PluginSettingsPersistenceTest 16、ProjectPluginSettingsTest 7、BundleParityGuardTest 5、
  SettingsRealityGuardTest 4、BundlePlaceholderArityTest 4——**合计 58 例 0 失败 0 错误**
  （`BUILD SUCCESSFUL`）。首轮（守卫修正前）为 45 例 1 失败，唯一失败即上述复选框计数守卫。
- gradle 全程登记 `t-countsimtoggle`（沙盒 countsimtoggle-default，build.gradle.kts 临时
  sandboxContainer 用后已还原、未入库）；未跑全量、未 clean、未 --stop。

### 实施偏差

1. S5 计划写「+2 用例」，实落 **3 个测试方法**——计划括号本身要求「Wrapper 链与
   applyPaginationShared 各一 + 父关子开」，3 方法是两场景的完整落地（子开关关 Wrapper 链、
   子开关关共享路径、父关子开双路径断言）。
2. 额外动了一处测试：`PluginSettingsPersistenceTest` 复选框计数守卫 41→42 + 双语断言
   （P4 证伪过程中发现的同步义务，非计划明示步骤）。
3. 定向回归在计划三类之外加了 3 个守卫类（BundleParityGuardTest / SettingsRealityGuardTest /
   BundlePlaceholderArityTest）——本任务动了 bundle 键与设置页，这三个守卫正护该面，读数全绿。

### 真机走查清单（留档，未实施）

1. 设置页：「模拟分页拦截器」与「附分页 COUNT 查询预览」父子开关往返勾选、Apply/重开持久化、
   zh/en 双语文案跟随。
2. 预览面板：子开关关后 Wrapper 链/XML/注解三路径预览只剩 LIMIT 行，COUNT 段注释头与
   第二条语句消失；面板「模型级重渲染回带 LIMIT 行」不受子开关影响（paginationLimitLine 仍在）。
3. 复制/导出可移植 SQL：子开关关后产物无 COUNT 残留（两链路取编辑器文本，随编辑器自然消失）。
4. 官网功能页「分页模拟」描述与 CHANGELOG、设置页 tip 三处口径核对——官网功能页不在本仓，
   **待主控处理**。
