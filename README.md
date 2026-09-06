# SQL Visualizer

> **别的插件给你文本，我们给你可执行的 SQL。**
> 支持 IntelliJ IDEA 2024.3~2026.x 全版本（含 2025.1–2025.3）。
> 四条理由：Wrapper 链静态生成 SQL 预览（无需运行代码，市场上唯一）；JPA/Hibernate 全家桶支持（派生查询 / JPQL / Criteria / HQL / NamedQuery / Specification / QBE）；预览即可一键执行验证（内嵌 IDEA 原生数据库控制台）；支持达梦/人大金仓等 9 种数据库方言（含信创场景）。

## 能力全景：覆盖调试的两个阶段

**编码期（写代码时，静态预览）**：Wrapper 链（Lambda / Query / Update / Delete / MPJ 联表 / 服务链）、XML 动态 SQL（if/foreach/OGNL）、注解 SQL、JPA 派生方法 / JPQL / Criteria、HQL —— gutter 图标一键预览最终 SQL（含逻辑删除、乐观锁、多租户拦截器模拟渲染），并支持 SQL 反向转 Java 代码。

**运行期（跑起来后，动态还原）**：控制台 SQL 日志自动捕获，`?` 占位符参数填充为可执行 SQL（MyBatis / Hibernate / JdbcTemplate 三族日志），日志条目与源码双向跳转，历史 SQL 落盘与两级导航。

**验证（随时）**：预览结果内嵌原生数据库控制台一键执行，EXPLAIN 执行计划（7 种方言真机验证），SQL 性能规则引擎与静态标注对照，JOIN 关系图可视化。

本插件原名为 MyBatis-Plus SQL Visualizer，现更名 SQL Visualizer——仍全面支持 MyBatis-Plus / MyBatis-Plus-Join / JPA / Hibernate，插件 ID 与升级链路不变。IntelliJ IDEA 插件：MyBatis-Plus / MyBatis-Plus-Join 的 SQL 调试利器——Wrapper 链、XML/注解动态 SQL 实时预览（含 MPJ 联表 JOIN），控制台日志一键还原，SQL 反向转 Java 代码。告别翻日志、手拼参数。支持 IDEA 2024.3 起全部版本（含 2025.1–2025.3 与 2026.x）。

## 功能截图

**Wrapper → SQL 预览弹窗**（动态条件勾选 / 参数回填 / 格式化）
<img src="docs/images/screenshot-01-sql-preview.png" width="720" alt="Wrapper→SQL 预览弹窗"/>

**SQL 日志工具窗口**（控制台日志捕获还原、日志转 SQL）
<img src="docs/images/screenshot-02-log-panel.png" width="720" alt="SQL 日志工具窗口"/>

**SQL → MP Java 代码反向转换**（右键 / Alt+Shift+J / 侧边栏 tab）
<img src="docs/images/screenshot-03-sql-to-java.png" width="720" alt="SQL→Java 反向转换"/>

**XML 动态 SQL 预览**（动态标签、OGNL 条件勾选、参数回填）
<img src="docs/images/screenshot-04-xml-preview.png" width="720" alt="XML动态 SQL 预览"/>

**Java 注解 SQL 预览**（@Select/@Update 等，注解行 gutter 图标）
<img src="docs/images/screenshot-05-annotation-preview.png" width="720" alt="注解动态 SQL 预览"/>

## 痛点分析

| 痛点 | 描述 | 影响 |
|------|------|------|
| **调试困难** | 开发者无法直观看到 MP 构造器最终生成的 SQL | 排查问题时需要开启 MyBatis 日志，查看繁琐的控制台输出 |
| **SQL 优化不便** | 无法快速验证生成的 SQL 是否合理 | 影响性能调优效率 |
| **联表查询复杂** | `MPJLambdaWrapper` 联表查询逻辑复杂，容易出错 | 需要频繁对比实际 SQL 验证逻辑正确性 |
| **参数不可见** | 日志中的 SQL 带着 `?` 占位符 | 无法直接看到实际参数值，调试效率低 |
| **CRUD 全覆盖** | 现有方案只能看查询 SQL，无法预览更新、删除语句 | 误操作风险高，特别是批量更新/删除 |
| **学习成本高** | 新成员学习 MP 时，无法直观理解 API 对应的 SQL 效果 | 团队培训成本增加 |

