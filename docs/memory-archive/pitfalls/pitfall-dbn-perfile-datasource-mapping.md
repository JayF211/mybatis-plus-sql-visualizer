---
name: pitfall-dbn-perfile-datasource-mapping
description: DBN 的 .idea/data_source_mapping.xml 把 exec 文件钉到某个数据源，控制台库上下文随之而定——与插件按数据源名选的 runner 错位时表现为 42S22/1054 跨库同名表报错；已修（T-ConsoleDbContext：库对齐到数据源 URL 库 + 错误上下文行）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_598c1639-3398-416c-ac31-3c129bbc5bca
---

20260917 用户报「预览窗口执行失败、IDEA 原生控制台同 SQL 正常」：`[42S22][1054] Unknown column 'u.username' in 'field list'`。**SQL 没写错，是执行落到了另一个库。**

**证据链（四步可复用）**：
1. 报错形态本身就指向「表里有 id、没有 username」——别名 `u` 解析成功、表也找到了，只是那张表缺列（`u.id` 通过而 `u.username` 报 1054）。
2. DB 直查（dbx MCP「本地 | MySQL」→ `information_schema.COLUMNS`）：`jpa_demo.user` = id,username,email,first_name,last_name,age,status,department_id,create_time；`mpj_demo.user` = **id,name,age,sex,head_img**（无 username）→ 与报错逐字吻合。
3. 截图/控制台提示符给出库上下文：预览控制台左侧库下拉 = `mpj_demo`，右侧数据源 = `jpa_demo@localhost`（连接是 jpa_demo，库却是 mpj_demo）。
4. **谁把库钉成 mpj_demo**：demo 工程 `.idea/data_source_mapping.xml` 里
   `<file url=".../.idea/sql-visualizer/exec/mpsql-exec.sql" value="56965ec0-…"/>`，该 UUID 属 **`mpj_demo@localhost`** —— DBN 记住「mpsql-exec.sql 用 mpj_demo 数据源」。

**机制**：插件执行链按**数据源名**选 runner（日志 `silent reuse last runner: jpa_demo@localhost`，SQL 文本也已核对正确），而 DBN 控制台的**库上下文**来自这份**文件级映射**；两者错位时 SQL 在该库解析未限定表名 → 命中同名但列不同的表 → 1054。原生控制台正常是因为它不经这份文件映射。该映射是工程内持久配置（可存在很久），只在「切到另一个数据源做预览」时才暴露。

**How to apply**：
- 用户报「同样的 SQL 在原生控制台能跑、预览里不能跑」时，先比「库上下文」（控制台提示符/库下拉 + `.idea/data_source_mapping.xml`），别从 SQL 文本或方言找原因。
- **已修（T-ConsoleDbContext，20260917 当日交付并真机通过，master d51cc0a 合入 d894e6b）**：用户定案语义 = **预览默认以「数据源 URL 里的库」为准；用户在控制台手调库后，数据源记忆照常保留**；方案 = A+B（C 未做）。A=`extractConsoleError` 追加 `[当前库=…，数据源=…，数据源默认库=…]`（裸 1054 报文看不出库选错）；B=控制台就绪两处（`prepareAfterEditorResolved`、`reuseSharedConsole`）调 `ExecConsoleContext.alignToDataSourceDatabase`——`JdbcUrlDatabase.nameFromUrl`（纯函数，表驱动 11 例）解析 URL 库 → 与 `SearchPath.current` 比对，**一致即 no-op（不覆盖用户手选）** → 不一致 `switchSchema(SearchPath.of(ObjectPath.ROOT.append(db, ObjectKind.DATABASE)), false)`，未生效退回 `setSearchPath` 并读回校验。
- **对齐所依赖的 API 面（243 jar 反编译实证，可复用）**：`JdbcConsole.getDataSource()/getSearchPath()/setSearchPath()/switchSchema(SearchPath, boolean)`（后两者：`switchSchema` 带事务与越界校验）；`SearchPath.of/getCurrent/getDisplayName`、`ObjectPath.ROOT.append(String, ObjectKind)`、`ObjectNamePart.name`（public final 字段）。
- **映射本身没有可用写入口**：`SqlDataSourceMappings` 只读，per-file mappings 的服务类在 243 全 jar 扫描中**找不到**——所以修法是「显式切库」而非「改映射」。
- 相关：[[pitfall-console-doc-is-file-doc]]（同一份「挂文件」结构的另一半）、[[project-pending-plans]] 的待决条目、[[pitfall-log-first-not-code-reasoning]]。
