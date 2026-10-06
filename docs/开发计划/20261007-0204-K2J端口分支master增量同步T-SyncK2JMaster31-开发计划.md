# K2J 端口分支 master 增量同步（T-SyncK2JMaster31）开发计划

- 日期：2026-10-07 02:04
- 分支：`feature/kotlin-to-java`（工作树 `mybatis-plus-sql-visualizer-kotlin-to-java`，tip `73a6bf3`，与远端零债、工作树净）
- 模式：**同步功能，不合并分支**（用户定案）——master 的 .kt 绝不进生产树；共享文件走手工 union/直取
- 锚点：`.port-sync` synced_master = `94c71e8` → 目标 master tip `7ffc642`，**31 笔增量**
- 用户指令：先计划后实施 + 解决冲突 + 全量测试并修复 + 插件构建校验通过

## 1. 增量清单与内容族（git diff --name-status 94c71e8..master 全清单亲证）

### A. SQL 日志性能族（T-SqlLogPerf，含 master 单独回流 cherry-pick 四笔）

生产 5 件：

| master 文件 | 增量 | 内容 |
|---|---|---|
| `log/LogPerfProbe.kt` | **新建** +38 | object 单例 + `internal inline fun <T> measure(name, detail={}, block)`，阈值 100ms 触发 INFO `[T-SqlLogPerf]` |
| `log/SqlLogFileReader.kt` | 42 行改 | 解析单遍化（joinToString 三份拷贝→行序列直传 parseBlockLines）+ readFromFile 打点 |
| `log/HistorySqlLogPanel.kt` | 137 行改 | 浏览态 refresh 惰性重读（(lastModified,length) 快照比对）+ 二级列表滚动追加（appendPendingForTest 测试缝） |
| `log/SqlLogPanel.kt` | 129 行改 | 「滚动到底部」ScrollToEndAction + 尾部跟随 + 实时重建链防抢占 + refreshList 打点 |
| `log/SqlLogEntryBuffer.kt` | 74 行改 | dedupeKey→最近时间戳 O(1) 索引六面（入队 upsert/头部淘汰回扫/dedupeExisting 重建/开关翻 true 补账/clear 清空/removeEntry 单键重算） |

测试 3 件：`SqlLogEntryBufferDedupeIndexTest.kt` **新建** 113 行 / `HistorySqlLogPanelNavigationTest.kt` 语义改写（pump-EDT 分块→滚动追加）/ `HelpDialogTest.kt` 工具栏组断言 6→7（cherry-pick 回流适配笔）。

### B. JPA 链式参数族（T-JpaParamOrder F1~F6 + 复合词误报修复）

生产 6 件：

| master 文件 | 增量 | 内容 |
|---|---|---|
| `psi/jpa/HqlQueryLocator.kt` | 50 行改 | F1 收集序按 argumentList 偏移（textOffset）归位 + F2 同名去重保序 |
| `converter/jpa/HqlConverter.kt` | +10 | Result 增加 `placeholderParamNames: List<String>`（HQL 命名参数首现序） |
| `model/PreviewResult.kt` | +12 | 增加 `placeholderParamNames: List<String>` + `duplicateParamNames: Set<String>` |
| `ui/SqlPreviewService.kt` | 104 行改 | F3 渲染/复制按名回填（出口保证：位置参数混入置空回退按序）+ F4 createQuery 结果类型 Class 不当实体 + F5 写重灰字提示 + F6 setParameter 字面量/枚举常量自动预填 |
| `ui/SqlPreviewPanel.kt` | 35 行改 | backfillSources()（按名回填源）+ 写重提示 + 参数占位启发式复合词误报修复（locationStatus 不再误配集合占位） |
| `ui/components/CopyExecutableSqlAction.kt` | +3/-1 | `panel.unresolvedParams()` → `panel.backfillSources()` |

测试 3 件：`integration/JpaChainParamBackfillTest.kt` **新建** 412 行 / `psi/jpa/HqlQueryLocatorChainOrderTest.kt` **新建** 128 行 / `ui/SqlPreviewPanelParamKindTest.kt` +13。

### C. 资源与发行面（v2026.10.7 发行同步 `8ce8e08` 的仓库内部分）