## 功能特性

**Wrapper 转 SQL 预览**

- 支持 `LambdaQueryWrapper` / `LambdaUpdateWrapper` / `LambdaDeleteWrapper`、字符串版 `QueryWrapper` / `UpdateWrapper`、`MPJLambdaWrapper` / `MPJQueryWrapper` 联表查询，以及 `service.lambdaQuery()` 等服务链
- 左侧面板可勾选动态条件（`.eq(true, ...)` 布尔重载）、回填 `?` 参数（LIKE 自动拼百分号、按字段类型决定是否加引号）、调整 foreach / bind 值
- SQL 自动格式化（多行 / 单行），分页子句按 9 种方言差异渲染（MySQL / PostgreSQL / Oracle / SQL Server / H2 / SQLite / MariaDB / DM / KingBase）

**XML 与注解 SQL 预览**

- mapper XML 动态 SQL 完整预览：`if` / `choose` / `where` / `set` / `foreach` / `bind` / `sql`+`include` 等标签按 MyBatis 语义渲染，OGNL 表达式可在面板勾选
- `@Select` / `@Update` / `@Delete` / `@Insert` 注解（含 `<script>` 与 Provider 注解）同样支持，注解行有 gutter 图标

**JPA / Hibernate SQL 预览**

- Spring Data JPA 派生方法（`findByXxx`）、`@Query`（JPQL / 原生 SQL）、CriteriaBuilder 调用链、HQL（含自定义 DAO 与命名查询）均可一键预览为 SQL
- 实体解析支持 `@Entity` / `@Table` / `@Column` / `@ManyToOne` 注解与 Hibernate `.hbm.xml` 映射；HQL 的 `SELECT u` 自动展开为实体列清单

**SQL 代码补全**

- mapper XML 与 Java SQL 注解字符串内 Ctrl+Space 补全：表名 / 列名（按子句位置提示）、`#{}` 参数名、XML 动态标签与 OGNL 表达式；预览弹窗的输入框同样带补全；Java 文件中 Wrapper 链上下文补全方法名 / 实体字段

**SQL → Java 反向转换**

- 选中 SQL 文本右键或按 Alt+Shift+J 生成 MyBatis-Plus 代码（QueryWrapper / LambdaQueryWrapper / Wrappers / MPJ 形态，默认形态可在设置页配置），支持增删改查与多表 JOIN，可指定调用类与返回 VO，生成的代码可直接编译
- 侧边栏工具窗口「SQL → Java」tab：粘贴 SQL 即转，支持形态快捷切换

**SQL 日志工具窗口（主侧边栏，4 个 tab）**

- 自动捕获运行控制台的 MyBatis `Preparing:` / `Parameters:` 日志（另支持 Hibernate 与 JdbcTemplate 日志格式），还原成可执行 SQL 条目列表，双击预览、右键复制 / 跳转源码
- 条目显示耗时（p6spy / MP TimeCount / 自定义前缀），超阈值红色标记并统计慢查询；列表支持 SQL 类型筛选、Ctrl+F 搜索、耗时排序、重复条目去重（⨉N 徽章即时同步）
- 「日志 → SQL」tab 可粘贴整段日志批量还原（多组语句自动分隔）；「历史」tab 浏览自动保存的历史 SQL（两级导航：文件 → 条目），可再次预览与跳转源码
- 双击/右键「跳转到源码」：应用注册内置 `InnerInterceptor` 示例（`docs/instrumentation/SqlSourceInterceptor.kt`）后可精确定位到触发 SQL 的代码行；未注册时按表名智能搜索实体近似跳转（覆盖 `Wrappers.lambdaQuery()` 等静态工厂与 `service.lambdaQuery()` 等 IService 服务链写法）

**检查更新（Gitee 抢先体验通道）**

- 稳定版由 JetBrains Marketplace 推送；设置页勾选「启动时自动检查更新」后进入 Gitee 内测通道，抢先获取新版本，发现新版弹通知一键下载安装（重启生效）；支持「忽略此版本」
- 内测用户若觉得好用，欢迎到 Marketplace 插件页（plugins.jetbrains.com/plugin/33633-sql-visualizer）评分留言；磁盘安装的包不会收到市场自动更新，在 Marketplace 搜索页点一次 Update 即完成迁移并交由 IDE 接管自动更新——到页面顺手评分留言即可

