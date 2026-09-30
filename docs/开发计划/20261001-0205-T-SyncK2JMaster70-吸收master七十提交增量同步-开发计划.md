# T-SyncK2JMaster70：吸收 master 七十提交增量同步 开发计划

- 立项时间：2026-10-01 02:05
- 分支/工作树：`feature/kotlin-to-java` @ `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java`（起点 25fcd97，与远端零债）
- 用户指令：同步 master 最近更新到 feature/kotlin-to-java（**同步功能，不合并分支**——k2j 定例），解决冲突、全量测试并修复问题、插件构建校验通过；先出本计划再实施。
- 登记名：`t-synck2jmaster70`，沙盒 `test-sandbox-k2jsync70`（跑 gradle 前 `parallel_tasks.py check` + register，收官 `done` + 还原沙盒临时行）。

## 一、背景与范围

上一轮 T-SyncK2JMaster187（2026-09-27）锚点钉在 `bccbfc5`。本轮量取 `git diff bccbfc5..master`＝**70 提交**，目标锚点＝**本地 master tip `32a7d99`**（origin/master 落后本地 11 笔；按「每轮必做」锚点钉实测 HEAD，11 笔未推送一并吸收）。

70 提交按内容分四块：

1. **生产 T-Embedded 族三连 + 发行面**（触达 src/main 仅 7 文件，+213/−24）：
   - `f3b7e47`（T-Embedded D1+D2）：JPA 嵌套点路径 @AttributeOverride + Spring Data @Embedded 形态与 prefix——落 `JpaMetadataReader`（isSpringDataEmbeddedField / springDataEmbeddedPrefixOf / isSpringDataEmbeddedAnnotation / flattenEmbeddedColumns 点路径键空间重构）+ `JpaEntityResolver`（SD 判定前置 + sdPrefix 拼列名）。
   - `c424353`（T-Embedded D3）：MP 轨混合注解实体（@TableName + jakarta @Embedded/@Embeddable/@EmbeddedId 共存）值对象字段展平为宿主表列——落 `EntityResolver`（resolveColumn→resolveColumns 返回 List + resolveEmbeddedColumns + compositeIdColumns 回填）。
   - `804331e`（fix T-Embedded）：派生查询嵌入段驼峰路径产出伪列——`TableInfo.columnByEmbeddedPath` 新方法 + `DerivedQueryConverter.resolveColumn` / `JpaQueryEnhancer.mapColumn` 接入。
   - `636ade0`（发行 v2026.10.6 预先整理）：plugin.xml 描述文案（9→11 方言）+ gradle.properties 版本号。
2. **测试 12 件**（+803/−263）：新建 `EntityResolverEmbeddedTest.kt`（181 行）与 helper `SignatureDistroDiscovery.kt`（102 行），其余 10 件增量（含签名测试三件重构与守卫三件扩容）。
3. **构建链 5 件**：gradle.properties（版本 2026.10.6 + T-TestPerf5/9 旋钮注记）、build.gradle.kts（T-TestPerf8 注释指引）、buildSrc `mpv-testing.gradle.kts`（testGc=serial / testProcCap / testGcThreads / testCiCount / testNoExplicitGc / testTieredStop 六旋钮 + T7 五片定例注记）、`sub-projects/sql-visualizer-agent/build.gradle.kts`（T-TestPerf8 agent 测试强制真跑）、plugin.xml。
4. **文档与外围约 48 件**：docs/开发计划 19、调研报告 13、经验总结 5、memory-archive 3、架构设计 1、CHANGELOG/README/MEMORY/变更记录、update/preview.update.json、gradle/plugin-change-notes.html。

## 二、同步策略

- **不 merge**：master 的 .kt 绝不进生产树；`.port-sync` 锚点收官推进到 `32a7d99` 并重写 note。
- **波次缩编决策**：生产增量仅 6 个 .java 文件（约 210 行语义迁移）且互为同族（JPA/MP 嵌入识别），不满足三波×3 子代理的规模前提（该结构为百提交轮设计）——本轮**主控直译一波收口 + 中央编译**，翻译坑位按 K2J 记忆 16 条预置自查；测试/文档主控逐件处理。此缩编记入实施记录。
- **结构性决策勿改**（上轮封存，本轮翻译不「修正」回去）：Java fatal 透传 `throw (Error) t` 两段式守卫（基线 660）；本轮改动不触 catch 面预期不变，收官时 fatal 站点计数复核（EntityResolver 新增 runCatching 一处——`resolveEmbeddedColumns` 内 `runCatching { flattenEmbeddedColumns }`，基线 660→661 预登记，守卫若判超基线按实登记）。

