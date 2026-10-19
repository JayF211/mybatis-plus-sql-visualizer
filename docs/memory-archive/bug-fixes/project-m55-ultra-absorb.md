---
name: project-m55-ultra-absorb
description: M55 Ultra 精华融合开发计划已立项待实施（f251577，mpsv-r2 分支）——四子任务 T-M55-01~04
  与标记行格式公共约定；R2 真机验证测试计划同步落盘待智能体自动执行
metadata:
  node_type: memory
  type: project
  originSessionId: sess_b60633b3-135d-4438-89b9-0654d88a6741
---

# M55 Ultra 精华融合（已立项待实施）

**2026-09-07 立项**，提交 f251577 于 feature/r2-competition-response（worktree mpsv-r2）。计划：`docs/开发计划/20260907-0905-MybatisLogUltra能力对照-开发计划.md`。

**来源**：N-10 调研（20260906-0230）结论「无需另立」被深化调研推翻——四源证据（updates API 经 `/api/updates/{id}` 端点逐版取全量 changeNotes、文档站 i18n、演示 GIF 帧、GitHub）修正三处认知：① 方法级聚焦完成度被低估（动态 `Class#method` 页签，与 Mapper 方法名分组是两个特性）；② 请求级聚合实为扁平过滤视图而非树形分组；③ Restful Api Tool 是真实内嵌 HTTP 客户端且为请求边界触发器。

**四子任务**：T-M55-01 跳转双目标 XML mapper（成本最小：拦截器模板 `ms.getId()` 现成未输出，标记行加第 4 段 + MapperXmlLocator 定位器仿 HbmXmlEntityResolver FileTypeIndex）；T-M55-02 SQL 执行报错同列（异常行反向绑定已入列条目是新机制，覆盖参数绑定报错反超 Ultra 4.0.14 自述边界）；T-M55-03 方法级聚焦页签（静态行号区间近似版：sourceFile+sourceLine → PSI 方法区间 → 过滤页签，不碰 VisibleDiff）；T-M55-04 请求级聚合过滤版（RequestContextHolder 标签 + 第 5 段，无会话管理）。

**关键公共约定**：标记行 `SQLVIS-SOURCE|path|line[|statementId[|requestTag]]` 3~5 段尾部追加、解析端宽松兼容——用户项目旧拦截器幂等不升级是常态，3 段兼容是一等验收项。实施顺序：01→02 串行（同动 SqlLogConsoleBridge.processConsoleLine），03∥04 并行（04 依赖 01 格式）。Ultra 采集原始素材在 `C:/Users/USER/AppData/Local/Temp/ultra-recon/`（临时目录，含 notes.md 与 API JSON/截图帧）。

**R2 真机验证测试计划**：`docs/开发计划/20260907-0900-R2交付真机验证-测试计划.md`（同提交）——T1 O-8 三缺陷/T2 N-9 生成测试/T3 O-6 跳转 UI 必测（T1~T3 共用一次 runIde 会话定序 T1→T3→T2）、T4 O-5 计划任务（合并 dev 前手动触发会失败，硬前置已标注）、T5 Ultra 实测可选。坑：demo 无 Device 实体（预期 SQL 用真实 User 表 + 逻辑删除双列）；demo Java 8 下 var 编译报错属预期（恰好走 typeElement 文本判定路径）。步骤键盘鼠标级，供智能体自动执行（操作要领沿 [[runide-demo-verify-workflow]]）。

## Ultra 官方素材 UI 时代（2026-09-07 用户问「官网图 IDEA 和我 UI 不一样」的答复）

结论：Ultra 官网/演示图全是 **New UI**（非经典 UI），素材停在 **2025 年**——用户 2026.2.1 是同一套新 UI 迭代两年后的形态，故观感不同。证据（素材在 Temp/ultra-recon/）：① 真机演示 GIF（GitHub 4 张，录于 2025-09，提交 2026-02）跑 2025.x 早期新 UI——顶部紧凑工具栏无传统菜单栏、项目树顶部无搜索框（2026.x 已常驻）、圆角细长编辑标签；② 官网/Marketplace 主视觉（2500x1608 PNG）是 **React mock 渲染**（「演示」字样自证，忠实复刻产品 UI），视觉停在 2023.x 深色主题时代；③ 4.0（2026-05）后未更新任何演示素材（活跃期 2025 上半年）。**对本插件的启示**：N-6/N-7 市场物料若做官网/宣传图，用当前版本实拍比 mock/旧素材更可信——Ultra 官图与用户真机存在一年代差是可感知的信任折损。

## M56 agent 字节码增强路线（2026-09-07 用户决策转向）

**用户推翻 M55/N-10 的「明确不吸收 agent」结论**：要求深度调研 agent 字节码增强并出开发计划（M56 号已确认未占用）。核心认知——agent 不是替代控制台日志，而是捕获层代差：**一个 JDBC 层 hook 全覆盖 9 框架族**（JdbcTemplate/Hibernate/JPA 底层全是 JDBC），能拿 SQL 文本 + 实参 + 耗时 + 影响行数 + 报错 + 调用栈 + 请求上下文，确定性远超解析控制台日志（漏参数/丢上下文/格式变化即断）。

已派两个后台调研子智能体（2026-09-07）：① agent 技术形态——premain(-javaagent 改运行配置) vs attach(热挂载不动配置，插件需从 Run 会话拿 pid，IDEA 公开 API 查 OSProcessHandler/ProcessHandler)；字节码工具选型 ByteBuddy(~4MB)/ASM(~200KB)/Javassist（agent jar 必须解压到文件系统路径才能 loadAgent，不能从插件 classpath 直挂）；IDEA 生态开源先例 arthas-idea-plugin；Ultra 59.7MB 内置 Arthas 包推断；JDBC 层 vs MyBatis 层 hook 矩阵（MyBatis 层才能拿 MappedStatement id → XML 跳转）；② 本插件链路改造面——控制台桥数据流、F-44-6/7 源码注入方案弃留（用户可见可删=信任底座）、M55 四任务在 agent 通道下简化/替代/不受影响、agent 服务与设置开关落点。

计划核心架构（拟定）：attach 热挂载 + 双层 hook 取舍（JDBC 全覆盖 vs MyBatis 保语义 ms.getId()）+ **agent/控制台桥双通道并存自动降级**（agent 不可达——远程/容器/attach 失败——回退现有桥，零损失）+ 用户显式确认与开关信任流程（agent 注入敏感；Instrumentation 不可逆不卸载只禁用；同一 JVM 幂等只挂一次）。关联 [[project-m55-ultra-absorb]]（M55 四任务待 M56 调研结论出来再定处置）。

## 2026-09-16 核实（待办盘点会话）

四子任务**全部未实施**：标志物 `MapperXmlLocator` 在 master / dev / feature/r2-competition-response 三分支 `git grep` 零命中。标记行 3~5 段宽松化公共约定已随 T-M56-00 提前交付；四子任务与 M56 agent 通道职责重叠，**建议与 M56 统筹而非独立排期**。另实测本计划文档在 r2 与 master 的 `docs/开发计划/20260907-0905-MybatisLogUltra能力对照-开发计划.md` 路径均 git cat-file 失败，文档实际位置待查（r2 分支现存 HEAD ad292ed）。