**其他**

- SQL 执行（IntelliJ IDEA Ultimate + Database 插件）：预览面板内嵌原生 Database 控制台，选数据源后即可执行并查看真实结果表格与错误信息；Community 版优雅降级提示
- JOIN 关系图：联表查询在预览面板「关系图」tab 自绘渲染表间 JOIN 关系
- 性能建议与复杂度：预览时按规则集给出 `SELECT *`、无索引列等性能提示与 SQL 复杂度评分
- 对比与分享：当前 SQL 与上次预览并排 Diff（IDEA 原生 Diff）；一键复制可执行 SQL；分享为 Markdown / HTML 报告
- 批量操作预览：`save` / `updateBatchById` 等批量 SQL 按列展开，参数可逐列勾选；`saveOrUpdate` 冲突子句按方言渲染（MySQL `ON DUPLICATE KEY UPDATE` / PG `ON CONFLICT` 等）
- 逻辑删除注入：实体含 `@TableLogic` 时自动追加 `AND deleted = 0`（可配置关闭）；多租户 / 数据权限拦截器模拟（占位符注入，可配置）
- UPDATE / DELETE 无 WHERE 条件时红色警示全表操作风险

## 从 MyBatis Log Free 迁移？

MyBatis Log Free 新版已放弃 2025.x；如果你正从它换过来，几个常见抱怨在这里都有对应解法：

- 日志面板不用盯：开始调试自动捕捉、首条 SQL 自动展开；嫌占空间手动关掉后本次会话不再弹回。
- 字符串值按字段类型自动加引号，IN 列表逗号分隔回填——不再漏引号。
- 日期时间按类型正确回填，9 种方言渲染经 1538 保留字逐词引擎验证。
- JPA/Hibernate 全家桶预览：findByXxx 派生、JPQL、Criteria、HQL 一键转 SQL——日志插件做不到的事，写代码时就能看到。

IntelliJ IDEA 2024.3 起全版本兼容（含 2025.1–2025.3 与 2026.x）。

## 预览 SQL 与运行时 SQL 的差异

> 预览目标是对齐 MP 运行时语义；以下差异是静态求值的固有特性，语义等价、仅文本不同。

| 差异 | 说明 |
|------|------|
| **静态常量内联** | `in(col, DicConstants.A, DicConstants.B)` 等静态 final 常量被求值内联（预览 `IN (1, 2)`）；MP 运行时渲染 `IN (?, ?)` 绑定参数 |
| **集合单占位符** | `in(col, 集合变量)` 运行时大小无法静态展开 → 预览 `IN (?)`；面板输入框支持逗号分隔多值回填（输入 `1,2,3` → `(1, 2, 3)`） |
| **分页字面量计算** | `page(new Page<>(1, 10), wrapper)` 字面量直接计算 `LIMIT 0, 10`；运行时变量渲染 `LIMIT ?, ?`（offset 来源标注 `(pageNum - 1) * pageSize`） |

## 使用说明

### 触发方式

| 方式 | 操作 |
|------|------|
| 右键菜单 | 光标置于 Wrapper 上 → 右键 → `Convert to SQL` |
| 快捷键 | 默认 `Alt+Shift+Q`（macOS `Option+Shift+Q`），可在 Keymap 或插件设置页修改 |
| 代码左侧图标 | Wrapper 锚点行左侧 gutter 图标，点击弹窗预览（可配置关闭） |

### 示例

```java
// 查询
LambdaQueryWrapper<DeviceInfoEntity> wrapper = new LambdaQueryWrapper<DeviceInfoEntity>()
    .eq(DeviceInfoEntity::getId, 1001L)
    .isNotNull(DeviceInfoEntity::getStationId);

// 生成的 SQL
SELECT id, station_id, share_scheme_id
FROM device_info_entity
WHERE id = 1001
    AND share_scheme_id IS NOT NULL
```