### 生产翻译映射（master .kt → k2j .java，全部已存在、增量修改）

| # | master 侧（.kt） | k2j 侧（.java） | 要点 |
|---|---|---|---|
| 1 | model/TableInfo.kt +15 | model/TableInfo.java | 新增 `columnByEmbeddedPath`（embeddedParent + 首字母大写 fieldName 拼接匹配） |
| 2 | psi/jpa/JpaMetadataReader.kt +81 | psi/jpa/JpaMetadataReader.java | SD @Embedded 三方法 + flattenEmbeddedColumns 点路径键空间（rootKeyPrefix/pathPrefix 双层查找：根相对→相对→裸名兜底） |
| 3 | psi/jpa/JpaEntityResolver.kt +19 | psi/jpa/JpaEntityResolver.java | SD 判定前置 + sdPrefix 参数（默认空串→Java 重载或显式双参调用点全改） |
| 4 | psi/EntityResolver.kt +98 | psi/EntityResolver.java | resolveColumns 返回 List + resolveEmbeddedColumns + embeddedIdColumns 回填（TableInfo 构造器 Java 位置参数序先读再写） |
| 5 | converter/jpa/DerivedQueryConverter.kt +17 | converter/jpa/DerivedQueryConverter.java | resolveColumn 嵌入路径二级查找 |
| 6 | converter/jpa/JpaQueryEnhancer.kt +3 | converter/jpa/JpaQueryEnhancer.java | mapColumn 挂 columnByEmbeddedPath |

翻译自查清单（本轮相关坑位）：具名实参→位置实参/构造器重载；`object`→静态；`internal object JpaMetadataReader` 已是 Java 静态方法类（沿用）；elvis 内层空合并双判（本轮无新增点）；枚举限定形态（本轮无新枚举）；`replaceFirstChar { uppercaseChar }` → Java `Character.toUpperCase(c)` 手写拼接（注意 String.copyWithRange 陷阱——直接 `sb.append(Character.toUpperCase(path.charAt(0)))` 风格）；runCatching 落 try/catch 计入 fatal 两段式基线。

### 测试移植分派（12 件按 k2j 侧形态三分）

- **A 类·.java 翻译件增量合并**（master .kt 改动合进 k2j 已有 .java）：
  - `DerivedQueryConverterTest`（+53：嵌入路径用例）→ DerivedQueryConverterTest.java
  - `SqlToJavaPanelTest`（+18/−x）→ SqlToJavaPanelTest.java
- **B 类·.kt 直取/union**（两侧同为 .kt，读 diff 后直取或 union）：
  - `HqlAliasColumnMappingTest`（+32）、`JpaEntityResolverMappingDeepTest`（+165）
- **C 类·k2j 适配版守卫/签名 union**（k2j 版含 Java 扫描根适配，**禁整文件直取**，逐 hunk 把 master 改动并进适配版、保 Java 判据）：
  - `AcpSignatureCrossVersionTest`（151 行重构）+ 新 helper `SignatureDistroDiscovery.kt`（102 行，直取后按需适配）
  - `SettingsRealityGuardTest`（84）、`BannedApiSourceScanTest`（115）、`EnumReachabilityGuardTest`（57）、`McpDbSignatureCrossVersionTest`（56）、`PlatformSignatureCrossVersionTest`（52）
- **D 类·新建**：`EntityResolverEmbeddedTest.kt`（181 行）直取，编译不过的 Kotlin→Java API 断点按互操作规则适配测试侧（禁为测试改生产 API 形态）。

### 文档同步

- 直取：docs/开发计划、调研报告、经验总结、memory-archive/bug-fixes 两件、架构设计 03（先核对无 k2j 专属行再取）、README、update/preview.update.json、gradle/plugin-change-notes.html。
- 四册 union（tmp 文件按册隔离命名）：CHANGELOG.md（[Unreleased] 拼 + 本轮同步台账行，`### Changed` 子标题每版本唯一）、docs/MEMORY.md、docs/变更记录.md、docs/memory-archive/README.md——三方合并后做行集合 byte-exact 校验。

