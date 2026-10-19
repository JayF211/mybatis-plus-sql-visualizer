# T-ErrorReportRedact 错误上报分级脱敏 — 开发计划

- 日期：2026-09-19 04:53
- 任务编号：T-ErrorReportRedact（第八轮报告 §9.4 **O-38** 的**拆分前半**，P3、**条件性（D1 已定案 B，转为实施）**；原 O-38 经核实拆为两项，fatal 面见 T-FatalPassthrough）
- 来源：第八轮报告 §7.2 zMyBatis 精华 **Z-21** 的脱敏侧面——「失败只留 cause 链末端类名」（`XmlMapperPreparationEngine.kt:372-378`）
- 性质：**隐私面**——**方向与 zMyBatis 相反**（本仓是「有闸门但内容全量」，不是「无闸门靠脱敏」），故本计划先做决策点评估再定是否实施
- 实施分支：自 master 拉任务分支 t-error-report-redact（D1 已定案 B）
- 验证基线：master

---

## 一、背景与依据（现状核实）

| 项 | 实测 | 证据 |
|---|---|---|
| 日志侧 | 带 throwable 的 `LOG.warn(msg, e)` 形态 **61 处**（异常 message 原样落 idea.log） | grep 计数；代表点 `execute/DataSourceSelector.kt:2624`、`:2586-2590`（含前 8 帧栈）、`execute/ExecuteSqlSupport.kt:74`、`database/DatabaseConsoleLauncher.kt:51` |
| 外传侧 | 用户点平台「报告」后：标题 = `异常类简单名: message 首行`（`feedback/ErrorReportComposer.kt:31-45`）、描述 = **message 全文**（`:47-56`）、附件 = **异常全文**（`:74-120`，上限 60 000 字符） | 同左 |
| **已有骨架（关键）** | ①`feedback/ErrorFingerprint.kt:10-16`/`:41-49` 的指纹**只吃类名 + 前 5 个业务栈帧**（SHA-256），**不含 message**；②整个上报**必须用户点平台「报告」按钮并在对话框内确认**，对话框内容可编辑，且有 `getPrivacyNoticeText()`（`feedback/IdeErrorReportSubmitter.kt:39-46`、`:99-101`） | 同左 |

**为什么是「方向相反」**：zMyBatis 的做法是「无用户闸门，所以在源头脱敏」；本仓已经有「用户显式确认 + 可编辑 + 隐私提示」的闸门，**message 全文在场反而是诊断价值**。因此本计划的真实命题不是「补脱敏能力」，而是「**闸门内的默认预填该给多少**」——这是产品取舍，须用户定案。

## 二、决策点（D1 已定案）

**D1：上报预填内容的三选一**

| 选项 | 形态 | 利 | 弊 |
|---|---|---|---|
| ~~A（维持现状）~~（**弃用**） | 预填 message 全文 + 异常全文附件 | 诊断信息最全；已有确认闸门 | 用户可能在未细看的情况下提交含敏感 SQL/表名/路径的 message |
| **B（分级脱敏）— 已定案** | 自动预填**只给类名 + 指纹 + 栈帧**（去 message）；message 全文改为**用户可见可编辑的可选附注**（默认折叠/未勾选） | 默认不外传业务文本；用户若需帮助可主动补上 | 首报诊断信息变少，可能需要一轮追问 |
| ~~C（配置项）~~（**弃用**） | 加设置项让用户选 A/B | 兼顾 | 新增设置面 + 多一个需要解释的开关（与「设置真实性」系列的克制原则相悖） |

**D1 已定案（2026-09-19，用户认可本会话推荐）：选 B。** 备选 A/C 保留在表内并标记**弃用**（不删，留决策痕迹）。定案理由：「默认最保守 + 显式动作才外传业务文本」符合本项目一贯的隐私姿态，且不新增设置项。

**D2：日志侧是否同步**——61 处 `LOG.warn(msg, e)` 的 message 落在**本机 idea.log**，不外传。建议**不动**（本机日志的诊断价值高于脱敏收益；且逐处改动风险高）。若用户要求「本机也脱敏」，另立任务。

## 三、方案（D1 已定案 B）

**T-ERR-01（预填分级）**：`ErrorReportComposer` 的标题/描述构造改为「类名 + 指纹 + 业务栈帧摘要」为默认预填；message 全文与异常全文降为对话框内**用户可勾选附加**的区块（勾选后行为与现状逐字节一致）。

**T-ERR-02（指纹可用性补强）**：D1=B 后首报信息减少，需确认 `ErrorFingerprint` 的栈帧选择在常见失败形态下仍有区分度（用历史报告的若干真实形态抽样核对；不足则调整帧数/过滤规则）。

