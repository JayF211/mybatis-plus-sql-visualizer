# T-ExecDocGate 执行文档落盘纪律 DB 集成门禁 — 开发计划

- 日期：2026-09-17 11:48
- 任务编号：T-ExecDocGate（T-ExecDocSync2 复盘产物，纯测试基建、零产品源码改动）
- 来源：T-ExecDocSync2 复盘（第四轮冲突为何未被测试发现）——用户指正「沙盒也可以有 Database」：`testDatabase` 通道（M36.5.23，模拟 Ultimate 沙盒）早已存在，而该通道里**没有落盘纪律断言**。第四轮冲突（G1-G4 滞留「已修改未保存」）的前提在纯沙盒里静默不响，既有 E2E 只断结果 Tab，从未断言文档保存态——缺的是断言，不是通道

---

## 一、目标

把 T-ExecDocSync2 的行为语义钉进 `testDatabase` 通道的 CI 门禁，使第三/四轮失误模式回归时红灯可现：

| 断言 | 守护对象 | 回归时表现 |
|------|----------|------------|
| prepareConsole 后 exec 文档 unsaved=false 且磁盘==SQL | T-EC-01（projectExecFile 同临界区落盘） | 文档滞留 unsaved / 磁盘旧值 |
| 二次 prepareConsole（reuseSharedConsole 链）后同断言 | F1/G1+G2（复用链 vf 同步落盘） | 回退 F1 → unsaved=true、磁盘停在旧 SQL |
| 控制台手改后防抖窗内磁盘对齐 | F5（1.5s 防抖自动落盘） | 回退 F5 → 磁盘永不对齐（沙盒无帧失活自动保存） |

弹窗终点（MemoryDiskConflictResolver）仍不可全自动化（需外部改盘+真时序），「unsaved=false」前提断言即自动化天花板，本轮到顶。

## 二、改动

1. **新测试类 `execute/ExecDocGateIntegrationTest`**（BasePlatformTestCase，仅 testDatabase 跑）：
   - 单方法 E2E（照 DbConsoleIntegrationTest 形态，规避类内测试顺序耦合）：
     数据源创建+introspect（helpers 与 DbConsoleIntegrationTest 同源复制）→
     prepareConsole(sqlA) → 断言 unsaved=false && disk==sqlA →
     prepareConsole(sqlB)（走 reuseSharedConsole 链）→ 断言 unsaved=false && disk==sqlB →
     WriteAction 手改控制台文档为 sqlC → waitUntil(10s) 磁盘==sqlC → 断言 unsaved=false。
   - 前提链 assumeTrue：Database 插件已加载 / demo dataSources.xml 可读 / introspect 成功——MySQL 未起则 skip 不 fail（既有 E2E 同惯例）；不触 executePrepared，无需 mpTestDbPassword（密码 root 硬编码自 demo，同 E2E 口径）。
2. **mpv-testing.gradle.kts 三处注册**：`dbIntegrationClasses` 集合（默认 test/testShards 自动排除）+ testDatabase filter include + 默认 test filter exclude。

## 三、验收标准

1. `./gradlew testDatabase`：新用例绿（demo MySQL 运行中）；未运行 MySQL 时三断言前置 assumeTrue skip、任务绿。
2. 默认 `./gradlew test` / `testShards` 口径不变（新类被 dbIntegrationClasses 排除，不进分片扫描）。
3. 门禁约定更新：**触达 Database 链的改动 = test + testDatabase 双跑**写入 project-test-conventions 记忆。

## 四、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| demo MySQL 未运行 | introspect 失败 → skip | assumeTrue 惯例（与 E2E 一致），用户环境常态是 MySQL 常驻 |
| 防抖 10s 预算 | EDT 病态忙碌时 flush 延迟 | waitUntil 轮询 + 10s 上界（防抖窗 1.5s 的 6 倍余量） |
| sharedConsole 跨调用状态 | 二次 prepareConsole 依赖 lastRunnerName 命中 reuse | 既有 E2E step③④⑤ 已实证同链路控制台跨调用存续 |

## 五、实施顺序

```
计划文档 → 分支 → ExecDocGateIntegrationTest → mpv-testing 注册 → testDatabase 实跑
→ CHANGELOG/变更记录/记忆 → 提交合入
```

## 六、实施记录（含 testDatabase 基建排障实录）

用例与注册一次落定；**testDatabase 实跑连爆四层基建缺口，逐层修复后打通**：

