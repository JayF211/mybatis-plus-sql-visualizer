---
name: project-dynupdate-hotswap
description: T-DynUpdate 自更新免重启热替换已交付实测（master 28b884b
  阶段，2026-09-11~12）——五入口反射范式/262 签名漂移护栏/「未完全卸载」hprof 定性，经验已沉淀 docs/经验总结
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5ce73ad8-8285-4465-86de-bef0cd909427
---

T-DynUpdate 免重启热替换已交付并实测通过。**权威经验文档：`docs/经验总结/20260912-0855-插件免重启更新与跨版本签名核验-经验总结.md`（master 28b884b，随仓库发布）**——平台机制/五入口反射范式/回退链/踩坑 11 条/四层验证体系/本地热替换测试三步均在其中，优先读文档而非本记忆。

要点速记：

- 根因链：T-M23.10「vetoer 否决动态卸载」系误判（performanceTesting vetoer 只护自身且仅回放期）→ M28 脚本路径锁死必重启；插件 9/9 EP 全 dynamic 合规。
- 实现：`DynamicInstallSupport`（zip 前置校验→dev 守卫→反射五入口探测→allowLoadUnload 预检→EDT unload+install）+ `PlatformPluginInstaller.installRestartScript` 降级兜底；`InstallResult` 三分支热生效/回退重启/失败。
- 关键签名漂移：262 `unloadPlugin` 首参 `IdeaPluginDescriptorImpl`→子类 `PluginMainDescriptor`；已弹性首参匹配 + `PlatformSignatureCrossVersionTest`（243/253/262 真实平台 jar 逐版本核验五入口，**新增跨版本反射入口必须同步登记**）。
- 「插件未完全卸载」警告：GCWatcher 只等 5s（registry `ide.plugins.unload.timeout`），项目环境下 GC 未及回收 24.89MB 结构性互联（parent/children 链等，非泄漏）即弹；hprof 实证零功能影响；缓解=Registry 调 30000 或点重启，不屏蔽平台诊断通道。
- 热替换能力自「含新代码的正式版」发布后才对真实用户生效；Gitee 通道实测需本地构建含新代码的低版本包装机。
- 工程坑速查（详见经验文档 §四）：KDoc 内 `路径/*` 触发 Kotlin 嵌套注释级联语法错；`.run` 改动对运行中 IDE 不生效且会被内存副本写回（重要行为落 gradle 任务）；配置缓存冻结配置期 System.getProperty（动态值走 jvmArgumentProviders 执行期读）；Kotlin DSL 三坑（java 访问器占位/CC 脚本引用/testing-DSL task{} 子块）；陈旧编译产物劫持已四犯（清 build/classes+kotlin）；跨版本反射须逐方法 javap（d2 元数据不体现参数漂移）；堆分析用 IDEA 自带能力勿自写解析器（hprof 快照已入 gitignore）。
- **clean 会弄坏普通 runIde（2026-09-12 实证）**：普通 `runIde` 的 doFirst 也无条件起 hotswap-local-server.py（为旧运行配置兜底，build.gradle.kts 两任务都挂）；该脚本启动即 `os.listdir(build/distributions)` 找最新 zip，目录被 clean 删掉后 FileNotFoundError → 「hotswap server exited during startup」→ runIde 整体失败。恢复=`./gradlew buildPlugin` 重建 zip。加固建议（已提出未实施）：runIde 的 doFirst 在 distributions 无 zip 时打提示跳过起服务，runIdeHotSwap 保持硬失败。

**Why:** 用户核心诉求「更新免重启」完整落地并实测；期间暴露的跨版本验证盲区已制度化为签名护栏测试。
**How to apply:** 本地验证三步见经验文档 §六；发版前 verifyPlugin 三版本门禁照旧；关联 [[project-dynamic-unload-barriers]]（静态缓存定性为内存卫生非卸载屏障）。
