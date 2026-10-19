---
name: project-kotlin-to-java-port-sync
description: Kotlin→Java 移植分支（feature/kotlin-to-java，独立 worktree）的 master 增量同步——
  锚点已推进 ed84f5b（2026-09-17 T-SyncK2JMaster176 一百七十六提交 4286 绿 + verifyGate 三目标 Compatible），
  .port-sync 锚点与转换缺陷清单、历轮同步实录与互操作铁律；远程 T-SyncK2JDev（dev 基线）计划保留未做，
  下轮须按新锚点重算增量；分支 6 笔收官提交未 push
metadata:
  node_type: memory
  type: project
  originSessionId: sess_67a8f4fc-e4fa-4a03-a907-912ada4c54f0
---

**2026-09-17 T-SyncK2JMaster176 已交付（锚点→ed84f5b，未 push）**：master c4c5f02..ed84f5b 共 176 提交同步完成——M56 agent 全链整包入 k2j（agent 子项目 36 文件直取 + settings 组合构建 + embedAgentJar 链 + IDE 侧五件翻译）、三解析器线程分桶、T-UiNative 四批次、feedback 多轮、execute/ui 修复链；生产 25 新建翻译+57 增量+9 退役删除、测试 31 新建+44 增量+5 删除。全量 testShards 4286/0/0/6 绿 + buildPlugin 出包 2026.9.18-beta.3 + verifyGate 三目标 Compatible。提交链 a0a7fef/52696ce/d85473c/5e36b8f/2ad5c15/0e34385。经验：大规模翻译用「主会话做增量移植+互锁基础件、子代理做新建文件全量翻译（≤3 并发防限流）」；J1 型翻译陷阱=Kotlin 默认参数 restorer 传成 null（24 失败根因）；internal 可见性跨包测试须 public 化。push 留用户。


**多克隆布局（易混）**：`mybatis-plus-sql-visualizer` = master checkout（IDE 工作区，勿做移植操作）；`mybatis-plus-sql-visualizer-kotlin-to-java` = 移植分支工作树（真实操作地，分支 feature/kotlin-to-java，生产源码 100% Java）；`mpsv-m37` = m37 竞品融合检出。用户消息模板可能残留旧目标分支名（如本次"同步到 m37"实为笔误——m37 由并行会话 merge-master-m37 负责），移植任务以 `.workbuddy/memory` + `.port-sync` + skill `~/.workbuddy/skills/kotlin-to-java-port/` 为准。**2026-09-06 反向实证**：本轮"切换到 feature/kotlin-to-java…同步到 feature/m37-competitive-fusion"被我误判成模板残留、先做了 m37 方向（后发现后整支回退 43da4ee 零残留重做）——目标分支存疑时先用 `.port-sync` 增量范围与用户所述"最近更新"对质，对不上直接问，不要靠措辞风格推断。

**同步工作流（方案A，既定惯例）**：手工移植、绝不 git merge（merge 会带回 .kt 污染与历史失真）；根目录 `.port-sync` 记录 synced_master SHA，每次只 diff `<SHA>..origin/master`；验证链 compileJava compileKotlin → compileTestKotlin --rerun-tasks（防过期测试字节码）→ test -I /tmp/redirectsandbox.gradle → buildPlugin verifyPlugin；push 留给用户 GUI（headless GCM 挂死，勿反复重试）；gradle 前必跑 parallel_tasks.py check 并登记独立沙盒（终验后还原 build.gradle.kts 的 sandboxContainer 行）。

**2026-09-05 dev 全量合并中被用户明确排除**（见 [[project-dev-merge-all-branches]]）——kotlin-to-java 不进 dev，另行回合 master 未做。

