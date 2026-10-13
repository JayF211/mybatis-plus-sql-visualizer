# T-SqlTemplateCaret：SQL 快捷模板支持「光标处表名」（开发计划）

> 仓库：`mybatis-plus-sql-visualizer`（插件仓）
> 日期：2026-10-13
> 触发来源：用户 2026-10-13 提出 —— 三个快捷模板动作（EXPLAIN 选中表 / COUNT 选中表 / 抽样 10 行）**必须先划出文本选区**才可用；要求改成**光标停在表名上（不划选区）也能用**，理由是「录制方便、使用也方便」。
> 状态：**方案已定案；实施 + 定向测试 + 反向验证进行中。**

---

## 一、需求

1. 三个动作的启用条件从「编辑器有非空选区」放宽为「编辑器有非空选区 **或** 光标压在一个标识符上」。
2. 有选区时行为**逐字节不变**（原样取选区文本当表名/片段，不做校验）。
3. 无选区时退化为取**光标处的标识符**（表名/片段）；光标没压到任何标识符时仍保持禁用（不能把「什么都没压到」变成可用）。
4. 不改动作语义（取到的文本一律原样当表名、不做表名校验）、不改启用条件的「非空白」底线、不改 P2 的通知接线、不动执行链（`execute/SqlExecutor.kt`、`execute/DataSourceSelector*.kt`）。

## 二、现状与实证

### 2.1 现状代码

`src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt`：

- `selectedText(e)`（旧 `:73-77`）：取 `CommonDataKeys.EDITOR` → `selectionModel.hasSelection()` 为假直接返回 null；为真取 `selectedText?.takeIf { isNotBlank() }`。
- `update()`（旧 `:35-47`）：`isEnabled = selectedText(e) != null`。
- `actionPerformed()`（旧 `:49-71`）：`selectedText(e) ?: return`，再 `template(text.trim())`。
- 三个子类只差模板串：`EXPLAIN SELECT * FROM <选中>` / `SELECT COUNT(*) FROM <选中>` / `SELECT * FROM <选中> LIMIT <sampleLimit 默认 10>`（旧 `:161-195`）。
- P2（`f6b4d159`）已给失败气泡挂「定位到出错语句 / 复制错误信息」两个动作（旧 `:88-159`）——**本轮保留不动**。

### 2.2 实证读数（本轮沙盒日志）

- 光标压在标识符上但**没划选区**时，`update()` 收到的是 `hasSel=true sel='              '`（14 个空格）→ `isNotBlank()` 判空 → `enabled=false`。
- 真正选中 `car_device_info` 时是 `enabled=true`。

即：**唯一缺的就是「没有可用选区时退化为取光标处的标识符」**。同时这条读数暴露一个必须处理的细节——**「有选区」不等于「选区可用」**：空白选区（纯空格）也会让 `hasSelection()` 为真，只按 `hasSelection()` 分流会把这种场景重新判成「无内容」而保持禁用。因此分流口径定为：**非空选区优先，否则一律退化到光标处标识符**。

> 说明：工作树里曾有一行排查用临时调试日志（`[A15DBG]`，`update()` 内，未提交）。本轮实施时删除，不提交。

## 三、方案与取舍

### 3.1 「光标处标识符」的实现选型：纯 `CharSequence` + 偏移量词边界扫描

候选与取舍：

| 候选 | 判断 |
|---|---|
| **`document.charsSequence` + 光标 offset 做词边界扫描**（选定） | `Document.getCharsSequence()` 是**免拷贝视图**（本仓 `mcp/McpSourceLocator.kt:111`、`execute/DataSourceSelector.kt:470-475` 同款用法），扫描只与光标所在词长度相关；**语言无关**、不依赖 SQL 注入是否生效；纯函数（文本 + 偏移）**可脱离编辑器做纯逻辑单测** |
| PSI / `PsiDocumentManager` | 依赖具体语言与文件类型；在 `.sql`、预览弹窗文本区、注入未生效时取不到锚点；且 PSI 读要 project / 读锁，动作在**任意编辑器**里都可能被调用，命中面随宿主而变 |
| 平台 `Editor`/`Lexer`/`EditorHighlighter` token | token 是**语言相关**的（Java 文件给 Java token），且 token 不含引号、光标在空白处给 `WHITE_SPACE`；比词边界扫描重，还引入 lexer 注册面 |

**结论**：选词边界扫描。它正好满足「任意编辑器都能用、不写死语言、不依赖注入」这条硬口径，且天然可单测。

规则（与 `log/LiteralParamRecovery.kt:65`、`sql/ExplainGate.kt:33` 等既有 `isWordChar` 口径同族）：

- 标识符字符 = `Character.isLetterOrDigit(c) || c == '_'`。
- 光标优先压**右侧**字符，否则压**左侧**字符；取该字符所在的极大标识符串。
- 两侧都无标识符字符（空白 / 标点 / 单引号字符串边界）→ 返回 null → 动作禁用。
- 额外支持两点，见 3.3 / 3.4。

