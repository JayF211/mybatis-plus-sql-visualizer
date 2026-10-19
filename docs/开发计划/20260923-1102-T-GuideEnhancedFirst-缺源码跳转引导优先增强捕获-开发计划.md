# T-GuideEnhancedFirst 缺源码跳转引导优先增强捕获 — 开发计划

- 日期：2026-09-23 11:02
- 任务编号：T-GuideEnhancedFirst（**O-66**，用户 2026-09-23 截图反馈立项）
- 来源：用户实测——SQL 条目无源码位置时的「无法跳转到源码」引导弹框，当前把「打开设置」排为首选出口；而增强捕获（agent 通道）本身就是携带源码位置的通道，引导应**优先提示开启增强捕获**（一键开启），而不是让用户自己去设置页翻开关
- 性质：UX 引导（无行为缺陷，两个既有出口都保留）
- 实施分支：自 master 拉任务分支（或按派单在在途回合分支上实施，以派单为准）
- 验证基线：master 快照 `1ff820c`

---

## 一、现状与依据（已核实锚点）

### 1.1 引导弹框现状

| 锚点 | 现态 |
|---|---|
| `log/SourceCaptureGuide.kt:34-53` | `showMissingSourceDialog`：三按钮 `[打开设置][立即注入拦截器][取消]`，默认按钮序 0＝打开设置；`OpenSettings → ShowSettingsUtil.showSettingsDialog(project, SqlLogConfigurable::class.java)`；`InjectNow → runInjection` |
| bundle 键 | zh `MpSqlVisualizerBundle_zh.properties:776-779` / en（默认 bundle）`:779-783`：`log.jump.guide.title/message/openSettings/injectNow/cancel`；message 文案只提「源码捕获/拦截器」，**通篇未提增强捕获** |
| 调用点 | 实时面板 `SqlLogPanel.kt:1621`（`MissingSource` 分支）、历史面板 `HistorySqlLogPanel.kt:649`（`NoSource` 分支）——两处入口共用，`jumpToSource(entry)` 手上都有 entry |
| 既有测试 | `SourceCaptureGuideChoiceTest`（返回码映射 + 键面断言）；双语 parity 由 `i18n/BundleParityGuardTest` 兜底 |

### 1.2 增强捕获 = 携带源码位置的通道（本立项的依据）

- agent 子项目：`StackLocator` 解析调用点 → `SqlEvent.sourceFile`（`sql-visualizer-agent/.../model/SqlEvent.java:22`）→ JSON `sf` 字段。
- 主插件侧：`AgentEventAdapter.kt:27` 把 `event.sourceFile` 直通面板条目 `sourceFile`；通道标记 `SqlSource.AGENT`（`LogSqlModels.kt:14`）。
- 结论：条目缺源码位置的高频根因（增强捕获未开启/未授权）恰恰是本弹框**没有引导到**的那条路，现文案把用户推向「打开设置」或「注入拦截器」，前者要自己找开关，后者要改业务工程代码并重启——增强捕获是两个出口里对用户最省事的（不改工程代码，同意一次 + 重启应用）。

### 1.3 增强捕获状态模型（引导分支的判定面）

- 全局开关（`PluginSettings`，APP 级，落 `<config>/options/mp-sql-visualizer.xml`）：`enhancedCaptureEnabled`（默认 true）/ `enhancedCaptureConfirmed`（默认 false）/ `enhancedCaptureRejectedAtMs`（24h 拒绝冷却）。
- 弹确认框的既有形态：设置页勾选未确认态时 `EnhancedCaptureConfirmDialog.confirm(null)` → 同意则直写 `state.enhancedCaptureConfirmed = true`（`PluginSettingsPanel.kt:879-890`）。
- 「重新授权」既有形态：`AgentManager.requestReauthorization()`（`AgentManager.kt:436-445`）＝清零冷却 + 重走确认流（无进程句柄则只恢复授权，挂载由下一次应用进程启动承接）；`enabled=false` 时早退忽略。
- 关键时序：agent 挂载发生在**主配置进程出现时**（`onProcessAppeared`，`AgentManager.kt:390-410`）——开启/授权动作本身不会碰已在运行的应用，**生效需重启应用**（与拦截器注入「生成后需重启应用生效」同话术）。
- 设置变更广播：`SETTINGS_CHANGE_TOPIC` 仅由设置页 `apply()` 发布（`PluginSettingsPanel.kt:1636`）；直写 state 不广播。AgentManager 监听该 topic，「关闭→再开启」上升沿自动清零拒绝冷却（`AgentManager.kt:258-261`）。

