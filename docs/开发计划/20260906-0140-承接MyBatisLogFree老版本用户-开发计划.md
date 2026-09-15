# 承接 MyBatis Log Free 放生的老版本用户 开发计划

- **任务ID**：T-MktLegacyUptake
- **立项时间**：2026-09-06 01:40
- **分支**：`dev`
- **前置**：第二轮竞品调研报告（`docs/开发计划/20260906-0120-竞品横向对比与功能建议-第二轮调研报告.md`，下称「第二轮报告」）§3.2 信号 2、§5.1 N-7；MyBatis Log Free 1.3.1 起兼容 262.0+，放弃 2025.x

## 1. 问题

MyBatis Log Free 新版把 2025.1–2025.3 上的存量用户放生（第二轮报告 §3.2），其 40 天 1.06 万下载证明这条赛道的流量池仍在；本插件 `since-build=243` 且无 until-build（`src/main/resources/META-INF/plugin.xml` 第 9 行），2025.x 实测全兼容（第二轮报告 §2），是这批无主流量第一次遇到的更强承接方。但承接的前提是被搜到、被看懂——逐项探查发现三处门面都没把话说满：

1. **兼容声明不点 2025.x**：plugin.xml `<description>` 英文段（第 24 行）只写 "since 2024.3"、中文段（第 40 行）只写「支持 IDEA 2024.3 及以上全部版本」；README.md 第 14 行与第 258 行同为「2024.3+」口径；`docs/发行说明/marketplace-custom-page.html` 的兼容信息只在 FAQ faq-1（第 316~320 行），且写的是 verify 目标集「兼容 2024.3 / 2025.3 / 2026.2 全系列」——2025.1/2025.2 用户对不上号。
2. **竞品搜索词零占位**：Marketplace 搜索索引的是名称、描述文本与后台 tags（发布流程 §8 表单 Tags：sql/mybatis/java/code-analyzer/database）；plugin.xml 描述全文 grep「mybatis log」0 命中，被更名甩掉的「MyBatis log / MyBatis SQL log」搜索词现在一个都不沾——搜竞品名的用户找不到本插件。
3. **痛点对比没写出来**：Log Free 四个存量抱怨（面板固定/IN 引号/LocalDate/JPA，第二轮报告 §4.2~§4.6）本插件分别已有对应能力甚至超额解决，但 README 与 HTML 没有一处把这层对照说给正在换插件的门店用户听。

## 2. 方案

纯文案与文档改动，不改任何生产代码；plugin.xml 只动 `<description>` CDATA 内文本。

### 2.1 兼容声明三处统一

统一口径：「IntelliJ IDEA 2024.3 起全版本兼容（含 2025.1–2025.3 与 2026.x）」。依据：plugin.xml 第 9 行 `since-build="243"` 无 until-build + 第二轮报告 §2「2025.1–2025.3 与 2026.1+ 实测全兼容」+ verifyPlugin 三平台红线（README.md 第 268 行）。

| 文件 | 现状 | 目标 |
|------|------|------|
| plugin.xml 第 24 行（英文段） | "Compatible with all IntelliJ platform versions since 2024.3 and continuously adapted." | 改写为明示含 2025.1–2025.3 的表述（如 "Compatible with every IntelliJ platform release since 2024.3 — including the full 2025.1–2025.3 line — continuously adapted."） |
| plugin.xml 第 40 行（中文段） | 「支持 IDEA 2024.3 及以上全部版本，持续适配」 | 「支持 IDEA 2024.3 起全部版本（含 2025.1–2025.3 与 2026.x），持续适配」 |
| README.md 第 14 行 | 「支持 IDEA 2024.3 及以上版本」 | 与中文段同口径 |
| marketplace-custom-page.html 第 318~319 行（faq-1） | 「兼容 2024.3 / 2025.3 / 2026.2 全系列」 | 「2024.3 起全版本兼容（含 2025.1–2025.3；2024.3/2025.3/2026.2 为发布前 verifyPlugin 校验目标）」 |

不做的取舍：不引入 `<keywords>` 元素——IntelliJ 平台 plugin.xml 无此配置项，Marketplace 搜索只吃名称/描述/tags，兼容性措辞进描述文本即可。

### 2.2 搜索关键词覆盖

在描述文本中自然植入真实功能对应的竞品搜索短语，不动结构、不堆砌：

- plugin.xml 英文段第 33 行 "Console SQL log capture &amp; restore (MyBatis / Hibernate / JdbcTemplate)" 改写为含短语的形式，如 "MyBatis SQL log capture & restore — console log to executable SQL (MyBatis / Hibernate / JdbcTemplate)"，让 "MyBatis log / MyBatis SQL log" 成为可索引的连续短语；
- 中文段第 47 行同步加「MyBatis SQL 日志还原」表述；
- **运营动作**：下次上传时在 Marketplace 后台 General Information 核对 tags（发布流程 §8），视情况补 `mybatis` 相关 tag；后台描述可与插件包分开单独编辑（发布流程 §8「描述可单独在页面编辑」），页面侧可先行生效，包内描述随下一版本对齐。

取舍：不做隐形关键词堆砌——发布流程 §9.2 列明「描述质量差（营销词/空话）」是官方被拒原因，植入以真实现有能力为限，每处短语都对应已交付功能。

### 2.3 四个存量痛点对比文案（草稿逐条落地）

