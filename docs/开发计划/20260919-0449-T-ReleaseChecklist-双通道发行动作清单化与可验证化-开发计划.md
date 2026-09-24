# T-ReleaseChecklist 双通道发行动作清单化与可验证化 — 开发计划

- 日期：2026-09-19 04:49
- 任务编号：T-ReleaseChecklist（第八轮报告 §9.2 **N-28**，P0；原拟名「发行闭环 checklist 化（防断供）」——核实后扩为**双通道**，见 §一）
- 来源：第八轮报告 §3.2 信号 1 与 §9.1——**Marketplace 断供实锤**：v2026.9.18 正式版 Gitee 已发，但 Marketplace 在售仍是 2026.9.14，`updates` 端点无 2026.9.18 条目且 `hasUnapprovedUpdate=false`（连待审痕迹都没有，curl 复核吻合）
- 性质：**发行流程可靠性**——不改产品行为，把「靠人记」的发行动作变成「靠清单 + 可回读验证」
- 实施分支：自 master 拉任务分支 t-release-checklist
- 验证基线：master（testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿）

---

## 一、背景与依据（现状核实，含五处口径修正）

| # | 原建议口径 | 核实事实 | 影响 |
|---|-----------|---------|------|
| 1 | 「Gitee 有脚本、Marketplace 靠手工」的对称叙事 | **Gitee 发行也没有脚本**——`dev-support/` 下无任何 Gitee 发行版脚本（grep `attach_files`/`releases`/`api/v5` → 0 命中），只有**文档内嵌的 Python 参考实现**（`docs/发行说明/Gitee自动发行工作流-说明文档.md:162-194`）。真实形态是**两条通道都靠人工照文档执行** | 范围从「Marketplace 上传」扩为**双通道**发行动作 |
| 2 | 文档引用的推送脚本 | `scripts/push-all.sh` 在 `Marketplace发布流程-说明文档.md:125-135`、`:309` 被反复引用，**仓库中不存在**（`ls scripts` → No such file or directory） | 引用失真要清理，否则照文档执行必然断 |
| 3 | 「GitHub 双推」为既有步骤 | 文档 §5 主张「Gitee 主 + GitHub 镜像」，但 `git remote -v` **只有 origin（Gitee）**，且 2026-08-18 已定案「不用 GitHub 镜像，直接用 Gitee 作为 Marketplace 源码链接」（`docs/memory-archive/bug-fixes/project-marketplace-publishing.md`） | 清单中 GitHub 条目**降级或删除**，不得当既有事实 |
| 4 | `dev-support` 验证脚本可信 | `verify-full.ps1:2` / `verify-smoke.ps1:2` 调的是 `./gradlew verifyPlugin`——该任务**已被明确降级为「报告生成器」**（`buildSrc/src/main/kotlin/mpv-verification.gradle.kts:44`：判定不采纳 `-ignored-problems`、输出解析有竞态）；权威门禁是 `verifyGate`（同文件 `:92`）。`ci-verify.ps1:2-8` 又包装 `verify-full.ps1` → **每周巡检跑的不是权威门禁** | 本计划新增实修项（T-RC-02） |
| 5 | 上传动作有自动化托底 | 确认**不存在**：全仓 grep `publishPlugin`/`PublishingToken`/`marketplace`/`hub`/`upload` 在仓库侧仅命中 `build.gradle.kts:33` 的守卫集合与文档一句命令示例；**无签名配置**（grep `signing`/`certificateChain`/`privateKey` → 0 命中）；仓库内无 marketplace token 存放约定（`.gitignore:79-80` 只忽略 `gitee-token.*`/`*.token`） | 上传本身维持人工（首次必须网页手工，官方原文见 `Marketplace发布流程-说明文档.md:39`）；本计划只做**可验证化**，不追求全自动 |

**现成资产**：

- **权威八处同步清单**：`docs/发行说明/Gitee自动发行工作流-说明文档.md:38-49`（发行说明 / CHANGELOG / gradle.properties / plugin-change-notes.html / plugin.xml description / preview.update.json / marketplace-custom-page.html / MEMORY+变更记录），另加发行后独立单文件提交 `preview.update.json → update.json`（`:199-200`）。
- **既有 checklist 载体**：`Marketplace发布流程-说明文档.md:354-369`（12 条 markdown 复选框：testShards → verifyGate → buildPlugin → 版本号/changeNotes → GitHub 同步 → Gitee Release → update.json → Marketplace 表单 → runIde 冒烟 → 签名 → 后台 listing 名 → 后台审核状态）——**无独立文件、无任何验证钩子**。
- **上传后回读端点已在本仓被使用**：`https://plugins.jetbrains.com/api/plugins/33633/updates`（官网与竞品哨兵在用），可断言在售版本 = `gradle.properties` 版本。
- **Gradle 任务面**：`releaseGate`（`mpv-verification.gradle.kts:205-208`）= `buildPlugin` + `verifyGate` + `testShards`，时序由 `build.gradle.kts:211-212` 的 `mustRunAfter` 钉死；`writeUpdateManifest`（`mpv-release.gradle.kts:35-54`）生成 update.json；`patchPluginXml`（`:16-27`）注入 changeNotes。

