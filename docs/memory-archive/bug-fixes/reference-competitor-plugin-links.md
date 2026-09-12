---
name: reference-competitor-plugin-links
description: 竞品插件链接总表（2026-09-11 经 Marketplace API 逐一核验可达）——README「参考与致谢」定稿 6 项的
  id/页面/源码仓库，附 Marketplace API 核实链接工作流与 MyBatis Log Free 仓库纠错
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_5583cb72-c450-46d1-8620-9c64eb925af3
---

# 竞品插件链接总表（2026-09-11 核验）

README.md「参考与致谢」节（2026-09-11 新增，位于「变更记录」与「友情插件推荐」之间）定稿 6 项主要借鉴对象，链接全部经实测核验：

| 插件 | Marketplace | 源码仓库 |
|------|-------------|----------|
| MyBatisX | plugin/10119 | github.com/baomidou/MybatisX |
| MyBatisCodeHelperPro | plugin/9837 | 无（闭源） |
| MyBatis Log Free | plugin/17898 | github.com/starxg/mybatis-log-plugin-free |
| Mybatis Log Ultra | plugin/26550 | 无公开登记 |
| JTracker | plugin/24694 | 无公开登记 |
| Cool Request | plugin/23555，官网 www.coolrequest.dev | github.com/houxinlin/cool-request（GPL-3.0） |

**纠错**：早期计划文档（20260822-1606/1608）把 MyBatis Log Free 源码记为 `github.com/Stkai/intellij-mybatis-log-plugin`——Marketplace 官方登记实为 **starxg/mybatis-log-plugin-free**（Stkai 仓库是另一个插件）。引用竞品仓库一律以 Marketplace API 登记为准，不信计划文档。

## Marketplace API 核实链接工作流

- `curl -x http://127.0.0.1:7890 https://plugins.jetbrains.com/api/plugins/{id}` → `name`/`vendor`/`urls.sourceCodeUrl`/`urls.homepageUrl`（sourceCodeUrl 即官方登记的源码仓库，权威）；`/api/plugins/{id}/links` 端点返回空数组，别用。
- 不知道 id 时 `api/searchPlugins?search=<名>&max=5`（如搜出 Mybatis Log Ultra=26550、vendor yalonglee）。
- 其余核验过的 id（未进 README 但调研报告在用）：MyBatisCodeHelperPro Marketplace Edition=14522、MyBatis Log=13905、Free MyBatis Tool=18617、MyBatisPlus=12670、MySQL Explain=13192（repo newhoo/mysql-explain）、Mybatis-Flex-Helper=22165、JPA Buddy=15075、MyBatisSQLPreviewer=28245、MyBatis SQL Analyzer=32766。BatsLog 上游 = github.com/PerccyKing/batslog。
- GitHub 直连在本机网络下不通（curl 000），验证仓库可达用直连超时后改走代理或只信 API 登记。

相关 [[reference-coolrequest-agent-analysis]]、[[project-m56-agent-bytecode]]；README 精简口径见 [[feedback-doc-lists-keep-main-only]]。