**2026-09-05 同步会话（ee377b4→e5ce5af，52 提交，登记名 port-sync-master-52/沙盒 portsync52）**：
- 里程碑内容：JOIN 图原生框架全套（nativefw 七文件 + db 虚拟外键三件套 JoinFkRegistry/JoinVirtualFkProvider/NativeDatabaseDiagramSupport + DiagramSchemaEnricher + SqlPreviewPanel B路径→A路径→自绘降级链 + plugin.xml diagram-optional）、psource JdbcTemplate 预览六文件、psi-jpa T-GutterDedup（PreviewReceiverResolver 家族分派）、SelectClauseExpander 共用（Hql/Jpql Converter 接入）、v2026.9.2→9.4 发行资源、设置页 joinDiagramNative/jdbcSqlPreviewEnabled 两开关。
- **已完成（同步收官）**：提交链 ad0e015 计划 → 14b65d9 Wave1 → e337575 测试源集恢复 → 353eeca Wave3(1/2) → a21b3a8 Wave2-5 主体 → 482d1b9 收官（版本对齐 2026.9.4、还原沙盒、.port-sync=e5ce5af）。全量 **3060/0/0/6 绿**（较上同步 +1634 用例），buildPlugin+verifyPlugin 三平台 0 compatibility problem。push 留用户 GUI。

**2026-09-06 增量同步（e5ce5af→44e8eb2，4 提交，T-SyncK2JMaster）**：T-CacheInfo 设置页缓存统计修复——5 处生产代码手工翻译成 Java（EntityResolver.cacheSizeBytes / WrapperCache.sizeBytes / SqlPreviewService 双委托 / PluginCacheManager 三类全口径 + clearAll 补 getServiceIfCreated 清 Wrapper 缓存 / 设置面板两参文案 + bundle en/zh 各两行），测试两类（7 用例）原样复制进 src/test/kotlin（测试源集仍全 Kotlin）；三份调研矩阵/开发计划文档直取 + MEMORY/变更记录行级并集（k2j 文档基线偏早，只插 master 增量行、移植期自有行不动）；锚点→44e8eb2。提交链 287d8e1 / 9ed97ab / b299379 / e5153c4，全量 **3067/0/0/6 绿** + verifyPlugin 三目标 Compatible。未 push。另：本轮曾误同步 m37 分支（6af71e9/f03a345/f4b3787），已 reset --hard 43da4ee 丢弃。

**2026-09-06 文档+测试补齐（T-SyncK2JBackfill，c9815ee/eb3c1ce/08fbb35/e31cbad）**：①docs 树镜像 master——移植期只搬代码欠下的 51 个文档直取（含 v2026.9.4 发行说明、Kotlin 转 Java 经验总结、记忆归档 35 篇），MEMORY/变更记录取 master 归档精简版后回补 k2j 专属行（master 侧老行已归档非丢失），README 补 FAQ、CHANGELOG 补移植收官条目；20260827 Kotlin转Java 旧计划保留 k2j 较新版（已标注被 v2 取代）。②测试对齐：master 254 测试类 vs k2j 252，**方法名集合逐一核对仅 2 整类缺失**（SqlLogConsoleBridgePerfTest 6 用例 + RealEnvTest 3 用例，已移植）；SqlLogPanelLayoutTest 4 用例"缺失"系转换版方法名 ASCII 化（testToolbarkeycomponentspresentdefaultvisibilitycorrect 等），本体已在——**判定用例缺失必须核对方法名集合而非中文原名**。全量 **3076/0/0/6 绿** + verifyPlugin 三目标。未 push。

