# 项目记忆归档索引

> 本目录存放从本地记忆系统（`~/.zcode/cli/memories/projects/.../memory/`）归档的历史记忆文件。
> 这些内容已完成/已交付/已修复，不再需要每次会话加载，但作为历史记录保留。
>
> **归档时间**：2026-08-22
> **原始位置**：`~/.zcode/cli/memories/projects/mybatis-plus-sql-visualizer-09474abd1ce62ee6/memory/`
> **全局备份**：`~/.zcode/cli/memories/global/`（41 个通用文件）
> **项目记忆**：保留 12 个核心文件 + MEMORY.md 索引

---

## 已修复 Bug（22 个）

| 文件 | 内容摘要 |
|------|----------|
| [project-exists-bugfix.md](bug-fixes/project-exists-bugfix.md) | exists 条件预览链终结点误判 |
| [project-iscrud-entity-resolution-bug.md](bug-fixes/project-iscrud-entity-resolution-bug.md) | IService/BaseMapper 实体解析后缀硬约束（v2026.8.19 已修复） |
| [project-iscrud-this-and-chain-qualifier-bug.md](bug-fixes/project-iscrud-this-and-chain-qualifier-bug.md) | 隐式 this 与链式调用预览失败 |
| [project-reverse-placeholder-bugfix.md](bug-fixes/project-reverse-placeholder-bugfix.md) | SQL→Java 反向 `?` 占位符识别缺陷 |
| [project-reverse-sql-java-improvements.md](bug-fixes/project-reverse-sql-java-improvements.md) | SQL→Java 反向链路质量大修（4d174c2 + 017d874） |
| [project-reverse-join-fix.md](bug-fixes/project-reverse-join-fix.md) | SQL→Java 反向联表 JOIN 转换修复 |
| [project-reverse-invocation-has-mpj-interface-bug.md](bug-fixes/project-reverse-invocation-has-mpj-interface-bug.md) | invocationHasMpj 漏判接口继承 MPJBaseService |
| [project-reverse-entity-scan-performance.md](bug-fixes/project-reverse-entity-scan-performance.md) | SQL→Java 无调用类性能问题（P0 反查缓存 + P1 启发式 + P2 扫描优化） |
| [project-jpa-hibernate-fix-20260820.md](bug-fixes/project-jpa-hibernate-fix-20260820.md) | JPA/Hibernate 识别缺陷三波并行修复（0bc39d8） |
| [project-jpa-test-hardening-20260821.md](bug-fixes/project-jpa-test-hardening-20260821.md) | JPA 测试体系三层加固 |
| [project-hibernate-jpa-support.md](bug-fixes/project-hibernate-jpa-support.md) | Hibernate/JPA 支持已交付（v2026.8.20） |
| [project-release-jpa-deferred.md](bug-fixes/project-release-jpa-deferred.md) | JPA/Hibernate 随 v2026.8.21 发行 |
| [project-sql-capture-no-project-required.md](bug-fixes/project-sql-capture-no-project-required.md) | SQL 监听手动启动无需等待项目（T-13，v2026.8.20） |
| [project-p051-import-dialog.md](bug-fixes/project-p051-import-dialog.md) | 参数导入增强（P-05.1，v2026.8.20） |
| [project-log-capture-lifecycle-design.md](bug-fixes/project-log-capture-lifecycle-design.md) | 日志捕获生命周期设计 |
| [project-sql-log-filterbar.md](bug-fixes/project-sql-log-filterbar.md) | SQL 日志面板过滤条 |
| [project-screenshot-placeholders.md](bug-fixes/project-screenshot-placeholders.md) | 功能截图占位已就位（7ca3f05） |
| [project-m51-api-cleanup-gaps.md](bug-fixes/project-m51-api-cleanup-gaps.md) | M51 清理后剩余 gap |
| [project-20260820-dev-plan-execution.md](bug-fixes/project-20260820-dev-plan-execution.md) | 2026-08-20 开发计划执行记录 |
| [project-test-speedup.md](bug-fixes/project-test-speedup.md) | 测试提速实测（clean test 带缓存 1m03s 最优） |
| [project-update-channel-beta-refactor.md](bug-fixes/project-update-channel-beta-refactor.md) | 更新机制改为 Gitee 抢先体验通道 |
| [project-architecture-docs.md](bug-fixes/project-architecture-docs.md) | docs/架构设计/ 三篇系列文档指针 |

