# T-ReverseParenWira：SQL 转 Java 括号组退化 + attachConsole 读权限硬抛

日期：2026-09-11　基线：master 0cb95eb（v2026.9.11 发行后）

## 一、问题一：括号条件导致 MPJ lambda 形态退化

### 现象

正向 MPJ 代码

```java
MPJLambdaWrapper<DeviceInfoEntity> wrapper = new MPJLambdaWrapper<>();
wrapper.select(DeviceInfoEntity::getId, ...)
    .leftJoin(StationInfoEntity.class, StationInfoEntity::getStationId, DeviceInfoEntity::getId)
    .eq(DeviceInfoEntity::getId, 1);
```

被 MPJ 渲染成 `... LEFT JOIN station_info st ON (st.station_id = t.id) WHERE (t.id = 1)`。
MPJ 渲染 ON 和 WHERE 时自带括号。反向转换回来却退化成：

- wrapper 形态从 `MPJLambdaWrapper` 降为 `MPJQueryWrapper<>`，select 变字符串逐列，
  leftJoin 变整段字符串 `"station_info st ON (st.station_id = t.id)"`；
- WHERE 条件多出无意义的 `.nested(w -> w.eq(...))`。

### 根因

`SqlReverseParser.parseGroup`（SqlReverseParser.kt:836-847）解析 `(x)` 单条件括号组时，
只有组内本身是 Group（如 `(a=1 OR b=2)` 被 parseConditionList 包成 OR 组）才直接复用；
单条件 `(st.station_id = t.id)` 被包成 `Group(AND, [ColumnComparison])`，`WHERE (t.id = 1)`
同理包成 `Group(AND, [Simple])`。两个下游各被击中一次：

1. `JavaCodeGenerator.extractJoinKey`（JavaCodeGenerator.kt:326）要求
   `onConditions` 里 `singleOrNull { it is Condition.ColumnComparison }`——Group 不是
   ColumnComparison，提取失败 → `canLambdaJoin` false → `generateSelect` 的
   effectiveKind 门（JavaCodeGenerator.kt:123）判「不能 lambda 化」回落 `MPJ_QUERY`
   字符串形态（MPJLambdaWrapper 无 leftJoin(String)，1.5.6 实证，宁可字符串保编译）。
2. `renderGroup` 对 `Group(AND, [单条件])` 忠实渲染 `.nested(w -> w...)`——WHERE 多一层嵌套。

### 修复（F-A）

`parseGroup` 末尾：组内仅 1 个子条件、该子条件不是 Group、且非 negated 时直接返回该
子条件（解包）。依据：单条件的括号无语义增量——MP 的 `eq()` 与 MPJ 的三参 leftJoin
生成的 ON/WHERE 本就不需要外层括号，round-trip 等价。保留两条例外：

- `inner.singleOrNull() is Condition.Group` → 沿现逻辑复用（negated 时 copy）；
- negated 单条件（`NOT (a=1)`）→ 保留 Group 承载 negated 标记。

不动 JavaCodeGenerator：解析器是唯一收敛点，生成器对显式构造的单子 Group（如
serialVersionUID 过滤后的测试用例）仍按 nested 渲染，语义正确。

同步更新 `parseGroup` KDoc。

## 二、问题二：预览准备控制台 Read access 硬抛

### 现象

真机鼠标双击日志开预览 → `prepareDefaultConsole` → `runRunner` → session consumer
回调 `prepareInConsole`（DataSourceSelector.kt:1117）反射 `attachConsole` 抛
`RuntimeExceptionWithAttachments: Read access is allowed from inside read-action only`，
堆栈经 `JdbcConsole.<init>` → `ConsoleExecutionEditor.<init>:54` →
`FileDocumentManager.getDocument`，全程 EDT 同步（`ModalityUiUtil.invokeLaterIfNeeded`
在已处分发线程时就地执行）。异常被外层 `runCatching` 吞掉，控制台静默挂载失败。

### 根因

T-PreviewSlow F2 为修 WIRA-in-ReadAction（`EditorImpl.setHighlighter` 在
read-action 内再开 WIRA 硬抛）把 attachConsole 的 ReadAction 包裹整个拆掉了，还原裸
EDT 插桩。当时只解决了 setHighlighter 那条链，漏了同一条构造链上的
`ConsoleExecutionEditor.<init>` → `FileDocumentManager.getDocument`：262 平台该 API
要求调用方已持读权限，裸 EDT 直调硬抛。即 T-PreviewSlow F2 注释预言的
「若真机仍刷 Read access 再单独处理」场景，这次真机兑现了，且崩点在 attach 内部、
无法事后单独包。

### 修复（F-B）

沿用同文件 T-ExecCopyFix F2b 的既有先例（createTempPsiFile 冷路径
`WriteIntentReadAction.compute`，243~262 交集 API——T-Wia262）：

1. `attach.invoke(null, project, session, vf)` 包 `WriteIntentReadAction.compute`。
   WIRA 隐含读权限，满足 `getDocument` 断言；内部 `setHighlighter` 的 WIRA 是
   WIRA-in-WIRA 重入，平台只禁「plain ReadAction 内再开 WIRA」，重入合法——此项列入
   262 真机验证（V-2），若真机仍见嵌套硬抛再议。
2. 其后 `console.getDocument`（:1127）单独包 `ReadAction.compute`（T-PreviewSlow F2
   注释预先授权的方案：该句不触发 WIRA，与 attachConsole 不同，plain ReadAction 即可）。
3. 两处注释同步改写：T-PreviewSlow F2 的「还原直接插桩」段改为记录本轮结论，避免
   下轮会话再次拆包反复。

## 三、测试

- 解析器：新增 `括号组内单条件_解包不产生Group`——`WHERE (t.id = 1)` 解析结果
  conditions 直接是 Simple 而非 Group；`NOT (a=1)` 仍保留 negated 组（回归护栏）。
- 生成器：新增用户实录 round-trip——parse 完整 SQL
  `SELECT t.id,... FROM car_device_info t LEFT JOIN station_info st ON (st.station_id = t.id) WHERE (t.id = 1)`
  + 主副实体映射，断言 `MPJLambdaWrapper`、三参 lambda leftJoin、平铺
  `.eq(DeviceInfoEntity::getId, 1)`、无 `nested(`。
- 现有受影响断言：`JavaCodeGeneratorTest` 各 nested 断言（168/188/236/313/1818）均为
  多条件组或直构 AST，不经 parseGroup，预期零改动；`SqlReverseParserTest` 括号组用例
  均为多条件组或 negated，预期零改动。全量跑一遍实证。
- F-B 无单测可覆盖（DBN 真链 + 262 断言，243 沙盒盲区——平台加固型缺陷固有盲区，
  见 T-PreviewDocLock 系列结论）。

## 四、验证

- V-1 全量单测绿（基线 3305 附近）。
- V-2 262 真机：双击日志开预览，idea.log 无新 `Read access` / `WriteIntentReadAction
  can not be called` 崩溃行，控制台正常挂载。留人工。
- V-3 262 真机：对实录 SQL 点「SQL→Java」，输出 MPJLambdaWrapper lambda 形态。