- `gradle.properties`：version 2026.10.6→**2026.10.7**
- bundle 双语 +4（log.panel.scrollBottom + tip 等，以实际 diff 为准）
- `src/main/resources/html/marketplace-custom-page.html`（演示动图内联+文案精简轮产物）/ `gradle/plugin-change-notes.html` / `update/preview.update.json`
- 无 plugin.xml、无 build.gradle.kts、无 buildSrc 变更（name-status 亲证）

### D. docs 轮（约 20 件）

非四册直取：开发计划 12（11 新增+1 改）/ 调研报告 2 新增 / 发行说明 3（1 新增+2 改）。
四册（k2j 有专属行或需对账）：`CHANGELOG.md` / `docs/MEMORY.md` / `docs/变更记录.md` / `docs/memory-archive/README.md`。

## 2. 波次与分工

- **波 A（子代理）**：log 族生产 5 件 .kt 增量 → Java 翻译（含 LogPerfProbe.java 新建）
- **波 B（子代理）**：jpa/ui 族生产 6 件 .kt 增量 → Java 翻译
- **波 T（子代理）**：测试 6 件直取 + Kotlin→Java 互操作适配（并发 3 路即达上限，测试子代理不跑 gradle，主控中央编译收口）
- **主控**：资源直取（先 diff 校验 k2j 侧相对 base 零专属改动）+ 四册三方合并（base/ours/theirs 全取 git blob，坑 18）+ 其余 docs 逐文件直取（**禁目录级 checkout**，Master22 新坑）+ 中央编译 + 门禁

## 3. 提交链八段（惯例）

C1 计划（本笔）→ C2 构建链（版本号）→ C3 波 A → C4 波 B → C5 资源 → C6 测试（波 T）→ C7 文档 → C8 收官（锚点推进 7ffc642 + note 重写 + 计划 §8 回填 + 变更记录台账行 + push）

## 4. 门禁方案

- gradle 前置：`python C:\Users\USER\.zcode\cli\parallel_tasks.py check` → register `t-synck2jm31`（沙盒 `test-sandbox-k2jsynkm31`）；沙盒临时行跑完还原不随提交入库
- **全量 testShards**（默认 5 片，单路收口；跑前 `timeout 10 docker info` 验 daemon；禁降片）
- agent 子项目测试基线对账（上轮 93/0）
- **verifyGate 三目标**（243/253/262 Compatible，用户要求的构建校验）
- **buildPlugin** → `mybatis-plus-sql-visualizer-2026.10.7.zip`
- testDatabase（docker daemon 活则跑，驱动种子链先确认 k2j 树本位在位）
- gradle 真实退出码一律 `; echo EXIT=${PIPESTATUS[0]}` 取值（坑 21，二度复现过）；判绿=BUILD SUCCESSFUL+报告 XML 计数双证

## 5. 待验证前提（实施者先证伪再动手）

1. ~~k2j 侧 11 个对应 Java 文件在场~~（已 ls 实证全在场；`LogPerfProbe.java` 为新建）
2. `HqlConverter.Result` / `PreviewResult` 在 k2j 侧的 Java 形态（class+构造器重载？builder？record？）——翻译前必读现文件，字段追加保持既有形态；master 侧默认参数值在 Java 需落显式重载或全参构造器同步调用点
3. `LogPerfProbe.measure` 调用点是否全部落在本轮已改文件内（grep master 侧调用点；若溢出到未改文件须一并适配）；inline+默认参 → Java 落两个静态重载 `measure(name, block)` / `measure(name, detail, block)`，object → 私有构造 final 工具类（k2j 惯例：枚举/常量限定形态写法，坑 9）
4. master 侧 `SqlPreviewPanel.backfillSources()` 是否取代/保留 `unresolvedParams()`——以 master HEAD 全文为准，不凭 diff 臆断
5. 直取测试件互操作三查（坑 23 固定自查）：具名实参→位置实参 / record 解构→访问器 / 函数类型**变量**赋值→显式 `java.util.function.Xxx` 构造；另查 `.isXxx` 布尔属性形态（坑 20）
6. `HistorySqlLogPanelNavigationTest` 滚动追加语义依赖 `appendPendingForTest` 测试缝——k2j 侧 `HistorySqlLogPanel.java` 须同步提供该缝（master .kt 里是 internal？可见性翻译口径）
7. fatal 守卫基线 674 预计前进（本轮 SqlLogPanel/SqlPreviewService 等新增 try/catch 站点）——**以守卫实测为准走完三层登记**（坑 24），grep 行计数不预判
8. `HelpDialogTest` k2j 侧当前断言=6 项（回流适配笔 `1efbfb8` 在锚点后），直取 master 版即 7 项——对账基线读数先取证
9. 四册三方合并 base=`git show 94c71e8:<file>`（不是工作树 CRLF 副本，坑 18）；回写前与 push 前各再查一次 k2j 分支是否被他會话前进；CHANGELOG/发行说明类先验证 k2j 侧相对 base 零 diff 则整册直取（Master29 先例）
10. bundle/html/update.json 等资源件同样先 `git diff 94c71e8..feature/kotlin-to-java -- <file>` 验零专属后直取

