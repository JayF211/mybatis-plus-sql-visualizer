# T-DdlLogPreview：日志 DDL 预览的字面量恢复误伤与 DDL 格式化缺失——开发计划

> 2026-10-06 第三轮验收走查新发现（用户实测）：从 SQL 日志点开两条 **DDL**（`CREATE TABLE ...` 与 `DROP TABLE IF EXISTS ...`）时出现两类问题——①参数区凭空冒出 `#param1..#param10`（值为 DDL 里的列注释文本），②点「格式化」看不出任何变化。本计划只做根因分析与方案设计，**写完停等拍板**（用户当轮明确「先分析这两个问题，生成开发计划文件」，未令实施）。分析基线 = dev 工作树 @ `acf4805`（行号以该 tip 为准）。
>
> 预定占号：O-114（本轮台账）、R59（候选登记）、D75~D76（决策点）、V98~V101（待验证前提），以全景盘点升版裁决为准。

## 一、缺陷台账

| # | 用户反馈 | 现象（截图实证） | 级别 |
|---|---|---|---|
| 1 | 这两条 SQL「没有正常识别」 | `CREATE TABLE` 条目的预览左侧参数区出现 **#param1..#param10**，值恰为 DDL 的 10 个列注释（主键 / 计划编号 / 业务对象编码（模拟自研框架…）/ 项目名称 / 负责人 / 状态（0停用…）/ 金额 / 创建时间（Unix 秒）/ 更新时间（Unix 秒）/ 项目计划表（模拟自研框架主…）） | P1 真缺陷（影响可执行性） |
| 2 | 「无法进行格式化」 | `CREATE TABLE` 条目点「格式化」后 SQL 仍是整行单行；`DROP TABLE IF EXISTS … ;` 点格式化同样无可见变化 | P1 功能缺失 |
| 3 | （疑似）标识符引号形态 | 预览里标识符显示为单引号（`CREATE TABLE 'biz_project_plan' ( 'id' BIGINT …`），而**捕获落盘原文是反引号**（见 §2.3 取证）——低分辨率截图难辨，列为待验证前提 V98，不据此立项 | 待验证 |

## 二、根因详述

### 2.1 参数区凭空冒出 #param1..#paramN：字面量恢复缺**语句类型门禁**

链路（逐环节出处）：

1. 点日志条目 → `LogEntryPreviewRecognizer.toPreviewResultInternal`（`log/LogEntryPreviewRecognizer.kt:137`）；
2. 该函数在 `literalRecovery && countPlaceholders(rawSql) == 0` 时调用 `recoverLiteralParams(rawSql)`（同文件 `:155-156`）——即**只要 SQL 里没有一个 `?`** 就走「字面量参数恢复」；
3. `LiteralParamRecovery`（`log/LiteralParamRecovery.kt`）把**单引号字符串字面量全量**改写成 `?` 并合成参数名 `param1..paramN`（`paramN → 字面量原文`），上限 `MAX_RECOVERED_PARAMS = 32`；
4. `literalParamRecovery` 设置**默认开**（`config/PluginSettings.kt:291`）⇒ 默认命中；
5. 于是 `CREATE TABLE … COMMENT '主键', … COMMENT '计划编号' …` 的 **10 个注释字符串**被当成 10 个参数 ⇒ 与用户截图逐条吻合（名称 `param1..param10`、值＝注释文本）。

**影响面比「多几行噪音」重**：恢复命中时 `PreviewResult.sql = recovery.placeholderSql`（`:158`）与 `compactSql = withTerminator(recovery.placeholderSql)`（`:159`）——即**预览与执行链用的是占位符化后的 SQL**：`CREATE TABLE \`biz_project_plan\` ( \`id\` BIGINT NOT NULL AUTO_INCREMENT COMMENT ?, … )`。该 SQL **语法不合法**，点「执行 SQL」必然报错；且用户若在参数行改值，改的是 DDL 的注释文本。DDL 本就没有绑定参数可编辑——这条恢复对它整体无意义。