### 3.2 选区 vs 光标处的优先级

- **有非空选区 → 用选区文本（逐字节原样）**：与既有行为完全一致，`actionPerformed` 里既有的 `text.trim()` 保留（选区可含首尾空白）。
- **无选区，或选区为空白 → 取光标处标识符**：空白选区也退化，是因为 2.2 的实证（纯空格选区会让 `hasSelection()` 为真）；若不退化，那个场景仍然禁用。
- 最终文本仍要求 `isNotBlank()`，否则禁用——守住「什么都没压到不可用」的底线。

### 3.3 引号怎么取：**连引号一起取**

光标压在 `` `car_device_info` `` / `"car_device_info"` / `[car_device_info]` 里时，取出的文本**连引号一并返回**。理由：

1. **与选区路径一致**：用户**选中** `` `car_device_info` `` 时，选区路径原样传 `` `car_device_info` ``，生成 `FROM `car_device_info``。光标路径取同一个表时应产出同一串，否则同一张表因「选 vs 压」得到不同 SQL。
2. **符合「原样当表名」口径**：剥引号是一种**变换**，与「选中/光标处文本一律不做表名校验、原样当表名」的既有口径冲突。
3. **执行上安全**：`FROM `tbl`` 与 `FROM "tbl"` 在各自方言下都合法，连引号不比剥引号差；真机失败时用户的原始 SQL 里本来也写着引号，与选区路径同命。

已知边界（见 §四）：mapper XML 的属性值 `id="foo"` 会被双引号包裹成 `"foo"`——这是「语言无关」的必然代价，属误压场景，记录不特殊处理。

### 3.4 限定名（`db.tbl`）与单引号字符串

- **限定名跨点取全**：`mpj_demo.t_order` / `` `db`.`tbl` `` / `[db].[tbl]` 这类以 `.` 连接的标识符段一并取全（每段各自可带引号包裹）。理由：本仓多库场景里 schema 限定名常见（错误文案本身就是 `Table 'mpj_demo.t_order' doesn't exist`），光标压在任一段上取到整串更符合直觉。`.` 只在**两侧都是合法标识符段**时才连接（`t.*`、`1.5` 里的孤立点按规则处理，见 §四）。
- **单引号字符串不连引号**：`'car_device_info'` 里取到的是 `car_device_info`（不含单引号）——`FROM 'x'` 是非法 SQL，把单引号带进去只会制造必然失败；单引号因此**不作为引号包裹字符**。

## 四、边界矩阵

| 场景 | 输入（`|` 为光标） | 取到 | 动作 |
|---|---|---|---|
| 标识符中间 | `FROM car_dev|ice_info` | `car_device_info` | 可用 |
| 标识符首/尾 | `FROM |car_device_info` / `...info|` | `car_device_info` | 可用 |
| 空白 | `FROM |  t_order`（压空格） | — | 禁用 |
| 标点 | `FROM t_order|,`（压逗号） | — | 禁用 |
| 反引号标识符 | `FROM `car_dev|ice_info`` | `` `car_device_info` `` | 可用 |
| 双引号标识符 | `FROM "car_dev|ice_info"` | `"car_device_info"` | 可用 |
| 方括号标识符 | `FROM [car_dev|ice_info]` | `[car_device_info]` | 可用 |
| 压在引号上 | `FROM |`car_device_info`` / `...`|` | `` `car_device_info` `` | 可用 |
| 单引号字符串 | `= 'car_dev|ice_info'` | `car_device_info`（不含单引号） | 可用 |
| 注释内标识符 | `-- car_dev|ice_info` | `car_device_info` | 可用（语言无关，不解析注释） |
| 注释符/纯标点 | `--|` / `/*|*/` | — | 禁用 |
| 限定名 | `FROM mpj_demo.t_|order` | `mpj_demo.t_order` | 可用 |
| 限定名带引号 | ``FROM `db`.`t_|order``` | `` `db`.`t_order` `` | 可用 |
| 文件首 | `|SELECT ...`（压 S） | `SELECT` | 可用 |
| 文件尾 | `... t_order|`（压 r） | `t_order` | 可用 |
| 空文档 | `|` | — | 禁用 |

## 五、改动清单

1. `src/main/kotlin/.../actions/SelectedTableSqlTemplateActions.kt`
   - 删除 `update()` 里的 `[A15DBG]` 临时日志。
   - `update()` / `actionPerformed()` 的目标文本入口由 `selectedText(e)` 改为 `selectedOrCaretText(e)`（薄封装，从事件取 EDITOR）。
   - 新增 `editorTemplateText(editor)`：非空选区优先，否则取光标处标识符（可测）。
   - 新增纯函数 `resolveTemplateText(selectedText, caretIdentifier)`：优先级 + 空白退化（可纯逻辑单测）。
   - 新增纯函数 `identifierAtCaret(text, caretOffset)` + 私有辅助 `isIdentifierChar` / `scanSegmentRight` / `scanSegmentLeft` / `QUOTE_OPENERS` / `QUOTE_CLOSERS`。
   - 类注释更新（说明光标退化口径）。
   - **不动**：P2 的 `ErrorLocateAnchor` / `resolveErrorLocateAnchor` / `sqlTemplateErrorActions` / `buildSqlTemplateErrorNotification` / `notifySqlTemplateError`，三个子类的模板串。
2. `src/test/kotlin/.../actions/SelectedTableCaretTextTest.kt`（新增）：`identifierAtCaret` / `resolveTemplateText` / `editorTemplateText` 三层测试。
3. `CHANGELOG.md`：`[Unreleased]` 增 `### Changed` 条目。

