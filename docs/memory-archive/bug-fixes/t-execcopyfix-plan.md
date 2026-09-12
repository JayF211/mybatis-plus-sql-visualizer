---
name: t-execcopyfix-plan
description: T-ExecCopyFix 已交付（master 27799c3，3180/0/6 绿）——复制错误对号闪变（CopyFeedback.flashCopied 文本按钮版）
  + 临时文件失败可见化（分级日志/WIRA/缓存存续）；F2b 后台化否决实录、K2J 陈旧类再犯、zipfleavor 剪贴板断言坑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2d16b36b-dec8-4671-bd93-6a23b5253389
---

T-ExecCopyFix 已交付（2026-09-09，master `27799c3`，8 文件 538+/28-）：复制错误对号反馈 + 执行临时文件失败修复。全量 3180/0/6 绿，verifySmoke 243 兼容（WIRA 报 experimental，与 T-PreviewWiLock 27ccfcf 同类，verdict Compatible）。

**交付内容**：① `CopyFeedback.flashCopied` 文本按钮适配版（图标换 Checked + 文案短暂换 `panel.copied`「已复制」，一次性 Timer 1500ms 恢复），接线 SqlResultPanel.errorCopyButton + SqlPreviewPanel 工具栏按钮（后者接线已随 T-PreviewFlash 7953aa2 先行入库，本会话初见到的「后台化」中间稿已被别的会话替换回 WIRA 版——落库的是 WIRA 版，与计划一致）。② createTempPsiFile 失败可见化：拆 runCatching，`fail(stage, detail)` 局部函数分级记录（writeFile/refreshVfs/findPsi + 异常摘要），`lastTempFileFailure` 供 `execute.tempFile.failed`（本地化带因 {0}）文案携带；projectExecFile 的 basePath null/mkdirs 忽略同样补日志。③ F2b refresh 段包 `WriteIntentReadAction.compute<T>` 单类型实参（T-Wia262 交集重载）。④ F2c clearPrepared 不清 cachedTempPsiFile（vf.isValid 守卫兜底）。⑤ 测试：CopyFeedbackUiTest(4) + ExecTempFileFixTest(3)，新增 ForTest 缝 cachedTempPsiFileForTest/markCachedTempPsiFileForTest/clearCachedTempPsiFileForTest。

**F2b 后台化否决实录**：曾实现 executeOnPooledThread + invokeAndWait(mapDialect) + EDT 有界等待产物的冷路径，自查发现互锁死锁——BGT invokeAndWait 等 EDT 回事件泵、EDT 阻塞在等待循环里等 BGT 产物，冷路径必超时。否决理由已写入 createTempPsiFile KDoc。教训：**跨线程交接若两侧都在"等对方"，先画等待图再写代码**。

**K2J 陈旧类再犯（[[pitfall-k2j-stale-classes-hijack]] 第二次实锤）**：定向测试 4 红全是"新代码没生效"——`build/classes/java/main` 里 57 个 Kotlin 编译产的陈旧类（ui 包无 Java 源却全量占位）排前压新，flashCopied 调用被旧类吞。清理法升级：只删与真实 `src/main/java` 源文件不匹配的类（history/log 两个包各留 1-2 个真 Java 源对应类，其余 `$` 附属类全删），比整目录删安全。

**测试坑两条**：① 系统剪贴板读 stringFlavor 可能抛 UnsupportedFlavorException（内容是 "stream" flavor）——断言剪贴板须 `runCatching().getOrNull()` 包裹 + 「不动剪贴板」断言改哨兵植入法（先 setContents 哨兵再断言原样保留），null 断言在真机剪贴板必假红。② bundle 文案断言别绑语言环境（沙盒随系统走 zh bundle），断言"占位参数出现 + {0} 不泄漏"而非英文前缀。

**提交拆分实录**：SqlPreviewPanel 工作树残差全归 refreshdbsteal 会话（rehost 监听治理），我的 flashCopied 接线已在 7953aa2；DataSourceSelector 残差全归本任务（153 行无 T-RefreshDbSteal 标记）。hash-object（**须 `-w` 写库，否则 update-index 引用悬空 fatal: unable to read**）+ update-index --cacheinfo 只提交 8 文件，build.gradle.kts 沙盒行不入库（refreshdbsteal 会话已改写成它的沙盒名）。注销用 `done --name execcopyfix-20260909`（有 refreshdbsteal 在途，裸 done 会误伤）。

关联：[[t-previewflash-plan]]（F2c 保缓存即保其跨点击吸收设计）、[[pitfall-k2j-stale-classes-hijack]]、[[parallel-session-index-commit-split]]、[[pitfall-toolcall-newline-escaping]]（parallel_tasks.py 路径反斜杠被吃，须正斜杠）。
