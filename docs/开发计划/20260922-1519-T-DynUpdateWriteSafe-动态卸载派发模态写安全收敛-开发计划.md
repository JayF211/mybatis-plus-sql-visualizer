# T-DynUpdateWriteSafe 动态卸载派发模态写安全收敛 — 开发计划

> 来源：2026-09-22 用户贴出真机日志 `java.lang.Throwable: Write-unsafe context! Model changes are
> allowed from write-safe contexts only. … current modality=ModalityState:{[DialogWrapperPeerImpl$MyDialog
> [dialog26,…,APPLICATION_MODAL,title=设置,…]], [Unloading of SQL Visualizer]}`。
> 定位依据：平台 262 源码逐帧（`TransactionGuardImpl` / `LaterInvocator` / `ModalityStateEx` /
> `IdeEventQueue` / `DynamicPluginsLegacyImpl` / `modality.kt`）+ 本机安装 IDE 的 registry 默认值实测
> （`util-8.jar!/misc/registry.properties`）+ 仓内派发点代码实证 + git blame。
> 相邻事项：与本仓既有 write-safe 系列（T-WriteSafeCtx、T-TS-03/04）**同族但不同形态**——那几轮的写
> 发生在「我们自己的回调体里」（词法守卫看得见），本轮的写发生在「平台卸载内部」（守卫结构上看不见，
> 见 §二.5）。与 `T-AgentConfigChannel` / `T-AgentSourceLocateStale` 等并行任务无写集交叠。

## 一、现象

用户在真机（设置对话框开启、插件被动态卸载的时间窗内）拿到平台记的一条 `Write-unsafe context!`。
模态串是判定问题的钥匙：它由**两个嵌套模态实体**组成——设置对话框（APPLICATION_MODAL）与
`Unloading of SQL Visualizer`。后者不是对话框，是平台动态卸载时压入的 ad-hoc 模态实体
（`DynamicPluginsLegacyImpl.kt:509` 的 `inModalContext(ObjectUtils.sentinel("Unloading of ${pluginDescriptor.name}"))`，
`ObjectUtils.sentinel` 的 `toString` 即该字符串；新实现默认关，`DynamicPluginsSupport.kt:51-62`
`Registry.is("dynamic.plugins.support.new.impl", false)` 为 false 时返回 null 而落到 legacy 实现）。

影响：写入本身照常完成、界面无异常；代价是每次自更新在用户日志留一条 SEVERE，并带
`Plugin to blame` 进入 FUS 错误上报（影响插件健康度），同一活动内 `myErrorReported` 只报一次；
测试环境下 logger 直接抛，故该形态在单测里表现为失败。判定开关对普通用户是开的：断言由
`LoadingState.COMPONENTS_LOADED && Registry.is("ide.require.transaction.for.model.changes", false)`
把门，而本机安装版 IU 261 的 `lib/util-8.jar!/misc/registry.properties` 实测该键 `=true`。

## 二、根因

### 1. 平台判定只看「模态实例 → 写安全」的一张表

```
TransactionGuardImpl:137-148  assertWriteSafeEnvironment()：EDT 且 myWritingAllowed==false 且
                              该模态未登记写安全 → LOG.error(reportWriteUnsafeContext(current))
TransactionGuardImpl:159-164  reportWriteUnsafeContext：消息原文（用户贴的就是它）
TransactionGuardImpl:131-134  isWriteSafeModality(state) = myWriteSafeModalities.get(state) == TRUE
TransactionGuardImpl:199-201  enteredModality(modality)：put(modality, myWritingAllowed)  ← 进入时快照
TransactionGuardImpl:203-220  wrapLaterInvocation：仅当 requested 模态写安全才套 runWithWritingAllowed
LaterInvocator.java:204-207   enterModal → guard.enteredModality(appendedState)
openapi/application/impl/modality.kt:9-17  inModalContext = enterModal + action + leaveModal
```

要点：**模态的写安全资格是「进入那一刻 `myWritingAllowed` 的快照」，且表以模态实例为键**。所以
「卸载窗口是否写安全」完全由**派发那一刻的 EDT 活动**决定，与卸载本身做了多少写无关。

### 2. 我们是用 `ModalityState.any()` 从后台任务派发这次卸载的

`DynamicInstallSupport.kt:226-248`（T-DynUpdate，2026-09-11 `23cf667` 引入）：