**T-ERR-03（测试与文档）**：用例——①默认预填不含 message 文本；②勾选附加后产物与改前逐字节一致（兼容既有提交链路与官网需求池字段）；③指纹在抽样形态下有区分度。文档：CHANGELOG + 变更记录 + 第八轮报告 Z-21 落地备注（含「方向与竞品相反」的口径说明）。

## 四、验收标准

1. 默认提交内容不含 message 文本（用例 1）；勾选附加后与改前逐字节一致（用例 2）。
2. 官网需求池端（`sql-visualizer-admin`）对两种形态的接收与展示正常（涉及云侧，须端到端验一次或明确登记未验）。
3. 全量 test 绿。

## 五、真机走查清单（留人工）

1. 真机触发一次 Internal Errors → 看对话框默认预填与可勾选附加的呈现是否符合预期。
2. 提交一份默认形态 → 官网需求池可见且信息足以定位（含指纹与栈帧）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 诊断信息不足 | 首报缺 message 导致需要追问 | T-ERR-02 指纹补强；对话框内一键勾选附加 |
| 云侧字段兼容 | 官网需求池可能依赖 message 字段非空 | 端到端验一次（或明确登记未验，按本项目「环境受限未验证」口径） |
| ~~与既有闸门语义重叠~~（已随 D1=B 弃用） | 已有确认对话框，再加分级是否多余 | D1 已定案 B，该顾虑不作为阻断：闸门管「发不发」，分级管「默认带什么」，职责不同 |

## 七、实施顺序

