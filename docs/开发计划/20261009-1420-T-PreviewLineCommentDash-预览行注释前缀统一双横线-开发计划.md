# T-PreviewLineCommentDash：预览编辑器行注释前缀统一为 `--`（不随 MySQL 方言映射产出 `#`）

> **来源**：用户 2026-10-09 真机报障——预览自建编辑器内注释快捷键插入 `#` 前缀，对 PostgreSQL
> 执行报 `[42601] syntax error`（PG 不认 `#` 行注释）。用户澄清 `#` 来自注释快捷键。
> **生成日**：2026-10-09；调查=子代理 H（file:line+平台字节码三互证）；计划落盘即排实施（用户令「生成计划并排队」）。
> **占号**：O-134/R80/D136~D137/V159~V160（引用前实测：O 最高 O-133 / R 最高 R79 / D 最高 D135 / V 最高 V158）。

## 一、根因（全链实证）

1. **预览文件被强制映射 MySQL 方言**：预热线程建临时文件于 `<project>/.idea/sql-visualizer/preview/`
   （SqlPreviewPanel.kt:885-891），PSI 创建前 `mapPreviewDialectBeforePsi`（:732）→
   `DialectPreMapping.ensureMapped`（DialectPreMapping.kt:91-119）EDT 反射
   `SqlDialectMappings.setMapping(目录, MySQL)`（:62-83）；映射对象是父目录（:942-949，平台逐级查表），
   幂等短路 `shouldSkip`=「已配置为 MySQL」（:42-43）。
2. **编辑器语言=MySQL** → 平台注释动作取 commenter 链：`CommentByBlockCommentHandler.getCommenter`
   → `psiFile.getLanguage()` → `LanguageCommenters.forLanguage(language)`（261 jar 字节码实证）；
   DatabaseTools 注册 `lang.commenter language="MySQL" → MysqlCommenter`，javap 实锤
   `getLineCommentPrefix() = "#"`；generic/PG 系无专属 commenter 落 `SqlCommenter` = `"--"`。
3. 降级链第二级内存 PSI（:3509-3516）不带映射走基础 SQL 语言= `--`——**只有主路径产出 `#`**，与现象吻合。

## 二、方案（D136=推荐 B3：EditorActionHandler 链式覆写 + userData 作用域透传）

- **否决 A（注释前缀跟随数据源方言）**：映射在预热 BGT 早于数据源绑定，跟随方言要绑定时二次
  setMapping（EDT 写 + SqlDialectPusher push-all-dumb 风暴，DialectPreMapping KDoc 记录的断言雷区）；
  映射目录级持久化 workspace.xml 只增不减，shouldSkip 硬编码需重写；PSI 方言牵动补全/exec 解析/
  scriptModel，把预览翻成 PG 反劣化 MySQL 味捕获 SQL 的解析。
- **否决 B1（lang.commenter EP 覆盖 MySQL）**：语言级全局生效，波及真实 MySQL 控制台/文件。
- **否决 B2（Swing InputMap 接管）**：Ctrl+C 当年能成是因为 `$Copy` 不可用落回 Swing；Ctrl+/ 的
  keymap 动作可用，IdeKeyEventDispatcher 直接走 action，InputMap 接不住。
- **B3 实现**：新增 `util/EditorCommentShortcuts.kt`——`install(editor)` 打标（userData KEY）+
  引用计数 `EditorActionManager.setActionHandler(IdeActions.ACTION_COMMENT_LINE, handler)`；handler
  对未打标编辑器直通原 handler（零行为差异）；打标编辑器执行自有逐行切换：行首 `--` → 剥；
  行首 `#` → 同样剥（修复前残留行兼容，双认先例 MysqlClientGSuffix.kt:99-104）；否则插 `-- `。
  WriteCommandAction 包裹；最后打标编辑器 dispose 恢复原 handler。
- **打标两处**：预览自建编辑器 `assemblePreviewEditor`（:3340 附近，已有 putUserData 先例）+
  原生控制台编辑器 `onNativeEditorReady`（:1830 附近——exec 文件同样被映射 MySQL，控制台注释
  同样产 `#`，一并覆盖）。
- **D137：Ctrl+Shift+/ 块注释（`/* */`）不接管**——MySQL/PG 块注释语义一致，缩小作用面。

## 三、实施步骤

- S1 新增 `util/EditorCommentShortcuts.kt`（打标+引用计数+未打标直通+`--`/`#` 双认逐行切换；
  测试缝照 `EditorCopyShortcuts` 传 lambda 风格）。
- S2 `SqlPreviewPanel.kt` 两处打标 + releaseEditor/dispose 路径减计数。
- S3 单测（纯逻辑）：comment/uncomment/`#` 残留剥除/多行选区/幂等/未打标透传（mock 前一 handler
  断言被调）。
- S4 反向验证：临时注释两处打标 → 快捷键恢复插 `#`（用例红），还原插 `--`（绿），贴读数。
- S5 计划 §八回填 + CHANGELOG 一行。

