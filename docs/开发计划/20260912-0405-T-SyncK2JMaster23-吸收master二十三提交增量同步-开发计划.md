# T-SyncK2JMaster23 开发计划——master 06d742a..aa49c76 二十三提交增量同步

> 立项日期：2026-09-12　分支：feature/kotlin-to-java（worktree `mybatis-plus-sql-visualizer-kotlin-to-java`）
> 同步源：**本地 master aa49c76**（增量范围 `git diff 06d742a..aa49c76`，23 提交，58 文件 +3538/−685）
> 上轮收官：T-SyncK2JMaster32（0eca647，全量 3261/0/0/6 绿 + verifyPlugin 三目标 Compatible，锚点 06d742a）

## 1. 背景与目标

master 于 2026-09-11 完成 v2026.9.11 发行日全部工作：T-DynUpdate 自更新免重启动态安装（本轮最大功能线）、T-HistParam 历史页参数识别根治与字面量恢复两轮、T-ReverseParenWira 括号组退化修复、HelpAction 单测浏览器短路，以及发行资源与文档。本轮按用户明确指示**同步功能、不合并分支**——方案A手工移植不变：生产代码 100% Java 手工翻译，测试按 k2j 侧既有形态（.java/.kt 混合）增量落位，共享构建/文档文件走 union，绝不 git merge。

## 2. master 增量功能线（23 提交归组）

| 功能线 | 提交 | 内容概要 |
|---|---|---|
| T-DynUpdate 主体 | 7b4b906 / 34055dd / 771b5f2 / e8c5f1b | 自更新免重启动态安装：DynamicInstallSupport 五入口反射编排（unloadPlugin 弹性首参匹配 + installRestartScript 兜底）、InstallResult 密封层级拆分 InstalledDynamic / InstalledRestartRequired、UpdateSection 状态机加 InstalledLive |
| 热替换验证套件 | 50748d1 / 11407b7 / 7e1d3e0 / 1a1faac / 5083859 | runIdeHotSwap 专用沙盒任务 + dev-support/hotswap-local-server.py 本地清单服务 + .run 配置 + runIde 同款自动起停 + manifest URL 执行期读（配置缓存陷阱） |
| 262 首参漂移修复 | 9c2788f | unloadPlugin 首参 IdeaPluginDescriptorImpl→PluginMainDescriptor 漂移，弹性首参匹配 |
| 跨版本签名护栏 | 9f8821d | PlatformSignatureChecker.java（纯 Java 检查器）+ PlatformSignatureCrossVersionTest.kt（本机全部已解压发行版 × 自带 JBR 跑检查器，Assume 跳过机制） |
| 更新通知治理 | 171b7a3 | Gitee 入口去重 + 立即安装先过期源通知（缓解「未完全卸载」警告） |
| T-HistParam 两轮 | bceeee9 / 3d72025 | 历史文件 v2 `-- pd: ` 参数行 + toPreviewResult 统一识别通道 + LiteralParamRecovery 字面量参数恢复（口径：字符串全量/数值限谓词语境，LIMIT 投影不识别；反斜杠歧义片段跳过）+ 设置项 literalParamRecovery（复选框台账 23→24） |
| T-ReverseParenWira | a24a30e | ① SqlReverseParser.parseGroup 单条件非取反括号组解包（NOT (a=1) 保留组承载 negated）；② attachConsole 包 WriteIntentReadAction + getDocument 单独 ReadAction（262 读权限硬抛） |
| HelpAction 短路 | f955d85 | SqlLogPanel 三处降级出口收敛 browseOnlineDoc()，isUnitTestMode 短路只记日志（定时回归弹真机浏览器根治） |
| v2026.9.11 发行 | 0cb95eb | gradle.properties 版本号 + CHANGELOG + 变更记录 + 发行说明 + preview.update.json + changeNotes 9.11 段 |
| 帮助页单源 | e1a9807 | 删除 docs/发行说明/marketplace-custom-page.html 副本，src/main/resources/html 为唯一 classpath 读取源 |
| 杂项/文档 | 200b187 / e80d3ea / 7531cfb / e0b1522 / 4c59d91 / aa49c76 | .gitignore *.hprof；README「参考与致谢」；hprof 归因收口；热替换两步流程文档；交付索引；M56 计划+竞品调研 |

