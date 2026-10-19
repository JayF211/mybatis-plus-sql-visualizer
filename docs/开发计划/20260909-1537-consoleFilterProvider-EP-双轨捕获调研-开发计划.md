# consoleFilterProvider EP 双轨捕获调研 开发计划

> **创建日期**：2026-09-09
> **分支**：dev（当前 HEAD `399eb3b`）
> **状态**：立项调研——**仅调研与方案设计，不实施**（用户明确「先不要实施」）
> **优先级**：P2（消灭日志捕捉链路唯一的常驻开销——反射桥 + 轮询兜底；非功能缺陷，不阻塞当前功能）
> **来源**：T-PerfBatch 计划 §4.3 的 P2 调研项。同类工具用稳定的 `consoleFilterProvider` 扩展点注册 Filter（声明该 EP，由 `getDefaultFilters` 返回单个过滤器实例），运行期零挂载负担、零轮询、零组件树遍历。本项目当前用反射桥 + 轮询兜底（T-M31-03），每拍仍有 `hasMainConsole`（descriptor 反射 + `isProcessAlive` 进程存活反射）+ `findConsoleView` 递归组件树遍历。本计划评估把 EP 作为主通道、反射桥降级为兜底的双轨方案。

---

## 一、背景与目标

### 1.1 现状（dev 实测核对）

- 捕获链路：`LogStartupCapture`（postStartupActivity）→ `SqlLogConsoleBridge.attach` 经 `runContentManagerOrNull` 双包名候选 `Class.forName`（`com.intellij.execution.RunContentManager` / `com.intellij.execution.ui.RunContentManager`，SqlLogConsoleBridge.kt:442-445）→ `attachContentListener`（`ContentManagerListener`，262 无该方法 → 轮询兜底）→ 轮询 `scanAll`（`Timer`，`cachedMethod` 反射已缓存、`shouldSkipRescan` 已短路，但每拍仍 `hasMainConsole` 反射 + `isProcessAlive` 反射）。
- 逐行解析：`LogFilterProxyHandler`（`java.lang.reflect.Proxy` 实现 `ConsoleView$Filter`/`execution.filters.Filter` 双候选，T-M31.12）经 `console.javaClass.getMethod("addMessageFilter", filterIface).invoke(...)` 反射挂载（SqlLogConsoleBridge.kt:328）。
- 全程反射的根因：IPGP 裁剪 execution 模块，插件编译 classpath 无 `com.intellij.execution.*`，运行期由真实 IDE 提供。

### 1.2 EP 的优势（对照平台契约结论）

`consoleFilterProvider` 是 JetBrains 平台**长期稳定**的扩展点（`com.intellij.execution.filters.ConsoleFilterProvider`），263/262/253/243 均未破坏：
- 平台在**每个控制台创建时**自动装配 Filter，`applyFilter` 逐行回调——**零轮询、零反射挂载、零组件树遍历**，彻底消灭 `SqlLogConsoleBridge` 的常驻 EDT 轮询负载（性能分析 §3.1 的 P1 项）。
- Provider 需要 Project：`ConsoleFilterProvider.getDefaultFilters(Project)`，与 `RunContentManager` 的 per-project 语义对齐。
- 同类工具的生产用法就是这一个 EP（实测），无任何配套轮询。

### 1.3 双轨的必要性

不能直接删反射桥改 EP，因为：
1. **`LogStartupCapture` 手动捕捉语义**：用户显式「开始捕捉」是外显门控，EP 由平台无差别装配到**所有控制台**（含数据库控制台、HttpSingleResponseConsole 等），需要区分「哪些控制台属于我们要捕的运行控制台」。
2. **`capturing` 状态机**：现桥在 capturing=false 时**不挂 Filter**（`attachFilter` 守卫，SqlLogConsoleBridge.kt 附近），EP 一旦注册就始终装配，须在 `applyFilter` 内做 capturing 守卫。
3. **运行期降级兜底**：EP 契约在 IPGP 裁剪发行版需 runIde 真机验证，反射桥已验证可用，保留为降级通道最稳妥。

---

## 二、调研与方案设计（本轮要做的事，不写生产代码）

### 2.1 平台契约核验（三重交叉核实）

1. **jar 反编译**：`com.intellij.execution.filters.ConsoleFilterProvider` 与 `Filter.applyFilter(String line, int entireLength)` 在 243/253/262 三发行版的存在与签名（app-client.jar / platform 模块）。目标：确认 `getDefaultFilters` 返回值形态、`Filter.Result`/`Filter.HyperlinkInfo` 是否被裁剪。
2. **MD 交叉核实**：`ConsoleFilterProvider` 是否 `DumbAware`、是否需要 `Project` 上下文、能否在 provider 内获取 `RunContentDescriptor`（用于区分运行控制台 vs 数据库控制台）。
3. **现有插件参考**：同类工具的 EP 实现是最简参考（单类单过滤器）；batslog 开源仓库可查其 ConsoleLogParser/ConsoleFilterProvider 的完整体。

### 2.2 双轨架构设计要点