## 二、目标与非目标

**目标**：发行动作有单一权威清单、每步可判「做了没」、失败可回读发现；消除文档与仓库实际的分叉。

**非目标**：不追求 Marketplace 上传全自动（首次必须手工，且 token 存放无约定，属用户决策）；不把「后台审核状态确认」自动化（平台后台人工动作，只能做成勾选项）；不改发行元数据与产品行为；不动 `update/update.json`（用户明示只能手改）。

## 三、方案

**T-RC-01（清单单一权威化）**：新建 `docs/发行说明/发行清单-说明文档.md` 作为**唯一权威 checklist**，按「准备 → 门禁 → 双通道发布 → 收尾」四段组织，每步标注：动作、命令/位置、**判定证据**（怎么知道这步成了）。现有两处清单（`Gitee自动发行工作流` §八处同步、`Marketplace发布流程` §12 条）改为指向本文件的引用，消除双份漂移。

**T-RC-02（验证脚本改挂权威门禁）**：`dev-support/verify-full.ps1` / `verify-smoke.ps1` 从 `./gradlew verifyPlugin -PverifyIdes=…` 改跑权威 `verifyGate`（或对应的 `verifyFull`/`verifySmoke` 任务别名——`mpv-verification.gradle.kts:189`/`:194` 已挂 `verifyGate`）；`ci-verify.ps1` 随动。**依据**：口径修正 4，当前每周巡检跑的是降级任务，等于门禁形同虚设。

**T-RC-03（发布后回读验证，断供的直接防线）**：清单中加入「上传后回读」强制步骤——`curl https://plugins.jetbrains.com/api/plugins/33633/updates` 断言最新条目版本 = `gradle.properties` 的 `version`；不等（含「无该条目」「hasUnapprovedUpdate=false 且无新条目」两种形态）即视为**发布未完成**，不得勾选该步。可选：把回读做成 `dev-support/check_release_landed.ps1`（读 gradle.properties 版本 → 打 updates 端点 → 退出码判定），供清单引用。

**T-RC-04（文档失真的清理与登记）**：①删除/替换 `scripts/push-all.sh` 的引用（口径修正 2）；②GitHub 双推条目按 2026-08-18 定案降级为「仅 Gitee」（口径修正 3），若保留 GitHub 条目须标注为「可选、未启用」；③在清单中明确「Marketplace 首次必须网页手工、后续可用 `publishPlugin` + PAT」的平台约束与 token 存放待决（**不在本任务内决定 token 落地形态**，登记给用户）。

**T-RC-05（本次断供的复盘登记）**：把 2026.9.18 断供写进清单文档的「事故登记」节——形态（Gitee 已发 / Marketplace 无待审痕迹）、发现方式（updates 端点回读）、根因归类（执行漏步，非能力缺失——历史记录显示 2026-08-18、2026-09-03 均有正常上架与拒后修复，见 `project-marketplace-publishing.md`）、防复发手段（T-RC-03）。**这一节的价值在于：下次有人问「为什么有清单」时有据可查。**

**T-RC-06（可选，脚本化程度由用户定）**：若用户要求进一步自动化，候选是「Gitee 发行脚本化」——把 `Gitee自动发行工作流-说明文档.md:162-194` 的 Python 参考实现落成 `dev-support/release_gitee.py`（含本机 `curl` multipart 必挂的 Python 手拼 boundary 规避、attach_files token 走 URL query、回读比对）。**本计划默认只登记为候选**，不实施，因为 Gitee 侧已有可用的文档流程，收益低于 Marketplace 回读。

## 四、验收标准

1. 存在唯一权威清单文件，且 `Gitee自动发行工作流`/`Marketplace发布流程` 两处旧清单已改为引用（无第二份可漂移的清单）。
2. 清单每一步都有「判定证据」栏；其中「上传后回读」步骤给出了可直接执行的命令与断言。
3. `verify-full.ps1`/`verify-smoke.ps1`/`ci-verify.ps1` 改跑权威门禁，且**实跑一次确认退出码与 verdict 输出正常**（不跑全量发版，只验脚本形态）。
4. 文档中 `scripts/push-all.sh` 引用清零；GitHub 条目状态与 2026-08-18 定案一致。
5. 断供复盘节在册，含发现方式与防复发手段。
6. 本任务为文档 + 脚本改动，不改产品代码；全量 test 不受影响（如触及 `dev-support` 外文件须跑 testShards）。

## 五、真机走查清单（留人工）