## 三、提交链（本轮六段）

- C1 本计划。
- C2 构建链：gradle.properties、build.gradle.kts、buildSrc mpv-testing.gradle.kts、agent build.gradle.kts、plugin.xml、update/preview.update.json、gradle/plugin-change-notes.html。
- C3 生产翻译 6 文件（含 TableInfo.columnByEmbeddedPath）。
- C4 测试移植 12+2 件（A/B/C/D 四类）。
- C5 文档同步（直取 + 四册 union）。
- C6 收官：`.port-sync` 锚点→32a7d99 + note 重写 + 计划 §七实施记录回填 + fatal 基线复核登记。

## 四、待验证前提（实施者先证伪再动手）

1. **模型零改动前提**：本轮新增产依赖的 ColumnInfo(insertable/updatable/embedded/embeddedParent)、TableInfo(compositeIdColumns/columnByField)、JpaMetadataReader.EmbeddedColumnSpec 在 k2j 已在位——已 grep 实证（ColumnInfo.java:45-50、TableInfo.java:62/72/206/214、JpaMetadataReader.java:855），预期生产翻译零模型文件改动；若编译证伪，回查锚点 bccbfc5..25fcd97 之间是否有漏移。
2. **TableInfo Java 构造器参数序**：master 调用点用具名参 `compositeIdColumns=`，Java 侧须按实参序位置传参——写 EntityResolver.java 前先读 TableInfo.java:72 全参序（含 discriminator 两参位置）。
3. **新测试直取可编译**：EntityResolverEmbeddedTest.kt 直取后对 Java 生产 API 编译——若 ColumnInfo/resolveColumns 断点不兼容，按互操作规则改测试侧（具名参→位置参、访问器 get 前缀）。
4. **签名测试重构可干净 union**：master 侧 SignatureDistroDiscovery 重构（Acp 151 行）若覆盖 k2j 适配点（Java 扫描根/分支专属清单），以保 k2j 适配语义为先、重构结构照搬；发现不可调和处登记进实施记录而非静默丢弃半边。
5. **枚举/守卫基线无漂移**：本轮无新枚举常量、无新反射入口声明，EnumReachability/PlatformSignature 的 master 侧改动预期为测试基建重构非新增判定项——union 后若判定项数变化，逐项归因。
6. **驱动种子在位**：testDatabase 前确认 k2j runIde 沙盒 jdbc-drivers 种子链（缺则从 master 工作树 runIde 沙盒复制，日志须见两行 seeded）。

## 五、门禁与验收

按验证三档分层，本轮为跨分支同步（大改动），全量+构建校验双跑：

1. 编译三链：compileJava / compileTestJava / compileTestKotlin 全绿。
2. 全量 `testShards`（默认 5 片，单路收口；跑前 `timeout 10 docker info`）：**0 失败 0 错误**，用例数 ≥6087+本轮新增（master 侧同源水位 6190，k2j 与 master 存在既有绿跳差，收官记实测读数）；判红先看报告 XML 计数与时间戳再定性。
3. `testDatabase`（种子前提 4）：0 失败。
4. agent 子项目 `:sql-visualizer-agent:test`：0 失败（上轮 93 用例基线）。
5. 构建校验：`verifyGate`（243/253/262 三目标 Compatible，for-removal/experimental 与上轮持平=无翻译漂移；deprecated 增量逐项归因 master 新 API 面）+ `buildPlugin` 出包 mybatis-plus-sql-visualizer-2026.10.6.zip。
6. 反向验证抽查：T-Embedded 三个新行为各至少一条测试在 k2j 树真实命中（MP 轨展平/SD prefix/伪列修复）——看测试报告中 EntityResolverEmbeddedTest / DerivedQueryConverterTest 新用例的执行计数非 0。

## 六、风险预置