**2026-09-08 增量同步（44e8eb2→f4a818d，4 提交，T-SyncK2JDaoSql）**：T-DaoSql 自研 DAO 字符串 SQL 预览与方法定义锚点贯通——8 处生产代码手工翻译成 Java（JdbcTemplateMethodDetector 增 DAO_STRING_SQL_METHODS+isStringSqlMethodCandidate 统一入口 / JdbcTemplateSqlLocator resolveSqlExpression→resolveSqlArgument 实参扫描泛化兼容 (Class, sql, ...) 形态+paramArg 取 sqlIndex+1 / JdbcTemplateSqlPreviewService 增 collectMethodCandidates 偏移升序 / JdbcTemplateSqlGutterLineMarkerProvider 家族排除制（CUSTOM_DAO/HIBERNATE/JPA 拒、UNKNOWN 兜底）+hasStringLikeArg / PreviewReceiverResolver 增 hasStringLikeArg+isStringLikeExpression 补 toString 分支 / WrapperAnchorDetector 场景 D 字符串 SQL 让位 / ConvertToSqlAction 方法定义 fallback tryMethodBodyStringSqlFallback / 新建 StringSqlMethodGutterLineMarkerProvider.java 方法定义锚点——kotlin.Pair 承 ReadAction 预计算产物、SqlSelectionDialog 泛型 SelectCallback、handler 走 enum INSTANCE 惯例），plugin.xml 注册+bundle en/zh 两键；测试 4 改 1 增按测试源集 Kotlin 约定原样复制（同一套 kt 测试双代码库跑）；9054544/6df2ca5/f4a818d 三文档提交——记忆精简 10 篇归档+03 指南 runIde 沙盒小节直取，MEMORY/CHANGELOG 行级并集回补 k2j 专属行。提交链 ba86fc4 立项/84aa764 生产/db89018 测试/1920102 资源文档/dee9e56 收官，全量 **3094/0/0/6 绿**（基线 3076+18；master 提交说明口径"9+8=17"与实际文件 8 测试方法有出入，以文件与 XML 为准）+ verifyPlugin 三目标 Compatible。未 push。

**2026-09-10 增量同步（bef7c15→8afe07a，14 提交，T-SyncK2JMaster14）**：T-AutoShowMaster/Recover + T-PortLossBatch1 + T-PreviewFlash + T-LogRowNumber + T-ExecCopyFix + T-RefreshDbSteal + T-DepApi + v2026.9.9。生产 26 翻译+2 新建（CopyFeedback/ConsoleReplayGuard）+2 直取补缺；测试 4 直取+QueryWrapperParserTest 增量翻译+4 .java 翻译+6 新 .kt 适配。提交链 ca6bbde/79066cb/f57f49e/5e32b12/5365a1c，全量 **3195/0/0/6 绿**+verifyPlugin 三目标 Compatible（弃用 243:4/253:7/262:39）。未 push。

**本轮新沉淀（三条）**：① **重大存量缺口**：3749116a（.kt→.java 转换轮）删了 SqlLogToolWindowFactory/ToolWindowVisibilityListener/SqlHistoryToolWindowFactory 旧版却未补转换版——前两个是 plugin.xml **活引用**，工具窗功能在 k2j 分支自删除起即死（静态链 compile/test/verifyPlugin 都不校验 toolWindow factoryClass 存在性，历轮全绿未暴露，runIde 真机才炸）；本轮直取 master HEAD 两文件根治（SqlLogManager 需补 `getCapturing()` 别名——直取 master Java 文件按 Kotlin 属性访问器调用）。同步前务必做 plugin.xml 活引用类审计。② **QueryWrapperParserTest 永远不能整替**：master 测试全用命名实参调 Kotlin 生产类，k2j 同名类是 Java（具名实参禁用）——该文件的"风格分叉"是适配 Java 生产侧的必然形态，后续同步一律增量翻译。③ **git merge-file 陷阱**：无 `-p` 时就地改第一个文件、stdout 为空——把 stdout 重定向当产物会落空文件（本次三份共享文档被清空，git 对象未损从 HEAD 重做）；正确用法 `git merge-file --union mine base theirs` 后取 mine。另：SqlLogManager 字段初始化器 lambda 读未定值 final 字段触发 javac DA 报错——改方法引用。