**为什么既有自校验没拦住**：`verifyLiteralRoundTrip(rawSql, recovery)`（同文件 `:157`，实现 `LiteralParamRecovery.kt:221`）只验「占位符化后填回原值能逐字符还原原文」——对 DDL 恰好成立（值就是注释原文），故**自校验不能替代语句类型门禁**。

**可复用件**：`execute/SqlTypeClassifier.kt` 已有 `SqlType { QUERY, DML, DDL, UNKNOWN }`（`:16`）与 `classify(statement)`（`:67`），门禁可直接复用（避免自写关键字表）。

### 2.2 格式化对 DDL 无效果：格式化器的换行关键字集只覆盖 DML

- 面板「格式化」= `SqlPreviewPanel.setDisplayMode(FORMAT)` → 渲染管线 `applyDisplayMode` → `SqlFormatter.format(sql)`（`ui/highlight/SqlFormatter.kt:41`）；
- 该格式化器按 `NEW_LINE_KEYWORDS`（同文件 `:22-27`）决定换行点，集合内容为 **SELECT / FROM / WHERE / GROUP / ORDER / HAVING / LIMIT / UPDATE / SET / DELETE / INSERT / INTO / VALUES / UNION / JOIN / LEFT / RIGHT / INNER / FULL / CROSS** ——**不含任何 DDL 关键字**（CREATE / ALTER / DROP / TRUNCATE / PRIMARY KEY / COMMENT / ENGINE / DEFAULT CHARSET 等）；
- 因此 DDL 输入走「无任何换行点」的默认路径，输出≈输入（单行原样）⇒ 用户点「格式化」看不到变化。`DROP TABLE IF EXISTS x ;` 本身已是单行、无换行点，故同样无变化（这条属**预期外观**，但仍会被用户读成"格式化无效"）；
- 同一 formatter 也被 `formatIfNeeded(...)` 用于日志/历史条目预览的文本（`LogEntryPreviewRecognizer` 的 `sql = formatIfNeeded(...)`），故**两处预览都受影响**；`formatSql` 设置默认开（`config/PluginSettings.kt:51`）。

### 2.3 标识符引号形态（疑似项，取证未定）

捕获落盘原文取证（demo 工程插件自存历史，`sql-visualizer-demo/.idea/sql-visualizer/sql-history/sql-history-2026-09-27.sql`）：

