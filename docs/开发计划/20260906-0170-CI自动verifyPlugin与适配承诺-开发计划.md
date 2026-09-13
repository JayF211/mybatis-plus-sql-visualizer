# CI 自动 verifyPlugin 与适配承诺 开发计划

- **任务ID**：T-CiVerifyPromise
- **立项时间**：2026-09-06 01:30
- **分支**：`dev`
- **前置**：T-VerifyPlugin / T-M31（verifyPlugin 三态封装已在 `build.gradle.kts` 落地：默认全量 243/253/262、`-PverifyIdes=minimal` 边界两版本、`-PverifyIdes=smoke` 仅 243）；第二轮调研报告 O-5（O-1 残项）

## 1. 问题

IDE 新版本适配慢是竞品第一抱怨，且正在出现信任透支的新形态（第二轮报告 §4.1）：MyBatisX 适配滞后把用户逼到互教「手改 plugin.xml 版本号」「从 GitHub 第三方重打包安装」；MybatisCodeHelperPro 最新一条评论是 IDEA 2026.1 + Kotlin K2 模式下插件无法加载、每次启动约 180 条错误日志；MyBatis Log Free 干脆放弃 2025.x。

本插件在这条线上有先天优势：`sinceBuild=243`、无 `untilBuild` 上限，verifyPlugin 三目标（243/253/262）已是发布与里程碑的手工红线（`README.md` 268/271 行「验证节奏」段）。但两块短板让优势打了折扣：

1. **验证纯手工，无自动化**。探查确认：仓库无 `.github` 目录，亦无 Gitee Go 的 `.workflow` 目录——任何平台上都没有定时或触发的自动验证。红线靠人记得，漏跑无感知。
2. **优势没有被说出来**。`README.md` 258 行只写了「兼容性：IDEA 2024.3+（`since-build=243`，Plugin Verifier 按目标 IDE 集合校验）」，是开发者视角的事实陈述；用户视角的「新版 IDEA 发布后多久能适配」承诺缺失。

## 2. 方案

三件事：定时自动验证跑起来留痕、README 写入适配时效承诺、Marketplace 页同步同一句话。

### 2.1 CI 平台选型（探查结论）

- **GitHub Actions：不选**。`git remote -v` 确认唯一 remote 为 Gitee（`https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer`），仓库不在 GitHub；走 Actions 需维护镜像双 remote，为一条定时任务引入双仓库同步不值。
- **Gitee Go：暂不选，列为后续可选**。`.workflow` 目录不存在（从未配置），且存在三个硬阻碍：① `gradle.properties` 硬编码 `systemProp` 代理 `127.0.0.1:7890`，云端 runner 无此代理，依赖下载直接失败，须命令行 `-D` 逐项覆盖；② `build.gradle.kts`（「Plugin Verifier 是独立子进程」段）对 `VerifyPluginTask` 硬编码注入同地址代理 jvmArgs（verifier 子进程下载 IDE 发行版走它），必须改造成按环境变量条件化——这违背「低成本收尾」的定位；③ `org.gradle.jvmargs=-Xmx4096m` 对免费档 2c4g runner 偏紧，且三版本验证首次要下载三个 IDE 发行版，runner 缓存不持久则每次重下，14 分钟/次起步的免费额度消耗大。
- **结论：Windows 本地计划任务兜底为主方案**。理由：`dev-support/verify-full.ps1`、`verify-smoke.ps1` 已封装 `verifyFull`/`verifySmoke` 调用（`--no-daemon`）；本机 `gradle.properties` 代理与 `.pluginVerifier` 热缓存全部现成；凌晨调度天然错开并行测试共享的默认沙盒（README 271 行已注明 verifyPlugin 需与并行会话错峰）；零构建脚本改动。发布前的手工全量验证红线不变，定时任务是对「没发版的平常日子里平台悄悄移 API」的兜底。

### 2.2 验证命令（现成能力，不新增 Gradle 逻辑）

调度调用的就是既有 task：`./gradlew verifyFull`（= verifyPlugin 默认全量，目标 IU-2024.3.7.1 / 2025.3.5 / 2026.2.1，`failureLevel` 仅 `COMPATIBILITY_PROBLEMS`，约 14 分钟；见 `build.gradle.kts` `pluginVerification` 块与 `verifyFull` task 注册处）。

### 2.3 定时脚本与留痕

新建 `dev-support/ci-verify.ps1` 包装脚本：拉取 dev 最新 → 调 `verify-full.ps1` → 时间戳日志落 `dev-support/verify-logs/`（`.gitignore` 追加该目录，先例见同文件已有的 `dev-support/verify-target/`）→ 失败额外写 `LAST-FAILED.txt` 标记 → 日志轮转只保留最近 8 份。调度用 PowerShell `Register-ScheduledTask` 而非裸 `schtasks`，设 `StartWhenAvailable=$true`（凌晨关机错过调度则开机补跑），频率每周一次（建议周三 03:00）。注册命令写进脚本头注释，便于重装。

### 2.4 README 承诺文案（落点与草稿）