## 3. 文件级移植矩阵

### 3.1 生产代码（15 个 .kt diff → Java 翻译）

| master 文件 | k2j 对应 | diff 规模 | 移植要点 |
|---|---|---|---|
| update/DynamicInstallSupport.kt | **新建** DynamicInstallSupport.java | +329 | 五入口反射编排（PluginInstaller→unloadPlugin 弹性首参：IdeaPluginDescriptorImpl/PluginMainDescriptor 双形态）、installRestartScript 脚本兜底、Light service 须显式 final |
| update/PlatformPluginInstaller.kt | PlatformPluginInstaller.java | +78/−xx | InstallResult 密封层级拆分（Installed→InstalledDynamic+InstalledRestartRequired）；动态安装主路径接线 DynamicInstallSupport |
| update/PluginUpdateRunner.kt | PluginUpdateRunner.java | +21 | 安装结果分流 + Gitee 入口通知去重 |
| update/StartupUpdateCheck.kt | StartupUpdateCheck.java | +44/−xx | 启动检查接线动态安装结果 + 先过期源通知 |
| config/UpdateSection.kt | UpdateSection.java | +16/−5 | State 枚举加 InstalledLive；renderInstall 三分支；renderState 按钮可见性 done 双态 |
| config/PluginSettings.kt | PluginSettings.java | +7 | State 加 literalParamRecovery 默认 true（**复选框台账 23→24**） |
| config/PluginSettingsPanel.kt | PluginSettingsPanel.java | +7 | 复选框字段 + addRow/isModified/apply/reset 五接线 |
| execute/DataSourceSelector.kt | DataSourceSelector.java | +40/−16 | attachConsole 包 WriteIntentReadAction（WIRA-in-WIRA 重入合法）+ getDocument 单独 ReadAction；三轮踩坑链注释一并搬 |
| log/HistorySqlLogPanel.kt | HistorySqlLogPanel.java | +27 | 参数识别通道统一（pd 行 + 字面量恢复经 toPreviewResult） |
| log/LogEntryPreviewRecognizer.kt | LogEntryPreviewRecognizerKt.java | +76 | toPreviewResult 统一通道接线 literalParamRecovery 设置 |
| log/SqlLogAutoSaver.kt | SqlLogAutoSaver.java | +5 | `-- pd: ` 参数行持久化写入 |
| log/SqlLogFileReader.kt | SqlLogFileReader.java | +43 | v2 参数行读取 + pd 行排除出旧头部判据 |
| log/SqlLogPanel.kt | SqlLogPanel.java | +68/−40 | browseOnlineDoc() 三出口收敛 + isUnitTestMode 短路 + 历史页识别通道 |
| log/SqlLogParamCodec.kt | **新建** SqlLogParamCodec.java | +71 | pd 行编解码（naive 计数守卫等逻辑内嵌） |
| log/LiteralParamRecovery.kt | **新建** LiteralParamRecovery.java | +237 | 字面量恢复识别器：字符串全量/数值谓词语境/LIMIT 投影不识别/反斜杠歧义跳过 |
| reverse/SqlReverseParser.kt | SqlReverseParser.java | +11/−1 | parseGroup 单条件非取反括号组解包 |
| bundle en/zh | 同名 .properties | 各 +4 | settings.literalParamRecovery(.tip?) / settings.update.installedLive 等，以实际 diff 为准 |

### 3.2 测试（18 文件：1 直取 + 8 增量翻译 + 2 增量 .kt + 6 新建 + 1 .kt 直取适配）