```
DROP TABLE IF EXISTS `biz_project_plan`;
CREATE TABLE `biz_project_plan` ( `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键', `plan_id` VARCHAR(64) NOT NULL COMMENT '计划编号', …
```

即**捕获侧与落盘文本用反引号**（与 agent EVENT 实录一致）；而用户预览截图里标识符形似单引号。当前 `src/main` 内未检索到「反引号 → 单引号」的改写逻辑（扫描 `replace/Regex/quote/identifier` + 反引号共现行无命中；`LiteralParamRecovery` 的 KDoc 亦声明只识别单引号、双引号/反引号不误伤）。⇒ 判定为**截图观感/字体渲染**的可能性大，列为 V98 真机核对；**不据此立项**（若核对确认被改写，另立专项定位改写点）。

## 三、方案设计

### S1 字面量恢复加**语句类型门禁**（缺陷 1 主修）

- 在 `LogEntryPreviewRecognizer` 的字面量恢复分支前加判定：`SqlTypeClassifier.classify(rawSql)` 为 **DDL**（及 UNKNOWN 中的非 DML/QUERY 形态）时**跳过恢复**，直接走既有「原文 + 已恢复值」路径（即 DDL 预览不产生参数行）。
- 白名单口径（推荐）＝**仅 `QUERY` / `DML` 恢复**，DDL 与 UNKNOWN 不恢复——理由：恢复的价值场景是「上游日志已内联值的查询/写入」，DDL/DCL/会话语句没有可编辑参数语义；UNKNOWN 保守不猜（与 fail-closed 惯例一致）。
- 顺带把「DDL 被误恢复」写成回归用例：`CREATE TABLE … COMMENT '主键', …` 预览**不得**出现参数行，且 `PreviewResult.sql` 必须保留原文（可执行性）。反向验证＝摘掉门禁即红。
- 边界确认：`INSERT INTO t (a) VALUES ('x')` 的字符串在**现行规则**下本来就会被恢复成参数（VALUES 列表命中）——属既有设计取舍，本批**不改**（列 V101 登记，若用户认为也是误伤可另议）。

### S2 DDL 格式化规则（缺陷 2 主修）

- 给 `SqlFormatter` 增加 DDL 分支：识别首关键字 ∈ {CREATE, ALTER, DROP, TRUNCATE, RENAME} 时走 DDL 排版——
  - `CREATE TABLE <name> (` 后**顶层逗号**（括号深度 1）逐列换行并缩进（列定义内的小括号如 `VARCHAR(64)` 不换行）；
  - 收尾 `)` 独立成行、表尾选项（`ENGINE=… DEFAULT CHARSET=…`）保持一行、语句末尾 `;` 紧贴；
  - `ALTER TABLE` 的 `ADD COLUMN` / `MODIFY` 逐个换行；`DROP` / `TRUNCATE` 单行原样（本就无换行点）。
- **红线**：DML 与既有语料的格式化输出**逐字节不变**（现网大量断言依赖），故新增规则以语句类型分派，不触碰 `NEW_LINE_KEYWORDS` 的既有语义。
- 验证：新增 DDL 语料（用户实测的两条 + `CREATE TABLE` 带 `PRIMARY KEY`/索引/表尾选项 + `ALTER TABLE ADD COLUMN`）断言排版形态；既有 `SqlFormatter*` 全部回归绿。

### S3 「格式化无变化」的反馈面（可选，D76）

`DROP TABLE IF EXISTS x ;` 这类**本就单行**的语句，点「格式化」无变化属正常；但用户会读成"按钮坏了"。可选增强：格式化动作后若文本零变化，给一次轻提示（`Notification` 或状态行「已是格式化形态」）——是否做由用户拍板（D76），不做也不影响 S2 的正确性。

### S4 文档与验收面

- CHANGELOG 记 Fixed 两条（DDL 预览参数误恢复、DDL 格式化缺失）；
- 验收文档第四轮修订块追加两行闭环 + 清单行（日志/历史条目预览：DDL 无参数行、点格式化有排版效果）。

## 四、待验证前提（实施者先证伪再动手）

- **V98**：预览面板显示标识符引号是否真被改写（真机放大截图或导出预览文本比对落盘原文）——若确被改写，本计划需加一项「定位改写点」；当前证据倾向「未改写，属低分辨率观感」。
- **V99**：用户现场 `literalParamRecovery`（默认 true）与 `formatSql`（默认 true）两开关实际取值——两处默认开即默认命中此二缺陷。
- **V100**：DDL 预览点「执行 SQL」的实际表现（占位符 SQL 是否真被送执行、报错文案形态）——用于定 S1 的严重度描述与验收口径。
- **V101**：DDL 之外还有哪些语句类型会被字面量恢复误伤（`SHOW CREATE TABLE`、`EXPLAIN`、含字符串的 `INSERT`/`UPDATE` 是否算误伤）——决定 S1 白名单口径是否需再收窄（如仅「含 `?` 形态的历史值恢复」之外一律不恢复）。
- **V102**（实现前提）：`SqlTypeClassifier.classify` 对本例两条 DDL 的判定确为 `DDL`（先跑一次既有分类器单测/探针确认，勿凭关键字集推断）。

## 五、验收标准

1. 从日志/历史点开 `CREATE TABLE … COMMENT '…'` 与 `DROP TABLE IF EXISTS …` 两条 DDL：**参数区无参数行**（无 `#param1..#paramN`），预览 SQL 与捕获原文逐字一致（反引号、注释原样）。
2. 点「格式化」对 `CREATE TABLE` 产出可读多行排版（列表逐行、收尾 `)` 独立行），对 `DROP/TRUNCATE` 单行原样（可选：给「已是格式化形态」提示，按 D76）。
3. DML 与既有语料的格式化输出逐字节不变（既有 formatter 测试全绿）。
4. 反向验证：摘掉 S1 门禁 → DDL 预览参数行用例必红；摘掉 S2 分派 → DDL 排版用例必红。
5. 定向测试全绿 + 全量门禁（`testShards` / agent / `verifyGate`）读数与基线对齐。

