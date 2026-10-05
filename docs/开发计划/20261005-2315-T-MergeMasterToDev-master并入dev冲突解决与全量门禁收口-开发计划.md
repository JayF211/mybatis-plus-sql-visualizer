# T-MergeMasterToDev master 并入 dev 冲突解决与全量门禁收口——开发计划

> 2026-10-05 23:15 立项。用户指令（逐字）：「dev分支合并主分支，切换到 dev 分支，最近主分支更新了跟多功能，可能会有冲突，现在需要主分支合并到dev并解决冲突、全量测试并修复问题、确保插件构建校验通过，在实际调整代码前 先生成一份开发计划文件再实施」。
> 执行树＝dev 工作树 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev`（分支 `dev`），master 树与 t-statementweave 树全程只读不动。
> 方向澄清：本任务是 **master → dev**（用户明令），不触碰用户在案的「dev 未经允许禁并 master」回流禁令；合并完成后 dev 只 push 自身，不做任何回流。

## 一、背景与分叉实测（2026-10-05 23:04~23:12 只读侦查）

1. **远端同步**：`git fetch origin` 后 origin/master=`2be5a50`＝本地 master、origin/dev=`a69c79e`＝本地 dev，两侧与远端零漂移，无 fetch 债。
2. **分叉计数**：`dev..master`＝**33 笔**（master 领先），`master..dev`＝**220 笔**（dev 领先，多为 docs 轮次报告与 O-93~O-96、T13/T14/T15 支链），merge-base=`073964e`。
3. **master 33 笔构成**（对 dev 是净新增）：
   - src 修复批：T-HqlUpdateEntityMap（`8e9ca7e`）+ T-JpaDeleteWhereGuard（`fa416e0`/`b557f51`/`7320723`/`0261b44`/`72c1ce1`/`a4af61d`）+ T-FramePrefixResize（`a34c84e`）+ 增强捕获 R1~R3（`5c1a28a`/`d3d3c8f`/`769a1b0`）+ T-McpDynamicToolset 真机 ASM 不可达修复（`c7a75a3`，含 build.gradle.kts 删 asm 依赖）+ T-McpWalkFindings 三项收口（`d03d616`）；
   - 发行批：v2026.10.6 八处同步 + 增强捕获确认流测试隔离修复（`ea0ed3f`）+ 实录 docs（`2be5a50`）；
   - docs 批：竞品二十二三轮、性能二十一轮、审计十四十五轮、T15/T-StatementWeave/R3 等计划、记忆精简二十六二十七次。
4. **冲突预演**（`git merge-tree --write-tree`，与真实 merge 同算法）：冲突**仅 5 文件**——`CHANGELOG.md`、`docs/变更记录.md`、`docs/开发计划/20261005-0238-T-TestPerf15-…-开发计划.md`（add/add）、`MpSqlVisualizerBundle.properties`、`MpSqlVisualizerBundle_zh.properties`。
5. **自动合并交集 11 文件**（两侧都改、文本合并成功但存在语义互踩风险，须门禁兜底）：`PluginSettingsPanel.kt`、`UnboundedWriteDetector.kt`、`McpToolsetDynamicRegistrar.kt`、`SqlPreviewService.kt`、`plugin.xml`、两 bundle、`FrameSkipPrefixesFieldVisibilityTest.kt`、`EasyQueryConverterTest.kt`、`MdsConverterTest.kt`、`QuerydslConverterTest.kt`。
6. dev 工作树干净；master 树仅 2 个无关 untracked（`dev-support/mcp-test/18`、`Parameters`，不动）。

## 二、冲突裁决原则（逐文件写死）

| 文件 | 冲突形态 | 裁决 |
|---|---|---|
| `CHANGELOG.md` | 两处 hunk 均「两侧各自新增 Fixed 条目」（hunk1：dev 的 R3 日期字面量 vs master 的 ASM 修复；hunk2：dev 的性能五件+R24 vs master 的走查收口+去重窗顺序+首轮零捕获+测试隔离） | **union 全保留**：dev 条目在前、master 条目紧随，2026.10.6 发行段已自动并入不动 |
| `docs/变更记录.md` | 两侧各自追加日期段 | **union 按日期序合并**，两侧段落全文保留 |
| T15 计划 add/add | master 84 行立项版（`6eb8d0f`）vs dev 133 行实施完成版（t15 链并入） | **取 dev 版**（含实施记录的终态）；合并时 diff 全文核对 master 版无独有实质内容，若有独有段落择要并入 |
| `MpSqlVisualizerBundle.properties` | dev 新增 `settings.sourceFrameSkipPrefixes.hint` 键+注释，master 改 `settings.log.autoInjectInterceptor` 值（加 enhanced capture recommended） | **键 union + 同键值取 master 新值**（两侧改动不同行，合并后两改动都在） |
| `MpSqlVisualizerBundle_zh.properties` | 同上中文侧 | 同上 |

裁决后双向在场校验：CHANGELOG 两 hunk 六侧条目逐侧 grep 关键词；bundle 三改动（dev hint 键/master autoInjectInterceptor 新值/中文侧对应）逐个 grep；变更记录两侧新段各 grep 一句锚点。

## 三、实施方案

- **S1 计划落库**：本文件提交到 dev（先于一切代码调整，用户令）。
- **S2 执行合并**：dev 工作树 `git merge master`；出现预演外冲突时以实际为准并回填本文件；五个冲突文件按 §二裁决；合并提交信息走 `-F` 文件（反引号陷阱）。
- **S3 合并语义核查**：11 个交集文件对 dev 侧净变化过目（`git diff a69c79e --merge或合并后 diff`）；重点 `SqlPreviewService.kt`（master 的 HQL 实体名共享提取器接入 vs dev 的 O-95 慢操作下沉/O-96 绑定关联/T15 formatSql 泄漏修）与 `UnboundedWriteDetector.kt`（master 的 JPA 字面量掩码 vs dev 的 O-93 effective 劫持修复）；仓内无残留冲突标记 grep。
- **S4 定向冒烟**：`parallel_tasks check`/register → `testFast`（快桶全量冒烟）+ 冲突面定向（Preview 端到端、JPA pipeline、AgentConsent 族、mcp 守卫族，按 --tests 点跑）。
- **S5 全量门禁**：`timeout 10 docker info` 验 daemon → `testShards` 单路收口（默认 5 片）→ `:sql-visualizer-agent:test` → 读数入册。
- **S6 构建校验**：`verifyGate` 三目标（243/253/262）Compatible 判定——本合并属大改动（plugin.xml/build.gradle.kts 在变更面），必跑。
- **S7 修复循环与收口**：有红→定向修复→定向复绿→受影响面复跑→最终权威全量读数；实施记录回填本文件 §七；push dev；`parallel_tasks done` 注销。

## 四、待验证前提（实施者先证伪再动手）

1. merge-tree 预演的 5 冲突文件＝真实 merge 全量冲突面（预演与真实合并同算法，若真实出现预演外冲突以实际为准）。
2. T15 计划 master 侧 84 行版本无 dev 侧缺失的独有实质内容（合并时全文 diff 核对后裁决，不盲取）。
3. 11 个自动合并交集文件语义兼容：master 的 JPA/增强捕获/MCP 修复与 dev 的 O-93~O-96/T15 改动不互踩——以 S4 定向 + S5 全量证实，不预断。
4. 合并后门禁预期：dev 基线 6395/0/0/9 + parity 566 + agent 94/0，叠加 master 新增测试（JPA pipeline 端到端、consent 族、mcp 守卫、TinyClassFileWriter 等）总数上行、失败为 0；skip 面 9（docker）以 daemon 活性实测为准。
5. verifyGate 三目标合并后仍 Compatible：master 发行批与 dev 各自过过 verifyGate，asm 依赖删除属 master 单侧干净承袭；若冲突解决触碰 build 面则天然由 S6 复跑兜住。
6. gradle 环境可用：`parallel_tasks` 无活跃任务或本任务登记后运行；构建并发 ≤2 路，全量分片单路。

## 五、红线与范围外

- **零语义丢失**：冲突裁决两侧条目全保留，禁「取一侧省事」；合并后双向在场校验留读数。
- **不动回流面**：dev→master 回流、tag、发行工作流、update.json 均不在本任务范围（用户未令）。
- **不动他树**：master 树（含其 untracked 两件）与 t-statementweave 树只读。
- **收口纪律**：门禁期间不碰 `src/main`（修复循环例外，修后复跑）；长命令后台启不 sleep 轮询；提交信息 `-F` 文件。
- 范围外：T-StatementWeave 车道在途件、盘点点火、竞品轮次跟进。

## 六、测试与门禁口径

- 冒烟：`testFast`（快桶）+ 冲突面定向 `--tests`。
- 全量：`testShards`（默认 5 片，单路收口）读数格式 `通过/失败/skip/docker skip`；`parity` 类数；`:sql-visualizer-agent:test`。
- 构建校验：`verifyGate` 三目标。
- 三档分层：本任务为主控直做，全量与 verifyGate 均本会话收口，不另派收口代理（无并行车道在飞）。

## 七、实施记录（实施后回填）

- 待回填：合并提交号、冲突裁决实际清单、门禁读数（testFast/testShards/agent/verifyGate）、修复循环记录（如有）、push 读数。