- 落点一：`README.md` 258 行「兼容性」bullet 追加承诺句；落点二：271 行「验证节奏」段补自动验证说明。
- 措辞草稿：**「适配承诺：新版 IntelliJ IDEA 正式版发布后 5 个工作日内完成兼容性验证并发布适配版本（或兼容性说明）；兼容性验证由计划任务每周自动执行（verifyPlugin 三目标 243/253/262），如遇平台破坏性变更导致无法按期适配，在本节与发行说明公示原因与预计时间。」**
- N=5 个工作日的理由：verifyFull 自动化后单次仅约 14 分钟，随时可手动触发；历史上适配动作（T-M30 最低版本调整、`supportsKotlinPluginMode` 声明）都是当日级操作；竞品催更潮证明用户对适配时效敏感——30 天级承诺无感知价值，1 天级承诺违约风险高，5 个工作日在「验证快、改动通常小」的现实下有余量；配套超期公示条款兜底，不做做不到的绝对化承诺。

### 2.5 Marketplace 页同步

`marketplace-custom-page.html` 有两份拷贝且内容已分叉（diff 确认）：`src/main/resources/html/` 与 `docs/发行说明/`。承诺句加进两份共有的 FAQ「插件支持哪些 IDE 版本？」答案（src 版 325 行附近、docs 版 318 行附近），措辞与 README 一致，两份同步改。

不做的取舍：

- **不引入 Gitee Go / 双仓库镜像**（理由见 §2.1，改造点记入本节，将来有需要另行立项）。
- **不改 build.gradle.kts 任何逻辑**：验证能力已齐，本计划只做调度、留痕与文案。
- **不对 EAP 版本承诺**：EAP API 变动频繁，承诺对象限正式版。

### 2.6 具体改动

| 文件 | 改动 |
|------|------|
| `dev-support/ci-verify.ps1` | 新建：拉 dev → `verify-full.ps1` → 时间戳日志 + 失败标记 + 日志轮转；头注释含 `Register-ScheduledTask` 注册命令（`StartWhenAvailable=$true`，每周三 03:00） |
| `.gitignore` | 追加 `dev-support/verify-logs/` |
| `README.md` | 258 行兼容性 bullet + 271 行验证节奏段：写入适配承诺（N=5 个工作日 + 超期公示）与每周自动验证说明 |
| `src/main/resources/html/marketplace-custom-page.html` | FAQ faq-1 答案补承诺句，与 README 措辞一致 |
| `docs/发行说明/marketplace-custom-page.html` | 同上一行，两份同步 |

Windows 计划任务本身是系统侧配置，不入仓库，以脚本头注释为唯一注册来源。

## 3. 实施步骤

1. 本计划提交立项（docs）。
2. 注册并行沙盒（ci-verify-promise）：`python C:\Users\USER\.zcode\cli\parallel_tasks.py check` → `register --name ci-verify-promise`，verifyFull 与并行测试共享默认沙盒/`.pluginVerifier` 缓存，必须错峰独占。
3. 写 `dev-support/ci-verify.ps1` + `.gitignore` 追加；手动跑通一次，核对 `dev-support/verify-logs/` 留痕与三目标 Compatible 结果。
4. 执行脚本头注释中的注册命令，`Get-ScheduledTask` 核对任务与下次触发时间。
5. README 两处 + 两份 HTML 承诺文案，逐句核对三处措辞一致。
6. 文档三处同步：README 与 Marketplace 页（本任务直接改动对象，即对外说明文档）、`docs/变更记录.md` 追加（日期倒序）、本计划 §变更记录回填。
7. 还原沙盒隔离、注销登记（`done`）。

## 4. 验收标准

- [ ] `dev-support/ci-verify.ps1` 真实跑通一次 verifyFull，`dev-support/verify-logs/` 留有成功日志（三目标 Compatible、0 COMPATIBILITY_PROBLEMS）
- [ ] Windows 计划任务注册成功，`Get-ScheduledTask` 可见且下次触发时间正确，`StartWhenAvailable` 生效
- [ ] README 承诺上线：258 行与 271 行含「新版 IDEA 正式版发布后 5 个工作日内完成适配验证」措辞与超期公示条款
- [ ] 两份 `marketplace-custom-page.html` FAQ 同步承诺，与 README 措辞一致
- [ ] `docs/变更记录.md` 追加、本计划变更记录回填（三处同步完成）
- [ ] `build.gradle.kts` 零改动（git diff 核对）

## 5. 风险

- 依赖开发机开机联网：`StartWhenAvailable` 已兜底错过后补跑；极端情况某周未跑也只损失一次平常日巡检，发布前手工全量红线不受影响。
- 最坏空窗：新 IDEA 正式版发布在两次调度之间（至多 7 天）才开始验证——承诺是 5 个工作日，靠「看到发布即手动触发一次」兑现，定时任务只做兜底而非唯一触发源；脚本日志中的 IDEA 版本清单每次运行都会核对最新发行版。
- 承诺违约风险：5 个工作日内既无法适配又忘了公示——对策写进 README 的公示条款本身；如平台破坏性变更常态化（如 K2 强制），届时重新评估 N 值并走变更记录。

## 变更记录

- 2026-09-06 T-CiVerifyPromise 立项：CI 自动 verifyPlugin 与适配承诺（本地计划任务兜底 + README/Marketplace 页 5 个工作日适配承诺，Gitee Go 与 GitHub Actions 经探查后不选）。