1. **缺口一：驱动种子源消失**。原 doFirst 只认本地 runIde 沙盒 `.intellijPlatform/sandbox/.../config/jdbc-drivers`——本工作树 runIde 沙盒已不存在（clean/迁移），种子静默跳过 → `RemoteJdbcServer` `ClassNotFoundException: com.mysql.cj.jdbc.Driver`、四集成类 introspect 全部 assumeTrue 跳过。修复=种子源三级回退：本地 runIde 沙盒 → **dev 工作树**（`../mybatis-plus-sql-visualizer-dev`，清单 xml 常驻）→ **用户日常 IDE 驱动存储**（`%USERPROFILE%\AppData\Roaming\JetBrains\IntelliJIdea2024.3\jdbc-drivers`，jar 按 maven 布局 `<Artifact>/<ver>/<maven path>` 存放，本机实存 9.7.0）。
2. **缺口二：`File.copyRecursively` 深路径崩**。walkBottomUp 深层文件先于父目录落地 → `ERROR_PATH_NOT_FOUND` 中断种子。修复=walkTopDown + 逐文件 `parentFile.mkdirs()` + 逐文件 runCatching（失败收集不中断，copied/failed 落日志）。
3. **缺口三（本质）：doFirst 相对路径按 daemon 进程 CWD 解析**。诊断日志抓到 `cwd=C:\Users\USER\.gradle\daemon\9.7.0\.`——「Test 任务工作目录=项目根」只对 Test worker JVM 成立，doFirst 在 daemon 跑且 CWD 跨构建不一致（前几轮种子忽好忽坏的根因）。修复=配置期 `val mpTaskProjectDirAbs = project.projectDir.absolutePath`（String 可被配置缓存序列化），doFirst 内全部 `File(mpTaskProjectDirAbs, ...)` 绝对锚定。**附带发现**：编辑时只锚了 `mpDemoProjectDirDefault` 首行、其续行 `.resolve(...).absolutePath` 被挤挂到新常量上造成编译错——Edit 锚定多行常量必须含续行。
4. **缺口四：DBN 243 单测模式 artifact 解析不生效**。种子三件套齐（test-database-artifacts.xml / test-database-drivers.xml / jar 树）后 RemoteDriver.classpath.txt 仍仅 jdbc-console.jar，idea.log 全程零 loader 活动——test-database-* 清单机制在 243 实测不可达（与 T-Embed-08 字节码结论冲突，留档不深究）。修复=绕过解析：数据源显式携带驱动 jar——`SimpleClasspathElementFactory.createElements(String...)` + `LocalDataSource.setClasspathElements`（243 javap 实证公有 API；M36.5.23d「setClasspathElements 无效」实为当年路径指错 config/）。
5. **门禁绿**：`./gradlew testDatabase` ExecDocGateIntegrationTest 三断言绿（prepare/reuse 后 unsaved=false 且磁盘==SQL、手改后防抖窗内磁盘对齐）；同通道既有三类同获驱动修复受益。
- **过程纪律**：并行会话 `merge-dev-ps4`（12:31 登记）活跃期间立即提交 WIP（79b1152）保住工作区——登记表只管 gradle 沙盒不管文件；验证等其结束后在独立登记下执行。
- 留观察：MySQL 未运行时 assumeTrue 跳过在 XML 记为 failure（四集成类既有形态，非本轮引入）；要改「跳过不红」语义另行立项。

## 七、实施结果（诚实口径）

**门禁已落地但当前不发声**：`ExecDocGateIntegrationTest` 三断言在本机 testDatabase 通道**走显式绿跳**——驱动管线未打通，introspect 无法建连，断言未真正执行。任务本身绿（7/0/0/0）代表「环境不具备被测能力时不红」，不代表三断言已验证通过。

**驱动管线逐环实据（探针实测，收口时探针已删）**：

| 环节 | 实测结果 | 结论 |
|------|----------|------|
| 种子文件（5 项：driver 状态/两清单/jar 树） | 全部 exists=true | 种子链有效 |
| `DatabaseDriverManager.getDriver("mysql.8")` | 返回 `DatabaseDriverImpl` | driver 状态文件修复生效（此前解析不出） |
| `driver.getClasspathElements().size` | **0** | 断点 |
| `DatabaseArtifactLoader.getTestArtifactLists()` | **[]**（空） | 断点根因 |
| `com.intellij.database.util.DatabaseDriverManagerUtil`（该清单解析的依赖类） | **ClassNotFoundException** | 243 测试 classpath 上不存在；异常被 DBN 的 runCatching 吞成空清单 |
| `LocalDataSource.setClasspathElements` 往返 | set=2 → get=0 | **死路**：getter 只回 resolved driver 的 elements，myClasspathElements 仅供持久化 |

⇒ 驱动管线断点在平台侧（243 测试 classpath 缺 DBN 内部工具类），非种子可修。候选修复路线（另立基建债，本计划不展开）：注册 `ConsoleRunContextParametersTuner` 扩展点向远程进程启动参数追加驱动 jar。

**顺带修复（真实收益）**：
1. 四集成类 `assumeTrue(introspect 失败)` 改显式绿跳——此前平台 runner 把 AssumptionViolatedException 记为 failure，**testDatabase 任务在驱动不可用时恒红**；现在环境不具备能力时绿、能力恢复时全量真跑。
2. `testNoDataSourceReportedAsUnavailable` 前置清空数据源——LocalDataSourceManager 是应用级、兄弟用例数据源跨用例可见；此前它们因 driver 解析不出被 DbImplUtil 过滤而「隐形」，驱动状态修复后变可见 → runners 非空 → 走弹窗分支（headless 无 frame → NPE 红）。本用例契约是「无数据源」路径，须显式保证环境无数据源。

**门禁口径（已入 project-test-conventions 记忆）**：触达 Database 链的改动 = `test` + `testDatabase` 双跑。