## 四、验证方案

定向单测 `--tests` 指定新测试类；runIde 沙盒（留走查）：绑定 PG → Ctrl+/ 插 `--` → 执行成功 →
再 Ctrl+/ 取消；控制台编辑器同验。

## 五、待验证前提（实施者先证伪）

- **P1** `EditorActionManager.setActionHandler(String, EditorActionHandler)` 与
  `EditorActionHandler.execute(Editor, Caret, DataContext)` 在 243/262 双目标签名——javap 双版本
  jar 核对（261 已确认 API 家族存在）。
- **P2** `IdeActions.ACTION_COMMENT_LINE` 常量值（"CommentByLineComment"）双版本一致。
- **P3** 平台字节码基于 261.25134.95；`com.intellij.lang.commenter` EP 十年稳定，`MysqlCommenter`
  类名跨版本若有出入以双版本 javap 为准。
- **P4** 块注释不接管前提「MySQL/PG 块注释均 `/* */`」——javap `MysqlCommenter.getBlockCommentPrefix`
  复核。

## 六、实施记录

实施：t-linedash 工作树（基线 a1d32be），2026-10-09，提交 d672855（实现 + 单测 + CHANGELOG）。

### P1~P4 证伪结论（javap 双版本）

- **P1 签名证实、但运行性被推翻（本轮最重要读数的来源）**：签名层——`EditorActionManager.setActionHandler(String, EditorActionHandler): EditorActionHandler`
  （返回值=原 handler）在 243（`lib/app-client.jar`）与 262（`lib/intellij.platform.ide.jar`）一致；
  `EditorActionHandler.execute(Editor, Caret, DataContext)` 为 `public final void`，可覆写钩子 =
  `protected doExecute(Editor, Caret?, DataContext?)` 与 `protected isEnabledForCaret(Editor, Caret, DataContext)`。
  **但该 API 对本任务的动作 id 不可用**（详见下节「层位前提被推翻与改造」）——P1 原判据只核了签名，
  漏核「该 id 的动作是不是 `EditorAction`」，故不成立。
- **P2 证实**：`IdeActions.ACTION_COMMENT_LINE = "CommentByLineComment"` 双版本常量池一致
  （243 `app-client.jar` / 262 `intellij.platform.ide.core.jar`）。
- **P3 证实**：`MysqlCommenter` 类名 `com.intellij.sql.dialects.mysql.MysqlCommenter` 双版本一致
  （243 `plugins/DatabaseTools/lib/database-plugin.jar`；262 `plugins/DatabaseTools/lib/modules/intellij.database.dialects.mysqlbase.jar`；
  仅父类包名迁移）；两版本常量 `SHARP_LINE_PREFIX = "# "` / `SHARP_LINE_PREFIX_NO_SPACE = "#"` 在场——`#` 根因实锤。
- **P4 证实**：两版本 `MysqlCommenter` 的带参 `getBlockCommentPrefix(int, Document, Holder)` 属 SelfManagingCommenter 面；
  无参 `getBlockCommentPrefix()` 在其基类 `SqlCommenter`，javap -c 字节码 `ldc "/*"`、`getBlockCommentSuffix()` `ldc "*/"`
  双版本一致 → D137「块注释不接管」前提成立。

### 层位前提被推翻与改造（D136 修正）

- **推翻证据（三互证）**：
  1. 注册实锤：243 `app-client.jar!idea/LangActions.xml`（262 同级 `product.jar!META-INF/plugin.xml`）把
     `CommentByLineComment` 注册到 `com.intellij.codeInsight.generation.actions.CommentByLineCommentAction`；
     `keymaps/$default.xml` 的 Ctrl+/ 绑的就是该 id。
  2. 继承实锤（javap）：`CommentByLineCommentAction extends MultiCaretCodeInsightAction`——**不是** `EditorAction`。
  3. 实现实锤（javap -c）：`EditorActionManagerImpl.getActionHandler/setActionHandler` 字节码为
     `ActionManager.getAction(id) → checkcast EditorAction` ⇒ 对 `ACTION_COMMENT_LINE` 调用**必抛 `ClassCastException`**。
  4. 单测首跑实证：改造前那一版 `EditorCommentShortcutsTest` **15/15 全红**，栈顶即
     `ClassCastException: …CommentByLineCommentAction cannot be cast to …EditorAction`（`EditorActionManagerImpl.getActionHandler`）。
  ⇒ 原 D136（B3＝handler 链式覆写）**层位不可用**；且旧实现会在预览装配时抛异常（**回归风险**，非仅功能失效）。
- **改造（修正层位）**：改用**动作注册层**——`ActionManager.replaceAction(IdeActions.ACTION_COMMENT_LINE, 自有 AnAction)`
  （双版本 javap 签名 `replaceAction(String, AnAction)` 一致）。落地前先跑一次性探针测试实证「换入生效 + 读回为自有动作 +
  可原样换回 + 自建 DataContext 可驱动」，探针用完即删（不留仓）。
