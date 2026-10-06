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