**2026-09-09 大增量同步（f4a818d→bef7c15，33 提交，T-SyncK2JMaster33）**：v2026.9.8 发行前后的全天增量一次吸收——T-PreviewWiLock/T-ClickPreview 五轮（SqlClickPreview 单击预览+SqlListSyntaxHighlighter ATTR_BY_TYPE 唯一配色）/T-LogListFreeze04（三渲染器弃 JLabel-HTML 改双 SimpleColoredComponent 片段+固定行高+SqlLogPanelKt.VisibleDiff 同一性差分）/T-LogDedupeMpMark（dedupeKey=sqlFingerprint(normalizeSqlWhitespace)+MP COUNT 逐条判定）/T-JdbcMultiline（JdbcTemplateLogParser COLLECTING 两态机+BracketScan string-aware 括号扫描）/自动监听四轮（onProjectProcessStarted+被动模式分流+RunConfigurationProcessListener）/T-DsCover/T-Wia262。生产 17 翻译+6 新建+2 删除；测试 config 5 直取+log 族 9 个 **.java 测试翻译增量**（k2j 测试树大半是 .java，M 级 .kt 测试须译入对应 .java 文件而非直取）+3 新 .kt 适配。提交链 48ac5fe/ee16e33/d87f3b5/d259581/f4a64fa/f1cfa7a/484026b/014bcf9，全量 **3138/0/0/6 绿**+verifyPlugin 三目标 Compatible。未 push。

**本轮新互操作铁律（六条，详见 k2j 计划文档 §5）**：① Java record 禁 extends 只可 implements（sealed 层级须降为接口，VisibleDiff 案例）；② Kotlin 调 Java 方法禁命名实参（直取 .kt 测试须机械化转位置实参）；③ Java 静态成员/嵌套类型在 Kotlin 无默认导入（须补 import）；④ 非 get/is 前缀 Java 方法不合成 Kotlin 属性（currentPopup()→getCurrentPopup()）；⑤ **工具调用层吃反斜杠再实证**：heredoc 内 `\\n` 落盘成真实换行——Java 字符串转义须用 Write 工具写脚本文件或 Edit 直落，且编译验证禁 grep 过滤（假绿教训：grep -E 只显示匹配行吞掉 FAILURE）；⑥ 行级并集用 `git merge-file --union` 三方合并，difflib 手写 union 会因双侧同位插入对齐成 replace 误吞行。⑦ Light service 在 Java 移植侧须显式 final（Kotlin 默认 final，平台校验 "must be final" 在 Java 侧才暴露，SqlHistoryManager 案例）。

**同步实操技巧（本轮沉淀）**：① 基线一致性判定必须 `git show <anchor>:<f> | diff --strip-trailing-cr - <f>`——CRLF 检出对 LF git-show 输出的裸 diff 会整文件假差异（`1,222c1,222` 全替换假象）；② 基线一致的文件直接在 k2j worktree `git checkout <masterSHA> -- <paths>` 整取（自动走 autocrlf 转换，比手工写文件稳），事后逐一 `diff --strip-trailing-cr` 复核（「多 pathspec 任一失效整体中止」记忆条目本轮再实证）；③ 锚点 full SHA 必须 `git rev-parse` 取真值，不可由短 SHA 脑补。

**新坑：JUnit3 前缀扫描撞 Kotlin lambda 合成方法**——test* 方法体内的 SAM lambda（如传给 Java 构造器的 Supplier/Runnable）编译成 `testXxx$lambda$N` 合成方法，JUnit3 TestSuite 按 test 前缀反射扫描会报「Test method isn't public」warning 假失败（BasePlatformTestCase 系）。规避：把带 lambda 的对象构造抽到非 test 前缀的私有工厂方法。

**重大发现：Java 测试源集断裂（e337575 修复）**：87f07bc 把 92 个 .kt 测试转成 .java 后仍留在 src/test/kotlin/（该目录默认不在 Java 测试源集），compileTestJava 恒 NO-SOURCE——93 个 Java 测试自转换日起从未编译执行，历史"1421/1426 全绿"只统计约 139 个 .kt 套件。修复：build.gradle.kts `sourceSets { test { java.srcDir("src/test/kotlin") } }`。教训：接手历史"全绿"基线先核对套件数与源集归属；重连后全量 2922 测试暴露 103 失败。