```kotlin
ApplicationManager.getApplication().invokeAndWait({
    … invoke(unloadPluginMethod, dynamicPlugins, …)        // 平台 unloadPlugin → inModalContext(sentinel)
    … invoke(installAndLoadMethod, …)
}, ModalityState.any())                                    // ← :248
```

调用链：`UpdateSection.kt:121` 按钮 → `runInstall()`（EDT，用户点击帧）→
`Task.Backgroundable.run()`（**后台线程**，`UpdateSection.kt:308-320`）→ `installRunner` →
`PluginUpdateRunner` → `DynamicInstallSupport.install` → `unloadAndInstallOnEdt` → 上面这段。

`any()` 排出的是 **posted runnable**：`IdeEventQueue.kt:1106-1122` 的
`performActivity(isInputEvent(e) || e is ItemEvent || e is FocusEvent, …)` 对 InvocationEvent 判 false
→ `myWritingAllowed=false`；`wrapLaterInvocation` 对 `any()`（不在表里）不套 `runWithWritingAllowed`。
于是 `inModalContext(sentinel)` 把「卸载窗口」登记成 **write-unsafe**，卸载期间平台自己的模型改动
全部中招。设置对话框在模态栈里，故模态串恰好是 `[设置对话框, Unloading of …]` 这个嵌套形态——与
「用户在设置页点下载并安装」完全吻合。

### 3. 卸载窗口里确实有大量模型改动

断言触发点（谁写谁中招）：

| 触发点 | 说明 |
|--------|------|
| `DocumentImpl.java:768-780` | 改**本地文件**支撑的文档（内存 PSI 不算） |
| `FileDocumentManagerImpl.java:265 / 349` | `saveAllDocuments()` / `saveDocument()`——**落盘也断言** |
| `PsiDocumentManagerBase.java:340/503/534/561` | PSI commit |
| `PsiModificationTrackerImpl.java:90` | PSI 修改计数 |
| `RefreshQueueImpl.kt:87` | 同步 VFS refresh |

卸载过程本身（`DynamicPluginsLegacyImpl.kt:560-660` 的 `runWriteAction { … }` 段）会 dispose 插件
组件（我们的 `dispose()` 链）、`IdeEventQueue.flushQueue()`（:605，会跑掉所有排队的 `any()` 回调）、
`PotemkinProgress` 泵事件、`ActionToolbarImpl.resetAllToolbars()` 等——都在这条判定之下。

### 4. 平台自己的卸载入口都不在这个形态里

平台发起卸载的三处（`DynamicPluginEnabler.kt:114`、`PluginDownloader.kt:399-409`、
`PluginInstaller.kt:118-123`）都是 `@RequiresEdt` 且由设置页按钮/更新动作**在用户活动帧内**同步调用，
进入 `enterModal` 时 `myWritingAllowed=true` → 卸载窗口写安全 → 平台自身不刷这条日志。平台把
「后台下载 + EDT 安装」这一形态的模态**显式传参**（`PluginManagerMain.java:87-118` 的 `modalityState`
参数，来源 `PluginsAdvertiserDialog.java:81` 的 `ModalityState.stateForComponent(myPanel)`），
只有我们这一处是自己拍了个 `any()`。

### 5. 既有守卫为什么没拦住

`BannedApiSourceScanTest` 的写安全规则是**词法**的：扫 `invokeLater/invokeAndWait` 站点的实参区与
lambda 体里有没有写令牌（`BannedApiSourceScanTest.kt:600-700`）。本处回调体里没有任何写令牌——写
发生在它**间接调用**的平台卸载内部，规则结构上永远看不见。T-WriteSafeCtx 逐站点收口（09-17）晚于
本行引入（09-11），收口时这一处不在写令牌命中面内。

## 三、影响面

- 触发链唯一：插件的**自更新动态热替换**路径（`DynamicInstallSupport` 是仓内唯一向平台
  `unloadPlugin`/`installAndLoadDynamicPlugin` 交棒的地方；grep 实证）。
- 频次：每次自更新 1 条（同活动内抑制重复）。用户侧可见形态 = 日志/错误上报，非功能故障。
- 功能侧的真实风险在别处（本次不扩大范围）：`any()` 包着 unload + install 两步，装载失败时插件停在
  「已卸载」态——这是既有回退链（`fallbackToRestartScript`）的职责，与本项无关。
- 不触发面：设置→插件里停用/更新本插件（平台入口，写安全帧）；插件首次安装。