素材全部来自第二轮报告 §4 的 Log Free 用户评论，逐条对应本插件已交付能力：

| Log Free 痛点（评论时间） | 本插件对应能力（证据） | 对比文案草稿 |
|------|------|------|
| 面板不能固定、debug 到一半才发现没开（2026-01-21） | 控制台出现即自动进捕捉态 + 首条 SQL 自动展开（60s 防抖）+ 手动关闭后本会话抑制不弹回（T-AutoShowSuppress）+ 日志合并主侧边栏 | 「日志面板不用盯：开始调试自动捕捉、首条 SQL 自动展开；嫌占空间手动关掉后本次会话不再弹回。」 |
| IN 漏引号（2024-07） | 参数回填「按字段类型决定是否加引号」（README.md 第 49 行、plugin.xml 第 46 行）+ IN 列表多值回填（README.md 第 99 行） | 「字符串值按字段类型自动加引号，IN 列表逗号分隔回填——不再漏引号。」 |
| LocalDate 报 ORA-01722（2025-04） | 日期/时间类型按类型回填 + 9 方言差异渲染 + 81 场景金样矩阵锁定语义（竞品翻车场景已进金样矩阵，第二轮报告 §4.2） | 「日期时间按类型正确回填，9 种方言渲染经 1538 保留字逐词引擎验证。」 |
| 「如果能支持 jpa 就好了」（2025-10） | JPA 派生查询/JPQL/@Query/CriteriaBuilder/HQL/NamedQuery/Specification/QBE 全覆盖（README.md 第 57~60 行；竞品能力矩阵该项全空白，第二轮报告 §3.4） | 「JPA/Hibernate 全家桶预览：findByXxx 派生、JPQL、Criteria、HQL 一键转 SQL——日志插件做不到的事，写代码时就能看到。」 |

**落点**：README「功能特性」节后新增小节「从 MyBatis Log Free 迁移？」放四行对照（上表第三列文案 + 一句兼容声明）；HTML 在 FAQ 区新增一问「我在用 MyBatis Log Free，可以换过来吗？」收纳四条精简版与 faq-1 兼容答复互链。plugin.xml 描述**不放**四条对比——描述以功能清单为限，营销对照文案放 README/HTML，规避 §9.2「描述质量差」风险。

### 2.4 具体改动

| 文件 | 改动 |
|------|------|
| `src/main/resources/META-INF/plugin.xml` | `<description>` CDATA 内：英文段第 24 行兼容句改写、第 33 行植入 "MyBatis SQL log" 短语；中文段第 40 行兼容句补 2025.1–2025.3、第 47 行加「MyBatis SQL 日志还原」。仅文本，不改任何 XML 结构/声明 |
| `README.md` | 第 14 行兼容句统一口径；新增「从 MyBatis Log Free 迁移？」小节（四行痛点对照 + 兼容声明） |
| `docs/发行说明/marketplace-custom-page.html` | faq-1 兼容答复改写（第 318~319 行）；FAQ 新增「MyBatis Log Free 用户迁移」一问（四条精简对照） |

## 3. 实施步骤

1. 本计划提交立项（docs）。
2. 注册并行沙盒（mkt-legacy-uptake）——本任务触碰 plugin.xml 并需跑 verifyPlugin 自证。
3. 实施 §2.4 三处文案改动（plugin.xml 描述、README、HTML）。
4. 验证：`./gradlew verifyPlugin -PverifyIdes=smoke`（243）——纯描述文本不涉 API，smoke 足够，全量三版本由下一次发行的 verifyFull 兜底；grep 核对三处兼容口径一致（2025.1–2025.3 均在）。
5. 文档三处同步：交付文档自身（plugin.xml 描述/README/HTML）+ `docs/变更记录.md` 追加（日期倒序）+ 本计划 §变更记录回填。
6. 还原沙盒隔离、注销登记。

## 4. 验收标准

- [ ] plugin.xml 描述 / README / HTML 三处兼容声明一致，均明示「含 2025.1–2025.3」
- [ ] 四个痛点（面板固定/IN 引号/LocalDate/JPA）对比文案各有着落（README 迁移小节 + HTML FAQ）
- [ ] 描述文本含 "MyBatis SQL log" 等竞品搜索短语，无营销堆砌（每处短语对应真实功能）
- [ ] verifyPlugin smoke（243）通过；零生产代码改动（仅 plugin.xml CDATA 文本与文档）
- [ ] Marketplace 后台 tags/页面描述核对动作列入下次上传清单（随 T-MktColdStart 补入发布流程的步骤执行）

## 5. 风险

- 描述改动的 Marketplace 页面生效路径有两条（后台单独编辑即刻生效 / 随包上传对齐），两处内容若不同步会造成页面与包描述漂移——以 plugin.xml 为唯一权威源，后台编辑仅作临时先行，下次上传必须对齐。
- 关键词植入分寸：官方审核对描述质量有明确被拒条款，宁可少植入不可堆砌；如审核退回描述，按退回意见裁剪并回写本计划风险记录。
- 承接文案对本插件能力的表述须与实测一致（2025.x 全兼容结论来自第二轮报告 §2 实测口径），若后续某版本适配出偏差，兼容声明同步回撤。

## 变更记录

- 2026-09-06 T-MktLegacyUptake 立项：承接 MyBatis Log Free 放生的 2025.x 老版本用户（三处兼容声明统一 + 竞品搜索词植入 + 四痛点对比文案），未实施。
