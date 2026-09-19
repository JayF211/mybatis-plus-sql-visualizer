# T-ConsoleDbContext 预览会话库上下文对齐与诊断 — 开发计划

- 日期：2026-09-17 14:55
- 任务编号：T-ConsoleDbContext
- 来源：用户报告——同一 SQL 在预览窗口执行失败（`[42S22][1054] Unknown column 'u.username' in 'field list'`），在 IDEA 原生控制台正常，数据核对无误
- 用户决策（20260917）：**方案 A + B**；语义定案=**预览默认以「数据源 URL 里的库」为准**，用户在控制台自行调整后的数据源选择下次照常保留
- 取证：真实 IDE 2024.3 `idea.log` + demo 工程 `.idea` 配置 + 本机 MySQL 元数据实测

---

## 一、根因（数据实证，非推断）

| 证据 | 内容 |
|---|---|
| 报错形态 | `u.id` 未报错、`u.username` 报 1054 → 别名解析成功、表找到了，但该表没有 `username` 列 |
| 本机 MySQL 元数据 | `jpa_demo.user` = id,username,email,first_name,last_name,age,status,department_id,create_time（SQL 要的表）；**`mpj_demo.user` = id,name,age,sex,head_img（无 username）** |
| 用户截图 | 预览窗口左侧库下拉=**mpj_demo**，右侧数据源=`jpa_demo@localhost` |
| 工程配置 | `.idea/data_source_mapping.xml`（2026-09-11 写入）把 exec 文件映射到 UUID `56965ec0…` = **`mpj_demo@localhost`** |
| 插件日志 | runner 选择正确（`silent reuse last runner: jpa_demo@localhost`）、SQL 文本正确（`console doc synced…saved to vf`） |

结论：**SQL 没错，库上下文错了**。DBN 按「文件→数据源」映射（及库记忆）决定控制台的当前库，
我们的 runner 选择只决定连接的数据源；两者不一致时，`FROM user` 就解析到另一个库的表 → 1054。
原生控制台不经该文件映射，故正常。

## 二、方案（A+B）

### A 诊断可见
`extractConsoleError` 提取错误时追加一行上下文：`[当前库=<SearchPath.current>，数据源=<名>，数据源默认库=<URL 解析>]`
——裸 MySQL 报文看不出「库选错」，带上即一眼归因。

### B 库上下文对齐
控制台就绪（`prepareAfterEditorResolved` 与 `reuseSharedConsole` 两处）即调用
`ExecConsoleContext.alignToDataSourceDatabase(console)`：
1. 取 `console.dataSource.url` → 解析默认库名（[JdbcUrlDatabase.nameFromUrl]）；
2. 与 `console.searchPath.current.name` 比对，**一致即 no-op**（不覆盖用户后来的手选）；
3. 不一致时 `switchSchema(SearchPath.of(ObjectPath.ROOT.append(db, ObjectKind.DATABASE)), false)`，
   未生效则退回 `setSearchPath(...)`。

**API 出处（243 平台 jar 反编译，非猜测）**：`JdbcConsole.getDataSource/getSearchPath/setSearchPath/switchSchema`、
`SearchPath.of/getCurrent`、`ObjectPath.ROOT/append`、`ObjectNamePart.name`（public final 字段）。

**隔离**：新对象直接引用 DBN 类，只在 Database 插件链内调用（同 `DbTableSpecAdapter` 惯例），
全部入口 runCatching 兜底（异常退化为「不改动、不附加」，绝不影响主链）。

## 三、测试

- `JdbcUrlDatabaseTest`：URL→库名表驱动（含事故现场两条真实 URL、分号参数形态、无库段、Oracle thin、空白/null）。
- 对齐与诊断需 DBN 类（默认 test 无 database jar），走真机走查（§五）；逻辑已用纯函数测试钉死输入判据。

## 四、验收标准

1. 全量 `test` 绿 + `testDatabase` 绿（DB 链双跑口径）。
2. **真机复验**：预览该 SQL → 日志出现 `console db-context: 库上下文对齐：mpj_demo → jpa_demo（数据源=jpa_demo@localhost，URL 库=jpa_demo）`，执行成功；控制台左侧库下拉显示 jpa_demo。
3. 用户在控制台手调库后，再次打开预览 → 数据源选择保留；库若与 URL 库不同则按定案回到 URL 库（对齐只在两者不一致时触发一次）。

## 五、真机走查清单

1. 触发场景（demo 工程 jpa_demo 数据源）预览并执行 → 成功，`console db-context:` 行显示 mpj_demo → jpa_demo 的切换。
2. 手动把控制台库切到 mpj_demo → 再次预览同一 SQL → 应回到 jpa_demo（数据源 URL 库）。
3. 断网/无库权限等异常路径：对齐失败只落日志，不影响预览与执行主链。
4. 故意执行错列 SQL（如 `SELECT u.nonexistent FROM user u`）→ 错误区应带 `[当前库=…，数据源=…，数据源默认库=…]` 行。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 覆盖用户库选择 | 用户在控制台手选的库会被拉回 URL 库 | 定案如此（URL 库为准）；仅在与 URL 库不一致时触发一次，且数据源选择仍按记忆保留 |
| URL 解析失误 | 解析错库名会把会话切到不存在的库 | 表驱动测试钉死形态；解析不出即跳过（不猜）；切换后读回校验，失败退回不动作 |
| 切换时机 | 事务中切换被 DBN 拒绝（switchSchema 带事务校验） | 对齐发生在控制台刚就绪（无事务）；失败仅落日志 |
| DBN 版本差异 | 反射/类形态跨版本变动 | 全部 runCatching + 类名/方法以 243 反编译为准；跨版本签名护栏（PlatformSignatureCrossVersionTest）新增反射入口按惯例登记 |
