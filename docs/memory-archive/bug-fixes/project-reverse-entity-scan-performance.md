---
name: project-reverse-entity-scan-performance
description: SQL→Java 无调用类性能问题已修复（P0 反查缓存 + P1 启发式候选 + P2 扫描优化，1111 全绿）——O(N) 全量扫描降 O(1)，估算 100-500 倍
metadata:
  node_type: memory
  type: project
  originSessionId: sess_51eafb4b-6feb-4624-a097-590ca8ab7f68
---

2026-08-16 用户反馈：SQL→Java 反向转换**未选择调用类**时有性能问题（大项目明显卡顿）。根因分析 + P0+P1+P2 已实施交付（用户确认实施），`clean test --rerun-tasks` 1111 全绿。

**根因**：`EntityClassFinder.find`（psi/EntityClassFinder.kt）二级查找在快路径（类名推断候选）0 命中时**全量扫描项目类**（`PsiShortNamesCache.getAllClassNames()` 全部短名 + 每短名 `getClassesByName` + 注解 PSI 访问 + `inferDecoratedTableName`）。无调用类 = 无实体指定 → 表名推断必 0 命中 → **每次转换都全量扫**（O(项目类数)，几千类几百 ms~秒级）；JOIN 每副表各扫一次（N+1 次）；输入变化/切换形态反复触发。

**P0 反查结果项目级缓存**（核心）：
- 扫描结果缓存于 **Project userData**（跨 `EntityClassFinder` 实例复用）；失效 = `PsiModificationTracker.modificationCount`（PSI 文档修改递增 → 自动重扫一次）。
- 缓存内容 = `MatchedClass(shortName, qualifiedName)`——**类名字符串而非 PsiClass 引用**（PSI 对象随文件修改失效）；命中按 shortName 索引查询 + qualifiedName 精确比对（防同名类误配）。
- **负缓存**同样生效（无匹配表名）；「首次转换扫一次 → 之后全命中」。
- 失效重建后 `scans` 计数归零（新缓存实例）——测试断言「失效后重新扫描」用结果正确性而非计数。

**P1 启发式候选扩展**：`candidateClassNames` 增加**去首段变体**（`sys_account_role_rel`→`AccountRoleRel[Entity]`、`biz_banner`→`Banner[Entity]`，`tableName.substringAfter('_')` + 驼峰）。行为变化：`AccountRoleRelEntity` 这类由「反查命中」改为「快路径命中」（返回 1 个候选而非多候选弹窗）；resolve 表名校验防误配。

**P2 扫描优化**：跳过接口/枚举/注解类（`isInterface/isEnum/isAnnotationType`——MP 实体恒为 class）。

**性能数量级**（估算）：每次转换 PSI 索引访问从 **N 次降到 4~8 次**（启发式候选或缓存命中重取）→ 扫描部分约 **1/100~1/500**；触发面从「每次转换」降到「新表名首次 / PSI 修改后首次」。

**实证**：
- **`PsiModificationTracker` 在 IPGP 位于 `com.intellij.psi.util` 包**（`com.intellij.psi` 包版本被裁剪，编译实证；`PsiModificationTracker.getInstance(project).modificationCount`）。
- `FileBasedIndex` 无 `getModificationCount()` 方法（javap 实证）。
- **新建文件不递增 modificationCount**（文档修改才递增）——失效测试须用 `Document.setText` + `PsiDocumentManager.commitDocument`（VfsUtil.saveText 只改 VFS 不触发）。

**测试**：Heavy 15 用例——`scanCountForTest(project)` 内部扫描计数器断言「同表名二次 find 零扫描/不同表名独立条目」、文档修改失效重扫、缓存按全限定名重取防同名误配、启发式候选去首段变体、缓存信息与清除（`reverseLookupCacheInfo`/`clearReverseLookupCache`）。

**后续**：设置页缓存管理 UI（PluginCacheManager 统一入口 + 大小展示 + 清除反馈）见 [[project-reverse-sql-java-improvements]]。

相关：[[project-reverse-sql-java-improvements]] [[project-t30-deliveries]]（getAllClassNames 无参签名实证）[[pitfall-parallel-inflight-block]]