## 四、修法

### T-01（P0，根因）派发模态改为「EDT 读当前模态 → 写安全则用它，否则 nonModal」

```kotlin
/** EDT 只读跳板取当前模态实例（ModalityState.current() 是 @RequiresEdt，本函数从后台任务线程调用）。 */
internal fun dispatchModalityForUnload(): ModalityState

/** 纯函数：写安全→原样用；取不到/非写安全→nonModal（延迟到模态结束，平台契约语义）。 */
internal fun chooseDispatchModality(captured: ModalityState?): ModalityState
```

- 为什么取「当前模态实例」：`accepts` 的 trivial 分支 `requested == this` 保证**立即执行**（不会被
  模态弹窗推迟），而该实例的写安全资格在设置对话框场景下为 true（对话框由用户动作进入）。
- 为什么非写安全时退 `nonModal` 而不是硬上：`nonModal` 恒 write-safe，是平台文档与仓内
  `DocWriteContext` 既有口径（「写入类回调一律 nonModal，延迟到弹窗关闭是正确语义，非退化」）。
- 为什么不改调用方签名：读跳板放在 `DynamicInstallSupport` 内，**所有入口**（设置页 / 更新通知 /
  启动检查）一次覆盖，`installRunner`/`install` 的既有测试缝不动。

### T-02（P1）判定可取证：派发时记一行模态与写安全判定

`LOG.info("dynamic update: dispatch modality=$modality writeSafe=${…}")`——真机复验时一眼可读
（这也是 §五 待验证前提 1 的唯一取证手段）。

### T-03（P1）单测三件套（性质断言 + 正对照 + 调用点钉住）

`update/DynamicUnloadWriteSafeTest`（平台测试基类，`test` 前缀）：

1. `chooseDispatchModality(any())` **不得**返回 any（改回 any 即红——本项的核心回归锁）；
2. `chooseDispatchModality(nonModal())` 原样保留、`chooseDispatchModality(null)` 退 nonModal；
3. **正对照**：`isWriteSafeModality(nonModal())=true` 且 `isWriteSafeModality(any())=false`——证明本
   环境的谓词具备判别力（防「谓词恒真」的空绿）；
4. 调用点钉住：注入 `edtDispatcher` 缝，断言 `unloadAndInstallOnEdt` 实际派发的模态非 any 且写安全。

### T-04（P1）守卫：`update` 包禁 `ModalityState.any()` 派发

按**包**封口而非按写令牌（写令牌在本形态永远看不见，见 §二.5），理由写进规则 KDoc；豁免沿用
「调用行上方 `// write-safe: <理由>`」既有口径（T-01 的只读跳板正需此标注）。附**负向自证**用例
（合成样本：违规必报 / 带标注放行 / 他包不受影响），对齐 T-TS-04 的守卫自证惯例。

### T-05（P1）文档三处同步

计划 §实施记录 + `docs/变更记录.md` 台账行（倒序插顶）+ `CHANGELOG.md [Unreleased] Fixed` 段。

## 五、待验证前提

| # | 前提 | 若不成立的后果 | 取证方式 |
|---|------|----------------|----------|
| 1 | 设置对话框的模态实例是 write-safe（其 `enterModal` 处于用户活动帧内） | T-01 落到 nonModal 分支：热替换延迟到设置关闭后执行（功能不坏，体验变化） | 真机日志新行 `dispatch modality=… writeSafe=true` |
| 2 | EDT 只读跳板取到的是模态栈顶实例，且请求它被平台按 trivial case 立即接受 | 跳板本身被推迟 → `invokeAndWait` 阻塞至模态结束 | 平台源码已判（`ModalityStateEx.accepts`）；真机观察热替换是否即时 |
| 3 | 真机端到端复现（自更新一轮）可做 | 本项只能给到「决策正确 + 单测钉住」，不能给「真机日志归零」的实测 | 见 §六.2：**开发态被 dev guard 挡住**，需安装版 + 更新源上有更新包 |

## 六、验收

1. **自动化**：定向（新测试类 + 守卫类）绿；全量 `testShards` 绿（基线按当轮实测口径报数）；
   守卫负向自证三态齐（违规必红 / 标注放行 / 他包不误伤）。