**Kotlin→Java 机械转换缺陷清单（重连后归因，均已修）**：
- `import org.junit.Assert.assertEquals` 缺 `static` 关键字（Kotlin import 原样搬运）——81 文件 250 行批量补
- Kotlin `"""...""".stripIndent()` 含去尾换行语义 → Java 文本块用 `.stripTrailing()`（172 处，消除 59 失败）
- Kotlin `drop(n)` 越界返回空表 → Java `subList` 抛异常，24 处补 `size() > n` 守卫；三元 else 用 `Collections.emptyList()`（List.of 泛型推断失败），for-each 需显式局部变量
- `new HashSet<>(paramNames)` 应为 LinkedHashSet（Kotlin `.toSet()` 保序，Set 序可观察时必须保序）
- JComboBox 取渲染器是 `getRenderer()`（getCellRenderer 是 JList 的）；`Language.getID()` 大写 ID；Kotlin 属性 `combo.renderer` 展开别凭记忆
- `Map.of`/`List.of` 拒绝 null → singletonMap/Arrays.asList/singletonList
- assertEquals(long字面量, Long返回值) 二义 → `Long.valueOf`/`Integer.valueOf` 包期望值
- 测试方法缺 `throws Exception`（File/Files IO 受检异常）→ 全量补
- BasePlatformTestCase 子类方法名必须 `test` 前缀（否则 JUnit3 静默不跑报 No tests found）；纯 JUnit4 类任意名可跑
- Kotlin `object`→ Java `final class + INSTANCE + 全 static`；顶层函数→`XKt.java`；具名实参→位置实参；默认参数→重载；record 组件访问器 `x()` 非 `getX()`
- 值语义 record 做弱引用缓存键会错命中 → 身份键包装（见 LogEntryListCellRenderer.IdentityKey）
- 平台 API 以本地 jar javap 为准实证过：DiagramVfsResolver.resolveElementByFQN 2 参、UmlFileEditorImpl ctor (DiagramBuilder, VirtualFile)
- Java 调 kotlin.Function0/Function1 SAM：lambda 参数类型需显式、返回 `Unit.INSTANCE`
- 反向生成三回归（子代理 D 修复）：`resolveColumn` 丢 elvis 回落（alias 非主别名时应保持主实体，致 t.name 全回落字符串列）；`renderLimit` 占位符 offset 折叠成单参（应保留 `LIMIT ?, ?`）；`setIncrBy` 用无参 `getQualifiedName()`（应 `qualifiedName(currentDialect())` 保留字反引号）
- `XmlPreviewModel.from` paramProps/variables 须 LinkedHashSet（paramProps 顺序=动态标签收集序+extraParamProps，可观察）
- 匿名包装类实现 DataProvider：只写 `getData` 方法不声明 `implements DataProvider`，`instanceof DataProvider` 不成立（SqlPreviewPanel EditorDataWrapper 修复）
- git checkout ref -- 多路径时任一 pathspec 失效会**整体中止**（部分成功假象）——批量对齐后必须逐一 diff 验证
- heredoc 内含 `[^() ]` 类正则会被工具层破坏反斜杠：复杂补丁写 .py 文件执行，或用纯字符串 replace + 行级手术

**2026-09-10 分支状态 + 下一轮立项**：feature/kotlin-to-java 已推远程（Gitee），T-SyncK2JMaster14 收官链 5365a1c 之上远程多一提交 **407006c「T-SyncK2JDev 开发计划」**——下一轮同步改以 **dev 为基线**：dev bef7c15..e64987e 共 96 提交增量同步方案（perf 红线 24 项 / M39 JPA 三波 / M44 四 ORM / M37M38 竞品融合 / ListMode 行高分流 / T-ErrReport feedback 包 / hbm 命名查询 / 设置导入导出，生产 24A+81M 约 13.5K 行），计划文档已在远程，本地 fast-forward 拉取即可，勿强推。

