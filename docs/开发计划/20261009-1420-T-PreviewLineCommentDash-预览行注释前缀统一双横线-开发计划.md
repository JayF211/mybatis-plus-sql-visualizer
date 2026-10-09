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

（空——实施启动后回填）