- 四册 union tmp 文件按册隔离（坑 17 前科：内容互换带冲突标记入库）；合并后行集合校验。
- 守卫/签名 union 是本轮最大不确定面（C 类 6 件，master 侧重构+扩容叠加 k2j 适配层）——逐件先读 master diff、再读 k2j 适配版头部注记，union 后跑对应守卫测试单项验证。
- 沙盒临时行（build.gradle.kts 的 test-sandbox-k2jsync70）收官还原，禁随提交入库。
- 判测试红之前先确认测试真的跑了（共享缓存三形态坑 10）；收口禁 `&&` 串联。
- master 未推送 11 笔：若作业中途 master 再前进，增量小于 10 提交则本轮锚点仍钉已吸收的 32a7d99、余量记入 note 留下轮（不为尾款二次开工）。

## 七、实施记录（收官回填）

- C1 计划提交：20261001-0205，`c5417df`。
- C2 构建链：`4409327`。六件锚点两侧逐字节一致实证后直取（gradle.properties/buildSrc/agent build.gradle.kts/plugin.xml/preview.update.json/plugin-change-notes.html）+root build.gradle.kts 保 k2j 专属段仅并注释三行。
- C3 生产翻译：`63afd75`。6 文件 + TableInfo 22 参构造直呼（计划内映射表兑现）。**待验证前提逐条回执**：①模型零改动前提成立（编译零模型文件改动；中途一波三折——Kotlin 具名默认参重载路线因与 associations 七参重载擦除同疑符编译红，改 JPA 轨既有 22 参直呼形态，见 §二缩编决策附注）；②TableInfo 构造器参数序先读后写兑现（discriminator 两参/compositeIdColumns/idClassType 位置对齐 JpaEntityResolver:187 既有形态）；③新测试直取可编译成立（EntityResolverEmbeddedTest 直取后仅 .isEmbedded 互操作适配 3 处）；④签名测试重构可干净 union 成立（Acp/McpDb/Platform 三件锚点两侧本就逐字节一致，直取）；⑤枚举/守卫基线：本轮无新枚举；fatal 站点 +6 基线 660→666 按守卫实测读数登记；⑥驱动种子在位兑现（build/test-sandbox/database/config_testDatabase/jdbc-drivers/jdbc-drivers.xml）。
- C4 测试移植：`391f445`。直取 5+union 5+A 类 .java 合并 2。**计划外发现两笔**：①hqlalias/settingsguard/enumguard 三件首轮三方合并整文件伪冲突＝工作树 CRLF vs git 库 LF，改用 git blob 作 current 重跑即零冲突（新坑：k2j 三方合并一律以 git blob 为基准，勿 cat 工作树文件）；②BannedApiSourceScanTest 首轮合并稿以 25fcd97 为 current，检查时发现会把作业期间并行会话（K2J 尾款清偿）已提交的基线归零改动抹回——废弃重做（HEAD blob 为 current + 叠加其 diff 补丁），其 fatalUnwrapUnjudgedBaseline=0 与 runRunner 用例保真复核在场。守卫三件适配语义见 C4 提交信息与 .port-sync note。fatal 基线 660→666（守卫实测读数，BannedApiSourceScanTest 首轮 1 红取数后登记复绿 29/0）。
- C5 文档同步：`5cc85b2`。docs 48 件直取 + 四册处理（CHANGELOG 整册直取＝计划「拼装」决策的实测修正——k2j 无专属行 grep 实证且 master 已轮换旧账进发行分区；三册三方合并零冲突+专属行 byte-exact 存活校验 8/16/1 条）。
- 门禁：编译三链全绿 + testShards **6100/0/0/9**（5 片 1244/1119/1282/1181/1234 + testExternal 40/0/1，4m13s）+ testDatabase 首轮 3 例 freetype 环境假失败复跑全绿（5m21s）+ agent 93/0 + buildPlugin 2026.10.6.zip（6,950,416 字节）+ verifyGate 三目标 Compatible（243=4+15+46、253=6+18+44、262=7+73+44，与上轮逐项持平零漂移）。
- C6 收官：`.port-sync` 锚点→32a7d99（全 SHA 32a7d996bc9bb02341f4f3dea4ca09396304f102）+ note 重写 + 变更记录台账行入册 + 本节回填；parallel_tasks 注销 `t-synck2jmaster70`。分支未 push（用户未指令）；作业期间 master 零新增量，登记表并行会话 t-o91r10/t-r33explain 均在自己 worktree 未与本轮冲突（bannedapi 除外，已按上述处置归一）。