| master 文件 | k2j 侧形态 | 处置 |
|---|---|---|
| src/test/java/PlatformSignatureChecker.java | — | **直取**（纯 Java 零平台 import，src/test/java 根目录无包；test java 源集 k2j 已配） |
| update/PlatformSignatureCrossVersionTest.kt | **新建** | .kt 基本直取（不调生产 API，只引用检查器类；Assume 跳过机制零构建依赖） |
| update/DynamicInstallSupportTest.kt | **新建** | 新建 + 互操作适配（Java 生产类禁命名实参/record 访问器方法调用化/getProject()） |
| update/PluginUpdateRunnerTest.kt | .java（有） | 增量翻译进 .java |
| update/StartupUpdateCheckTest.kt | .kt（有） | .kt 增量 |
| config/PluginSettingsPersistenceTest.kt | .kt（有） | .kt 增量（**复选框计数 23→24**） |
| config/UpdateSectionTest.kt | .java（有，位于 src/test/kotlin 目录） | 增量翻译进 .java（InstalledLive 三分支） |
| heavy/UpdateFlowHeavyTest.kt | .java（有） | 增量翻译进 .java |
| log/HelpUiTest.kt | .kt（有） | .kt 增量（isUnitTestMode 短路断言） |
| log/HistorySqlLogPanelPreviewTest.kt | **新建** | 互操作适配 |
| log/LiteralParamRecoveryTest.kt | **新建** | 互操作适配 |
| log/SqlLogParamCodecTest.kt | **新建** | 互操作适配 |
| log/LogEntryPreviewRecognizerTest.kt | .java（有） | 增量翻译进 .java |
| log/SqlLogAutoSaverTest.kt | .java（有） | 增量翻译进 .java |
| log/SqlLogFileReaderTest.kt | .java（有） | 增量翻译进 .java |
| log/SqlLogHistoryRoundTripTest.kt | .java（有） | 增量翻译进 .java |
| reverse/JavaCodeGeneratorTest.kt | .java（有） | 增量翻译进 .java（MPJ 括号 3 用例） |
| reverse/SqlReverseParserTest.kt | .java（有） | 增量翻译进 .java（parseGroup 解包 2 用例） |

### 3.3 资源 / 构建 / 文档

| 类别 | 文件 | 处置 |
|---|---|---|
| 构建 | build.gradle.kts | **手工 union**：头部 import 2 行 + hotswapServerStop 任务 + runIde 改造（doFirst 起本地服务 + finalizedBy + jvmArgumentProviders 执行期读 manifest URL 替换旧 systemProperty 块）+ changeNotes 加 v2026.9.11 段 + intellijPlatformTesting.runIde 注册 runIdeHotSwap；k2j 自有块（test java.srcDir、plainFjpfJar 等）原样保留；配置缓存约束注释一并搬 |
| 版本 | gradle.properties | 2026.9.10 → 2026.9.11 |
| 脚本 | dev-support/hotswap-local-server.py | **直取**（全新） |
| 运行配置 | .run/runIdeLocalManifest.run.xml | **直取** |
| 杂项 | .gitignore | **直取**（+*.hprof） |
| 更新清单 | update/preview.update.json | **直取** |
| 帮助页 | src/main/resources/html/marketplace-custom-page.html | **直取**（M，2 行）；docs/发行说明/marketplace-custom-page.html **删除**（单源化） |
| bundle | MpSqlVisualizerBundle(.zh).properties | 各 +4 键直取 |
| 文档直取 | docs/开发计划 5 新增 + 发行说明 1 新增 + 架构设计 3 修改 + 测试沙盒验证清单 + README.md | 直取（README 若有 k2j 分叉则 union 增量节） |
| 总册 | CHANGELOG.md / docs/MEMORY.md / docs/变更记录.md | 行级并集（**union 后必须做两侧行集合校验**——上轮实证「一侧删除、一侧未动 = 删除胜出」静默吞 k2j 行） |

## 4. 波次与验证

1. **W1 生产翻译**：update 包（PlatformPluginInstaller → DynamicInstallSupport → PluginUpdateRunner → StartupUpdateCheck → UpdateSection，InstallResult 层级自底向上）→ log 包（SqlLogParamCodec/LiteralParamRecovery 新建先行，再增量五件）→ config 两件 + reverse + execute。
2. **W2 测试移植**：按 §3.2 矩阵。
3. **W3 资源/构建/文档**：§3.3。
4. **W4 验证链**：parallel_tasks 登记（沙盒 portsync23）→ compileJava compileKotlin → compileTestKotlin --rerun-tasks → 全量 test → buildPlugin → verifyPlugin 全量三版本（发行级门禁）。全绿后收官提交：.port-sync 锚点 → aa49c76 + 计划文档实施记录回填 + MEMORY/变更记录交付行。

