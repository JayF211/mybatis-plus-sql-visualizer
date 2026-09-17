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