## 六、验证方案

### 6.1 定向测试

```
./gradlew test --tests "cn.song.mybatisplus.visualizer.actions.SelectedTableCaretTextTest" --console=plain -Pkotlin.compiler.execution.strategy=in-process
```

覆盖：§四边界矩阵逐行 + `resolveTemplateText` 优先级/空白退化 + `editorTemplateText` 用 `myFixture` 真编辑器验「有选区取选区、无选区取光标处」。（跑 gradle 前按仓规 `parallel_tasks.py check`，有活跃任务则 `register --name T-SqlTemplateCaret`。）

### 6.2 反向验证

把「无选区退化」分支短路（`resolveTemplateText` 在无有效选区时直接返回 null）→ 新增用例中所有依赖光标退化的用例必须**变红**；贴两次读数（短路后、还原后）。

### 6.3 不做（本轮约束）

- 不 runIde / 不 prepareSandbox / 不 clean / 不 `--stop`；不跑全量测试与构建校验（`verifyGate`/`verifyPlugin`/`buildPlugin`）——沙盒正在录制。
- 不真机验证（沙盒被录制任务占用）。

## 七、待验证前提（不确定的机制/平台行为，实施者先证伪再动手）

1. **预览弹窗 SQL 文本区的 `CommonDataKeys.EDITOR` 解析**：沿用既有动作路径（P2 已在此路径上取 `EDITOR`），但本轮不真机复验——弹窗获得焦点时该 key 是否指向 SQL 文本区编辑器，未在录制沙盒里实测。
2. **「空白选区 + 光标在标识符上」能否在同一编辑器同时成立**：纯逻辑层按「非空选区优先、否则取光标处」处理，**不依赖二者可否共存**；若二者物理上不可共存，退化分支仍能覆盖「真·无选区」场景。
3. **`Document.getCharsSequence()` 免拷贝语义**：沿用 `McpSourceLocator` / `DataSourceSelector` 既有用法，未在超长文档上复测（扫描只取光标所在词，风险面极小）。
4. **`SelectionModel.selectionStart` 无选区时等于光标偏移**：`resolveErrorLocateAnchor`（P2）依赖它取定位行号；本轮不改该函数，但光标路径下锚点行是否等于光标行未真机复验。
5. **EDT 读 `caretModel.offset` 的线程安全**：动作 `getActionUpdateThread() = EDT`，与既有读选区同线程同性质；`update()` 里新增的读取未单独加锁。
6. **XML 属性值双引号**：`id="foo"` 会被取成 `"foo"`——「语言无关」的已知边界（§3.3），非缺陷但需记录。

## 八、实施记录（2026-10-13）

- 实施：`actions/SelectedTableSqlTemplateActions.kt` 删除 `[A15DBG]` 临时日志；`update()`/`actionPerformed()` 改走 `selectedOrCaretText(e)`；新增 `editorTemplateText` / `resolveTemplateText` / `identifierAtCaret` / `isIdentifierChar` / `scanSegmentRight` / `scanSegmentLeft` / `QUOTE_OPENERS` / `QUOTE_CLOSERS`。
- 测试：`src/test/kotlin/.../actions/SelectedTableCaretTextTest.kt`，28 用例。
- 定向读数：`./gradlew test --tests "cn.song.mybatisplus.visualizer.actions.SelectedTableCaretTextTest"` → `tests="28" skipped="0" failures="0" errors="0"`，BUILD SUCCESSFUL。
- 反向验证：`resolveTemplateText` 无有效选区时短路返回 null → `tests="28" ... failures="4"`，4 条退化用例全红（`test 无选区取光标处`、`test 空白选区退化到光标处`、`test 编辑器无选区取光标处标识符`、`test 编辑器空白选区退化取光标处`，均为 `expected:<car_device_info> but was:<null>`）；还原后复跑 28/0/0 绿。
- 未做：真机 / runIde / 全量测试 / 构建校验（沙盒正在录制，本轮约束）。

