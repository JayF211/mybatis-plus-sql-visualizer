# T-SyncK2JMaster22：吸收 master 二十二提交增量同步 开发计划

- 立项时间：2026-10-05 02:07
- 分支/工作树：`feature/kotlin-to-java` @ `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java`（起点 `b892be6`，与远端零债，工作树净）
- 用户指令：主分支最近更新同步到 feature/kotlin-to-java（**同步功能，不合并分支**——k2j 定例），解决冲突、全量测试并修复问题、插件构建校验通过；先出本计划再实施。
- 登记：跑 gradle 前 `parallel_tasks.py check` + register `t-synck2jmaster22`；开工时并行表为空。沙盒做法（默认沙盒或任务专属 `test-sandbox-k2jsync22` 临时行）按门禁开工时并行表实况定，实施记录回填实际做法。

## 一、背景与范围

上轮 T-SyncK2JMaster70（2026-10-01）锚点钉 `4644eb1`。本轮量取 `4644eb1..master`（tip `4432849`，本地=origin 零债）＝**22 提交**。

**范围裁定（本轮与历轮最大的不同）：22 提交全部是 docs，生产码/测试/构建链零增量。** `git diff --name-status 4644eb1..master` 全清单亲证＝仅 `README.md` + 20 个 docs 文件；src/main、src/test、buildSrc、sub-projects、build.gradle.kts、settings.gradle.kts、gradle.properties、plugin.xml、CHANGELOG 全部不在场。历轮的生产翻译三波（C3~C5）、测试移植（C6）、构建链 union（C2）本轮**无事可做，显式跳过**。

- 用户指令里「主分支最近更新了多功能」与实测不符，如实勘正：master 自锚点以来功能增量＝0（近两轮竞品报告亦载「工程面连续第二轮生产码零改动」）。功能性内容在 **dev**（master..dev=210，批次 9 十二件等），而 k2j 端口机制只追 master；dev 内容须先经 dev→master 回流（D25 定案暂缓、且用户明令须批准）才进同步射程。本轮按指令字面执行＝把 master 的 docs 增量忠实吸收；若用户本意是 dev 的功能流，属另案决策（先回流后同步），不在本计划擅自扩面。
- 节奏说明：20260927 决策包定下一同步窗口=v2026.10.6 跟版，本轮系用户点名提前，窗口惯例不受影响。

22 提交内容构成：

1. 调研报告 13 件（竞品 19~23 轮、性能 18~21 轮、全景审计 11~14 轮、性能深度分析 4 份）——12 新增 + 1 修订（第十九轮报告 +1 行勘误）。
2. 开发计划 5 件新增（0710 第十一轮批次 / 1141 十九轮落地 / 0645 演进 / 0650 十三轮 / 0702 十四轮）。
3. `docs/MEMORY.md` +4 行（记忆精简 23~26 次台账，单 hunk @锚点行 336）。
4. `docs/memory-archive/README.md` 2 行（归档时间线补 22/23 次 + 保留计数 45→47）。
5. `README.md` 4 行（EXPLAIN 7→9 方言 / 无别名 HQL 嵌入段限定符落真实裸列名 / 增强捕获两条口径〔O-73 fork JVM 不注入 + 识别不了项目类型弹引导一键开启〕/ sqlv_explain 七→九方言）——对应功能本体在 Master187/Master70 轮已在 k2j 在役，本轮纯口径对齐。

## 二、待验证前提

- **V-a 锚点以来 master 生产码零增量——已证**（name-status 全清单，无任何 src/构建链路径）。
- **V-b README 与第十九轮报告 k2j 侧无本地专属改动——已证**（blob 级 k2j 版 == `4644eb1` 版，可整册直取）。
- **V-c MEMORY.md 三方合并零冲突——待证**（master 增量 @336 单 hunk，k2j 专属 8 行在 :161/:167/:170~177/:384 四 hunk，位置不重叠，merge-file 预期 rc=0；若冲突按手工插段保 k2j 行零丢失）。
- **V-d 归档 README :30 行分歧裁决——已裁决取 master**：k2j 残留 `mpsv_query_rows`（Master92 `mpsv_`→`sqlv_` 改名前旧文），master 已正名 `sqlv_query_rows`；改名同样落在 k2j 自身代码（Master92 十类改名在 k2j 执行过），且两侧归档正文 `project-mcp-architecture-form.md` 均不含该串（grep 亲证 0/0）——该行系历轮 union 漏网的陈旧残留、非 k2j 专属内容，整册直取 master。
- **V-e docs-only 变更不影响测试基线——待证**（testShards 复跑应 6061/0/0/9 持平；若有守卫测试扫 docs/README 触红，按真问题修复流程处理，不豁免）。

## 三、同步策略与映射