## 六、范围外

- 标识符引号改写的定位（V98 未证实前不做）。
- `INSERT … VALUES ('字符串')` 的字面量恢复取舍（V101 登记，另议）。
- 参数预设 / 增强捕获 / 徽章文案等本轮已收口项不动。

## 七、实施记录（2026-10-06 当轮，主控直做；用户令「格式化零变化给轻提示——按你的推荐实施」）

- **S1 字面量恢复加语句类型门禁**：`LogEntryPreviewRecognizer.toPreviewResultInternal` 恢复分支前加 `SqlTypeClassifier.classify(rawSql)` 判定，白名单 `QUERY`/`DML`（`DDL`/`UNKNOWN` fail-closed 不恢复）。
  **红读数**：门禁放开 → 新增用例 `DDL 不产字面量参数且预览 SQL 保留原文` 必红——`AssertionError: DDL 不得产生参数行（列注释不是参数）：[param1, param2]`（即用户现场形态）；收口后绿。对照用例 `DML 字面量恢复行为不变` 钉住白名单只拦 DDL/UNKNOWN。
- **S2 格式化器 DDL 分派**：`SqlFormatter` 新增 `DDL_LEAD_KEYWORDS`（CREATE/ALTER/DROP/TRUNCATE/RENAME）门控——定义列表 `(` 后换行缩进 4、顶层逗号逐列换行、外层 `)` 恒独立成行且表尾选项随行、类型尺寸括号贴紧、`ALTER` 顶层动作逐行；新增 `DDL_PAREN_SPACED_KEYWORDS`（KEY/REFERENCES/CHECK/IN/EXISTS/USING/VALUES）收口「括号前留空格」的惯例形态。
  实现中实测抓到并收口两个额外形态：①**`VARCHAR` 是 KEYWORD token 而非标识符**（贴括号判据须含关键字分支，实测输出 `VARCHAR (64)` 暴露）；②**列表内嵌套括号会复位 `parenNewlined`** ⇒ 既有判据会把表尾选项粘在最后一条列定义行尾（DDL 域内按 `depth == 1` 显式断行）。
  **红线守住**：新增 `testDmlUnaffectedByDdlBranch` 逐字断言 DML 排版不变；`SqlFormatterTest`/`SqlFormatterUpdateNewColumnsTest` 全量回归绿。
- **S3 零变化轻提示（D76=A）**：`SqlPreviewPanel.formatSql()` 记渲染前文本，渲染后一致即发 INFORMATION 轻提示；新键 `panel.format.alreadyFormatted`（中英）——「已是最可读形态，格式化后无变化（单行语句或本就没有换行点的语句）」；与既有轻提示同规格（`NOTIFICATION_GROUP_ID` + 非模态），单测环境静默。
- **定向读数**：`SqlFormatterTest` / `SqlFormatterUpdateNewColumnsTest` / `LogEntryPreviewRecognizerTest` / `LiteralParamRecoveryTest` / `InLiteralRecoveryRegressionTest` / `BundleParityGuardTest` 合 **119 例 BUILD SUCCESSFUL**（首轮两例红为断言口径与既有形态不符，已按实测订正：`if` 属 FUNCTION_NAMES 不小写；外层 `)` 断行）。
- **待验证前提落地**：V99 已核（`literalParamRecovery`/`formatSql` 默认均 true）；V100 随 S1 消除（DDL 不再产占位符 SQL）；V101 按白名单口径收口（`INSERT … VALUES ('x')` 维持既有恢复，未改）；V102 由红绿对照证（分类器对两条 DDL 判 `DDL`）；**V98（预览标识符引号观感）仍待真机核对**——落盘原文为反引号，实施未触碰引号链路。
