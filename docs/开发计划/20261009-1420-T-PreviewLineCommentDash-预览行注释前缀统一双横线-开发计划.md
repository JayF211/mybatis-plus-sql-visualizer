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

### P1~P4 证伪结论（javap 双版本，全部证实，无一被推翻）

- **P1 证实**：`EditorActionManager.setActionHandler(String, EditorActionHandler): EditorActionHandler`
  （返回值=原 handler）在 243（`lib/app-client.jar`）与 262（`lib/intellij.platform.ide.jar`）签名一致；
  `EditorActionHandler.execute(Editor, Caret, DataContext)` 为 `public final void`（不可覆写、可直调做链式委托），
  可覆写钩子 = `protected doExecute(Editor, Caret?, DataContext?)`（caret/dataContext @Nullable）与
  `protected isEnabledForCaret(Editor, Caret, DataContext)`（三参 @NotNull），双版本一致——Kotlin override
  可空性照此落签名。
- **P2 证实**：`IdeActions.ACTION_COMMENT_LINE = "CommentByLineComment"` 双版本常量池一致
  （243 `app-client.jar` / 262 `intellij.platform.ide.core.jar`，javap -constants 读数）。
- **P3 证实**：`MysqlCommenter` 类名 `com.intellij.sql.dialects.mysql.MysqlCommenter` 双版本一致
  （243 `plugins/DatabaseTools/lib/database-plugin.jar`；262 `plugins/DatabaseTools/lib/modules/intellij.database.dialects.mysqlbase.jar`；
  仅父类包名迁移：243 `com.intellij.sql.psi.impl.support.SqlCommenter` → 262
  `com.intellij.database.sql.common.core.psi.impl.support.SqlCommenter`）；两版本常量
  `SHARP_LINE_PREFIX = "# "` / `SHARP_LINE_PREFIX_NO_SPACE = "#"` 在场——`#` 根因实锤。
- **P4 证实**：块注释均 `/* */`——两版本 `MysqlCommenter` 的带参 `getBlockCommentPrefix(int, Document, Holder)`
  属 SelfManagingCommenter 面；无参 `getBlockCommentPrefix()` 在其基类 `SqlCommenter`，javap -c 字节码
  `ldc "/*"`、`getBlockCommentSuffix()` `ldc "*/"` 双版本一致 → D137「块注释不接管」前提成立。

### S1~S5 实施情况

- **S1 完成**：新增 `util/EditorCommentShortcuts.kt`——`install` 打标（userData Key
  `mpsv.editor.lineCommentDash`，重复打标幂等）+ `setActionHandler(ACTION_COMMENT_LINE, DashLineCommentHandler)`；
  handler 对未打标编辑器直通原 handler（`originalHandler.execute(...)`，零行为差异），打标编辑器走
  `toggleLineComments`：受影响行集=选区触及行/光标行（多光标各算各的、自底向上套用），整段同态切换
  （全已注释→剥；否则补 `-- `，已注释行幂等跳过），`--`/`#` 双认剥除只吞前缀后**一个**空格，
  WriteCommandAction 一步撤销（命令名走 bundle 新键 `preview.lineComment.commandName` 双语）；
  `uninstall` 引用计数归零恢复原 handler。测试缝照 EditorCopyShortcuts：纯逻辑 `toggleLines` 与
  `DashLineCommentHandler` 均 internal（单测免平台 action 分发）。
- **S2 完成**：`SqlPreviewPanel.kt` 打标两处——`assemblePreviewEditor`（自建编辑器）+
  `onNativeEditorReady`（控制台编辑器，换装先对旧编辑器减计数）；减计数四路——
  `disposeCurrentEditorOnly`（自建编辑器唯一释放收口）、`detachConsole`、`clear`、`dispose`，全部幂等。
- **S3 编码完成 / 执行待门禁后补跑**：`EditorCommentShortcutsTest` 13 例（纯逻辑 5 + 全局注册表链路 8），
  驱动方式 = `getActionHandler` 解析后直调（与 keymap 取用路径同源）；未打标直通用例以 RecordingHandler
  mock 前一 handler 断言被调。**compileKotlin + compileTestKotlin 已绿**（首轮报
  `:81:81 Unresolved reference 'originalHandler'`——`installedHandler` 字段类型从 `EditorActionHandler`
  收窄为 `DashLineCommentHandler` 后过）；执行轮撞主控 t-batchmerge 合并态门禁（登记表两次 check 均在跑），
  按并行纪律不抢跑不轮询，**定向测试读数待门禁后补跑**。
- **S4 待门禁后补跑**：反向验证路径已内建——`test打标编辑器_无选区注释光标行插双横线` 等用例从全局
  注册表解析 handler；补跑时临时注释 `assemblePreviewEditor`/`onNativeEditorReady` 两处打标（或 install 内
  `setActionHandler` 一处）→ 用例落到平台原 handler、`-- ` 断言必红 → 还原绿，贴两次读数。
- **S5 完成**：CHANGELOG `[Unreleased]` 加 Fixed 一行。

### 验证读数

- 编译：`./gradlew compileKotlin compileTestKotlin`（in-process 策略，避开与门禁共抢 Kotlin daemon）——绿。
- 定向测试 `--tests "cn.song.mybatisplus.visualizer.util.EditorCommentShortcutsTest"`：**待门禁后补跑**（读数回填此处）。
- S4 反向验证：**待门禁后补跑**（读数回填此处）。
- runIde 沙盒走查（绑定 PG → Ctrl+/ 插 `--` → 执行成功 → 再按取消；控制台编辑器同验）：留走查，未跑。