## 已解决踩坑（31 个）

| 文件 | 内容摘要 |
|------|----------|
| [pitfall-criteriaquery-in-clause.md](pitfalls/pitfall-criteriaquery-in-clause.md) | CriteriaQuery IN 子句参数替换 |
| [pitfall-custom-dao-gutter-dedup.md](pitfalls/pitfall-custom-dao-gutter-dedup.md) | 自定义 DAO 方法 gutter 去重 |
| [pitfall-dataclass-signature-clean-rebuild.md](pitfalls/pitfall-dataclass-signature-clean-rebuild.md) | Data class clean rebuild 签名问题 |
| [pitfall-dialog-editor-dispose.md](pitfalls/pitfall-dialog-editor-dispose.md) | 对话框编辑器释放问题 |
| [pitfall-e2e-testing-gap.md](pitfalls/pitfall-e2e-testing-gap.md) | 端到端测试覆盖缺口 |
| [pitfall-filter-wrong-console.md](pitfalls/pitfall-filter-wrong-console.md) | 一条 SQL 显示两条 + 过滤条误触发 |
| [pitfall-gitee-hotlink-302.md](pitfalls/pitfall-gitee-hotlink-302.md) | Gitee 图片外链 302 重定向 |
| [pitfall-hibernate-parser-strict-idx.md](pitfalls/pitfall-hibernate-parser-strict-idx.md) | Hibernate 解析器严格索引越界 |
| [pitfall-hql-converter-structure.md](pitfalls/pitfall-hql-converter-structure.md) | HQL 转换器结构缺陷 |
| [pitfall-hql-jpa-preview-debugging.md](pitfalls/pitfall-hql-jpa-preview-debugging.md) | HQL/JPA 预览调试踩坑（10+ Bug） |
| [pitfall-hql-locate-precision.md](pitfalls/pitfall-hql-locate-precision.md) | HQL 定位精度问题 |
| [pitfall-hql-log-multiline-parse.md](pitfalls/pitfall-hql-log-multiline-parse.md) | HQL 日志多行 SQL 解析 |
| [pitfall-hql-param-extraction.md](pitfalls/pitfall-hql-param-extraction.md) | HQL 参数提取缺陷 |
| [pitfall-hql-query-locator-compilation.md](pitfalls/pitfall-hql-query-locator-compilation.md) | HQL QueryLocator 编译问题 |
| [pitfall-in-clause-comma-split.md](pitfalls/pitfall-in-clause-comma-split.md) | IN 子句参数回填三层修复（JPQL/XML/MP/MPJ） |
| [pitfall-jpa-converter-delete-and-params.md](pitfalls/pitfall-jpa-converter-delete-and-params.md) | JPA 转换器删除与参数处理 |
| [pitfall-jpa-gutter-anchor.md](pitfalls/pitfall-jpa-gutter-anchor.md) | JPA LineMarker 锚点必须是叶子元素 |
| [pitfall-json-format-quotes.md](pitfalls/pitfall-json-format-quotes.md) | JSON 格式化引号处理 |
| [pitfall-log-startup-capture-regression.md](pitfalls/pitfall-log-startup-capture-regression.md) | 日志启动捕获回归 |
| [pitfall-model-field.md](pitfalls/pitfall-model-field.md) | 测试引用错误字段名 |
| [pitfall-mpj-factory-duplicate-gutter-icon.md](pitfalls/pitfall-mpj-factory-duplicate-gutter-icon.md) | JoinWrappers.lambda() 工厂双图标 |
| [pitfall-plugin-update-jar-path-windows.md](pitfalls/pitfall-plugin-update-jar-path-windows.md) | 插件自更新 Windows 静默失败三断点 |
| [pitfall-reverse-subquery-join.md](pitfalls/pitfall-reverse-subquery-join.md) | 子查询 JOIN 反向解析修复 |
| [pitfall-selectcollection-preview-sql-bug.md](pitfalls/pitfall-selectcollection-preview-sql-bug.md) | selectCollection 预览 SQL 三缺陷 |
| [pitfall-settings-persist-inplace.md](pitfalls/pitfall-settings-persist-inplace.md) | SettingsPanel.apply() 原地修改不持久化 |
| [pitfall-sql-formatter-clause-line.md](pitfalls/pitfall-sql-formatter-clause-line.md) | SQL 格式化关键字换行 |
| [pitfall-startcapture-guard-bare-return.md](pitfalls/pitfall-startcapture-guard-bare-return.md) | SQL 监听全链路陷阱（11 轮迭代） |
| [pitfall-stopcapture-filter-leak.md](pitfalls/pitfall-stopcapture-filter-leak.md) | SQL 监听 stop 后 Filter 未移除 |
| [pitfall-test-coverage-gaps-jpa.md](pitfalls/pitfall-test-coverage-gaps-jpa.md) | JPA 测试覆盖缺口 |
| [pitfall-tm51-api-cleanup.md](pitfalls/pitfall-tm51-api-cleanup.md) | Marketplace Internal API 清理 |
| [pitfall-tm52-deprecated-api-replacements.md](pitfalls/pitfall-tm52-deprecated-api-replacements.md) | 废弃 API 替代方案全量实施 |

