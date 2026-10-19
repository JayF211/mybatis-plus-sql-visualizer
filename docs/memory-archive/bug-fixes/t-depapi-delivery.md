---
name: t-depapi-delivery
description: T-DepApi 已交付——三处弃用 API 换现代等价物（master 08d75c4），三平台弃用 40→38；剩余四项 javap 实证无等价替换留档
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5361d041-6f37-4c2c-9d97-515b95c961df
---

T-DepApi 已交付（2026-09-09，master 08d75c4）：verifyPlugin 弃用 API 渐进清债第一轮。三处替换均有 243/253/262 三平台 javap 实证（transform 缓存解包 dist 路径：`~/.gradle/caches/9.7.0/transforms/*/transformed/idea-*-win`，`lib/util-8.jar`(243/253) 与 `lib/intellij.platform.core.jar`(262) 是核心 API 权威）：

- `StdLanguages.JAVA` → `com.intellij.lang.java.JavaLanguage.INSTANCE`（java 插件 jar：243 `plugins/java/lib/java-frontback.jar` / 253 `java-impl-frontend.jar` / 262 `intellij.java.frontback.psi.jar`；plugin.xml 已依赖 com.intellij.java）
- `FilenameIndex.getFilesByName(project, name, scope)` → `getVirtualFilesByName(name, scope)`（**无 project 重载**才不弃用，有 project 参数的全部弃用；返回 VirtualFile 后 PsiManager.findFile 转 PsiFile）
- `Notification.setListener(URL_OPENING_LISTENER)` → `notification.addAction(BrowseNotificationAction(text, url))`（点击动作开浏览器，正文 `<a href>` 保留默认行为）

**Why：** 弃用清理受「243 编译基准 + 三平台运行」约束——262 的替代 API（如 `ReadAction.computeBlocking`）243 不存在，编译期用不了；`SlowOperations.startSection` 首行 `if(!EDT.isCurrentThreadEdt()) return EMPTY`（反编译实锤），EDT 上 `allowSlowOperations(GENERIC)` 是空壳。

**How to apply：** 剩余四项经 javap 三平台核实**均无等价交集替换**，勿再浪费时间找：① `ReadAction.compute(ThrowableComputable)`（33 处，243/253 ok 仅 262 deprecated，替代 computeBlocking 262-only）；② `TransactionGuard.submitTransactionLater`（整类全平台弃用，无替代）；③ `TextAttributesKey.createTextAttributesKey(id, TextAttributes)`（固定自定义配色场景，`createTempTextAttributesKey` 语义不符——temp key 不持久）；④ `Messages.showChooseDialog(Project,...)`（三平台全弃用无替代）。全量 3173 绿 + verifyPlugin 复跑 Compatible（243 弃用 5→3 / 253 8→6 / 262 40→38，experimental 10→8）。相关：[[t-wia262-delivery]] [[pitfall-toolcall-newline-escaping]]
