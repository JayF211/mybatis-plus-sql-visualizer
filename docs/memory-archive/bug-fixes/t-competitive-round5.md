---
name: t-competitive-round5
description: 第五轮竞品横向对比调研报告已交付（master 3213a21，20260916-0124，未立项实施）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_47e7ba14-d1b8-4c9b-aa27-cecb3b123bc7
---

第五轮竞品横向对比报告交付于 master `3213a21`（2026-09-16 凌晨，文档 `docs/调研报告/20260916-0124-竞品横向对比与功能建议-第五轮调研报告.md`），承接 [[t-competitive-round4-plans]]。四路并行子任务（本地增量核查/Marketplace 刷新/DBX 增量/竞品动向），主智能体抽查复核（十工具 mcp-optional.xml:19-28、七规则探测器、Unreleased 13 条等均 grep 实证）。

核心结论：
- **MyBatisX 1.7.7 盲区威胁证伪**：下载插件包与 1.7.6 逐条目 diff——8 新增 class 全是包装/拼写级、plugin.xml mcp/preview/explain 零命中、since 232→251 自筑兼容墙、GitHub 25 个月无 push；hasUnapprovedUpdate=true 判定为标志残留噪声。威胁降级「低威胁·兼容维护」。
- **v2026.9.14 已过审上架**（09-15 02:37 UTC，即第四轮报告落库后一小时），+13/日为上架以来最高但仅 1 天样本；0 票 0 评论仍是唯一冷启动短板。9.18-beta.1 只走 Gitee 抢先体验通道，Marketplace 零踪迹。
- **交付债第三犯且创纪录**：dev 领先 94/落后 26，七任务+快捷键节+MCP 十工具全在 dev；m56 三线 17 提交无远端；master/dev 各有 5/2 提交未推送。§8.1 合流清账为最高优先级（合流后发 v2026.9.18 正式版）。
- **DBX 清单 v4 #19~#26**（承接第三轮 v2 #1~#13、第四轮 v3 #14~#18）：#19 错误位置→编辑器定位跳转评 P0（marker 单一性门控+基建语句不带位置两细节照搬）；v3 #14~#18 全部有效/已落地，#15 被上游加固为执行前预判可回灌（O-17）。新建议 N-17 首评/叙事、N-18 错误定位、N-19「置信度 vs 证据」叙事、O-15~O-21。

**分支盘点定案（2026-09-16 用户质询后全量核实）**：九分支中七条已被 master∪dev 完全包含（t-mcp-toolset2 0领先于dev、log-timestamp/log-search-copy 0领先于master、r2备份点含于dev、backup-m56 含于feature/t-m56）；两条有意排除：feature/t-m56（17 提交独有，自身「独立性能评估前置」未反向合）与 feature/kotlin-to-java（09-14 Master171 收官后停更，活跃开发全在 Kotlin 线）。**M56 的 21 个 src/main 文件与十份计划锚点文件零交集**（M56 改 log 管线+config+plugin.xml 注册行，不碰 execute/ui/mcp 锚点），将来反向合并不推翻计划。

**Marketplace API 口径沉淀**（下轮抓取直接用）：updates 端点说明字段是 `notes` 不是 changeNotes；rating 端点 `meanRating` 是全站常量 4.064 不可用、须票型分布自加权；comments 时间字段是 `cdate` 毫秒时间戳、总数靠 Link 头翻页；详情端点无 rating 字段；搜索端点 max=20 封顶、cdate 是最近更新时间非首发。竞品 changeNotes 监控已由 `dev-support/check_competitor_watch.ps1`（计划任务每日 09:30）自动化。原始 JSON 留档 `%TEMP%\jbmkt_r5\`。

**2026-09-16 立项（master `b6c0e1b`，十份计划）**：T-MergeDev2Master（合流前置，94/26 漂移+推送留底+发行闭合）+ T-ErrorLocate（N-18 P0）+ T-McpNarrative（N-19）+ O-15~O-21 九任务；N-17 市场动作不立计划（第四轮 N-15 定例）。立项前两路 Explore 锚点核实（dev 工作树 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev`），**五份计划口径据实修正**：O-17「执行前预判」实测已在位（mayChangeMetadata 挂 executeOnConsole 入口 ：2242，真缺口=执行后兜底）；O-19 DBN 页签 displayName 只读不可命名→改语句注释标注；O-20 MCP 输出全是 buildString 纯文本无 JSON 面→改 PerfAdviceTool 预算补齐（全链唯一无上限工具）；O-21 DasModel 内省无「廉价源排序」问题→改负命中缓存（负命中不缓存+全遍历重扫是真缺口，挂 MetadataInvalidator 登记）+ identity 包裹定案不适用（crud_sql 从 PSI 实体生成无数据行）；O-15 MCP 链不执行 DDL→收窄 IDE 执行链（依赖 T-EL-02 富错误通道，SQLState/errorCode 现全链零消费）。顺手发现：RenderSqlTool @McpDescription 声称 `-- SQL #N` 多语句但实现单语句（描述失真）、plugin.xml:86 注释仍是「三只读工具」旧口径——均归 T-McpNarrative。可复用锚点：错误→编辑器跳转基建 OpenFileDescriptor 两处同构（SqlLogPanel.kt:1188-1207 / HistorySqlLogPanel.kt:482-497）；日志还原组间 `-- SQL #N` 注释头（LogToSqlPanel.kt:610-620）是原生控制台接受注释的现成先例。

**2026-09-16 二次核实（待办盘点会话）**：十份计划文档实测在库 `20260916-0300~0318`（0300 T-MergeDev2Master 前置 / 0302 T-ErrorLocate / 0304 T-McpNarrative / 0306 T-DdlRecover / 0308 T-NoticeChannel / 0310 T-DdlPostInvalidate / 0312 T-AliasDualTrack / 0314 T-StatementNaming / 0316 T-McpOutputBudget / 0318 T-MetaCheapSource），**全部未实施**；实测漂移 dev+94 / master+32（计划文档写 26，master 又前进）。dev 94 提交内含 T-RollbackSql 已实施（RollbackSqlDialog.kt + SqlPreviewPanel 接线 + RollbackSqlDialogTest/RollbackSqlPanelTest）。M37 系列（P-立项-06）全章结清见 [[m37-series-closure]]。待办主轴=交付链收口：先 T-MergeDev2Master 合流+推送留底+v2026.9.18 发行闭合，再推九任务（P0=T-ErrorLocate）与全景审计四计划；T-BranchHygiene 排合流之后。