## 二、待验证前提（实施前先证伪）

1. **开启动作不会挂到已在运行的应用进程**：判据是 `onProcessAppeared` 为唯一挂载入口、无轮询扫描（代码可判）；实施时 grep `doAttachAsync` 全部调用面复核。成立则话术必须写「重启应用后生效」，且不能承诺「立即开始捕获」。
2. **直写 `state.enhancedCaptureEnabled = true` + 手动发布 `SETTINGS_CHANGE_TOPIC` 等价于设置页 Apply**：发布路径照抄 `PluginSettingsPanel.kt:1636`（从模态对话框 EDT 直调）；需复核发布后 AgentManager 上升沿清冷却与 CONFIG 下发链无序要求。
3. **`requestReauthorization()` 可直接复用为确认流**：`enabled=false` 时早退（前提 2 先把 enabled 置 true 再调用即绕开）；接受后落 confirmed、无句柄不挂载——语义与本弹框一致。需复核其 `consentProvider` 默认实现 headless 行为不影响测试。
4. **`SqlSource.AGENT` 且 `sourceFile == null` 的条目真实存在**（StackLocator 未命中 / 帧过滤命中）：若存在，对这类条目提示「开启增强捕获」是误导（已经开着）——D2 决策点，实施时先造样实证。
5. **设置页同时打开时的表现**：直写 state 后面板 `isModified` 对比可能显示脏勾选（面板 checkbox 未重读）——低频场景，登记缓解口径即可，不阻塞。

## 三、方案

### T-GEF-01 状态感知引导编排（SourceCaptureGuide）

新纯函数（测试驱动，不触 UI）：

```
decideActions(enabled: Boolean, confirmed: Boolean): GuideBranch
  ├─ !enabled || !confirmed → GuideBranch.EnhanceFirst
  │    按钮 [开启增强捕获(默认)][立即注入拦截器][取消]；message 换增强捕获优先文案
  └─ enabled && confirmed  → GuideBranch.Legacy
       维持现态 [打开设置][立即注入拦截器][取消]（缺位置另有原因：帧过滤前缀命中 /
       应用未重启 / agent 未挂载）；message 末尾附一行增强捕获实时状态
       （D1 定案＝做：AgentManager.statusSummary() + 复用 log.diag.enhanced 键）
```

- `GuideChoice` 增 `EnableEnhanced`；`resolveChoice` 按**分支**给数组序映射（两条序表：EnhanceFirst＝0 开启 / 1 注入 / 2 取消；Legacy 维持现序）——返回码映射必须带分支参数，防止两条序表错位。
- 开启动作（UI 编排，仅 EnhanceFirst 分支按钮触发）：
  1. `!enabled`：直写 `state.enhancedCaptureEnabled = true` + 发布 `SETTINGS_CHANGE_TOPIC`（上升沿自动清冷却）；
  2. `AgentManager.getInstance(project).requestReauthorization()` → 弹 `EnhancedCaptureConfirmDialog`（三态持久化语义全部沿用既有链，不新写持久化代码）。`requestReauthorization` / `requestConsentThenAttach` 增加可选 `onOutcome` 回调（默认空实现，既有调用方零行为变化）——同意后由回调展示生效提示，持久化仍单点收在 AgentManager，不在引导侧复写状态机；
  3. 同意 → info 弹窗「已开启增强捕获，重新启动应用后生效」（`requestReauthorization` 无句柄不挂载，等下次启动，话术与之一致）；拒绝/关闭 → 静默（尊重 24h 冷却，不重复打扰）。
  4. `enabled && !confirmed`：跳过第 1 步直接进 2~3。

### T-GEF-02 文案（双语同步，parity 守卫兜底）

- 新 `log.jump.guide.enableEnhanced`＝`开启增强捕获` / `Enable Enhanced Capture`；
- 新 `log.jump.guide.message.enhanceFirst`（EnhanceFirst 分支 message）：以增强捕获为首选项说明（应用 JVM 内捕获真实调用点、同意一次、重启应用生效），拦截器注入为次选（改工程代码、重启生效）；
- 现键 `log.jump.guide.message` 保留给 Legacy 分支不动；
- 新 `log.jump.guide.enhanceDone`＝同意后的生效提示；
- zh 与默认（en）bundle 逐键同步，`BundleParityGuardTest` 兜底。