## 6. 风险预置（系统性坑摘引，全文见项目记忆 project-kotlin-to-java-port-sync）

具名实参禁用 / object→INSTANCE 或工具类 / data class 无 copy / 受检异常透传 sneakyThrow（fatal 透传必须 `throw (Error) t` 勿「修正」）/ 私有字段名遮蔽 / 默认参数落显式重载 / 守卫测试扫描根 java 化 / elvis 双层 null 合并 / 枚举常量限定产出点 / **`@Service` 轻服务类必须显式 final** / jsqlparser 静默 null 判空 / Kotlin 属性初始化器引用主构造参数→构造器内装配 / Kotlin 函数类型字段落 Function0/Function1 / union tmp 文件按册隔离

## 7. 验收口径

- 全量 testShards 0 失败（skip 桶读数与上轮 8 对账，漂移如实登记）
- verifyGate 三目标 Compatible（对上轮逐项 243=4+15+46、253=6+18+44、262=7+73+44 零漂移）
- buildPlugin 2026.10.7 zip 产出
- 四册 union 后 k2j 专属行 byte-exact 校验全存活
- 锚点推进 `7ffc642` + note 重写为本轮记录；push 与远端零债

## 8. 实施记录回填（2026-10-07 收官）

### 8.1 提交链七段

C1 `3a4d89b` 计划 → C2 `cad6f9f` 版本 2026.10.7 → C3 `213c2f2` 波A log 族 5 件 → C4 `b588f61` 波B jpa/ui 族 6 件+跨文件适配 3 处 → C5 `8b025ce` 资源 → C6 `756a291` 测试 6 件 → C7 `ebade40` 文档 → C8 本笔（锚点推进+台账+本节回填+沙盒行还原验证）。

### 8.2 待验证前提逐条回执

| 前提 | 回执 |
|---|---|
| 1. k2j 侧对应文件在场 | ✅ 实证全在场；LogPerfProbe.java 新建 |
| 2. Result/PreviewResult Java 形态 | ✅ PreviewResult=final class+伸缩构造器链（非 record）；HqlConverter.Result 保留 getXxx()/xxx() 双访问器。**衍生修复笔见 8.3** |
| 3. LogPerfProbe 调用点 | ✅ master 全仓 4 处（SqlLogFileReader:70/HistorySqlLogPanel:500/SqlLogPanel:1478+定义）全在本轮清单内，无溢出；object→final 工具类+measure 双静态重载落地 |
| 4. backfillSources/unresolvedParams 关系 | ✅ master 全文证实**并存不取代**：unresolvedParams 仍有 SqlShareManager×3+ShareDialog×1 共 4 调用方，原样保留；仅 CopyExecutableSqlAction 切换 |
| 5. 直取互操作三查 | ✅ SqlLogEntryBufferDedupeIndexTest 8 处适配（具名实参→位置 7 处+LogSqlEntry 4 参便捷构造器 1 处）；其余两新建件 blob 级一致免适配取证 |
| 6. appendPendingForTest 测试缝 | ✅ 波 A 落 public（注明 master 为 internal），波 T 核对调用形态逐字匹配 |
| 7. fatal 基线 674 | ✅ 本轮 **零新增** catch(Throwable) 站点（两波子代理分文件自报+守卫实测维持），基线 674 不动 |
| 8. HelpDialogTest 基线 | ✅ k2j 侧 6 项取证（原 28 行断言），直取 master 版 7 项 |
| 9. 四册合并 | ✅ CHANGELOG/归档 README 零专属整册直取；MEMORY/变更记录 merge-file rc=0 零冲突，k2j 专属 8+19 行存活、master 增量 2+1 行在场；ledger 4 行 2026-09-14~19 差异=分支基线固有（ours vs theirs 实证）维持现状 |
| 10. 资源零专属 | ✅ 9 件 diff --stat 空（零输出）后 restore 直取；bundle 双语新 key grep 3 处验证 |