```java
// 更新（无 WHERE 时会警示全表更新）
LambdaUpdateWrapper<DeviceInfoEntity> update = new LambdaUpdateWrapper<DeviceInfoEntity>()
    .set(DeviceInfoEntity::getStationId, 101L)
    .eq(DeviceInfoEntity::getId, 1001L);
```

```java
// 联表查询（MyBatis-Plus-Join）
MPJLambdaWrapper<DeviceInfoEntity> wrapper = new MPJLambdaWrapper<DeviceInfoEntity>()
    .selectAll(DeviceInfoEntity.class)
    .leftJoin(StationInfoEntity.class, StationInfoEntity::getDeviceId, DeviceInfoEntity::getId)
    .eq(DeviceInfoEntity::getId, 1001L)
    .orderByDesc(StationInfoEntity::getCreateTime);

// 生成的 SQL
SELECT t.id, t.station_id, t.share_scheme_id
FROM device_info t
LEFT JOIN station_info t1 ON t1.device_id = t.id
WHERE t.id = 1001
ORDER BY t1.create_time DESC
```

### 其他入口

- **XML / 注解 SQL**：mapper XML 的 `<select>` 等语句或 Java Mapper 注解行左侧 gutter 图标，点击即预览；选中包含多条 SQL 的代码段会弹出候选栏（行号 + 摘要 + 定位跳转）
- **JPA / Hibernate**：Repository 派生方法、`@Query`、Criteria 链、HQL（含自定义 DAO）行左侧 gutter 图标，点击即预览
- **SQL → Java**：选中 SQL 文本右键 → `Convert SQL to MP Java Code`（或 Alt+Shift+J）；侧边栏工具窗口「SQL → Java」tab 粘贴即转
- **SQL 日志**：侧边栏「SQL Visualizer」工具窗口点「捕捉」开始监听控制台；双击日志条目预览还原 SQL；「日志 → SQL」tab 粘贴日志批量还原；「历史」tab 浏览自动保存的历史 SQL
- **帮助**：工具窗口工具栏「?」按钮在内嵌浏览器打开功能与快捷键一览（本地页面，无网络依赖）

### 配置

`Settings → Tools → SQL Visualizer`，设置按分区组织：

**SQL 预览**

| 配置项 | 默认值 | 作用 |
|--------|--------|------|
| 数据库方言 | MySQL | 分页 / upsert 子句按方言渲染（MySQL / PostgreSQL / Oracle / SQL Server / H2 / SQLite / MariaDB / DM / KingBase） |
| 替换 ? 占位符 | 开 | 关闭后变量也渲染为 `?`，在左栏输入框手动填值；代码字面量恒内联 |
| 格式化输出 | 开 | 多行缩进（关 = 单行） |
| 逻辑删除注入 | 开 | 实体含 `@TableLogic` 时自动追加 `AND deleted = 值` |
| 逻辑删除值 | 0 | 逻辑删除条件的值（对齐 MP `logic-delete-value`） |

**表名映射**

| 配置项 | 默认值 | 作用 |
|--------|--------|------|
| 表前缀 / 表后缀 | 空 | 推断表名统一加前缀 / 后缀（`sys_` + device_info → `sys_device_info`；`@TableName` 显式表名同样修饰） |
| 移除表名前缀 / 后缀 | 空 | 推断表名去除指定前缀 / 后缀（首个匹配；`;` 分隔多值） |
| 忽略字段名 | 空 | 指定字段不进 SELECT 展开与补全（`;` 分隔多值） |
| 表名推断策略 | SNAKE | 无 `@TableName` 时类名转表名的规则：SNAKE 驼峰转下划线 / CAMEL 保留驼峰 |

**代码生成（SQL → Java）**

| 配置项 | 默认值 | 作用 |
|--------|--------|------|
| SQL→Java 默认 Wrapper 形态 | 自动 | 反向转换生成代码的默认形态：AUTO 自动选择 / QUERY / LAMBDA / WRAPPERS / MPJ |
| 自动推断失败时允许手动选择实体类 | 开 | 关闭后纯自动推断，不弹实体选择框 |

**SQL 日志**

