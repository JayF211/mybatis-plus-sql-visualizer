---
name: pitfall-dataclass-signature-clean-rebuild
description: Kotlin data class 增减字段后旧测试类触发 NoSuchMethodError——必须 clean 重建
metadata:
  node_type: memory
  type: project
  originSessionId: sess_9e5ca82c-6f90-4edd-83de-ff5b686eb544
---

**BUG**：给 `LogSqlEntry`（data class）新增 `source: SqlSource` 字段、给 `ParserConfig` 新增 Hibernate 配置字段后，运行 `./gradlew test` 报 67 个 `NoSuchMethodError`。

**根因**：data class 的 `copy()`/`componentN()` 方法签名随字段变化。旧编译的测试字节码引用旧签名（如 `copy$default(String, String, List, Long, String, int, Object)`），运行时找不到匹配方法。

**解决**：`./gradlew clean test`（clean 清除旧编译产物，强制全量重编）。clean 后 2012 测试全绿。

**Why:** Kotlin data class 编译器为每个字段顺序/数量生成特定的 `copy$default` 方法。增量编译只重编修改的文件，不重编引用旧签名的测试类。

**How to apply:** 任何 data class（`LogSqlEntry`/`ParserConfig`/`PluginSettings.State` 等）增减字段后，必须 `clean test` 而非直接 `test`。这是本仓库高频踩坑点——`PluginSettings.State` 每次新增配置字段同理。