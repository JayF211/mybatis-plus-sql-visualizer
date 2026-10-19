---
name: pitfall-console-error-signature-coverage
description: 预览面板错误条只认控制台文本里的短语词表——MySQL 语法错整类漏检（面板无红字无复制按钮，错误只在 Output 页），漏检后还落回「密码/驱动」错向归因
metadata:
  node_type: memory
  type: project
  originSessionId: sess_78a01635-8bac-41dd-8f6f-f47bae641c09
---

用户真机 2026-09-22 08:48 报：预览面板执行一条 MySQL 语法错 SQL，**错误只出现在控制台 Output 页**，面板下方红字 +「复制错误信息」按钮全程不出现。

**机制（关键前提，别只改词表）**：服务端 SQL 错误**不以 Java 异常穿过插件链**——`doRunQueryInConsole` / `executeQueries` 只要提交成功就正常返回，失败由 DBN 自己渲染进控制台。插件识别它的唯一手段是轮询扫描控制台历史文本（`execute/DataSourceSelector.kt` 的 `extractConsoleError`，由 600ms×8 拍与 400ms×40/100 拍两个 Timer 逐拍调用），判据原本是 14 条短语词表的 `contains`：`sql exception` / `syntax error` / `sqlstate` / `unknown column` / `unknown table` / `doesn't exist` / `does not exist` / `access denied` / `deadlock` / `error code` / `broken pipe` / `connection refused` / `sql（` / `exception in thread`。

用户那条 `[42000][1064] You have an error in your SQL syntax;` **一条都不含**（写的是 `SQL syntax;` 而非 `syntax error`；SQLSTATE 以数字形态 `[42000]` 出现，没有 `sqlstate` 这个词）→ 返回 null → `onError` 从未被调用。
**反证手法（可复用）**：插件一旦识别到错误，会同时往同一个 Output 写一行 `执行失败: …`；用户贴的文本里没有这一行 → 证明是「识别环节没过」而不是「显示了被后续回调覆盖」。

**同类漏检**：Oracle `ORA-00933`、达梦「无效的表或视图名」、MySQL 1062 `Duplicate entry`、1451 `foreign key constraint`。**命中系只有** `Unknown column` / `doesn't exist` / `does not exist`——而 M36.5 期间真机验证过的报错**恰好全在这一系**（留存读数是 `[42S22][1054] Unknown column 't.station_name'`），故缺口长期未现。**性质=既有覆盖面缺口，非回归**；用户「以前会显示」的印象很可能来自那类错误的成功记忆。

**漏检后果比「没显示」更糟**：16s 后走 40 拍兜底分支，`realError == null` 且未报过提示 → `execute.timeout.hint`（「SQL 已提交执行，但等待结果超时……请检查 IDEA 主窗口是否有密码输入框；若连接一直未建立……测试连接/下载驱动」）——**把一条语法错归因成密码/驱动问题**。旁证：`docs/变更记录.md` 2026-09-14 行（T-ExplainDbl）记的 `[42000][1064]` 那次，用户当时看到的就是「等待结果超时」。

**已实施修法（T-ConsoleErrorCoverage，master `23d2ede`）**：判据升级为「结构性判据 + 短语族」——`SQLSTATE_PREFIX` 正则 `\[[0-9A-Za-z]{5}]\[\d+]`（DBN 给服务端错误加的 `[SQLSTATE][errorCode]` 前缀，跨方言同形；**5 位定长是关键**，19 字符的时间戳前缀 `[2026-09-22 08:48:12]` 不会被误命中）+ `ORACLE_CODE` `ORA-\d{5}` + 6 条厂商报文，原 14 条逐字不动；加自写回显排除（`EXEC_FAILURE_ECHO_PREFIX` 与 `writeConsoleOutput` 共用常量，防自反馈）；摘录 4→8 行并逐行剥 `[时间戳]` 与 DBN 实读的提示符（`LanguageConsoleView.getPrompt`，243 源码 jar 实证 `var prompt: String?`）。**兜底呈现**：40 拍窗口到期且无判据命中时，把「新增文本减去本次 SQL 回显（剥前缀后是 SQL 子串即认）与状态/结果摘要行」后的残余交回面板，前缀新键 `execute.error.unknownShape`（中英）——不声称是 SQL 错误、不猜原因。
**反例红线**：DML 与 `use` 这类工具语句**成功时也不建结果 Tab、同样走满 40 拍窗口**，故状态摘要词表刻意偏多剔（`completed in` / `affected` / `query ok` / `\b(rows?|records?|updated|deleted|inserted)\b`）——把 `1 row affected` 当残余报出来等于把成功说成失败。

**How to apply**：改这类「文本判据」前先问「这条报文在真实驱动上长什么样」——拿用户贴的原文逐字喂判据矩阵（本次即靠此定位）；判据优先认**结构性形态**（SQLSTATE 前缀）而非厂商措辞，措辞随版本/语言变。反向验证必做：摘掉新判据 → 新覆盖用例必红、反例仍绿（本次 13 例中 2 失败，点名恰为两个新用例）。相关：[[project-pending-plans]]、[[pitfall-verification-answer-must-state-evidence-limit]]。

**顺带观察（既有行为，未修）**：`use mpj_demo` 这类无结果 Tab 的语句会走满 40 拍窗口并落回 `execute.timeout.hint`（把成功执行的工具语句说成超时）——独立议题，未立项。
