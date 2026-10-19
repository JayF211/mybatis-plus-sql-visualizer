---
name: t-dynupdate-plan
description: 自更新免重启动态安装改造已交付合入 master 并全链实测通过（merge 771b5f2，验证套件 50748d1/11407b7/7e1d3e0/1a1faac，沙盒实测收口 5083859，262 签名漂移修复 9c2788f + 跨版本签名护栏 9f8821d，2026-09-11，全量
  3305 绿）——动态热替换主路径 + installRestartScript 兜底；runIdeHotSwap 一键全链（服务随任务自动起停）；F0 javap 243 实证 installFromDisk 路线否决；「未完全卸载」警告经 hprof 归因=无泄漏纯 GC 时机（Registry ide.plugins.unload.timeout 默认 5s）；KDoc 内 `lib/*.jar` 触发 Kotlin 嵌套注释新坑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5ce73ad8-8285-4465-86de-bef0cd909427
---

T-DynUpdate 已交付并合入 master（2026-09-11，分支 34055dd → merge 771b5f2 → 交付行 e8c5f1b；合并树全量 3300/0/0/6 绿 = master 3292 + 本任务 8；合并零冲突，静默核查甄别 T-HistParam 总册行欠账与 gutter.pages.accessible.name 重复键均系 master 既有；合并后 compileTestKotlin Unresolved 系陈旧增量编译缓存，清 build/classes+kotlin 缓存复绿非代码问题；工作树 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dynupdate`，计划立项 master 7b4b906）：

- **根因链**（计划文档 docs/开发计划/20260911-0614-T-DynUpdate-… §2）：插件 9/9 EP 全 dynamic 合规；直接根因=M28 `PlatformPluginInstaller` 走 StartupActionScriptManager 必重启；T-M23.10「vetoer 否决」系误判（performanceTesting vetoer 只护自身、仅回放期 activeExecutionCount>0）——**教训：vetoer 语义必须反编译读实现，不能按存在性推全局结论**。
- **F0 定论**：installFromDisk 复用路线否决（243 包私有+File+6 参 vs 262 public+Path+7 参，且两平台流程都不卸载已加载旧版，热更新必失败）；五入口手工编排双端实证——`loadDescriptorFromArtifact(Path, BuildNumber)`/`findPlugin`/`allowLoadUnloadWithoutRestart(descriptor)`/`unloadPlugin(descriptor)`/`installAndLoadDynamicPlugin(Path, JComponent?, descriptor)`；**descriptor 参数双端反编译实证仅进度标题/日志/fireState 用，解包布局由 zip 顶层目录名（rootEntryName）决定**——传新版描述即可。
- **实现**：`DynamicInstallSupport`（zip 前置校验纯函数→dev 守卫→反射五入口探测一次缓存→卸载预检→EDT invokeAndWait 卸载+装载）；`PlatformPluginInstaller.install`→`installRestartScript` 降级兜底（卸载失败/装载失败/反射缺失一律回退，无中间死态）；`InstallResult` 三分支 InstalledDynamic/InstalledRestartRequired/Failed + StartupUpdateCheck/UpdateSection(新 InstalledLive 态) UX 分叉 + bundle 双语三键。
- **F3 审计零改动**：父less messageBus connect 全库零命中；repeating Timer（SqlLogConsoleBridge）经 SqlLogManager.dispose()（项目服务 Disposable）覆盖卸载出口；静态缓存属类内引用随 classloader 同亡非卸载屏障（引用方向 map→PsiFile 锁 PSI 不锁 classloader）。
- 253 无本地发行版（gradle 缓存仅 243/262 本机装）——反射探测 runCatching 整体降级覆盖，最劣=现状；发版前 verifyPlugin 门禁硬核留人工，runIde 沙盒 e2e 更新演练与真机 Gitee [立即更新] 留人工。
- **新坑（Kotlin 嵌套注释）**：KDoc 里写 `lib/*.jar`，其中 `/*` 开启嵌套块注释吃掉外层 `*/` →「Unclosed comment」级联出整文件语法错误且报错行号错乱；注释/文档措辞禁用 `路径/*` 形态。

- **热替换本地验证套件已备好（master 50748d1，2026-09-11）**：`runIdeHotSwap` 任务（intellijPlatformTesting.runIde，独立沙盒 `.intellijPlatform/hotswap-sandbox`，**autoReload 必须关**——自动重载器监听 plugins 目录会在解包中途触发并发重载与 EDT 卸载/装载竞态）；「Run IDE (Local Manifest)」改跑此任务；`dev-support/hotswap-local-server.py`（8919，自动选 build/distributions 最新包生成 update.json）。**沙盒布局新知**：IJPG 2.18 默认沙盒在 `.intellijPlatform/sandbox/<product>/<build>`（路径无 dev 特征段），插件为 jar-copy 安装（`plugins/<name>/lib/*.jar`）非 build/classes 直载——T-M28.3 开发守卫在沙盒正常放行，旧 KDoc「runIde 从 build/classes 加载」描述过时。测试流程：`./gradlew buildPlugin -Pversion=更高版本` → 起脚本 → Run IDE (Local Manifest) → 设置页检查更新→下载安装 → 通知「已更新并立即生效」+ idea.log `dynamic install succeeded — plugin hot-reloaded`。从磁盘安装测不出免重启（平台不卸载已加载旧版）。

- **服务随任务自动起停（master 11407b7）**：runIdeHotSwap doFirst 拉 hotswap-local-server.py（端口被占复用不接管、pid 文件留空）+ finalizedBy hotswapServerStop（IDE 关闭/崩溃/失败三出口都停、只杀 pid 文件自有进程）。已实测：任务启动 3s 服务上线、强杀 IDE 服务自动停。**Gradle Kotlin DSL 三坑**：① 脚本内 `java` 被扩展访问器占位，`java.net.Socket` 须文件顶部 import；② 任务动作引用脚本级 val/函数 = 配置缓存报「script object references」，路径须配置期 `.get().asFile` 解析成 File、探测逻辑内联 doFirst；③ testing-DSL register 块的 autoReload/classpath 等任务级属性须进 `task {}` 子块（register 层只有 sandboxDirectory）。

- **首次实测失败定性 + 修复（master 7e1d3e0，2026-09-11）**：用户报「检查更新失败:network unavailable」→ 沙盒日志 `update manifest fetch failed: http://localhost:8919/update.json — Connection refused`（URL 转发正常，失败=点击时无服务）。根因：**IDE 把内存旧运行配置（runIde 任务）与磁盘新配置同时写回 `.run` 文件形成同名双配置**，用户点的是旧配置（无自动起服务）——.run 改动后必须在 IDE 里同步并重跑新配置。修复：.run 重写为单一 runIdeHotSwap 配置；manifest 转发改 jvmArgumentProviders 执行期读（配置期 System.getProperty 被配置缓存冻结，首次无 -D 存下空配置后续不转发）；去掉 runIde 测试 DSL 上未实际生效的 sandboxDirectory（专用沙盒目录从未创建），回归默认共享沙盒。

- **第二次失败定性 + 兜底（master 1a1faac）**：用户仍报失败，日志首行 `> Task :runIde`——IDE 内存残留在旧配置上（`.run` 磁盘文件被 IDE 反复写回成同名双配置，改一次写回一次）。兜底=**全局 runIde 也挂同一套 doFirst+finalizedBy 自动起停**（manifest 转发同步改执行期读），点哪个配置都通；autoReload 保留（热替换后自动重载器可能把新版再载一次，无害）。**教训：.run 文件改动对开着 IDE 的用户不生效——IDE 以内存运行配置为准并会回写磁盘，靠改文件分发不可靠，重要行为须落到 gradle 任务本身。**

- **实测通过收口（master 5083859，2026-09-11）**：用户 runIde 沙盒实测免重启热替换全链成功（本地清单→下载→校验→卸载→动态装载）。「runIde 真机留人工」验证项关闭。发版前仅余 verifyPlugin 三版本门禁。`.run` 按 IDE 实际状态提交（双配置并存、均带生命周期，消除磁盘与内存反复写回打架）。update.json 由 hotswap-local-server.py **每次启动时**自动重生成（自动选 build/distributions 最新 zip、从文件名解析版本）——下次测试只须先 `buildPlugin -Pversion=更高版本` 再点 Run 配置，手写 update.json 仅在绕开脚本手动 http.server 时才需要。
- **诊断小教训**：用户在 IDE 里见到 build.gradle.kts「应为标识符」报错但 `git status` 干净 + `./gradlew help` BUILD SUCCESSFUL = **IDE 编辑器缓冲区未保存改动所致**（多半是标红行的上一行漏引号/括号/逗号），磁盘文件以 gradle 解析为权威。

- **262 签名漂移修复（master 9c2788f，2026-09-11）**：用户日常 IDE Gitee 通道实测报重启，日志定位 `unloadPlugin(IdeaPluginDescriptorImpl) not found`——**262 unloadPlugin 首参漂移为其子类 PluginMainDescriptor**（243 仍基类；双参形态的 UnloadPluginOptions 有公开无参构造）。修复=首参弹性匹配（互为可赋值+最少参数优先）+ 双参自动构造 options。**教训：跨平台反射入口须逐方法 javap 实际签名——反编译元数据 d2 不体现重载参数类型漂移，本例 262 验证缺口由此产生**。陈旧编译产物第四犯（internal 函数名混淆 NoSuchMethodError）。Gitee 通道热替换须知：动态安装代码跑在**旧版插件**进程里——实测须本地构建含新代码的低版本包装机（Install from Disk+重启），再走 Gitee 更新到高版本；已发布的 2026.9.10 包不含新代码，其下次更新仍走 M28 脚本路径，**热替换能力自下个正式版起效**。

- **验证盲区制度化（master 9f8821d，2026-09-11，用户要求）**：262 漂移能溜到真实用户的根因=反射入口绕过编译期检查与 verifyPlugin、单测只跑 243。新增 `PlatformSignatureCrossVersionTest`：自动发现本机解压发行版（gradle transforms 含 verifyPlugin 下载的 **243/253/262 三套** + 已安装 IDE 兜底），各发行版**自带 JBR** 跑纯 Java 检查器（规避测试 JVM 17 读不了 262 major-69 类文件），按生产同款弹性逻辑核验五入口，漂移即本地测试失败；缺失版本 Assume 跳过。生产 allowLoadUnload 同步改弹性匹配。**工程坑**：外部进程 -cp 全测试类路径超 Windows 32K（error=206，只传检查器类目录）；codeSource.location 可能为 null（用 classloader resource 定位）；JBR 工具链 JVM 无 SystemJavaCompiler（检查器走 src/test/java 正常编译，勿测试期编译）。**方法论：今后新增跨版本反射入口，必须同步在此测试登记核验。**

- **「未完全卸载」警告溯源与缓解（master 171b7a3，2026-09-11）**：热替换后平台弹「插件未完全卸载，这可能导致功能问题」——262 反编译 `AwaitClassloaderUnloadAsyncPostReconfiguration`：重配置后 GCWatcher 观察旧 classloader 数秒，仍被强引用即弹（信息级）。**本次持有链=更新流程自身调用链 + 事件日志常驻的「立即安装」通知动作闭包**。影响定性：新版完整接管、旧 loader 仅一过性驻留（数 MB/重启清）、无功能增长点，JetBrains 自家动态更新亦常见。缓解=「立即安装」点击先 expire 源通知；不硬屏蔽平台诊断通道。另修：更新通知正文内嵌 Gitee 链接与动作行按钮重复，收敛为动作行一个，测试改断言正文无 href。

- **「未完全卸载」二次实测溯源（2026-09-11 下午）**：用户日常 IDE 再弹警告，日志显示本次更新**未走我们通知链**（update 链 INFO 零命中）且发生在 IDE 启动后 70s——持有者=**打开的项目环境里旧实例产生的存活对象**（编辑器 gutter 标记/索引缓存等，项目 32 万文件），平台卸载只清注册表清不掉已渲染产物。**关键注册表：`ide.plugins.unload.timeout=5000`**（平台仅等 5s 旧 classloader 被 GC，超时即弹 didn't-unload-cleanly 警告）；`ide.plugins.snapshot.on.unload.fail=false` 默认关。缓解=Registry 调大 timeout 至 30000；精确归因=点「保存内存快照」拿 hprof 查 PluginClassLoader 引用链。沙盒无项目环境故从不弹。

- **hprof 归因收口（master 7531cfb+200b187，2026-09-11，已全部清理完毕）**：用户按弹窗「保存内存快照」产出 1GB hprof，与 IDEA 自带内存分析器「传入引用」视图双重核对：旧 PluginClassLoader（24.89MB）引用全是**结构性互联**（parent/children 加载器链、Module、ProtectionDomain、classDataConsumer、旧描述对象、弱引用），仅本插件两处 static 小引用（RunConfigurationProcessListener.platformClassLoader、SqlLogConsoleBridge LOG，2-3KB 可忽略）。**定性=GC 时机非泄漏，零代码改动**：平台默认只等 5s（registry `ide.plugins.unload.timeout`），项目环境下 GC 未在窗口内回收即弹警告。用户侧缓解=Registry 调 timeout 30000 或点重启。收尾：快照与临时脚本目录 `C:\Users\USER\mat\` 已全部删除。**事故：1GB hprof 被误 `git commit`（未在 .gitignore），已 git rm --cached + amend 移除（7531cfb 为 amend 后版本）并加 `*.hprof` 入 gitignore（200b187）——大体积诊断产物禁止入库。**方法论红线（用户定例）：临时自写 hprof 解析器太耗时（调试数轮才对齐 IDEA 私有格式变体），此后堆分析一律用 IDEA 自带能力——弹窗「保存内存快照」→ hprof 直接用 IDEA 打开看传入引用/支配树。**

**Why:** 用户核心诉求「更新免重启」落地；本次为动态安装链路的第一次真实热替换实现。
**How to apply:** 后续 runIde 真机验证照 [[runide-demo-verify-workflow]]；发版走 [[release-verifyplugin-gate]]；回退场景=平台否决/反射缺失自动走 M28 脚本路径（行为与 v2026.9.10 等价）。关联 [[project-dynamic-unload-barriers]]（8-20 P1/P2 静态缓存项已审计定性为内存卫生非卸载屏障，暂不实施）。