### T-GEF-03 测试（先红后绿 + 反向验证）

- `SourceCaptureGuideChoiceTest` 扩展：
  - `decideActions` 三态输入 → 分支判定（未开启 / 开了没授权 → EnhanceFirst；开了已授权 → Legacy）；
  - 两条序表各自映射 + 关闭/负值兜底 Cancel（现测试同款）；
  - 新键键面有真实文案（现测试同款循环）。
- 反向验证：把 `decideActions` 的判定条件改回恒 Legacy（或删分支）→ 新增判定断言必红，贴改前红/改后绿两次读数。
- 开启动作编排：headless 下 `Messages.showDialog` 不可测，编排函数拆薄（写入 + 发布 + requestReauthorization 的调用序），参照 `SettingsTruthTest` / `EnhancedCaptureConsentDialogTest` 的测试缝口径实施时定；`requestReauthorization` 的三态持久化语义已有链路，不重复造测试。
- 定向范围：`--tests "cn.song.mybatisplus.visualizer.log.SourceCaptureGuideChoiceTest"` + `--tests "cn.song.mybatisplus.visualizer.i18n.BundleParityGuardTest"`；全量由收口代理统一跑。

### T-GEF-04 文档

- CHANGELOG `[Unreleased]` Added/Changed 行 + 变更记录行；
- 若全景盘点有引导链（T-JumpCapture）描述，补一句引导分支口径。

## 四、验收标准

1. 增强捕获未开启（或未授权）时，无位置条目点「跳转到源码」→ 弹框默认按钮＝开启增强捕获、文案以增强捕获为首选项；点开 → 确认框 → 同意 → `enabled=true`（如原关）+ `confirmed=true` + 生效提示弹窗，设置页状态行转「已授权，等待应用进程启动后挂载」。
2. 重启应用后新条目带源码位置，跳转精确命中（真机走查覆盖）。
3. 已开启已授权态弹框维持现态三分支与现文案，末尾附一行增强捕获实时状态（D1），行为零变化。
4. 反向验证两次读数留档；`SourceCaptureGuideChoiceTest`、`BundleParityGuardTest`、`AgentStatusTextTest` 定向全绿；全量由收口代理跑。

## 五、真机走查清单

- runIde 沙盒（新工作树 config 的 `enhancedCaptureConfirmed` 缺省＝天然未授权样本，授权按配置目录隔离）：跑 demo 应用产生无位置条目 → 点跳转 → 走新引导 → 同意 → 重启应用 → 条目带位置、跳转命中。
- 走查完把沙盒 config 的增强捕获开关还原（premain 会挂进后续 fork JVM，T-GradleTaskInjectScope 在册未定案）。
- 历史面板入口（`HistorySqlLogPanel.kt:649`）同样点一遍（两面板共用编排，重点验文案分支一致）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 设置页同开时的勾选脏态 | 直写 state 后打开着的设置面板 checkbox 不重读，`isModified` 可能显示脏 | 低频；apply 即一致（写的是同一 state）；登记口径，不做面板联动 |
| 确认框拒绝后的打扰循环 | 引导弹框每次用户点击才出现，不主动弹；确认框有 24h 冷却 | 静默拒绝分支 + 既有冷却语义，不加新打扰 |
| 「开启增强捕获」对 AGENT 通道无位置条目属误导 | 条目已来自 agent（帧过滤/未命中）时开关本就开着 | Legacy 分支不出现该按钮（enabled 已 true）；D2 若实证条目形态再决定是否定向文案 |
| `showSettingsDialog(Class)` 顺带实例化整棵设置树 | 现有 OpenSettings 分支的已知平台坑（Settings.find 路径） | 本任务不修（既有行为不动）；EnhanceFirst 分支默认态绕开了该入口 |

## 七、实施顺序与决策点

```
T-GEF-02 文案先行 → T-GEF-03 红用例（decideActions 判定+序表映射）→ T-GEF-01 编排实现
→ 反向验证（两次读数）→ T-GEF-04 文档 → §四 收口（真机走查并行登记）
```