- **EP 为主**：新增 `SqlLogConsoleFilterProvider : ConsoleFilterProvider`，`getDefaultFilters(project)` 返回单例 `ConsoleSqlLogFilter(project)`；`applyFilter(line, entireLength)` 内先 `capturing` 守卫（懒查 `SqlLogManager`/`PluginSettings`），命中的行转发给现有 `processConsoleLine` 管线（复用 `parseLine → buffer.addEntry`，零重复解析）。
- **反射桥为兜底**：`SqlLogConsoleBridge` 保留，但默认关闭轮询（`startPolling` 只在「EP 未生效」检测后启用——通过 runIde 真机验证 EP 的 `applyFilter` 是否真的被平台调起）。检测方式：EP Filter 里置一个「已收到首行」标志，桥侧在 N 拍内未见标志则启用轮询兜底。
- **去重**：双通道都进 `SqlLogEntryBuffer.addEntry`，buffer 的去重/指纹/淘汰天然去重（同一条目不重复入列），无额外双挂载去重逻辑（核实 `isDuplicate` 窗口语义是否覆盖跨通道毫秒级重复）。
- **capturing 状态同步**：EP 侧每行读 capturing 是 O(1) volatile 读（`SqlLogManager.capturing` 已 `@Volatile`），无设置读取成本；或经 `plugin.xml` `<projectService>` 直接注入 manager 引用。

### 2.3 关键风险与验证项（写入计划，供实施时逐条核）

1. **EP 是否 262 真会装配到运行控制台**：runIde 真机（demo 项目 run 一个打 SQL 的 Spring Boot），日志断言「EP 首行标志置位」——这是双轨方案成立的先决条件，不敢在沙盒猜。
2. **EP 与反射桥双挂载是否双解析**：若 EP 已生效仍保留桥，同一行会被解析两次 → 虽 buffer 去重抵消条目重复，但解析成本翻倍；须在「EP 生效」后**主动 detach 桥的轮询**（挂载保留、轮询停）。
3. **数据库控制台误捕**：EP 无差别装配，`applyFilter` 须过滤（`entireLength` 或 `RunContentDescriptor`/`ConsoleViewImpl` 判定）只捕运行控制台——与现桥的 `hasMainConsole`（排除 `HttpSingleResponseConsole`）同语义，需把该判定下沉到共享层。
4. **`Filter.getDefaultFilters` 返回的 Filter 是否被平台复用/缓存**：若每行重建 Filter 实例，`parseLine` 的状态机（parser pending 态）会被打散——须确认平台对 provider 返回 Filter 的生命周期（通常 getDefaultFilters 调一次、Filter 常驻）。
5. **手动「停止」后 EP 是否仍被调用**：`applyFilter` 每行都进，只是 `capturing=false` 早退；与现桥「停止即 detach Filter」等价（早退成本 O(1)，无解析）。

### 2.4 产出物（本轮调研 + 计划，不含生产代码）

- 本计划文档（§一至§四的能力评估 + 双轨设计 + 风险核验清单）；
- 一个「EP 契约核验笔记」（平台 EP 签名核验 + MD 结论 + batslog 参考，附在本计划 §附录或独立文档）；
- 明确「实施前必须过的真机门禁」（§2.3 第 1 条）——作为后续实施计划的起手条件。

---

## 三、原 T-PerfBatch §4.3 与该计划的承接关系

T-PerfBatch 计划把「consoleFilterProvider EP 双轨」列为 P2 调研，结论是「涉及 T-M31-03 链路改造与双挂载去重，需 runIde 真机验证」。本计划是该调研的落地：在**不写一行生产代码**的前提下，把「EP 契约是否裁剪」「双轨如何设计」「真机门禁是什么」三件事查清定稿。实施另立后续任务（届时复用本计划的架构设计 + 核验清单）。

## 四、不实施声明与范围

- **本轮不做**：不注册 plugin.xml EP、不写 `ConsoleFilterProvider` 实现、不改 `SqlLogConsoleBridge`、不跑真机。
- **本轮要做**：完成 §2.1 平台契约核验（EP 签名 + MD + batslog 参考）、§2.2 双轨设计定稿、§2.3 风险核验清单，并回填本计划的「调研结论」章节。
- **产出落位**：本计划文档留在 `docs/开发计划/`；契约核验结论直接写入本文档，不另建文件（避免碎片化）。

## 五、实施记录（2026-09-09）

> 本计划立项当日，T-PerfBatch 计划在 dev 上的「批量通知节流」改动点经全量回归发现外溢破坏（单通道耦合三语义），已回退。本计划作为独立 P2 调研文档与之并交，记录一份立即可复用的事实：**EP 双轨是 T-PerfBatch「捕获→UI 解耦」之外、另一个消灭常驻开销的正交手段**——两条路径互不依赖，可分别推进。

**本计划立项后的差异（相对 T-PerfBatch §4.3 的承接收敛点）**：
1. 命名时间戳从 `20260909-0000` 修正为 `20260909-1537`（同类两份计划统一，避免同一功能两个 `0000` 占位重名）；
2. 明确「仅立项不实施」边界收得更死——§2.1~2.3 的契约核验/双轨设计/风险清单是**本轮的调研产出**，而非可选后续；生产代码与真机验证全部压到后续实施任务；
3. 双轨「detach 桥轮询」的时机从「EP 生效即 detach」收敛为「EP 首行标志置位后 N 拍内未见则启轮询、已置位则停轮询」——把「EP 是否真生效」从假设变成可观测的运行时标志，避免凭空信任平台契约。

**与 T-PerfBatch 的关系（两条正交的性能路径，均已立项待实施）**：
- T-PerfBatch（改动点一）：捕获线程 → buffer 通知的**EDT 调度节流**，前置「监听器分层」重构；
- 本计划（EP 双轨）：消灭**挂载/轮询阶段**的常驻 EDT 负担，前置「真机门禁」验证 EP 契约。

（后续调研回填：三发行版 EP 签名核验结论、MD 结论、batslog 参考要点、双轨设计是否调整、真机门禁最终清单。）