2. **真机（受限，如实登记）**：`DynamicInstallSupport` 的安装链在开发态被
   `PlatformPluginInstaller.devGuardOrFailure()` 主动拒绝（runIde 从 `build/classes` 加载，卸载会误删
   构建产物），且自更新需要更新源上存在**比当前安装版本更新**的发行包——两项都具备时方可复跑
   §五 前提 1。本轮**不具备**该条件，登记为「环境受限未验证」，禁止以推演冒充实测。
3. 反向验证：临时把 `dispatchModalityForUnload()` 改回 `ModalityState.any()` → 单测 1/4 与守卫必红，
   随后逐字还原（附两次读数）。

## 七、不做的事

- **不收编「卸载窗口内被 flush 的 `any()` 回调」**：那是另一族（posted runnable 一律
  `performWritingAllowed=false`），既有词法守卫按写令牌覆盖；本项只修「我们自己制造的写不安全窗口」。
- **不动卸载/装载序列本身**（unload → install 手工编排的理由见类 KDoc，T-DynUpdate 已定案）。
- **不给 dev guard 开后门**（不为可测性引入生产侧开关）。

## 八、待用户定案

| 选项 | 做法 | 利 | 弊 | 结论 |
|------|------|----|----|------|
| A | 当前模态写安全则用，否则 `nonModal` | 契约合规；平台文档与仓内既有口径一致 | 非写安全场景下热替换推迟到模态结束 | **— 已定案**（用户 2026-09-22 授权按推荐方案实施） |
| B | 一律 `ModalityState.any()`（现状） | 永不被模态推迟 | 每次自更新记 SEVERE + FUS 上报 | ~~（**弃用**）~~ |
| C | 一律 `ModalityState.nonModal()` | 恒 write-safe，代码最简 | 设置页点安装必推迟到关设置（常见路径体验退化） | ~~（**弃用**）~~ |
| D | 调用方（EDT 点击帧）捕获模态并逐层传参 | 平台自身同款做法（`PluginManagerMain`） | 三个文件签名与测试缝连锁改动；更新通知/启动检查入口需各自接线 | ~~（**弃用**）~~ |

## 九、涉及文件清单

- `src/main/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.kt`（T-01/T-02）
- `src/test/kotlin/cn/song/mybatisplus/visualizer/update/DynamicUnloadWriteSafeTest.kt`（新增，T-03）
- `src/test/kotlin/cn/song/mybatisplus/visualizer/convention/BannedApiSourceScanTest.kt`（T-04）
- `docs/变更记录.md` / `CHANGELOG.md` / 本计划（T-05）

## 实施记录

### 一、车道与提交（2026-09-22）

- 工作树 `F:/code/Java/Gitee/SongRenShuo/mpsv-dynws`，分支 `t-dynws-writesafe`，基点 `master` `ddb694a`。
- 代码提交 `b7ac235`（本计划 + `DynamicInstallSupport.kt` + 新测试类 + 守卫规则，4 文件 +476/−4）；
  文档同步提交（计划实施记录 / 变更记录 / CHANGELOG）。
- 两度并 master 均零冲突（期间 master 被并行会话推进两次）：`7bb7eab`（T-ExplainChainHardening + T-ErrorLocateUsability）→ `3ef8236`，`0abc1bb`（T-AgentResiduals）→ 见提交历史。
- §四 T-01~T-04 全量落地；T-04 的规则形态在实施中被修正（见 §四）。

### 二、读数

| 项 | 读数 | 说明 |
|----|------|------|
| 定向（新测试类 + 守卫类） | 32 例 / 0 失败 | 守卫类由 24 例增至 26 例（本项 +2） |
| 全量 `testShards`（并入 master 后，清 `build/classes`+`build/kotlin` 重跑） | **498 类 / 5695 例 / 1 失败 / 0 错误 / 6 跳过** | `7bb7eab` 并轨点读数；含本项新增 1 类 8 例 |
| 全量 `testShards`（再并 `0abc1bb` 后的最终树，即待并入 master 的那棵树） | **499 类 / 5705 例 / 1 失败 / 0 错误 / 6 跳过** | 两段链读数（另一段来自 T-AgentResiduals 自身门禁）；唯一失败仍为 **master 既有**（见 §五） |

### 三、反向验证（两臂；均临时改动 → 必红 → 逐字节还原，`sha256` 还原前后一致）