**2026-09-11 增量同步（8afe07a→06d742a，32 提交，T-SyncK2JMaster32，用户明确指定主分支同步）**：v2026.9.10 整个发行日增量——T-VStack 三笔（formatVertical 竖排+配对标注/扩面 IN/ORDER BY/GROUP BY/FROM 纵向化/IN 阈值 5）+ T-VPairAnno（复选框台账 22→23）+ T-PreviewDisplayMode（枚举状态化，applyDisplayMode 收敛点）+ T-HibDualCapture（quarantined 隔离槽 + placeholderPositions 单点口径 + 参数框 16 列/tooltip）+ T-PreviewTopOverlap（WEST→CENTER + 更多 JBPopup 菜单 + MetaPerfSegment 宽度截断）+ T-ExecSilentNoop（探针 + 提示三分流去重）+ T-PreviewDocLock(2)（ReadAction 包裹 + 临时文件尽力降级）+ T-PreviewSlow（预热 60ms）+ T-VersionUtil（平台比较器）+ T-LogRowNumber 续修（主配置 gating + 跨窗口去重）。生产 15 翻译 + 测试 16 文件（4 新建 .kt）+ 资源版本四件 + 文档 16 直取 + 三册 union。提交链 75e6822/c75ddeb/3561de8/ed256f3/0eca647，全量 **3261/0/0/6 绿**（263 套件，+66）+ verifyPlugin 三目标 Compatible（弃用 243:4/253:7/262:41，experimental 6）。锚点→06d742a。未 push。

**本轮新沉淀（三条）**：① **Java 测试侧访问平台 fixture 用 `getProject()`**——BasePlatformTestCase 的 `project` 是 Kotlin 属性名，Java 测试代码写 `new SqlPreviewPanel(project)` 报「找不到符号 变量 project」（Kotlin 侧才有属性合成），本轮 SqlPreviewPanelTest/SqlLogManagerBehaviorTest 新增方法 12 处返工实证。② **master 发行重构会吞 union 的 k2j 行**——v2026.9.10 发行时 master 把锚点期 CHANGELOG Unreleased 的四条（T-PreviewFlash/T-DepApi/T-ExecCopyFix/T-RefreshDbSteal）直接删除未搬入新版本段，`git merge-file --union` 里「一侧删除、一侧未动」= 删除胜出，k2j 保留行静默丢失；双向零丢失校验脚本捕获后改手工方案（k2j 内容原样 + master 新版本段插入到旧版本段之前）——**union 后必须做两侧行集合校验，不能只看行数**。③ **SqlFormatter 局部类/局部函数转 Java 的既定收敛式**：Kotlin 局部可变捕获收敛为 `private static final class XxxState` 实例状态机（fmt/Vertical 两例同型）；enum 嵌套类直译 `public enum`；data class→record；默认参数→重载或改全部调用点（私有方法后者即可）。

**2026-09-12 增量同步（06d742a→aa49c76，23 提交，T-SyncK2JMaster23，用户明确「同步功能非合并分支」）**：v2026.9.11 发行日增量——T-DynUpdate 免重启热替换（DynamicInstallSupport 五入口反射编排/unloadPlugin 首参 262 漂移弹性匹配/InstallResult 拆 InstalledDynamic+InstalledRestartRequired/installRestartScript 兜底/UpdateSection InstalledLive）+ T-HistParam 两轮（LiteralParamRecovery 谓词语境状态机+SqlLogParamCodec pd 行编解码+toPreviewResult 统一识别通道+v2 历史文件格式）+ T-ReverseParenWira（parseGroup 单条件括号解包+attachConsole 包 WIRA）+ HelpAction isUnitTestMode 短路 + 跨版本签名护栏（PlatformSignatureChecker 直取+CrossVersionTest 本机三平台实测）。生产 15 翻译+测试 18 文件+资源/构建 union（runIdeHotSwap/hotswapServerStop/build.gradle.kts 五处）。提交链 b841a26/25e19b2/86c4d59/c73c644/29e0989，全量 **3315/0/0/6 绿（268 套件，+54）**+verifyPlugin 三目标 Compatible（弃用持平 243:4/253:7/262:41，experimental 6→8 归因 attachConsole WIRA.compute 与 master 同源）。锚点→aa49c76。未 push。