- **D1 定案（2026-09-23 用户确认按推荐）＝做**：Legacy 分支 message 末尾附一行 `AgentManager.statusSummary()` 实时状态（复用 `log.diag.enhanced` 键）；取值口径＝只取弹框所属项目（弹框手上有具体 project，不做设置页的多项目聚合）；无读数（null/空白）时原样不附。
- **D2 定案＝登记不做**：`SqlSource.AGENT && sourceFile == null` 条目的定向文案（指向设置页帧过滤前缀）——本任务不扩；Legacy 分支文案保持中性表述，后续若实证该形态高频再立项。

## 实施记录

（2026-09-23 本工作树实施，分支 `t-guide-enhanced-first`，基线 master `71a0c46`）

- **T-GEF-02 文案**：`log.jump.guide.enableEnhanced` / `log.jump.guide.message.enhanceFirst` / `log.jump.guide.enhanceDone` 三键双语落地（zh 781-783 行区 / 默认 bundle 同位）；生效提示弹窗 title 复用 `settings.enhancedCapture.confirm.title`，状态行复用 `log.diag.enhanced`，零冗余键。
- **T-GEF-01 编排**：`SourceCaptureGuide` 状态感知三分支（`decideActions` 纯函数 + `resolveChoice(rc, branch)` 分支序表 + `legacyMessage` D1 状态行）；`enableEnhancedCapture(project, onOutcome)`：开关关闭时置位 + 发布 `SETTINGS_CHANGE_TOPIC`（已开启不重复发布），随后复用 `AgentManager.requestReauthorization`。`AgentManager.requestReauthorization` / `requestConsentThenAttach` 增可选 `onOutcome`（默认空实现，既有调用方零行为变化；持久化单点不变，三态各自落盘后回传，前置早退不回调）。
- **守卫同步**：`SettingsTruthTest:85` 入口断言放宽为 `fun requestReauthorization(` 前缀匹配（签名加参后原断言会假红）；`publishStatusChanged` 计数守卫不受影响（未新增发布点）。
- **定向测试（8 类 62/0/0/0 绿，新工作树首跑 3m9s）**：SourceCaptureGuideChoiceTest 12 / SourceCaptureGuideEnableFlowTest 4 / BundleParityGuardTest 5 / AgentStatusTextTest 14 / AgentConsentFlowTest 6 / SettingsTruthTest 5 / SqlLogPanelLiveJumpWithSourceTest 10 / HistorySqlLogPanelJumpToSourceTest 6。EnableFlowTest 以 consentProvider 注入走真实确认流：拒绝进冷却、同意清冷却+confirmed、DISABLE 持久化关闭、已开启不重复发布 SETTINGS_CHANGE，全部实证。
- **反向验证（两次读数）**：`decideActions` 恒 Legacy 探针 → `test分支判定_未开启或未授权走EnhanceFirst FAILED`（12 例 1 红，Legacy 正对照不受影响）；还原 → `BUILD SUCCESSFUL`（ChoiceTest+EnableFlowTest 复绿）；探针 grep 0 残留。
- **前提复核**：前提 1 实证成立——`doAttachAsync` 全部调用面仅 `onProcessAppeared`（:409）与确认流同意分支（:493），无轮询扫描，「开启动作不碰已运行的应用、重启后生效」话术成立；前提 2/3 由 EnableFlowTest 实测覆盖（发布通道与 requestReauthorization 复用均按方案走通）；前提 4（AGENT 通道无位置条目）按 D2 定案登记不做，未实证；前提 5（设置页同开脏态）按风险表登记口径，未另行处理。
- **文档**：CHANGELOG `[Unreleased]` Changed 顶部新条目；`docs/变更记录.md` 表顶一行；架构文档 4 处（01 总览 `log` 行 SourceCaptureGuide 括注、02 详解 §6.4 表行 / §mermaid MissingSource 节点 / §排障表行）。
- **移交事项**：① 真机走查未做（runIde 沙盒天然未授权样本，步骤见 §五；走查完还原沙盒开关）；② 全量门禁未跑——按用户指示「定向测试无问题后合并主分支」执行，定向 62 绿即合并；③ 合并 master 时与在途发版会话（T-Release20260923，`MPSV-Release0923`）可能撞 CHANGELOG/变更记录 表顶，冲突按「两侧行全保留」惯例处置。