| 臂 | 变异 | 实测 |
|----|------|------|
| M1 | 派发点改回改动前形态 `val modality = ModalityState.any()` | 32 例 **2 失败**：`update包派发平台卸载装载禁用any模态`、`testUnloadDispatchNeverUsesAnyModality` |
| M2 | `dispatchModalityForUnload()` 直接 `return ModalityState.any()` | 32 例 **3 失败**：上两例 + `testResolvedDispatchModalityIsWriteSafe` |

两臂合起来覆盖「派发点被改回」与「决策函数被绕过」两种真实回退形态；守卫侧三态自证（违规必报 /
带标注放行 / 他包不误伤）随规则同批落地。

### 四、实施中对计划的两处修正

1. **T-04 规则形态（计划口径被实施证伪）**：原计划写「按包 + 派发模态（站点实参区）封口」，实施后发现该
   形态只覆盖 `invokeAndWait({…}, ModalityState.any())` 一种写法——把模态赋给变量再传、或决策函数直接
   返回 `any()`，规则都看不见。改为**按包 + 令牌**（update 包内任何形态的 `ModalityState.any()` 都报，
   豁免沿用 `// write-safe:` 标注），与写法无关。顺带实测发现既有提取器 `invokeLaterSites` 只认
   `invokeLater(`——`invokeAndWait` 站点对两条既有写安全规则从未生效；本轮**登记不改**（属
   T-WriteSafeCtx 规则族的面），故最终实现不依赖该提取器。
2. **派发模态来源（计划前提被实施收窄）**：`ModalityState.current()` 是 `@RequiresEdt`，
   `Application.getCurrentModalityState()` 是 `@Deprecated @ApiStatus.Internal`，
   `defaultModalityState()` 在后台线程可能返回 `nonModal`（模态弹窗存续期被推迟 → 设置页点安装体验
   退化）。故「EDT 只读跳板取当前模态实例」是同时满足「立即执行」与「写安全」的唯一形态，方案成立。

### 五、未取证项与残留

- **真机端到端未验（环境受限，如实登记）**：动态安装链在开发态被
  `PlatformPluginInstaller.devGuardOrFailure()` 主动拒绝，且自更新需更新源上存在比当前安装版本更新的
  发行包——两项均不具备。§五 前提 1/2 的真机证据只能由派发日志行
  `dynamic update: dispatch modality=… writeSafe=…` 产出，本轮无法产出。
- **master 既有红（非本项引入）**：`EnumReachabilityGuardTest` 1 例失败，指向 `sql/ExplainSupport.kt:19`
  的 `ExplainOutputFormat.XML`——T-ExplainChainHardening 有意留的占位枚举值（KDoc 自述「本插件未接入，
  仅占位」），零产出点故被枚举可达性守卫判死常量。本项 diff 未触及该文件
  （`git diff 7bb7eab..t-dynws-writesafe` 仅 4 文件），系并入 master 时带进来的既有红；该任务有在途复核
  会话，本项**登记不修**。
- **环境噪音（已如实登记，非代码问题）**：①首轮门禁 5 例 `ConnectionErrorClassifierTest` `NoSuchMethodError`
  经清 `build/classes` + `build/kotlin` 重跑证伪为增量陈旧类；②本会话一次被超时中断的 gradle 运行遗留了 3 个分片
  测试 JVM，锁住本工作树 `build/test-sandbox/*` 的沙盒 jar 致后续 `prepareSandbox_testShardN` 失败——只 kill
  命令行含本工作树名的进程（`mpsv-dynws`）后恢复；③共享 transform 缓存两次被并行会话的清理动作打坏
  （`Unable to delete directory …transforms/…` / `Cannot resolve 'product-info.json'`），等待自愈后重跑即恢复
  （未删共享缓存、未 kill 他人进程）。
- **既有债登记（本轮不改）**：`BannedApiSourceScanTest` 的站点提取器 `invokeLaterSites` 只认 `invokeLater(`，
  `invokeAndWait` 站点对两条既有写安全规则从未生效（本项事故点正是 `invokeAndWait` 形态）。修它属
  T-WriteSafeCtx 规则族的面，本项以「按包 + 令牌」规则绕过该缺口，缺口本身留待该族收口。
- **首轮门禁另有 5 例失败已证伪**：`ConnectionErrorClassifierTest` 5 例 `NoSuchMethodError`（并入 master
  后测试类解析旧构造签名），清 `build/classes` + `build/kotlin` 重跑即全绿——属回归假失败清单既有形态
  （多分支合并后的增量陈旧类）。
