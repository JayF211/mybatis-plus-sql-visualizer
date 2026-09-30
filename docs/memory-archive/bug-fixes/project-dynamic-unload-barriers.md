---
name: project-dynamic-unload-barriers
description: 插件无法动态卸载的根因分析（2026-08-20）——自定义语言/FileType 注册 + 静态缓存 + 生命周期缺失
metadata:
  node_type: memory
  type: user
  originSessionId: sess_c1f1dc68-dd23-45ab-80e5-ea7b222a538b
---

## 问题

插件卸载后 IDEA 仍显示插件存在，必须重启才能完全移除。用户预期安装/更新/卸载均免重启生效。

## 根因分析（按严重程度排序）

### P0（阻塞级）：自定义语言 + 自定义文件类型注册

`plugin.xml` 注册了：
- `lang.parserDefinition language="MP_SQL_Preview"`（SqlParserDefinition）
- `completion.contributor language="MP_SQL_Preview"`（SqlCompletionContributor）
- `fileType name="MP_SQL_Preview" extensions="mpsql"`（SqlPreviewFileType）

这些属于平台**结构性注册**——写入全局 `Language` 和 `FileTypeManager` 注册表，**不支持运行时移除**。即使服务全部 dispose、classloader 释放，平台仍持有这些注册项的引用，插件 JAR 无法被卸载。

这是当前卸载失败的**首要原因**。

### P1（高危）：EntityResolver 静态缓存持有 PsiFile 引用

`EntityResolver.companion.cache`（`ConcurrentHashMap<EntityCacheKey, Entry>`）是类级别静态字段，持有 `EntityCacheKey.file: PsiFile` 引用。即使所有 Project 服务 dispose，静态 Map 仍被 EntityResolver 类（由插件 classloader 加载）持有，PSI 对象无法 GC → classloader 无法释放 → 插件无法卸载。

`clearCache()` 方法存在但仅在 `PluginCacheManager.clearAll()` 中调用（设置页手动点「清除缓存」），没有接入服务生命周期。

### P1（高危）：PluginCacheManager 未接入服务生命周期

`PluginCacheManager.clearAll(project)` 清除 `EntityClassFinder` 和 `EntityResolver` 的缓存，但仅在设置页「清除缓存」按钮触发。项目关闭或插件卸载时没有自动触发缓存清理。

### P2（中等）：SqlPreviewService 未实现 Disposable

`SqlPreviewService.cache`（WrapperCache）持有 PsiFile 引用，但未实现 `Disposable`，项目关闭时不会显式清空。

## 修复方案概要

1. **移除 `lang.parserDefinition` + `fileType` EP 注册**（P0），改用内置 SQL 语言高亮
2. **EntityResolver 静态缓存 → project 级 Disposable 服务**（P1）
3. **PluginCacheManager 升级为 project 级 Disposable**（P1），`dispose()` 自动 clearAll
4. **SqlPreviewService 实现 Disposable**（P2），显式清理 WrapperCache

## 方案决策记录（2026-08-20）

**P0 方案选择：方案 A（移除自定义语言）vs 方案 B（保留但标注限制）→ 推荐方案 A。**

理由：自定义语言是平台结构性注册，无法通过代码手段绕过；方案 B 等于放弃用户核心诉求。

**替代高亮策略：**
- Ultimate：`SqlInputEditor` 创建嵌入编辑器时改用内置 `SQL` 语言（`com.intellij.sql` 模块，`SqlFileType`），无需自定义语言注册
- Community：回落 `PlainTextFileType`（插件核心功能是 Wrapper 转 SQL，只读预览无需编辑补全）
- 保留 XML / Java 注解的 `completion.contributor`（它们注册在内置语言 XML/JAVA 上，不影响动态卸载）
- `SqlParserDefinition` / `SqlPreviewFileType` 可能废弃或改为非 EP 注册方式

## 补全能力保留决策（2026-08-20）

用户确认语法高亮和补全重要，要求保留。确认方案：

- `plugin.xml`：`completion.contributor language` 从 `MP_SQL_Preview` 改为 `SQL`（内置语言），保留补全注册
- `SqlInputEditor`：创建嵌入编辑器时用 `SqlFileType.INSTANCE`（内置 SQL，Ultimate 可用），Community 回落 `PlainTextFileType`
- `SqlCompletionContributor` 提供两层补全：
  - 关键字补全（SELECT/FROM/WHERE 等）— 内置 SQL 语言自带，需加守卫避免与普通 SQL 文件冲突
  - 实体感知补全（表名/列名/注释/位置感知/聚合函数 COUNT/SUM/MAX/MIN/AVG）— 插件独有价值，来自 `SqlCompletionContext`
- 需加守卫：只在预览编辑器（Editor userData 含 `SqlCompletionContext`）时才提供关键字补全，避免与普通项目 SQL 文件冲突

## 影响文件

- `plugin.xml`（删除 `lang.parserDefinition` + `fileType` + `completion.contributor language="MP_SQL_Preview"` 三行）
- `EntityResolver.kt`（移除 companion cache，改为委托）
- `EntityCacheService.kt`（新增，project 级 Disposable）
- `PluginCacheManager.kt`（改为 project 级 Disposable 服务）
- `SqlPreviewService.kt`（添加 Disposable 接口）
- `SqlPreviewPanel.kt` / `SqlInputEditor.kt`（改用内置 SQL 语言 / PlainText 回落）
- `SqlParserDefinition.kt` / `SqlPreviewFileType.kt`（可能废弃）

详细开发计划见 `docs/开发计划/20260820-12-动态卸载问题修复-开发计划.md`。

**Why:** 用户反馈插件卸载后仍需重启，根因是自定义语言/FileType 平台注册无法动态移除 + 静态缓存阻止 classloader GC。
**How to apply:** 方案 A 已推荐，待用户确认后实施。移除自定义语言后用内置 SQL 语言替代高亮，Community 回落纯文本。

## 关联：T-DynUpdate 免重启热替换已交付（2026-09-11，本文件动态卸载知识的延续）

- 动态安装主路径已落地并随 v2026.9.11 发行（详见归档 t-dynupdate-plan）。**新增反射入口必须同步登记 `PlatformSignatureCrossVersionTest`**（243/253/262 三套发行版签名核验，反射入口漂移本地即炸）；「插件未完全卸载」警告经 hprof 归因=无泄漏纯 GC 时机，`Registry ide.plugins.unload.timeout` 默认 5000ms 超时即弹，调 30000 缓解，功能无影响。

## 关联：卸载窗口的 write-safe 资格（2026-09-22 报障分析，未改码）

自更新路径用 `invokeAndWait(..., ModalityState.any())` 从后台任务派发平台卸载 → ANY 排出的活动恒 write-unsafe，**卸载窗口本身被登记成写不安全**（write-safe 资格是「进入模态那一刻 myWritingAllowed 的快照」，不是模态自身属性）→ 窗口内平台自己的 commit/save、被 flush 的排队回调、我们自己的落盘都会记 `Write-unsafe context!`，模态串带 `[Unloading of SQL Visualizer]`。完整根因链、平台源码落点、两个触发分支与判别栈帧见 [[pitfall-unload-modality-write-unsafe]]。