```
D1 已定案（B）→ T-ERR-01 → T-ERR-02 → T-ERR-03 → 真机走查 §五
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| D1 | —（决策已定，无代码提交） | **已定案 2026-09-19（用户认可本会话推荐）：选 B**——默认预填只给类名+指纹+栈帧，message 全文改为用户可勾选附加；A/C 保留并标记弃用 |
| T-ERR-01 | `6600605` | 预填分级落地（§8.1）；**偏离**：完整堆栈附件的默认勾选同步改为不勾（§8.4-1） |
| T-ERR-02 | `6600605` + `9db5323` | 指纹补强 + 真实形态抽样结论（§8.2） |
| T-ERR-03 | `9db5323` | 用例与门禁读数（§8.3）；云侧端到端**未验证**（§8.5） |

### 8.1 T-ERR-01 落地形态（`6600605`）

- `ErrorReportComposer`：新增 `buildRedactedTitle`（异常类简单名）与 `buildRedactedDescription`
  （`异常: <全名>` + `原因链: <cause 链类名>` + `栈帧: <前 10 个业务帧含行号>` + 「消息全文默认不附带」说明行）；
  `prefill` 默认走这两种脱敏形态，同时把 message 全文与含 message 的旧形态
  （`Prefill.message`/`titleWithMessage`/`descriptionWithMessage`）交给对话框做勾选附加源。
- `ErrorReportDialog`：新增「附带异常消息全文」勾选行（默认不勾，无 message 时整行隐藏）；
  切换只回填「内容仍等于上次预填写入值」的字段（`messagePrefillTargets` 纯函数），
  用户手工改过的字段不被覆盖。勾选后标题/描述与改前**逐字节一致**（`buildTitle`/`buildDescription` 原式未改）。
- 批量路径同口径：`buildBatchTitle`/`buildBatchDescription` 加 `includeMessages`（默认 false）；
  `BatchSubmitter.BatchItem.includeMessage` 由同一开关在收集时统一回写，>5 条的错峰补发帖子同样默认脱敏。
- bundle：新增 `error.report.dialog.attachMessage(.tip)` 中英双键；`error.report.privacy` 与
  `attachStack.tip` 按新默认改写（旧文案宣称「提交包含异常堆栈」，附件默认不勾后已不实）。
- **D2（日志侧）按计划建议不动**：61 处 `LOG.warn(msg, e)` 只落本机 idea.log、不外传，
  逐处改动风险高于脱敏收益；本任务未改 `DataSourceSelector.kt`/`ExecuteSqlSupport.kt`/`DatabaseConsoleLauncher.kt`
  （三者在 §一 里只作现场取证，方案 §三 无日志侧条目）。

### 8.2 T-ERR-02 抽样结论（`9db5323`）

- 帧解析收口：`businessFrames`（指纹用，去位置）与 `businessFrameLines`（描述摘要用，带行号）
  共用同一正则与平台前缀过滤，杜绝两处口径漂移。
- 补强：cause 链类名（`Caused by:`/`Suppressed:` 行**只取类名**，行上消息一律丢弃）并入指纹材料，
  最多 3 条。
- 抽样（五形态，取自本仓真实日志点：DataSourceSelector:2624 提交事务、ExecuteSqlSupport:74 反射加载、
  DatabaseConsoleLauncher:51 临时文件、XmlMapperPreparationEngine、BatchOperationConverter）结论：
  **不同站点指纹互不相同、行号漂移不改指纹**；驱动包装形态（前 5 业务帧全是 `com.mysql.cj.*`）
  此前帧序列完全相同，并入 cause 链后「有 cause / 无 cause、cause 类名不同」可分。
- **残留塌缩（诚实登记）**：类名 + 前 5 帧 + cause 链三者全同而仅 message 不同时仍同指纹
  （例：驱动直抛的「拒绝访问」与「库不存在」）。改前的指纹也不吃 message，故**不是** D1=B 引入的退化；
  把 message 哈希并入会让指纹随消息里的参数漂移（同一个 bug 反复上报撞 5 次/小时限流），本轮**不改**，
  按已知限制登记（24h 去重是内存态、重启即清；同站点首报已在需求池）。
  用例 `残留塌缩_同站点同cause链而消息不同仍同指纹_已知限制` 钉住该语义，避免将来被当成 bug 反复「修」。

### 8.3 T-ERR-03 门禁读数（`9db5323`）

- 定向：`./gradlew test --tests "cn.song.mybatisplus.visualizer.feedback.*"` →
  **122 例 / 0 失败 / 0 错误 / 0 跳过**（8 个测试类）。
- 全量：`./gradlew test` → **5275 例 / 1 失败 / 0 错误 / 6 跳过**。唯一失败
  `EntityClassFinderHeavyTest.testTableNameMismatchExcluded` 为**无关 flake**（平台 Git 插件
  `DefaultDispatcher-worker … @Git update present check` 线程泄漏；隔离复跑该单类 BUILD SUCCESSFUL）；
  6 跳过全是 `DialectDockerVerificationTest`（本机无 docker 实例，存量环境跳过）。
- `testDatabase` **未跑**：本任务零改动触达 Database 链（`git diff 8d5386f..HEAD` 仅 feedback 包 +
  两个 properties），按项目门禁口径（触达 Database 链才双跑）不触发。
- 用例覆盖验收口径：①默认预填与默认提交载荷不含 message 文本（含批量清单、补发帖子）；
  ②勾选附加后标题/描述与改前逐字节一致（含批量描述逐字节钉死）。

### 8.4 偏离计划之处

1. §三「message 全文**与异常全文**降为对话框内用户可勾选附加的区块」——D1-B 行只写了 message，
   本次**把完整堆栈附件的默认勾选也改为不勾**：附件首行就是 `异常类: message`，默认勾选等于绕开脱敏，
   验收口径①在附件通道上不成立。勾选后附件内容与改前逐字节一致（`buildStackAttachment` 未改）。
2. 改写了 4 处 bundle 文案（新增 attachMessage 双键 + 改写 privacy 与 attachStack.tip），
   超出写集里列的源码文件，属新增 UI 文案的必要落点（缺键会原样显示键名）。
3. §T-ERR-03 提到的「CHANGELOG + 变更记录 + 第八轮报告 Z-21 落地备注」按派活方口径**未改**
   （`docs/变更记录.md` 与盘点文档明确不动，CHANGELOG 不在写集内），落地说明全部收在本文件。
4. 指纹材料新增 cause 链类名（§T-ERR-02 授权「不足则调整帧数/过滤规则」）；帧数（5）与平台前缀表**未动**。

### 8.5 未验证 / 环境受限

- **云侧端到端（验收 2）未验证**：本机无 `sql-visualizer-admin` 源码，也无测试环境凭据，
  按派活方口径不碰云环境。缺的是「提交一份默认（脱敏）形态与一份勾选形态到需求池，
  核对两种形态的接收与展示」。本机只做到**契约侧静态核对**：标题走同一 `finalizeTitle`（5~200）、
  描述走同一 10 字符兜底，且比改前更短更稳（≤10 帧 + 3 条 cause，量级 ~1KB，不会触及长度上限）。
  服务端若硬依赖 message 非空，客户端侧无法预判——这正是必须端到端验的那一项，**不得以静态核对冒充实测**。
- **真机走查（§五）未做**：Internal Errors 对话框里默认预填与勾选附加的实际呈现、
  提交后需求池能否定位，仍留人工。