| 配置项 | 默认值 | 作用 |
|--------|--------|------|
| SQL / 参数前缀 | `Preparing:` / `Parameters:` | 控制台日志识别前缀（「混合前缀」开启后可同时识别两种格式；另支持 Hibernate / JdbcTemplate 前缀与耗时前缀配置） |
| 自动捕捉 | 关 | IDE 启动后自动开始监听控制台 |
| 发现 SQL 自动打开窗口 | 关 | 捕获到 SQL 时自动打开日志工具窗口 |
| 时间戳显示 | 开 | 条目列表显示捕获时间（格式可配，默认 `yyyy-MM-dd HH:mm:ss`） |
| 耗时显示 / 慢查询阈值 | - | 解析 p6spy / TimeCount / 自定义前缀耗时，超阈值红色标记并统计慢查询 |
| 最大条数 | 可配 | 日志条目上限（M46），超出自动淘汰 |
| 去重窗口 | 60000 ms | 相同 SQL 在窗口内重复不显示（勾选「移除重复的sql」可立即裁剪既有条目，⨉N 徽章计数） |
| 移除包含关键字 | 空 | 过滤包含指定关键字的日志行（`;` 分隔多值） |
| 自动保存捕获的 SQL | 关 | 条目自动追加到 `.idea/sql-visualizer/sql-history/`，供「历史」tab 浏览 |

**界面与操作**

| 配置项 | 默认值 | 作用 |
|--------|--------|------|
| 代码左侧显示预览 SQL 图标 | 开 | Wrapper 锚点行 gutter 图标入口 |
| 界面语言 | 简体中文 | 插件 UI 语言（English / 跟随系统可选） |
| 快捷键 | `Alt+Shift+Q` / `Alt+Shift+J` | 分别对应 Wrapper 转 SQL 与 SQL→Java 反向转换；录制式修改，保存写入 IDE Keymap（冲突可「强制使用」抢占） |

**更新与诊断**

| 配置项 | 默认值 | 作用 |
|--------|--------|------|
| 启动时自动检查更新 | 关 | 勾选后进入 Gitee 抢先体验通道，启动静默检查（检查源为仓库 `update/update.json`），发现新版弹通知（可一键后台下载安装，支持「忽略此版本」）；稳定版由 JetBrains Marketplace 推送 |
| 检查更新 / 下载并安装 | - | 手动检查更新、查看变更日志、一键下载安装（重启生效） |
| 插件缓存 | - | 显示反查缓存占用大小，可一键清除 |
| 内部构建号 | - | 每次构建唯一（MPV-Build），反馈问题时一键复制粘贴 |

设置变更无需重启 IDE：重新触发预览即按新配置生效；弹窗内「单行 / 多行」按钮为临时视图切换，不写回「格式化输出」配置。

### SQL 预览弹窗

弹窗默认尺寸按屏幕比例自适应（宽 62% / 高 65%，下限 900×600），可手动拉伸并记忆尺寸；左侧动态条件复选框 + 参数输入，右侧 SQL 可编辑（多 tab：SQL / 联表关系图 / 列信息）。工具栏提供格式化、复制、复制可执行 SQL、对比（Diff）、分享、执行（Ultimate 原生控制台内嵌）等操作。

## 安装常见问题

**Q: 从插件市场安装时提示 "Remote host terminated the handshake"？**

这是 TLS 握手阶段网络中断导致的瞬时错误，不影响最终安装结果（重试后通常成功）。常见成因：

- **本地代理拦截**：若使用 HTTP 代理（如 127.0.0.1:7890），尝试 `Settings → Appearance & Behavior → System Settings → HTTP Proxy` 切换为「No proxy」或「Auto-detect」后重试
- **网络抖动**：等待几秒后点击「Install」重试
- **防火墙/安全软件**：确认未拦截 plugins.jetbrains.com 的 HTTPS 连接

若反复失败，可手动下载 zip 后通过 `Settings → Plugins → ⚙ → Install from disk` 安装。

**Q: 安装时提示 "Required plugin 'com.intellij.database' is not installed"？**

`com.intellij.database` 是可选依赖（仅「执行 SQL」功能需要），缺失不影响核心预览/日志/反向转换功能：

- **Ultimate 用户**：点「Install」安装 Database 插件即可解锁「执行 SQL」能力（原生控制台内嵌）
- **Community 用户**：无 Database 模块，点「Cancel」跳过即可；预览、日志、SQL→Java 完全可用

