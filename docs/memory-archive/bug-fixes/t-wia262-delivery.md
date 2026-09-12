---
name: t-wia262-delivery
description: T-Wia262 已交付——WriteIntentReadAction 重载 262 移除致 verifyPlugin
  失败（市场发布受阻）修复；javap 权威必须覆盖目标平台版本；update.json 已回退待发行重生成
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7095f938-1245-4858-abfa-08abdb6f604b
---

T-Wia262 已交付（2026-09-08，master 44f77d0 fix + e73a338 docs）：`SqlPreviewPanel.createEditor` 的 `WriteIntentReadAction.compute(ThrowableComputable)`（T-PreviewWiLock 27ccfcf 引入）在 262 平台已移除，verifyPlugin 全量三版本 BUILD FAILED 阻断 2026.9.8 市场发布；换三版本交集重载 `compute(Computable)`（单类型实参消歧）后 test 3131 全绿 + verifyPlugin 复跑 BUILD SUCCESSFUL。

**Why：** ① T-PreviewWiLock 当时只 javap 核验了 243 编译基准——该重载 243/253 存在、262 移除，243 编译目标编译期发现不了，只有 verifyPlugin 对 262 的字节码级校验能暴露；② 262 平台该类方法签名变化：`run(ThrowableRunnable)` 更名 `runThrowable`、新增 `computeThrowable`、删 `compute(ThrowableComputable)`。

**How to apply：** ① javap 权威核实必须覆盖**全部目标平台版本**（243/253 在 `lib/util-8.jar`，262 在 `lib/intellij.platform.core.jar`；注意 Git Bash `unzip -l` 对部分 IDE jar 静默失败，用 python zipfile 扫类位置）；② 发布前必跑 `./gradlew verifyPlugin` 全量（build/check 同链，262 报 COMPATIBILITY_PROBLEMS 即市场发布被拒同款）；③ 遗留不阻断项：262 报 4 处 scheduled-for-removal（`SlowOperations.allowSlowOperations(String)`×2 / `StdLanguages.JAVA` / `DbPsiFacade.findElement(DasObject)`）+ 40 deprecated，javap 实证 262 仍在，后续立项渐进迁移；④ 发布状态：`update/update.json` 被用户回退至 2026.9.4（8cfcac7），`gradle.properties` 仍 2026.9.8——发行时按发行工作流重新生成清单。计划文档：`docs/开发计划/20260908-1857-T-Wia262-*`。相关：[[t-dscover-t-clickpreview05-delivery]]
