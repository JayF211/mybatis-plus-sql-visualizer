---
name: t-intranet
description: T-Intranet 内网/离线可用性专项审计结论与四任务计划（2026-09-15
  立项，借助换源属性只在启动时读一次的新发现）——插件默认配置下内网完全可用
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6e2551a5-c9ad-4d81-ae53-317d6f133dd0
---

2026-09-15 用户提问「插件在内网环境能否正常使用」触发全量网络路径审计，结论 = **默认配置下内网 / 全离线完全可用**，并据此立项 T-Intranet（计划 `docs/开发计划/20260915-1430-T-Intranet-内网离线可用性声明与换源入口-开发计划.md`，**已立项待实施**，分支规划 `t-intranet`）。相关：[[project-website]]、[[t-listparamcollect]]（同批次文档期任务）。

## 运行期网络面（审计结论，只有 2 个出口 4 处调用点）

| 出口 | 调用点 | 超时 | 默认状态 | 失败表现 |
|---|---|---|---|---|
| 更新通道 | `update/UpdateApi.kt:25`（拉清单）、`:41`（下载包），目标 Gitee raw | connect 15s / read 30s | 启动检查由 `autoCheckUpdate=false` 拦下（`config/PluginSettings.kt:151`） | 启动静默 return；设置页手动检查显示 `settings.update.checkFailed` 红字 |
| 反馈通道 | `feedback/FeedbackApi.kt:272`，目标 `sql-visualizer.jvtools.cn` | connect 10s / read 10s | 用户主动点提交 | 状态行显示原因（`describeHttpFailure` 识别代理拦截页），非模态 |

明确**不存在**（grep 实证）：遥测/埋点/统计上报、AI/LLM 接口、内嵌 web server（`src/main` 无监听端口/无入站面）、许可证/激活/账号/Token 校验、JDBC 驱动自动下载（执行 SQL 走 IDE 原生控制台，插件只创建临时 `.sql` 文件，驱动由 **IDE 平台**联网下载）、DTD 联网拉取（`XmlMapperParser.kt:108` + `PagesXmlParser.kt:109` 装空 EntityResolver 且关外部实体——内网 DNS 黑洞下最关键一条）。帮助页为 jar 内本地 HTML（`SqlLogPanel.kt:914` 读 `html/marketplace-custom-page.html`，纯内联样式零外部请求）。日志里那些 `mybatis.org/dtd` 只是注释/特性名字符串，不是请求。

依赖面：硬依赖仅 `com.intellij.java` + `com.intellij.modules.xml`（恒有），Kotlin/database/diagram 三可选依赖缺失只降级不阻断安装，`since-build=243` 不设上限。

## 关键新发现（此前无人记录）

- **`mpvs.update.manifest.url` 只在进程启动时读一次**：`UpdateSource` 是 Kotlin object，`manifestUrl()` 首次调用触发类初始化并缓存 `DEFAULT_MANIFEST_URL`；「改 `-D` 后立即生效」不成立，**需重启 IDE**。内网私有更新通道文档必须写明这条。
- 该换源属性目前只写在 `UpdateSource.kt` 代码注释里，README / 官网 / 需求池全无用户文档。
- 清单字段 `downloadUrl` 无域名白名单，整体可指向内网文件服务（`UpdateManifest.kt` / `parseManifest`）。
- 构建期才是内网的真风险（与运行期分开说）：`gradle.properties:33` 硬编码 `127.0.0.1:7890` 代理、`build.gradle.kts:181`(verifyPlugin)/`:756`(testDatabase) 注入同代理、`settings.gradle.kts` 仓库为阿里云+MavenCentral+JetBrains 且无 `--offline`、`intellijIdeaUltimate` 需下 GB 级发行版。

## 四任务（已交付合入 master，2026-09-16 核实）

- **01**：`UpdateSource.manifestUrl()` 补一次性 `LOG.info`（**仅在被系统属性覆盖时打印**，不打印默认值避免噪音）+ `UpdateSourceTest` 2 例纯函数断言。
- **02**：README 新增「内网与离线环境」节（网络行为清单 / IDE 驱动下载提示 / 私有更新通道用法含「需重启 IDE」/ 构建期需网与运行期可用的区分）。
- **03**：官网 `mybatis-plus-sql-visualizer-docs` 仓新增中英 `guide/offline.md` + `.vitepress/config.mts` 两处侧边栏接线 + compatibility / faq 链接（新页不进侧边栏等于不可发现）。
- **04**：`docs/变更记录.md` + CHANGELOG 同步（本轮用户可见行为无变更，CHANGELOG 是否入版待定）。

## 口径红线（本轮最大的返噬风险）

文档**禁用**「完全不联外网」「零网络」这类绝对措辞——两个可选出口真实存在，且内网用户会把「执行 SQL 缺驱动」和「点检查更新失败」归咎到插件。所有陈述须能回溯到 `文件:行`。不采纳「加 `-Doffline` 全局开关」：功能本就能离线工作，加开关反而暗示「不开就不能离线用」。