## 用户环境（1 个）

| 文件 | 内容摘要 |
|------|----------|
| [user-grok-api.md](user-context/user-grok-api.md) | Grok 中转 API 配置 |

---

## 全局通用记忆（41 个）

> 已复制到 `~/.zcode/cli/memories/global/`，跨项目复用。

**方法论（7 个）**：agent-parallel-test-isolation / agent-task-split / agent-verification / agent-commit-rhythm / wave-parallel-development / methodology-research / methodology-test-first

**用户偏好（6 个）**：user-ide-environment / user-local-proxy / user-token-efficiency / feedback-doc-style-no-ai-flavor / feedback-compilable-generated-code / feedback-batch-param-ux

**平台陷阱（28 个）**：pitfall-actiontoolbar-refresh-api / pitfall-classloader-forname-execution-api / pitfall-contentmanagerlistener-unavailable / pitfall-edt-psi-read-in-callbacks / pitfall-execution-api-missing / pitfall-getJarPathForClass-243-missing / pitfall-gradle-windows / pitfall-headless-validate / pitfall-heavy-psi-interface-resolution / pitfall-ipgp-stripped-ui-classes / pitfall-jblist-copy-itemToText / pitfall-kotlin-incremental / pitfall-kotlin-synthetic-bridge-internal-api / pitfall-lightplatform / pitfall-notification-setlistener-no-2param / pitfall-notificationgroup-displayname / pitfall-panel-disposer-leak / pitfall-parallel-inflight-block / pitfall-plugin-xml-listener-unsupported / pitfall-psi-create-edt-readaccess / pitfall-runide-sandbox-warnings / pitfall-sandbox-corruption / pitfall-script-edit-kotlin-escapes / pitfall-swing-mouse-no-bubble / pitfall-toolwindow-manager-listener / pitfall-uicompatible-dataprovider / pitfall-verifyplugin-proxy

---

## 统计

| 位置 | 文件数 |
|------|--------|
| 项目 memory（保留） | 13（含 MEMORY.md） |
| 全局记忆 global/ | 41 |
| 归档 archive/ | 54 |
| **合计** | **108** |