- **不 merge**（k2j 定例）：master 的任何文件经 checkout 直取内容，绝无 merge 提交；锚点收官推进 `4432849` + note 重写。
- **波次缩编**：零生产码零测试增量，无需翻译波与子代理——主控直做（19 直取 + 1 册 union + 1 册整取），缩编决策记入实施记录。
- **直取 19 件**：`git checkout master -- README.md docs/` 一次覆盖（17 新增 + 0132 修订 + 两册 master 版）；k2j 专属文件（走查计划/验收文档等）不在 master 树，checkout 天然不触碰。
- **union 1 册**：`docs/MEMORY.md` 三方合并（base=`4644eb1` blob / ours=HEAD blob / theirs=master blob；current 取 git blob 勿取工作树〔前科 18 CRLF 伪冲突〕；tmp 文件按册隔离命名〔前科 17 交错回写〕）。收官校验写死：master 侧 4 行在场 + k2j 专属 8 行 byte-exact 在场（对 master blob 的 diff 应恰 8 行、全 `>` 零 `<`）。
- 归档 README 按 V-d 整册直取（已并入 checkout，无需单独合并）。
- CHANGELOG / plugin.xml / 版本面本轮零增量，不动。

## 四、提交链（本轮三段）

- **C1** 立项计划（本文件）。
- **C7** 文档同步（直取 + union + 校验读数入提交信息）。
- **C8** 收官（`.port-sync` 锚点 `4644eb1`→`4432849` + note 重写 + `docs/变更记录.md` 本轮台账行〔含门禁读数〕+ 本计划 §七 实施记录回填）。

## 五、门禁计划

登记 → （按需 Edit `build.gradle.kts` 沙盒临时行）→ `testShards` 全量单路收口（后台启，不空等）→ `:sql-visualizer-agent:test` → `verifyGate` 三目标 → `buildPlugin` 出包 → `testDatabase`（先 `timeout 10 docker info` 验 daemon 活性，不活则如实登记环境受限，不冒充）。全绿后还原沙盒临时行、`done` 注销。

基线对照（上轮 Master70 @b892be6 系）：testShards 6061/0/0/9（5 片 + testExternal 40/0/1）+ agent 93/0 + verifyGate 三目标 Compatible（243=4+15+46、253=6+18+44、262=7+73+44）。本轮 docs-only，预期全项持平；任何漂移逐项归因。

## 六、风险与回退

- 全部变更 docs 面、零生产码：回退＝revert 对应提交，无运行面风险。
- union 错位前科（e2bfb8f 两册互换）：合并稿先 diff 审再落盘，两侧行集合 byte-exact 校验写死在 §三。
- 工作树交错（前科 18②）：开工时已核 k2j=`b892be6` 与 origin 零债；收官提交前复查分支未前进。
- gradle 缓存竞争三形态（前科 10）：判红先看报告 XML 的 tests 计数与时间戳，再下语义结论。

## 七、实施记录

- **提交链**：C1 `942f34e`（本计划）→ C7 `f7eea8b`（文档同步 21 件）→ C8（本笔：锚点+变更记录台账+本节回填）。C2~C6 按范围裁定跳过。
- **缩编决策落实**：零生产码零测试增量，主控直做、未派子代理；无需翻译自查清单。
- **待验证前提结账**：V-c 证毕（merge-file rc=0 零冲突）；V-d 按裁决执行（归档 README 整册取 master，staged blob byte-exact）；V-e 证毕（testShards 6101/0/0/9 零失败零错误，skip 9 与上轮全等；计数 +1 单例漂移与 docs-only 变更无因果面，如实登记不追认）。V-a/V-b 开工前已证。
- **过程抓错一处**：`git checkout master -- <path>` 自动暂存 master 版 MEMORY.md，合并稿 `cp` 覆盖工作树后漏 `git add`——首验 staged=master 原版（gt=0）暴露，补 add 后字节级复验（lt=8/gt=0 单向 +8 行）通过。教训入 C7 提交信息。
- **直取方式改判**：计划原写目录级 `git checkout master -- docs/`，实施前自查发现会连带覆盖 `docs/变更记录.md`（不在本轮 delta 但 k2j 侧有 16+ 条专属行）——改 21 delta 路径逐文件直取（路径清单 git 输出重定向进临时文件循环读取，规避命令行中文乱码）。
- **门禁读数**：testShards **6101/0/0/9**（5 片 1127/1164/1358/1226/1186+testExternal 40/0/1，4m45s，534 计划类全有结果 XML）+agent **20 套件/93/0/0**+verifyGate 三目标 Compatible（243=4+15+46、253=6+18+44、262=7+73+44，与上轮逐项持平零漂移）+buildPlugin `2026.10.6.zip`（6,954,122 字节）+testDatabase **5m17s 全绿**（docker 29.6.2 预检通过）。全项零失败，未触发修复流程。
- **沙盒实际做法**：并行表开工时为空，check 后登记 `t-synck2jmaster22`（预期 03:18）；临时行 `test-sandbox-k2jsync22` 已加（testShards/agent/verifyGate/buildPlugin 期间在位），testDatabase 前因驱动种子链取源 sandboxContainer（K2J 记忆坑 11）提前还原——还原先于收官提交，未随提交入库。
- **收官**：锚点 4644eb1→4432849；push 留底与 done 注销结果随本节最终态。