**本轮新沉淀（四条）**：① **master 具名实参吃 data class 默认值的 Java 收敛式**——PreviewResult 等大类构造补 9 参便捷重载委托 20 参全构造，默认值逐字段对齐 master data class；② **Kotlin 顶层函数新文件落位惯例定型**：LiteralParamRecovery.java(record 数据类)+LiteralParamRecoveryKt.java(函数+常量) 双文件；③ **Kotlin 扩展函数无 Java 等价提醒**：`.buffered()` 等 IO 扩展翻译时须改 `new BufferedInputStream(...)`，compileJava 可兜住；④ **verifyPlugin 报告目录脏状态硬崩**：中途停止的 verifyPlugin 在 build/reports/pluginVerifier 留半成品 report.html，重跑报 FileAlreadyExistsException——清目录复跑即绿（勿误判为兼容问题）；另 **TaskStop 打断 gradle 任务会留下脏产物**，核对命令里严禁夹带 gradlew 重跑。

**2026-09-14 大增量同步（aa49c76→c4c5f02，171 提交，T-SyncK2JMaster171，史上最大轮）**：dev 全量合入 master（37e0728）后首次同步——生产 36 新建+82 增量翻译约 30K 行（M44 四 ORM 转换器/M39 JPA 八新件 4906 行/CriteriaQueryLocator 重写 1764 行/T-CrudSql 套件/feedback 九件/T-CrudColor/T-AutoFold/T-PickMpjErr/T-Perf 系列/T-TestGuard 三族守卫）+测试 89 文件（51 新建 .kt 适配+38 增量）+资源构建 union（版本 2026.9.14-beta.2/gifRecord 开关/T-TestPerf）+文档 137 件直取+三册 union。执行方式定型：**主会话手翻小件+全部接线+编译门禁，子代理并行分片翻译大件（生产 4 批+测试 4 批，简报含「引用生产 API 前必须打开对应 .java 核实」铁律），8 轮编译错误迭代**。提交链 c283dff 计划/7fdf0f9+e11b9b3+b832f51+fb7a029+ed9d5af 生产五批/c35ef0e 资源/f018a9d+ca0d798+71a0dca 测试/f34ee4f 文档/159997c 收官，锚点→c4c5f02。全量 **3975/0/0/6 绿（319 套件，+660 用例/+51 套件）**+buildPlugin 出包+verifyPlugin 三目标 Compatible（experimental 8 与 master 同源）。未 push。

**本轮新沉淀（五条，子代理协作轮特有）**：① **Kotlin 访问 Java `isXxx()` 前缀访问器**：属性合成的名字是 `isXxx` 而非 `xxx`（与 get 前缀不同）——.kt 测试调 Java 生产类布尔访问器须显式 `isResolved()`/`isValue()`，本轮 39 处；② **Java `split("\n")`（limit=0）丢尾空串 vs Kotlin split 保留**——独立 `"\n"` 空白 token split 后得空数组，换行 token 整个被吞（关键字换行 3 套件回归），`split("\n", -1)` 复刻 Kotlin 语义；源码注释里「与 Kotlin split 同语义」的假设是错的；③ **测试沙盒 plugins-test 旧 jar 遮蔽新生产类**：`prepareTestSandbox` 装进 `build/<沙盒>/plugins-test/*/lib/*.jar` 的旧插件 jar 在测试 classpath 压过 build/classes 新类——埋点打印不出现即此症，删 plugins-test 目录重跑即愈（探针取证必须先确认沙盒新鲜）；④ **plugin.xml 活引用类会静默丢失**：SqlHistoryToolWindowFactory.java 在 k2j 树中消失（3749116 教训重演），每轮资源直取后必须跑「plugin.xml 类引用→.java 文件存在性」审计脚本；⑤ **并行任务登记超时**：parallel_tasks expected_end 过期后条目仍挂表，跑 gradle 前若 check 提示自己的旧条目，直接续用/重登。

相关：[[project-test-conventions]]、[[project-m37-competitive-fusion]]