1. 按新清单走一次完整发行（下一版发行时执行）：门禁 → Gitee → Marketplace 上传 → **回读断言** → N-24 三件套。
2. 断供场景演练：故意在 Marketplace 未上传时执行回读脚本 → 脚本必须以非零退出码报「未落地」。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 清单与文档双份漂移复发 | 新建清单后旧清单未同步引用 | 验收 1 明确「唯一权威」并要求旧处改引用 |
| 改脚本破坏既有巡检 | `ci-verify.ps1` 有日志/轮转/LAST-FAILED 逻辑 | 只改被调命令，不动日志与轮转面；实跑一次验证 |
| 回读端点的判定边界 | 上传后审核期可能「有条目但未过审」 | 断言分两档：条目存在 = 已上传；`listed=true` = 已上架；清单按两档勾选 |
| 自动化范围蔓延 | 追求全自动上传会引入 token 存放等新决策 | T-RC-06 明确默认不实施，token 形态登记给用户 |

## 七、实施顺序

```
T-RC-02（脚本改挂权威门禁，独立可并行）→ T-RC-01（清单单一权威化）→ T-RC-03（回读验证）→ T-RC-04（失真清理）→ T-RC-05（复盘登记）→ [T-RC-06 候选，用户定] → 真机走查 §五（随下一版发行执行）
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-RC-01 | （实施后填） | 清单文件路径与旧清单改引用情况须回填 |
| T-RC-02 | （实施后填） | 脚本实跑结论须回填 |
| T-RC-03 | （实施后填） | 回读断言两档判定是否够用须回填 |
| T-RC-04 | （实施后填） | GitHub 条目最终处置须回填 |
| T-RC-05 | （实施后填） | |
| T-RC-06 | （实施后填） | 用户是否要求实施须回填 |

## 九、增补登记（2026-09-24 回填，第十二轮调研实证后）

> 本计划实施顺序未变、T-RC-01~06 内容不变；本节登记 2026.9.23 发行实走过的**审核响应回路**带来的三件实证素材与一处收编事实。**优先级确认 P0**；已收编进第四轮全景演进批次 W0（发行收口波，暂占 O-75，`1ea1bc7`）——实施随 W0 窗口。

### 9.1 T-RC-05 复盘登记节新增第二案例（与 2026.9.18 形态不同）

| 案例 | 形态 | 发现方式 |
|------|------|---------|
| 2026.9.18 断供（原 T-RC-05 案例） | **没传**：Gitee 已发、Marketplace 无条目无待审旗标 | updates 端点回读 |
| **2026.9.23 零痕迹（新增）** | **传了但 API 不可见**：tag+Gitee 上线+上传 → 审核退回 → O-72 代理化改造重传 + 回复稿 v2 后，updates 仍 8 条止 2026.9.14、hasUnapprovedUpdate=false（20260924 01:15 主控 curl 复核；对照 MyBatisX 待审压着时旗标 true，旗标确会置位） | 竞品调研兜住（外部发现）；「队列延迟 vs 上传未落位」两解不可区分，**后台人工确认是唯一闭环手段** |

第二案例的教训归因与第一案例不同：不是「执行漏步」，而是**回读时机与语义**——回读断言（T-RC-03）必须在**每次上传（含审核响应后的重传）之后**立即执行，且「无条目 + 旗标 false」的读数要显式判为「API 不可见→后台确认」，不得留成静默通过。

### 9.2 T-RC-01 清单内容追加两条（zMyBatis Z-36 runbook 纪律 + 审核响应回路）

1. **Z-36 runbook 纪律**（第十二轮报告 §7.1）：发行中断后重跑前，先核对「版本号 / tag / Gitee 发行版」三者的**已落位状态**（git tag + Gitee API 回读），禁止按记忆续跑——2026.9.23 当天「盘点 34 版记『不建 tag 不调 API』→ 实际下午全做了」的状态漂移就是对口场景。
2. **审核响应回路步骤**（2026.9.23 首次实走，模板入清单）：审核退回 → 根因修复（本轮＝探测式动态代理化消 internal API，兼容问题 14→2）→ `verifyMarketplace` 门禁复验 → 重传 → **重传后立即执行 T-RC-03 回读** → 申诉回复稿落库 `docs/发行说明/`（本轮样例：`20260923-marketplace-审核回复稿.md`）。

### 9.3 T-RC-03 断言口径补第三档语义

原两档（条目存在＝已上传；`listed=true`＝已上架）补第三档实测事实：**条目存在且 `hasUnapprovedUpdate=true`＝待审压着**（MyBatisX 长期 true 实证）；**无新条目且旗标 false＝API 不可见**，脚本退出码判「未确认」，清单勾选项引导去 Marketplace 后台人工确认——脚本无法区分该档的两解（队列延迟/未落位），如实交人工。

### 9.4 优先级依据更新

原依据＝第八轮断供推演；现依据＝第十二轮实测（N-28 缺席的现实代价已现形：零痕迹若当晚回读即暴露，实际由外部竞品调研在两天后发现）。