- **自有动作** `DashLineCommentAction : DumbAwareAction(), LightEditCompatible`——与原动作的两标记对齐
  （否则换入后在索引/轻编辑态被平台整体禁用，Ctrl+/ 会在**所有**编辑器里失效=回归）；对打标编辑器走本地逐行切换，
  其余 **update/actionPerformed 双向委托**原动作，并在构造时复制原动作模板 presentation（同 id 换入，菜单文案/
  图标/键位（按 id）不退化）；EDITOR 缺失或未打标一律委托（与平台原动作 `e.getData(EDITOR)` 取用路径同构）。
- **未变部分**：打标（userData）/引用计数/卸载复原语义、`toggleLines` 逐行切换本体（`--` 插入、`--`/`#` 双认剥除、
  只吞一个空格、整段同态切换、多光标自底向上）、WriteCommandAction 一步撤销（bundle 键 `preview.lineComment.commandName` 双语）
  ——S1 其余设计与 S2 接线全部照旧。

### S1~S5 实施情况

- **S1 完成**：新增 `util/EditorCommentShortcuts.kt`（按上述修正层位落地）。
- **S2 完成**：`SqlPreviewPanel.kt` 打标两处——`assemblePreviewEditor`（自建编辑器）+
  `onNativeEditorReady`（控制台编辑器，换装先对旧编辑器减计数）；减计数四路——
  `disposeCurrentEditorOnly`（自建编辑器唯一释放收口）、`detachConsole`、`clear`、`dispose`，全部幂等。
- **S3 完成**：`EditorCommentShortcutsTest` 15 例（纯逻辑 5 + 打标接管/未打标双向委托 2 + 注册表链路 8），
  驱动方式＝`ActionManager.getAction(ACTION_COMMENT_LINE)` 解析后以**自建 DataContext** 驱动 actionPerformed/update
  （与 keymap 取用链路同源；裸编辑器组件不提供 EDITOR——探针实证 `editorInCtx=false`，故测试显式带 EDITOR）；
  另新增 `PreviewLineCommentWiringTest` 2 例（面板真实构造链）——这是**原测试面的空白**：只验接管件本体、
  不验「面板有没有把编辑器交进接管件」，S4 的红点就落在它上面。
- **S4 完成**（三段反向验证，读数见下节「验证读数」）：R1 第一处打标点有红点；R2 第二处打标点**无轻测覆盖**（如实登记）；
  R3 换入动作本身为载荷（非空绿）。
- **S5 完成**：CHANGELOG `[Unreleased]` 加 Fixed 一行。

### 验证读数

- 编译：`./gradlew compileKotlin compileTestKotlin`（in-process 策略，避开与并行会话共抢 Kotlin daemon）——绿。
- **定向测试**（`--tests EditorCommentShortcutsTest --tests PreviewLineCommentWiringTest`，独立沙盒 `linedash-sb`，
  期间另一会话 t-firstexec 在跑）——**17 例全绿**：`EditorCommentShortcutsTest tests=15 failures=0 errors=0 skipped=0`、
  `PreviewLineCommentWiringTest tests=2 failures=0 errors=0 skipped=0`。中途修掉两处自身缺陷：`install` 内
  `installedHandler` 字段类型收窄（编译红）、`test纯逻辑_补前缀保缩进` 期望值漏写前导缩进（1 红）。改造前那一版曾
  **15/15 全红**（即 D136 层位被推翻的那次实证）。
- **S4 反向验证（三段，逐段还原后复绿）**：
  - **R1**（注掉 `assemblePreviewEditor` 的 `install(created)`）→ 17 例 **2 红**，恰为接线类两例，失败断言原文
    `装配的预览编辑器必须已打标——未打标时 Ctrl+/ 落到平台 MySQL commenter 插 \`#\`（本轮缺陷）`、
    `前置：面板编辑器应已打标` → 还原后绿。**第一处打标点有红点**。
  - **R2**（注掉 `onNativeEditorReady` 的 `install(consoleEditor)`）→ 17 例**仍全绿**：**该打标点无轻测覆盖**
    （原生控制台编辑器需真数据源，轻测里不构造）。这是覆盖边界、不是「通过」——该点只能由 runIde 走查兜底，
    勿按「两处打标都验过」引用。
  - **R3**（注掉 `install` 内的 `replaceAction` 换入本身）→ 17 例 **7 红**（6 util：机制/计数/复原/多行/混合/`#` 残留
    + 1 接线）→ 证明新层位确为载荷、非 no-op 空绿 → 还原后绿。
  - 最终复绿：三段全部还原 + 新文件 CRLF 归一后重跑 → **17 例全绿**（0 失败 0 错误 0 跳过）。
- runIde 沙盒走查（绑定 PG → Ctrl+/ 插 `--` → 执行成功 → 再按取消；控制台编辑器同验）：**留走查，未跑**——
  控制台打标点无轻测覆盖，此走查是它唯一的验收口。