### 8.3 修复笔（一笔）

**PreviewResult 23 参历史构造重载恢复**：波 B 把 23 参规范构造器扩为 25 参（追加 placeholderParamNames/duplicateParamNames），只保全了 20/21/22/9 参伸缩链；但 k2j 测试树 `SqlPreviewPanelStackDigestTest.makeResult` 以 23 参位置实参构造（历轮 K2J 互操作适配形态，注释自证），compileTestKotlin 首轮 5 处编译红。根因=波 B 构造点扫描只 grep 了 Java 形态 `new PreviewResult(`（.java 树 33 处全对账），**漏了 .kt 测试树的 `PreviewResult(` 形态**——坑 23「直取≠免适配」的构造点扫描变体。修复=恢复 23 参重载委托 25 参传 `List.of()/Set.of()`（master 侧新字段带 Kotlin 默认值，历史构造形态天然合法，Java 等价即保留重载），带 K2J 互操作注释。复跑 compileJava+compileTestKotlin 全绿。

### 8.4 门禁终态

| 项 | 读数 | 对账 |
|---|---|---|
| 中央编译 | compileJava 一次过；compileTestKotlin 首轮红→修复后绿 | EXIT=0（PIPESTATUS 真值） |
| testShards | **6202/0/0/9**（5 片 1197/1321/1194/1375/1075） | 一次全绿；+56 例=本轮新增测试（JpaChainParamBackfillTest 等）；skip 9 回历史基线（上轮 8 系漂移） |
| testExternal | 40/0/0/1 | 全绿 |
| agent | **93/0**（20 套件） | =基线；UP-TO-DATE 清 `sub-projects/sql-visualizer-agent/build` 真跑（include 名与 projectDir 分离，先清错路径白清一次——新坑记录） |
| verifyGate | 三目标 Compatible 零漂移 | 243=4+15+46、253=6+18+44、262=7+73+44 与上轮逐项相等 |
| buildPlugin | mybatis-plus-sql-visualizer-2026.10.7.zip **6,858,569 字节** | +10,172 vs 上轮 2026.10.6=功能增量 |
| testDatabase | 复跑全绿 **10/0/0/0** | 首轮 3 例 `UnsatisfiedLinkError: no freetype`=transform 争用家族环境性假失败（Master70 同款 3 例），复跑排除；docker 29.6.2 主控补启 |

### 8.5 过程坑与遗留

- **坑 23 变体入册**：互操作构造点扫描必须同时覆盖 `.java` 的 `new X(` 与 `.kt` 的 `X(`（测试树），下轮直取/翻译固定自查项沿用。
- **agent 子项目路径坑**：`settings.gradle.kts` 里 include 名 `:sql-visualizer-agent` 与 projectDir `sub-projects/sql-visualizer-agent` 分离，清 build 清错路径不报错白清一次；UP-TO-DATE 时「清 build 真跑」须清真实 projectDir 下的 build。
- freetype 假失败家族第三次复现（Master70→本轮），处置恒定=复跑排除，不入代码修复面。
- 沙盒临时行 `test-sandbox-k2jsynkm31` 已还原（build.gradle.kts 与 HEAD 一致零 diff），未随提交入库；并行表 `t-synck2jm31` 随 C8 注销。
- 遗留观察项：真机走查随下轮 runIde 批（滚动到底部/尾部跟随/写重提示/F6 预填四处 UI 新交互）。