该提示在 v2026.9.2 起已优化为可选依赖声明，旧版本可能仍弹确认，直接点 Cancel 跳过不影响使用。

## 文档

| 文档 | 说明 |
|------|------|
| [功能概览](docs/需求文档/20260812-1058-MP-SQL可视化-需求文档.md) | 完整功能清单与规格（`F-xx`） |
| [开发计划](docs/开发计划/20260812-1058-MP-SQL可视化-开发计划.md) | 分阶段开发计划与验收标准（`T-xx`） |
| [架构设计](docs/架构设计/01-项目架构总览.md) | 架构总览 / 核心流程详解 / 开发与测试指南系列 |

## 开发环境

- JDK 17（`jvmToolchain(17)`）
- 构建平台：IntelliJ IDEA Ultimate 2024.3.7.1（最低支持版本编译；数据源 API 在 `com.intellij.database`，仅 Ultimate 捆绑）
- 兼容性：IDEA 2024.3+（`since-build=243`，Plugin Verifier 按目标 IDE 集合校验）
- Gradle 9.x（Wrapper 内置）

## 常用命令

| 命令 | 说明 |
|------|------|
| `./gradlew build` | 编译 + 测试 + 打包插件 |
| `./gradlew test` | 运行单元测试 |
| `./gradlew runIde` | 启动沙箱 IDEA 调试插件 |
| `./gradlew verifyPlugin` | 兼容性校验：默认全量三版本（243/253/262，发布前）；`-PverifyIdes=minimal` 边界两版本（243+262，约 10 分钟）；`-PverifyIdes=smoke` 仅构建基准 243（约 2 分钟，日常开发后冒烟） |
| `./gradlew buildPlugin` | 生成发布 zip（`build/distributions/`） |

验证节奏：日常功能提交前 `test` + `verifyPlugin -PverifyIdes=smoke`（约 3 分钟）；使用新平台 API 后 `minimal`；发布 / 里程碑交付用全量三版本。verifyPlugin 与并行测试共享默认沙盒 / `.pluginVerifier` 缓存，并行会话需错峰。

## 发布

详见 [开发计划](docs/开发计划/20260812-1058-MP-SQL可视化-开发计划.md) 的 M6 里程碑。

M28 起发布流程新增一步（插件设置页「检查更新」的数据源）：打版发布时维护仓库根 `update/update.json`——运行 `./gradlew writeUpdateManifest` 生成骨架（版本号 / 下载地址 / 发布日期），人工补充本次 `changeNotes`（纯文本，`\n` 换行），提交推送后生效（清单 URL 固定：`https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/raw/master/update/update.json`）。

自 v2026.9.2 起该清单转为 **Gitee 抢先体验通道**（内测）：`update/update.json` 为检查源，`preview.update.json` 为推送前草稿，可配合 `local-manifest-server.py` + `Run IDE (Local Manifest)` 沙盒运行配置在推送前本地验证通知效果；稳定版由 JetBrains Marketplace 推送，`autoCheckUpdate` 默认关闭、用户主动勾选才开启抢先体验。

## 变更记录

见 [CHANGELOG.md](CHANGELOG.md)。

## 友情插件推荐

如果你也在做后端 API 开发，推荐试试 [Cool Request](https://www.coolrequest.dev)——一款 IDEA 内置的 HTTP 客户端插件，类似 Postman 但直接嵌在 IDE 里。支持 Spring Boot、Spring MVC、JAX-RS 等框架，一键发送请求、调用任意对象方法、统计 API 耗时、生成在线文档，总下载量已超百万次。

<a href="https://www.coolrequest.dev" target="_blank"><img src="docs/images/cool-request-icon.svg" width="32" height="32" alt="Cool Request Logo" style="vertical-align:middle;margin-right:8px;"/>Cool Request — 让 API 调试变得简单</a>

---

## 联系作者

扫码添加作者微信或加入交流群（二维码为限时码，仓库内图片定期更新）：

| 作者微信 | 交流群 |
|----------|--------|
| ![微信](docs/images/wechat-qr.png) | ![交流群](docs/images/group-qr.png) |

遇到问题或有建议？请在 [Issues](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/issues) 提交反馈。