预期基线：3261 → 约 3300+（新增套件 LiteralParamRecovery/SqlLogParamCodec/DynamicInstallSupport/HistorySqlLogPanelPreview/PlatformSignatureCrossVersion + 解析器与生成器增量用例；签名护栏无发行版时 Assume 跳过不计失败）。

## 5. 风险与既知坑清单

- **InstallResult 密封层级变化贯穿五文件**：Kotlin sealed class → Java 需按既有 InstallResult.java 形态改造（abstract + static 嵌套 final 子类或 sealed 接口降级），when 三分支 → instanceof 链。
- **build.gradle.kts 是 k2j 高风险共享文件**：上上轮实证被并行会话整文件覆盖——本轮 union 后 diff 复核自有块零丢失。
- **runIdeHotSwap / runIde 的 doFirst 引用脚本对象受配置缓存约束**：注释原样搬，`fun listening()` 局部函数在 Java 侧不存在（这是 .kts 不是 .java，直抄即可）。
- **互操作铁律**：Java record 禁 extends；Kotlin 测试调 Java 生产类禁命名实参；Java 静态成员无默认导入；非 get/is 前缀方法不合成属性；BasePlatformTestCase 子类用 getProject()；JUnit3 test 前缀方法内 lambda 抽私有工厂。
- **K2J 陈旧输出劫持**：Unresolved 先清 build/classes/java/main 再重跑，勿先改代码。
- **编译验证禁 grep 过滤**（假绿教训），看完整 FAILURE 输出。
- gradle 每次跑前 parallel_tasks.py check；收官后还原 sandboxContainer（若本轮改过）。
- push 留用户 GUI。

## 6. 实施记录（2026-09-12 收官）

按 §4 波次全部落地，矩阵与计划一致，偏差两处：

1. **PreviewResult 增 9 参便捷构造**（k2j 特有结构性适配）：master Kotlin 具名实参构造吃 data class 默认值，Java 侧 20 参构造无默认值——新增 `PreviewResult(sql, compactSql, unresolvedParams, paramTable, wrapperType, tableName, warning, paramHints, initialParamValues)` 重载委托 20 参，默认值逐字段对齐 master data class（columns=emptyList 等）。
2. **LiteralParamRecovery 双文件落位**：data 类 → `LiteralParamRecovery.java`（record），顶层函数 + 上限常量 → `LiteralParamRecoveryKt.java`（同包 public，对齐 LogSqlRestorerKt 先例）。

翻译要点摘记：Kotlin 扩展函数 `.buffered()` 无 Java 等价（改 `new BufferedInputStream(...)`，compileJava 抓住）；`SqlLogFileReader` 单例访问 `SqlLogFileReader.INSTANCE`（新建测试两处 `new` 返工）；互操作铁律②再实证（.kt 测试对 Java record 构造禁命名实参，LogSqlEntry 三处转位置实参）；工具调用层吃反斜杠坑二犯（heredoc 内 `\\n` 落成真实换行致 Java 字符串断裂，改 Write 脚本文件方式追加后修复——该坑对「脚本内容含转义序列」场景无免疫力，须直接绕行）。

验证链：compileJava/compileKotlin/compileTestJava/compileTestKotlin --rerun-tasks 全绿 → **全量 3315/0/0/6 绿（268 套件，较上轮 3261 +54）** → buildPlugin 成功 → **verifyPlugin 三目标 Compatible**（弃用 243:4/253:7/262:41 与上轮持平；experimental 6→8，+2 为 attachConsole 包 `WriteIntentReadAction.compute(Computable)` 新站点——与 master 本轮同源同站点）。`PlatformSignatureCrossVersionTest` 在本机实际覆盖 243/253/262 三平台发行版全绿（签名护栏自本轮起在 k2j 分支生效）。

插曲：verifyPlugin 第二轮报 `FileAlreadyExistsException`（上一轮被中途停止的运行在报告目录留下半成品 report.html，报告器不容忍已存在文件）——清 `build/reports/pluginVerifier` 后复跑即绿，非插件问题。

提交链：本笔计划 → 生产 → 测试 → 资源文档 → 收官（.port-sync 锚点 → aa49c76）。未 push，push 留用户 GUI。
