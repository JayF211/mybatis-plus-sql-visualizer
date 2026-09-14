---
title: docs 文档检索索引
purpose: 本文件是 docs/ 目录的**文档检索索引**（导航清单），用于快速定位项目文档——"哪个文档讲了什么、去哪查"
scope: docs/ 全目录（README / 需求文档 / 开发计划 / 发行说明 / 测试清单 / 覆盖矩阵 / 图片）
metadata-note: >-
  与 ~/.zcode/cli/memories/projects/mybatis-plus-sql-visualizer-*/memory/ 的用途不同：
  本文件是**文档检索索引**（随仓库发布，回答"文档在哪、查什么"）；
  ~/.zcode 记忆是**会话经验沉淀**（本地私有，回答"多智能体怎么调度、踩过哪些坑、代码怎么扩展"）。
  两者不互斥：检索文档看本文件；检索开发经验看 ~/.zcode 记忆。
update-rule: 新增/归档文档时同步更新本索引，与 docs/ 目录保持一一对应；按目录分组、时间倒序
---

# docs 文档检索索引（MEMORY.md）

> 本文件为 docs/ 目录导航清单，随仓库发布。用途与本地 ~/.zcode 会话记忆不同（见上方元数据）。

> 🔴 **多会话并行开发必读（重点）——并行测试协调登记表**：
> 场景原因：2026-08-15 T-M26.1 验证时两个并行会话同仓库跑测试，测试沙盒互锁 + 互相误杀对方测试 JVM（误判残留进程），导致索引/test-results 损坏、1.3GB hprof 垃圾的「互锁互杀」循环；此后落地共享登记表机制，**任何会话跑 Gradle 测试前必须查表**。
> - 脚本（本机工具，不在仓库）：`C:\Users\USER\.zcode\cli\parallel_tasks.py`；数据文件 `parallel-tasks.json` 同目录（全局共享）
> - 用法：`python parallel_tasks.py check`（无活跃任务 exit 0 / 有活跃任务 exit 1 并列出对方）→ 有并行活动时 `register --name <任务名> --sandbox <独立沙盒>` 登记 → 跑完 `done` 注销；`prune` 清超时残留
> - kill 白名单：kill java/idea 进程前查表——命令行含**自己登记的沙盒名**才允许 kill
> - **沙盒本身也会冲突**：① `prepareTestSandbox` 目标文件互锁（「用户映射区域」错误——对方/残留测试 JVM 把沙盒内 jar 映射锁定，复制必失败）；② SDK 解压目录（`~/.gradle/caches/.../transforms/idea-2025.3.5-win`）两会话共享，沙盒 system 索引（`records.dat`）互锁会强制重取；③ 已隔离的沙盒也可能被**自己早前运行的残留测试 worker** 锁住——按白名单杀（命令行含自己沙盒名）或直接删沙盒目录再跑
> - M28×M29 二次实证教训：登记会自锁 `check`（别把 check 链进自己命令）；共享 gradle daemon 会被对方 `--stop` 误杀（测试执行器莫名 FAILED）——权威全量回归建议 `git worktree` 独立目录 + `--no-daemon`（沙盒随 build 目录一并完全隔离）
> - 完整约定见本地记忆 `agent-parallel-test-isolation.md`（本机 `~/.zcode/cli/memories/projects/.../memory/`）

## 核心文档（docs/ 根目录）

| 文档                                                                          | 用途 |
|-------------------------------------------------------------------------------|------|
| [需求文档/MPJ用法收集与兼容性测试清单.md](./需求文档/MPJ用法收集与兼容性测试清单.md)    | **真实项目语料库**：MPJ 61 文件/原生 92 文件/XML 20 个；模式编号 P01~P20 / Q01~Q19 / U01~U13 / X01~X12；§5.6 官方 API 覆盖矩阵（现状）；§6 测试建议 |
| [架构设计/MP-MPJ官方API覆盖矩阵.md](./架构设计/MP-MPJ官方API覆盖矩阵.md)                | **官方 API 全量覆盖矩阵**（224 API 行，✅/🟡/⬜ + 任务编号 + 版本引入）：MP 3.5.17 / MPJ 1.5.9 面，T-M20-01 语料基座 |
| [架构设计/JPA-Hibernate官方API覆盖矩阵.md](./架构设计/JPA-Hibernate官方API覆盖矩阵.md)      | **JPA/Hibernate 官方 API 全量覆盖矩阵**（约 200 行挂 J1~J23 任务号，M39 交付后 83✅39🟡）：Spring Data JPA / jakarta / Hibernate / Criteria / HibernateTemplate 八分类面 |
| [架构设计/其他ORM官方API覆盖矩阵.md](./架构设计/其他ORM官方API覆盖矩阵.md)                | **候选 ORM 框架调研矩阵**（2026-09-05 四路调研，SD/JO/QD/EQ/JM/XB/DS/FM/DV 任务号预分配）：Spring Data JDBC/R2DBC、jOOQ、Querydsl、Easy-Query、Jimmer、xbatis、MyBatis Dynamic SQL、Fluent MyBatis、dbVisitor、BeetlSQL/sqltoy/lazy——含立项决策表（成本门槛 15% 判例） |
| [变更记录.md](./变更记录.md)                                                  | **全量变更记录总册**（M23.9 起唯一权威位置；历史迁移自清单 §8；单文件变更记录仍留各自文档） |
| [Marketplace发布流程-说明文档.md](./发行说明/Marketplace发布流程-说明文档.md) | **首次发布 JetBrains Marketplace 全流程**（官方文档查证 2026-08-16）：账号/Vendor 验证 → 双仓库（Gitee 主+GitHub 镜像）→ 构建/签名 → 手动上传表单逐字段 → 审核 4 步 → 日常更新联动（update.json 同版本）；§10.3 内测包用户 Installed 列表看不到市场新版，但 Marketplace 搜索页按 pluginId 匹配可点 Update 手动更新并完成迁移 |
| [文档命名规范-说明文档.md](./文档命名规范-说明文档.md)                        | 需求文档/开发计划/调研报告/**验收文档**/发行说明/SQL 文件/**图片设计稿**的命名与格式规范（2026-09-13 增 §验收文档：合并/里程碑交付的集中验收材料归 `docs/验收文档/`） |
| [验收文档/20260913-1225-dev与CrudColor合并master-新增功能验收文档.md](./验收文档/20260913-1225-dev与CrudColor合并master-新增功能验收文档.md) | **本次双分支合并的集中验收材料**（2026-09-13）：9 项新增功能入口速查表 + 逐项验收步骤（T-CrudColor 颜色标记/T-ErrReport 上报/T-ListMode 显示模式/gutter 左右键与最近执行反查/EXPLAIN/ORM 扩展/被动监听/性能观察）+ 明确「勿重复验收」历史功能清单 + 回归基线 |
| [记忆精简规范.md](./记忆精简规范.md)                                          | 本地记忆文件管理：分类标准（保留/归档/全局）、归档目录结构、操作流程、数量基线 |
| [MEMORY.md](./MEMORY.md)                                                      | 本文件：docs 目录检索索引 |

## 架构设计（docs/架构设计/，2026-08-16 新建）

> 项目逻辑/代码逻辑梳理文档（Mermaid 架构图 + 流程图），面向新上手开发者与 AI 会话速览。

| 文档 | 用途 |
|------|------|
| [01-项目架构总览.md](./架构设计/01-项目架构总览.md) | 产品定位、分层架构图、包依赖图、运行时注册图、代码地图（包→类→职责全量）、关键设计决策（SPI/共享编排/缓存失效/反射隔离） |
| [02-核心流程详解.md](./架构设计/02-核心流程详解.md) | 6 条链路流程图：正向预览（含 XML/注解子链路）、反向 SQL→Java、日志捕获、启动与自更新；每链路关键类方法表 |
| [03-开发与测试指南.md](./架构设计/03-开发与测试指南.md) | 构建命令、测试体系（76 类三类 fixture）、扩展指南（converter/方言/设置项/gutter）、文档三处同步、并行开发铁律 |
| [预览触发面清单-说明文档.md](./架构设计/预览触发面清单-说明文档.md) | 10 个 lineMarkerProvider 入口矩阵（触发位置/锚点/行为，含 hbm 命名查询）+ Wrapper 链 8 形态细分 + 负例边界 + 登记约定 |
| [M37M38竞品融合新增功能-说明文档.md](./架构设计/M37M38竞品融合新增功能-说明文档.md) | **本分支新增功能使用说明**：gutter 左右键交互、最近执行反查、EXPLAIN 按钮（方言矩阵）、~~生成测试资产~~（2026-09-07 下线，见 §2.4 标注）、日志窗自动展开的入口与用法；含 master 合并吸收能力与已知边界 |

## 性能分析（docs/性能分析/，2026-09-02 新建）

> 对插件自身运行时行为（定时器/监听器/线程模型/内存驻留）的深度分析，附风险分级与优化建议。

| 文档 | 用途 |
|------|------|
| [20260902-0054-IDEA性能影响-性能分析.md](./性能分析/20260902-0054-IDEA性能影响-性能分析.md) | **IDEA 性能影响深度分析**：结论=默认配置零常驻开销；唯一持续开销在日志捕捉链路（armed 态 100ms EDT 轮询 + 逐行 Filter 解析），P1~P3 风险分级 + 4 条优化建议 + 实测验证方法 |
| [20260910-0345-IDEA性能影响-性能分析.md](./性能分析/20260910-0345-IDEA性能影响-性能分析.md) | **IDEA 性能影响深度分析（复查）**：上一版 P1/P2 全部兑现复核；M54 红线治理 24 任务 master 落地全景（全量 3/半项 6/未落地 15）；新发现重放守卫逐行全文档 getText、CriteriaQuery gutter resolve、Regex 17→42 三处新热点；更正收藏 500 上限 dev-only；P1~P3 分级 + 优化建议 |

## 经验总结（docs/经验总结/）

> 跨任务可复用的工程实践沉淀（脚本管线、平台踩坑、录制/迁移等一次性但可复制的经验）。

| 文档 | 用途 |
|------|------|
| [20260914-0907-ZCode仓库Wiki同步Gitee-经验总结.md](./经验总结/20260914-0907-ZCode仓库Wiki同步Gitee-经验总结.md) | **ZCode repo-wiki → Gitee Wiki 同步经验**：wiki.json 结构解析 + 导出脚本（`dev-support/export_zcode_wiki_to_gitee.py`）+ Gitee wiki 机制实测（页面树按文件名排序/Home 落底坑/00- 前缀落地首屏/_Sidebar 自动生效/API 需登录）+ 刷新后两条命令再同步流程 |
| [20260902-1730-Kotlin转Java-经验总结.md](./经验总结/20260902-1730-Kotlin转Java-经验总结.md) | **Kotlin → Java 全量改写经验**：Wave 分批迁移 + 固定验证链 + 测试源集共存策略 + master 同步 + Git 历史管理 + Windows 内存映射锁/OOM 规避 |
| [20260910-0100-IntelliJ演示GIF录制-经验总结.md](./经验总结/20260910-0100-IntelliJ演示GIF录制-经验总结.md) | **IntelliJ 插件演示 GIF 录制经验**：脚本重放+帧稳定采样管线（分块最大均值差消稀释/mss 光标回绘/无害 mp4 反复转制）、坐标标定、分镜偏离教训（关键词补全不存在/编辑器 Action 未注册/Ctrl+Space 系 IME 热键）、262 沙盒编译三处必改（v69 需 JDK24+JCEF 插件+沙盒隔离）、环境还原清单。脚本在 `record/` |

## 需求文档（docs/需求文档/）

| 文档 | 用途 |
|------|------|
| [20260812-1058-MP-SQL可视化-需求文档.md](./需求文档/20260812-1058-MP-SQL可视化-需求文档.md) | **功能需求源**：功能编号 F-01~F-20 定义（Wrapper 转换/参数替换/格式化/展示/配置/批量等） |

## 开发计划（docs/开发计划/，时间倒序；调研性质报告已于 2026-09-12 拆分至独立目录 docs/调研报告/，见下方「调研报告」节）

> 开发计划文档 = **任务编号源**（T-Mxx-xx）+ 实现思路（定位到 文件:行号）+ 变更记录。

| 文档 | 里程碑/内容 | 当前状态 |
|------|-------------|----------|
| [20260914-1242-T-ExplainDbl-执行计划重复EXPLAIN前缀-开发计划.md](./开发计划/20260914-1242-T-ExplainDbl-执行计划重复EXPLAIN前缀-开发计划.md) | **T-ExplainDbl：重复点击「执行计划」EXPLAIN 前缀双加致 1064**。根因：explainSql 每次无条件 renderExplainStatement 加前缀，首次执行后包装语句已写回原生控制台文档（nativeEditorDoc），二次点击在已包装文本上二次加前缀。修复：Dialect 新增默认方法 `renderExplainStatementIdempotent`（已以 EXPLAIN 开头则原样返回，词边界防标识符误判），explainSql 改调幂等入口。+5 例测试，合入 master（18b3f03）后全量 **3980/0/0/6 绿**；分支与 worktree 用后即删 | ✅ 已实施并合入 master（2026-09-14；真机连点走查留人工） |
| [20260914-1043-T-JumpCapture-日志跳转仅依赖捕获位置缺失时引导开启拦截器-开发计划.md](./开发计划/20260914-1043-T-JumpCapture-日志跳转仅依赖捕获位置缺失时引导开启拦截器-开发计划.md) | **T-JumpCapture：日志跳转源码仅依赖拦截器捕获位置**。用户定性「按表名搜 Wrapper 定位」不可用（同表名≠同调用点）：LogJumpTarget 收敛 Precise/NoSource（MP COUNT）/NotFound/MissingSource，WrapperSearchService 整族删；新 `SourceCaptureGuide` 三按钮引导弹框（打开设置直达 SqlLogConfigurable / 立即注入 / 取消），实时+历史面板统一接线；设置页注入四类弹窗收编 i18n 化；i18n 删5增11。worktree 分支 3971/0/0/6，合入 master（609a90d）后全量 **3975/0/0/6 绿**；分支与 worktree 用后即删 | ✅ 已实施并合入 master（2026-09-14；真机走查 §六 留人工） |
| [20260914-1017-T-PreviewExecDs-预览补全缺失与执行跨项目数据源错乱-开发计划.md](./开发计划/20260914-1017-T-PreviewExecDs-预览补全缺失与执行跨项目数据源错乱-开发计划.md) | **T-PreviewExecDs：SQL 预览补全缺失 + 执行跨项目数据源错乱双缺陷**。问题一（预览无补全/无注释）=预热线程静默失败回退裸文档（无 PSI）+ 预览临时文件落系统 Temp（Database 插件不解析，M36.5.12 exec 同教训预览未跟进）+ 无方言映射。问题二（执行弹窗显示另一项目数据源 + `parent has already been disposed` 报错）同一根因链：`cachedTempPsiFile` 静态缓存只查 `vf.isValid` 不校验项目归属 → B 项目执行返回 A 项目 vf+psi → runner 按 A 项目 facade 枚举 → session 项目与 attachConsole 项目不一致 → `JdbcConsole.isValid()` 的 `facade.findDataSource` 为 false → 7ms 内被 `getActiveConsoles` 清扫 → `Disposer.register` 抛 IncorrectOperationException。修复 F1 缓存项目绑定 / F2 isValid 三分量预检+自愈 / F3 disposed 特判清缓存+可读文案 / F4 runRunner 传 project（错项目 bug）/ F5 弹窗标题带项目名 / P1-1 预览文件迁 `.idea/sql-visualizer/preview/` / P1-2 预热分级日志+重试 / P1-3 三级降级链（内存 PSI 保补全）/ P1-4 预览方言映射。全量裸命令 **3978/0/0/6 绿**（320 类，基线 3974+4）；verifyFull/buildPlugin 未跑（用户口径仅发版前/大功能）。并行：T-FeedbackDlg 占默认沙盒 → `-PparallelSandbox` 独立沙盒隔离（用完还原 build.gradle.kts） | ✅ 已实施（2026-09-14；真机双项目走查 §四清单留人工） |
| [20260914-0949-T-MergeFollowup-合并后真机反馈四缺陷-开发计划.md](./开发计划/20260914-0949-T-MergeFollowup-合并后真机反馈四缺陷-开发计划.md) | **T-MergeFollowup：合并后真机走查反馈四缺陷修复**。① EXPLAIN 按钮恢复（T-PreviewTopOverlap 重构删 dev add 行注释残留——合并语义丢失实锤）；② gutter 右键根治（243 平台 LineMarkerGutterIconRenderer 不覆写 getRightButtonClickAction、基类恒 null，GutterIconNavigationHandler 只接左键——匿名 LineMarkerInfo 覆写 createGutterRenderer 走平台正解 API）；③ 模式切换渲染错乱（repaint 不重算 JList 行高布局缓存，revalidate + applySqlSearch 全量重建）；④ 显示模式下拉原生化（ComboBoxAction 替换裸 JComboBox，presentation 单向同步）；verifyFull/buildPlugin 未跑（**用户口径：仅发版前或大功能执行**）。全量 3968/0/6 绿 | ✅ 已实施（2026-09-14，四提交 806b887~43911e1；真机复查 §五清单留人工） |
| [20260913-2330-测试守卫补全与全量提速-开发计划.md](./开发计划/20260913-2330-测试守卫补全与全量提速-开发计划.md) | **T-TestGuard / T-TestPerf：测试守卫补全与全量提速**。172 篇档案盘点后落地三族约定守卫：bundle 完整性（中英 parity/占位符/字面量 key）、plugin.xml 结构七条负向断言、禁用 API 源码扫描（新增 util/SafeBrowser 收口四处缺沙盒短路的 browse 调用）；零覆盖区补测 9 文件 + 渲染器冻结红线守卫 + exists 双语义回归 + settings 引用替换守卫；SqlAutoFoldPlannerTest 14 死测试复活并暴露 planner EOF 不收口真缺陷（已修）。提速：build cache 关闭（--no-build-cache 惯例作废）、configuration cache 复活（--no-configuration-cache 惯例作废）、testShard1/2 自动分区零维护（双开终端真并行）；纯逻辑并行分桶证据驱动否决（68 用例隐性依赖平台状态，结论沉淀 build.gradle.kts）。全量 **3968/0/0/6 绿 4m03s**（基线 3880/0/6、5m36s 快 28%） | ✅ 已实施（2026-09-14，分支 feature/t-testguard-perf） |
| [20260913-1134-T-MergeDevCrudcolorIntoMaster-dev与CrudColor分支合并master-开发计划.md](./开发计划/20260913-1134-T-MergeDevCrudcolorIntoMaster-dev与CrudColor分支合并master-开发计划.md) | **T-MergeDevCrudcolorIntoMaster：dev（119 提交）与 t-crudcolor（7 提交）两分支合并 master**。merge-tree 串联预演定合并顺序（先小后大）；13 文件冲突逐块定案：渲染器=组件池×色条×描边融合、ConvertToSqlAction=T-Perf-14 全后台结构+Row.of、T-AutoFold applyTo 移 applyConversion、市场页文案取 master 定稿；T-SelToggle 测试递归收集适配 CrudColor 图例行面板；503 假失败=build cache 陈旧 test 产物劫持（--no-build-cache 解）；全量 3880/0/6、verifyPlugin 三目标 Compatible、buildPlugin 出包 | ✅ 已实施（2026-09-13，merge a5d9307 + 8d2c919 + 7f20336 适配，runIde 真机走查留人工） |
| [20260912-1612-T-SelToggle-SQL预览全选全不选合并单按钮-开发计划.md](./开发计划/20260912-1612-T-SelToggle-SQL预览全选全不选合并单按钮-开发计划.md) | **T-SelToggle：全选/全不选双按钮合一为状态切换按钮**。预览面板动态条件/批量列两分区 + SqlLogPanel 类型过滤弹窗三处「全选」「全不选」并排按钮各合并为一个随勾选态切换文案的单按钮（全勾→「全不选」点击清空；空集/部分→「全选」点击补满），行为与旧两按钮等价。预览侧文案收口单点=renderSql 末尾 syncToggleSelectLabels()（判定复选框 UI 态）；弹窗侧无公共下游三处显式同步（判定 activeFilter.containsAll(FILTERABLE_TYPES)）+ 构建纯提取 buildTypeFilterContent() 供 headless 测试；i18n 零新键复用四既有键 | ✅ 已实施（2026-09-12，全量 3353/0/6 绿（基线 3346+7），runIde 走查留人工） |
| [20260912-1118-T-CrudColor-SQL列表CRUD类型颜色标记-开发计划.md](./开发计划/20260912-1118-T-CrudColor-SQL列表CRUD类型颜色标记-开发计划.md) | **T-CrudColor：SQL 列表 CRUD 类型颜色标记（颜色与形态均可自定义）**。SQL 日志页 + SQL 历史页二级列表按语句类型着色标记；选型经八维度对比定「左侧 3px 色条」为默认——否决整行背景色的**首要理由是选中态被平台填充整块覆盖**（`background = if (isSelected) selectionBackground else background`，键盘浏览时丢色），用户提议「背景色样式下选中态用一圈边框替代整块填充」后该硬冲突消解、背景色升级为等价可选样式，默认仍留色条（暗色下背景色压 SQL 高亮对比度）。新增 `SqlListTypeColor`（样式归一化 / 类型→色 / `#RRGGBB` 容错解析 / **显式 half-up 混色**——Kotlin `round` 是 ties-to-even 会致色值偏 1/255 / 暗色描边提亮 `ensureOutlineContrast` / Color 实例缓存）+ `SqlListSelectionOutline`（`AbstractBorder` 描边：W 夹紧 1..4、可变 color 字段改色不重建、`fillRect` 四条边带、内层 insets 四边 +W）；两渲染器加色条挂 WEST（左内边距 5 + 色条 3 = 原 8，文本左缘不位移）+ 两套 Border 预建引用切换（热路径零分配）+ `applyCrudColorMark` 单一收口两面板共用；`LogSqlEntry.statementType by lazy`（body 内不进 equals，消除 `classify` 每次编译 Regex）。**选中态三处定稿**：① 内层分隔线改透明占位（否则灰线画在描边环内把底边割断）② 两状态 insets 逐边相等防文本位移（单测按 W ∈ 1..4 参数化）③ 描边色过对比度保障提亮（暗色 `#2E436E` 压 `#5A3C22` 行底不可见）。§3.5 类型过滤弹窗色块图例（off 样式不显示）。设置 5 字段 + 样式下拉/四色 `ColorPanel`/恢复默认 + 中英 bundle 12 键（不新增复选框，保 25 台账）。§七 性能分析（实施后复核） | ✅ 已实施（2026-09-12，worktree `mpsv-crudcolor` 分支 `t-crudcolor`，提交 641c815 / dbc45bf / e57a34e / 1989546；新增 44 例：SqlListTypeColorTest 18 + SqlListSelectionOutlineTest 10 + LogStatementClassifierTest +3 + SqlLogPanelRendererUiTest +6 + HistoryCellRendererTest +5 + PluginSettingsPanelBindingTest +2；**clean 全量 3393/0/0/6 绿**）。§七 性能复核发现并修复「渲染热路径逐格 Swing 属性赋值触发 repaint」隐患（实测 `setBackground(新 Color·同值)` → `repaint()+1`，属 T-LogListFreeze 同类成因）+ 4 条实例同一性防回退用例；runIde 真机走查 9 项留人工 |
| [20260912-1003-T-CrudSql-实体类与数据库表右键生成CRUD-SQL-开发计划.md](./开发计划/20260912-1003-T-CrudSql-实体类与数据库表右键生成CRUD-SQL-开发计划.md) | **T-CrudSql：实体类与数据库表右键生成 CRUD SQL**。双入口：实体类右键（GenerateCrudSqlFromEntityAction，EditorPopupMenu+ProjectViewPopupMenu，BGT update 判定 MP 注解短名，ReadAction 内 EntityResolver 解析）+ Database 工具窗口表节点右键（GenerateCrudSqlFromDbTableAction，database-optional.xml 注册 add-to-group DatabaseViewPopupMenu——本地 ideaIU-2024.3.7.1 jar 实证 DatabaseTools 无视图 action EP、第三方走该组；PSI_ELEMENT_ARRAY 在 PlatformCoreDataKeys 非 CommonDataKeys）。展示层零新弹窗：现有 SqlSelectionDialog 选语句 → SqlPreviewService.showResult 打开既有预览窗口（格式化/执行/复制复用）；INSERT/UPDATE 复用 BatchModel 列勾选重建（renderSql 唯一渲染源，@Version 语义对齐 BatchOperationConverter.buildUpdateBatch）；SELECT/DELETE 纯文本生成；无主键降级 `WHERE 1 = 0 /* */` 且 UPDATE 不挂 batchModel；逻辑删除变体仅实体侧（@TableLogic）。核心层 CrudTableSpec/CrudSqlGenerator/DbTypeQuoteHint 纯 Kotlin 无平台依赖 | ✅ 已实施（2026-09-12，worktree mpsv-crudsql 分支 feature/t-crudsql，CrudSqlGeneratorTest 11 + DbTypeQuoteHintTest 3 + CrudSqlPreviewFlowTest 3 例，clean 全量 3339/0/6 绿，真机反馈修复后 3344/0/6 绿；runIde 真机走查留人工）。真机反馈修复（50dd0ae）：选择弹窗 OK/双击不进预览=onSelect 在模态 doOKAction 栈内同步 show modeless 被吞（回调包 invokeLater 出模态修复）+ 双击直开 + 候选样式分层（加粗摘要/灰色等宽）；再原生化（bcc7322）：JBList 主题色双行渲染器 + 速度搜索 + Row 模型迁移全部调用点（HTML 渲染弃用；BasicHTML.propertyKey 是 View 缓存键不可作 html.disable 开关）；再 JBPopup 浮层化（dd63466）：CRUD 候选选择弃模态对话框改 createPopupChooserBuilder 浮层（无按钮/打字过滤/Enter双击），SqlSelectionDialog 留给既有流程 |
| [20260911-1028-T-AutoFold-SQL预览字段列表自动折叠-开发计划.md](./开发计划/20260911-1028-T-AutoFold-SQL预览字段列表自动折叠-开发计划.md) | **T-AutoFold：SQL 预览字段列表自动折叠**。宽表 SQL 字段列表逐行展开定位 FROM/WHERE 困难（官网 FR-0017），新增「SQL 预览字段较多时自动折叠」配置项（默认关，关闭零行为变更）。程序化 FoldingModel 路线：`SqlAutoFoldPlanner` 纯逻辑规划器（SqlLexer 单遍状态机，SELECT 列/INSERT 列/VALUES 组/SET 四类顶层列表，元素数 ≥ 阈值且跨 ≥ 2 行才折，嵌套外层命中即互斥）+ `SqlAutoFolding.applyTo` 公共应用器唯一收口（本方区域清单挂 Editor UserData 只摘自己清单内的，绝不 getAllFoldRegions 全清越权碰平台 ConsoleFolding 区域；异常 runCatching 只 warn）。六点位接入=showSql 三分支 + 原生控制台（nativeConsoleEditor 引用登记/detach 清空防泄漏）+ 双击弹窗 + 日志转 SQL 结果区 + 输入区（仅 setSql 程序性写入）。反编译实证推翻计划一处：243 FoldRegion 无 setFolded，收起是 setExpanded(false)。设置 autoFoldEnabled/autoFoldFieldThreshold + 面板两控件联动 + 台账 24→25 + SettingsIO 两键 | ✅ 已实施（2026-09-12，SqlAutoFoldPlannerTest 12 例 + SqlAutoFoldingTest 5 例 + 点位行为 5 例，clean 全量 3322/0/6 绿，V1 runIde 走查留人工） |
| [20260911-1656-T-ReverseParenWira-括号组退化与attachConsole读权限-开发计划.md](./开发计划/20260911-1656-T-ReverseParenWira-括号组退化与attachConsole读权限-开发计划.md) | **T-ReverseParenWira：MPJ 括号条件反向退化 + 262 预览 attachConsole 读权限硬抛**。① MPJ 渲染 ON/WHERE 自带括号，`parseGroup` 把单条件括号包成单子条件 Group → ON 侧 `extractJoinKey` 失配 MPJLambdaWrapper 退化 MPJQueryWrapper 字符串形态 + WHERE 多渲染 `nested()`；修复=单条件非取反括号组解析器解包（`NOT (a=1)` 保留 negated 组），生成器零改动。② 262 真机 attachConsole 裸 EDT → DBN `ConsoleExecutionEditor.<init>` → `FileDocumentManager.getDocument` 读权限硬抛被吞（T-PreviewSlow F2 拆包兑现）；修复=attachConsole 包 WriteIntentReadAction（262 `runWriteIntentReadAction` 反编译实证仅持纯 Read 锁抛嵌套禁，WIRA 重入合法）+ getDocument 包 ReadAction | ✅ 已实施（2026-09-11，master dab3d3b，全量 3308/0/0/6 绿，262 真机 idea.log 复验留人工） |
| [20260910-1421-T-VPairAnno-竖向排列VALUES行尾列名标注配置项-开发计划.md](./开发计划/20260910-1421-T-VPairAnno-竖向排列VALUES行尾列名标注配置项-开发计划.md) | **T-VPairAnno：竖向排列 VALUES 行尾列名标注做成配置项**。用户需求——竖排下 INSERT 每值行尾的 `-- 列名` 配对标注默认开启、可自行关闭。实现：`SqlFormatter.formatVertical` 加 `annotatePairs` 默认参数（单点收口=关闭时 `collectInsertColumns` 收空名单使 renderGroup 标注追加与对齐补齐条件恒假，渲染主体零改动，默认参数使既有用例零改动）；`PluginSettings.verticalPairAnnotation` 默认 true（数据类默认值兜旧 xml 无字段升级）+ SettingsIO 导出/导入两行 + 设置面板 SQL 预览区 formatSql 行后复选框（isModified/apply/reset 三处接线，复选框总数台账 22→23）+ 双 bundle 新键与竖排按钮 tooltip 补「可在设置中关闭」；面板 `verticalPairAnnotationSetting()` 对齐 `formatSqlSetting()` 的 runCatching 兜底范式，applyDisplayMode VERTICAL 分支传入，不订阅 SETTINGS_CHANGE_TOPIC（下一次渲染生效，与 formatSql 同构）；边界=用户自带注释不受开关控制、关闭后重跑已带标注文本按用户注释归位保留 | ✅ 已实施（2026-09-10，master 770313a，SqlFormatterTest +4 / SqlPreviewPanelTest +1 / SettingsIOTest +3 断言，全量 3242/0/6 绿，runIde 留人工） |
| [20260910-0920-T-ExecSilentNoop-预览首次执行静默不执行与误报凭据提示-开发计划.md](./开发计划/20260910-0920-T-ExecSilentNoop-预览首次执行静默不执行与误报凭据提示-开发计划.md) | **T-ExecSilentNoop：预览首次执行 SQL 静默不执行 + 误报凭据提示**。沙盒日志实证两次执行差别（首次绑错数据源 mpj_demo + dumb 中 runners 退化为 4，全程无 `Connecting to:` 行=查询从未到连接阶段）；javap 实证 DBN 两条全静默出口（`chooseStatements` 空选集 `execOutside==1` 直接 return；`beforeExecuteQueries` 遇 `statements()` 空或 `isValid()==false` 返回 false），且执行 model 是光标派生的 `subModel(PositionRange)` 而非我们构建的 freshModel。方案：埋点分辨静默出口 + 提交后回执三分类 + 兜底走 `executeQueries(freshModel)` + 提示分流去重 + 数据源可见性 | ✅ 已实施（git grep 实证：DataSourceSelector 对 DBN 全静默出口的埋点回执与 freshModel 兜底已入库；runIde 真机复验留人工） |
| [20260904-2317-设置页缓存信息恒0B-开发计划.md](./开发计划/20260904-2317-设置页缓存信息恒0B-开发计划.md) | **T-CacheInfo：设置页缓存信息恒 0 B 修复**。用户反馈设置页「插件缓存」一直显示 0 B——根因=统计口径只含 EntityClassFinder 反查**兜底**缓存，而主流命名（XxxEntity↔xxx）快路径恒命中、该缓存恒空；真正积累的 EntityResolver 解析缓存（全局静态）与 WrapperCache 预览缓存（项目服务）均未计入。实现：`PluginCacheManager.totalSizeBytes/entryCount` 三类缓存全口径（解析缓存全局只计一次），`clearAll` 补清 Wrapper 缓存（getServiceIfCreated 不触发服务实例化），文案增条目数「当前缓存：{0}（{1} 项）」 | ✅ 已实施（2026-09-04，定向 6 测试类 37 用例全绿） |
| [20260904-1620-T-BareFromSelect-无别名HQL预览缺SELECT列清单修复-开发计划.md](./开发计划/20260904-1620-T-BareFromSelect-无别名HQL预览缺SELECT列清单修复-开发计划.md) | **T-BareFromSelect：无别名 HQL 预览缺 SELECT 列清单修复**。用户实测 `getHibernateTemplate().find("from WmsAsn")` 只显示 `FROM wms_asn`——根因=`HqlConverter.parseAliasColumns` 只认「表名+别名」两段式，裸表名不产生别名条目导致隐式查询补列清单分支永远短路；`JpqlConverter` 则完全没有展开。实现：抽取 `SelectClauseExpander` 共享展开器（裸表名回退登记表名限定符 + JOIN 族关键字排除防 `left` 被当别名 + 无 SELECT 分支列不可得降级 `alias.*`），两转换器统一接入 | ✅ 已实施（2026-09-04，converter/jpa + integration + gutter + SqlPreviewServiceJpa 回归绿） |
| [20260904-1326-T-GutterDedup-SQL预览图标重复与缺失修复-开发计划.md](./开发计划/20260904-1326-T-GutterDedup-SQL预览图标重复与缺失修复-开发计划.md) | **T-GutterDedup：SQL 预览图标重复与缺失修复**。根因=gutter provider 只按方法名判中、不校验接收者类型——`execute` 被 JdbcTemplate 与 CustomDaoHql 双收（`commonDao.execute` 2 图标、`getHibernateTemplate().execute` 3 图标），`HibernateTemplate.find/findByValueBean` 无 provider 覆盖（0 图标）。实现：`PreviewReceiverResolver` 按接收者归类（CUSTOM_DAO/HIBERNATE_TEMPLATE/JDBC_TEMPLATE/JPA_SESSION）；Jdbc gutter 加接收者∈JDBC+首参字符串门；CustomDaoHql 按接收者分派（拒 JDBC/JPA_SESSION、接 HIBERNATE 的 find/findByNamedParam/findByValueBean/iterate 方法名门） | ✅ 已实施（2026-09-04，DemoRecognitionTest 加载真实 demo 两文件逐处断言恰一图标 + 回归绿） |
| [20260904-1010-T-PreviewShortcut-非Wrapper预览快捷键贯通与命名查询-开发计划.md](./开发计划/20260904-1010-T-PreviewShortcut-非Wrapper预览快捷键贯通与命名查询-开发计划.md) | **T-PreviewShortcut：非 Wrapper 预览快捷键贯通 + `@NamedQuery` 实体级预览**。① `ConvertToSqlAction` 兜底链新增 `tryHqlFallback`（HQL/JPQL 运行时+自定义 DAO HQL）/`tryNamedQueryFallback`/`tryJdbcTemplateFallback` 短路，接 `previewHql`/`previewCustomDaoHql`/`JdbcTemplateSqlPreviewService.preview`；② `NamedQueryLocator` 补 `annotation` 字段+`findByQueryName`，`SqlPreviewService.previewNamedQuery` + `previewHql` 对 `createNamedQuery` 按名解析，新增 `NamedQueryGutterLineMarkerProvider`（前情：`feature/jdbc-template-sql-preview` 已合入 master 151d56e） | ✅ 已实施（2026-09-04 提交 210c802，定向/回归测试绿） |
| [20260904-0843-JOIN图直接内嵌Database原生显示图组件-开发计划.md](./开发计划/20260904-0843-JOIN图直接内嵌Database原生显示图组件-开发计划.md) | **JOIN 图直接内嵌 Database 原生「显示图」组件（T-Embed-01~08）**：A 侧已交付——数据源富化（DB 真实类型 int(11)/COMMENT）、原生 `/* 注释 */` 三段式字段行、PK 底部独立分区、JOIN 表字段空修复；B 侧调研实证（ShowDiagramBase.createSeed/provider id=DATABASE/虚拟外键=ModelRelationProvider EP）待带数据源真机验证轮实施 | 🔧 A 侧已交付（2026-09-04，全量回归绿），B 侧待真机轮 |
| [20260903-1545-JOIN图全原生UI对齐Database-开发计划.md](./开发计划/20260903-1545-JOIN图全原生UI对齐Database-开发计划.md) | **JOIN 图渲染全原生对齐 Database「显示图」（T-NativeUI-01~06）**：表头表注释（灰斜体）、字段行名+类型+注释（PK 加粗+图标）、连线按 JOIN 类型分箭头/线型/ON 标签、工具栏分类切换（列/主键列/注释）；弃 NO_CATEGORIES 改为直接实现 DiagramNodeContentManager 默认全启用（根因实证 Abstract myEnabledCategories 初空 + DiagramCategory 3 参布尔= enabledByDefault） | ✅ 已完成（2026-09-04 提交 8d1e370，真机验证字段正常；遗留差距转入 T-Embed） |
| [20260902-1400-HQL变量引用解析修复-开发计划.md](./开发计划/20260902-1400-HQL变量引用解析修复-开发计划.md) | **HQL 变量引用解析修复**：`HqlQueryLocator.extractHqlString()` 拼接初始化器（`String hql = "select" + " ..."`）回落返回变量名导致预览显示"hql"；新增 `PsiBinaryExpression`/`PsiPolyadicExpression` 分支复用 `collectStringLiterals()` 递归展开；方法参数等不可静态解析场景优雅降级 | ✅ 已完成（2026-09-02，HqlQueryLocatorTest 17/17，全量 HQL 套件 460+ 用例 0 失败） |
| [20260902-0106-日志捕捉性能优化-开发计划.md](./开发计划/20260902-0106-日志捕捉性能优化-开发计划.md) | **日志捕捉性能优化**（源自《性能分析 20260902-0054》P1/P2 建议）：快速轮询 500ms 起步指数退避 + 反射 Method 缓存 + descriptor 短路 + 逐行配置同步改 SETTINGS_CHANGE_TOPIC 事件驱动 | ✅ 已完成（2026-09-02，全量 2860 用例 0 失败） |
| [20260828-2000-插件更名SQL-Visualizer-开发计划.md](./开发计划/20260828-2000-插件更名SQL-Visualizer-开发计划.md) | **插件显示名更名 SQL Visualizer**：plugin.xml name + i18n bundle 32 处 + 生成代码注释/测试断言联动 + 用户文档；id/包名/rootProject.name/仓库路径不动（升级兼容） | 🔧 开发中（2026-08-28） |
| [20260822-0000-HQL-JPA-SQL预览调试-开发经验与踩坑记录.md](./开发计划/20260822-0000-HQL-JPA-SQL预览调试-开发经验与踩坑记录.md) | **HQL/JPA 预览全方位调试**：10+ Bug 修复（SQL 重复/参数丢失/unknown_table/双图标/IN 空子串等）；核心教训：单元测试 ≠ 集成测试，必须端到端验证；涉及文件 10 个，新增测试 46+ | ✅ 已完成（2026-08-22，2107+ 全绿） |
| [20260820-2020-更新机制Windows路径解析缺陷修复-开发计划.md](./开发计划/20260820-2030-更新机制Windows路径解析缺陷修复-开发计划.md) | **插件自更新静默失败修复**：2026.2 平台 `codeSource.location` 返回 null + 目录名误用 `<id>` FQN（实际安装目录=zip 顶层短名）+ 缺 DeleteCommand 三处叠加断点；改 `PathManager.getPluginsDir()/INSTALL_DIR_NAME` + 删整个插件目录 | ✅ 已修复（2026-08-20，全量全绿） |
| [20260820-1753-全量优化与新功能开发计划-总纲-10.md](./开发计划/20260820-1753-全量优化与新功能开发计划-总纲-10.md) | **11 项全量优化与新功能总纲**（P0 多租户拦截器模拟 / MPJ fill 关联填充 / SQL 性能分析建议 / 参数批量导入与历史召回 / 日志智能分组与高频折叠 / 批量 SQL→Java 转换 / 暗色主题适配 / 实体解析缓存增强 / Gutter 图标防抖 / SQL 日志面板布局修复 / VerifyPlugin 构建提速） | 立项（2026-08-20） |
| [20260820-1922-导入失败弹框与驼峰匹配-开发计划.md](./开发计划/20260820-1922-导入失败弹框与驼峰匹配-开发计划.md) | **导入失败弹框手动输入 + 灰色占位 + 驼峰/下划线自动匹配**（P-05.1）：按钮文案「导入」→「导入参数」；剪贴板导入失败时弹多行输入框对话框（灰色占位提示 JSON/CSV/key=value 格式）；导入时 key 与 source 匹配新增驼峰↔下划线互转（`userName` ≡ `user_name`） | 🔧 开发中（2026-08-20） |
| [20260820-1905-工具窗口面板内存泄漏修复-开发计划.md](./开发计划/20260820-1905-工具窗口面板内存泄漏修复-开发计划.md) | **工具窗口面板内存泄漏修复**：IDE 退出时 Disposer 报 4 个插件组件未被释放（LogToSqlPanel/SqlInputEditor/SqlToJavaPanel/SqlPreviewPanel 的 DocumentListener/MessageBusConnection 未注销） | ⬜ 待开发 |
| [20260820-1815-SQL日志前缀与SQL分行兼容-开发计划.md](./开发计划/20260820-1815-SQL日志前缀与SQL分行兼容-开发计划.md) | **SQL 日志前缀与 SQL 分行兼容**：MyBatisLogParser 兼容「前缀与 SQL 分行」的日志形态（`Preparing:` 独占一行、SQL 在下一行），正确配对还原 SQL | ⬜ 待开发 |
| [20260820-1803-移除历史召回弹框与参数清除按钮-开发计划.md](./开发计划/20260820-1803-移除历史召回弹框与参数清除按钮-开发计划.md) | **移除历史召回弹框 + 参数面板清除按钮**：去掉 `recallFromHistory()` 命中后的弹框提示；参数面板全选/全不选旁新增「清除」按钮（清空参数值 + 重渲染 SQL） | ⬜ 待开发 |
| [20260820-1617-SQL监听手动启动无需等待项目-开发计划.md](./开发计划/20260820-1617-SQL监听手动启动无需等待项目-开发计划.md) | **SQL 监听手动启动无需等待项目运行**（Option A armed 态）：点击捕捉立即进入 capturing 态（⏸），项目启动自动捕 SQL；manual/auto 区分（userArmed 持久标志）；onConsoleRemoved 守卫 `userArmed && !autoStart` 决定 armed/停止 | ✅ 已完成（c1632ab + 71e7294，18/18 全绿） |
| [20260820-1232-参数面板getter显示字段名-开发计划.md](./开发计划/20260820-1232-参数面板getter显示字段名-开发计划.md) | **参数面板 getter 方法显示字段名**：ExpressionEvaluator 增强——`#user.getId()` 渲染为 `#user.id`，减少面板参数表达式的冗余感 | ⬜ 待立项 |
| [20260820-1225-SQL转Java调用类校验与智能识别-开发计划.md](./开发计划/20260820-1225-SQL转Java调用类校验与智能识别-开发计划.md) | **SQL→Java 调用类校验 + 智能识别当前打开类**（P1）：VO 报错不友好修复 + 选择器自动识别当前编辑器打开类（减少手动选择） | ⬜ 待立项 |
| [20260820-1122-IService隐式this与链式调用修复-开发计划.md](./开发计划/20260820-1122-IService隐式this与链式调用修复-开发计划.md) | **IService/BaseMapper 内置方法——隐式 this 与链式调用修复**（P0）：① `this.getById()` 隐式 this 时 qualifierExpression 为 null → 实体解析失败；② `getBaseMapper().selectById()` 双重缺陷（selectById 未注册 + qualifier 是方法调用非变量引用） | ✅ 已实施（git grep 实证：BatchOperationConverter 隐式 this 与 getBaseMapper() 方法调用 qualifier 双回退已入库） |
| [20260820-1022-Hibernate-JPA全面支持-开发计划.md](./开发计划/20260820-1022-Hibernate-JPA全面支持-开发计划.md) | **Hibernate/JPA 全面支持**（4 阶段交付）：三种查询风格（Derived/CriteriaBuilder/@Query）；静态分析路线（不依赖运行时 Hibernate）；新增 23 个文件（4 定位器/解析器 + 4 转换器 + 1 中间模型 + 1 gutter provider + 8 测试文件） | ⬜ 计划中 |
| [20260820-1753-动态卸载问题修复-开发计划-8.md](./开发计划/20260820-1753-动态卸载问题修复-开发计划-8.md) | **动态卸载问题分析与修复**：P0 自定义语言 MP_SQL_Preview + FileType 平台注册不可动态移除 / P1 EntityResolver 静态 ConcurrentHashMap / P1 PluginCacheManager 未接入 dispose / P2 SqlPreviewService 未实现 Disposable；方案决策=移除自定义语言改内置 SQL 语言 | 部分实施（P1 静态缓存修复已完成） |
| [20260820-1800-VerifyPlugin构建提速-开发计划.md](./开发计划/20260820-1800-VerifyPlugin构建提速-开发计划.md) | **verifyPlugin 构建提速**（P1）：日常开发 `-PverifyIdes=smoke`（仅 243，约 1.5min）+ 发布前全量三版本（243/253/262，约 14min）双轨机制 | ✅ 已实施（脚本封装 + task 注册已完成） |
| [20260820-1753-SQL日志面板布局修复-开发计划-7.md](./开发计划/20260820-1753-SQL日志面板布局修复-开发计划-7.md) | **SQL 日志面板布局修复 + 列表多行预览 + 过滤条优化**：JBList 改 BorderLayout.CENTER 铺满；工具栏与过滤条间加分隔线；SQL 多行显示；筛选按钮高亮状态 | ✅ 已完成（全量测试通过） |
| [20260820-1753-Gutter图标防抖-开发计划-6.md](./开发计划/20260820-1753-Gutter图标防抖-开发计划-6.md) | **Gutter 图标响应防抖**（P1）：hover/click 事件加 200ms 防抖，避免连续悬停/点击重复触发 PSI resolve | ⬜ 待立项 |
| [20260820-1916-实体解析缓存增强-开发计划.md](./开发计划/20260820-1916-实体解析缓存增强-开发计划.md) | **实体解析缓存增强**（P1）：EntityResolver.resolve 结果按 PsiClass + PsiModificationTracker 缓存，避免重复 PSI 遍历（大项目几百实体类预览提速） | ⬜ 待立项 |
| [20260820-1753-暗色主题适配-开发计划-5.md](./开发计划/20260820-1753-暗色主题适配-开发计划-5.md) | **暗色主题适配**（P2）：检查并修复预览弹窗在暗色主题下文字对比度/分隔条发白等问题；所有文字颜色使用 IDE 命名色 | ⬜ 待立项 |
| [20260820-1753-批量SQL转Java转换-开发计划-4.md](./开发计划/20260820-1753-批量SQL转Java转换-开发计划-4.md) | **批量 SQL→Java 转换**（P1）：SqlToJavaPanel 和 ConvertSqlToJavaAction 支持多条 SQL 批量转换（自动拆分为多个 SqlReverseResult） | ⬜ 待立项 |
| [20260820-1831-日志智能分组与高频折叠-开发计划.md](./开发计划/20260820-1831-日志智能分组与高频折叠-开发计划.md) | **日志智能分组/高频折叠**（P1）：按语句类型过滤（SELECT/INSERT/UPDATE/DELETE 计数）、高频 SQL 折叠、按耗时排序 | ⬜ 待立项 |
| [20260820-1753-参数批量导入与历史召回-开发计划-3.md](./开发计划/20260820-1753-参数批量导入与历史召回-开发计划-3.md) | **参数批量导入/历史召回**（P1）：剪贴板批量导入（JSON/CSV/key=value 格式）+ 同一实体类查询复用上次填的参数 | ⬜ 待立项 |
| [20260820-1753-SQL性能分析建议-开发计划-2.md](./开发计划/20260820-1753-SQL性能分析建议-开发计划-2.md) | **SQL 性能分析建议**（P1）：纯文本静态分析 SELECT SQL，检测 SELECT */缺少 WHERE/子查询嵌套过深/ORDER BY 无索引 4 类性能反模式 | ⬜ 待立项 |
| [20260820-1920-MPJ-fill关联填充-开发计划.md](./开发计划/20260820-1920-MPJ-fill关联填充-开发计划.md) | **MPJ fill 关联填充**（MPJ 1.5.1+）：MPJLambdaWrapper `fill(field, tagField)` 两段式渲染（主查询 + 关联表 IN 附加查询 + 内存回填） | ⬜ 待立项 |
| [20260820-1753-XML关联展开文档同步-开发计划.md](./开发计划/20260820-1753-XML关联展开文档同步-开发计划.md) | **XML `${ew.customSqlSegment}` 关联展开文档同步**（P2，纯文档同步，实现已完成 M26-B）：矩阵两处 ⬜→✅ + 剩余立项关闭 | ✅ 实施中（文档同步） |
| [20260820-1752-多租户拦截器模拟-开发计划.md](./开发计划/20260820-1752-多租户拦截器模拟-开发计划.md) | **多租户拦截器模拟**（TenantLineInnerInterceptor，P0）：中央渲染层注入 tenant_id 条件/列；新增「租户模拟」配置组；INSERT 追加租户列；方法级 `@InterceptorIgnore(tenantLine=true)` 跳过 | ✅ 已实施（TenantLine 模拟配置组/渲染注入在库，市场页已列多租户与数据权限模拟） |
| [20260818-2105-监听启动时机与窗口关闭停止及日志顺序-开发计划.md](./开发计划/20260818-2105-监听启动时机与窗口关闭停止及日志顺序-开发计划.md) | **日志监听生命周期收敛**：①自动监听加主运行控制台守卫（打开项目不监听，启动运行才监听；startCapture 收敛点）②关闭侧边栏面板停止监听改监听 ToolWindow 可见性（contentRemoved 不触发；ToolWindowManagerListener ex 包 + isVisible 转换沿）③日志正序（SqlLogEntryBuffer addLast/removeFirst + 测试断言翻转）④下午 13 次迭代提交合并为单次（软重置重写未推送历史，保留 aec71f2 内容） | ✅ 已实施（2026-08-18，1224 全绿，待提交） |
| [20260818-2030-工具栏按钮状态实时刷新-开发计划.md](./开发计划/20260818-2030-工具栏按钮状态实时刷新-开发计划.md) | **工具栏「捕捉/停止」按钮状态实时刷新**：根因=刷新方法名错误（updateActionsAll/updateActions 不存在，日志实证 NoSuchMethodException）→ 改调接口公开方法 ActionToolbar.updateActionsImmediately()（243 javap 实证，三平台皆存，事件驱动无轮询）；capturingListeners + invokeLater 回调 | ✅ 已修复（2026-08-18，aec71f2，全量测试通过） |
| [20260816-2300-T-M45-复制纯代码与格式化-开发计划.md](./开发计划/20260816-2300-T-M45-复制纯代码与格式化-开发计划.md) | **T-M45 任务源**：SQL→Java ①复制只复制 Java 代码（JavaCommentStripper 纯文本剥离：整行注释行删/行尾注释保代码/字符串内斜杠不误伤/空行保留；弹窗+面板复制统一）②行尾说明注释改独立行注释 ③结果区「格式化代码」按钮（JavaCodeFormatter：代码包合成类方法体再 reformat；PSI 创建包 ReadAction；默认风格不折行，折行依赖用户风格） | ✅ 已交付（2026-08-16，5e1c8b2，1207 全绿） |
| [20260816-2200-T-M43-方言全兼容与反向分页-开发计划.md](./开发计划/20260816-2200-T-M43-方言全兼容与反向分页-开发计划.md) | **T-M43 任务源**：① UPSERT 按方言渲染——Dialect SPI 第 4 差异点 renderUpsert（MySQL/MariaDB ON DUPLICATE KEY UPDATE+VALUES()、PG/H2/SQLite/KingBase ON CONFLICT+EXCLUDED、Oracle/SQLServer/DM 降级纯 INSERT，BatchModel.renderSql 分支+modelBuild 注入零面板改动）；② 反向分页解析宽容——LIMIT n OFFSET m / OFFSET..FETCH / FETCH FIRST 三形态（含 ? 占位），consumeOptionalAlias 防护列表补 OFFSET/FETCH + isClauseStart/isClauseToken 列名防误判；③ DM 实证（DM8 支持 LIMIT M,N 为 4 种写法之一） | ✅ 已交付（2026-08-16，ab06bed，1175 全绿领地验证，并入 v2026.8.17） |
| [20260816-2140-调用方法按调用类真实API选择-T-M44-开发计划.md](./开发计划/20260816-2140-调用方法按调用类真实API选择-T-M44-开发计划.md) | **T-M44 任务源**：SQL→Java 调用方法按调用类真实 API 选择——invocationHasMpj 判断 MPJ 能力（getSuperClass() 对接口返回 null → getSuperTypes() 修正）；unmappedColumns 反引号误报顺带修复（normalizedColumnName） | ✅ 已交付（2026-08-16，1183 全绿，并入 v2026.8.17） |
| [20260815-2230-设置面板布局分组调整-T-M31.8-开发计划.md](./开发计划/20260815-2230-设置面板布局分组调整-T-M31.8-开发计划.md) | **T-M31.8 任务源**：设置面板布局分组——高频上/低频下/同类相聚 + TitledSeparator 分隔线（对齐 SQL 预览面板左侧分区）；5 组（SQL 预览/表名映射/代码生成/界面与操作/更新与诊断）；现有测试依赖已核实无风险 | ✅ 已实施（TitledSeparator 分组布局在库，分组数由 PluginSettingsPanelBuildInfoTest 锁定） |
| [20260815-1900-日志SQL还原与Wrapper实时提示及P2深化-开发计划.md](./开发计划/20260815-1900-日志SQL还原与Wrapper实时提示及P2深化-开发计划.md) | **T-M31~T-M35 任务源**：T-M31 日志还原已交付（938 全绿，含 T-M31.1~7 迭代：verifyPlugin 三态提速/侧边栏图标/原生 UI/捕捉默认关/去重默认关）；T-M32 实时提示 / T-M33 拦截器模拟 / T-M34 Kotlin / T-M35 Database 待实施 | T-M31/M32/M33/M34/M35 均已交付（实时提示/多租户与数据权限模拟/Kotlin Wrapper 链/原生 Database 控制台执行均在库实证） |
| [20260815-1734-兼容IDEA243-T-M30-开发计划.md](./开发计划/20260815-1734-兼容IDEA243-T-M30-开发计划.md) | **T-M30 任务源**：最低兼容从 2025.3 降至 2024.3——构建基准 2024.3.7.1 + since-build=243 + update 清单；API 适配 4 处（XML 模块名、PluginInstaller.uninstallAfterRestart 签名、Messages 嵌套 DoNotAskOption、DaemonCodeAnalyzer.restart）；Kotlin 方案 A（编译目标 stdlib 2.0.21） | 实施中 |
| [20260815-1555-更新安装失败与通知修复-T-M28.3-开发计划.md](./开发计划/20260815-1555-更新安装失败与通知修复-T-M28.3-开发计划.md) | **T-M28.3 任务源**：更新安装失败（2026.2 descriptorPath URL 风格致守卫误拒，路径归一化）+ 通知修复（插件名/内容只留标题/失败标题）+ 下载包权限加固（POSIX 644） | 已交付（2026-08-15，812 测试全绿） |
| [20260815-1416-检查更新无反应与构建号不显示修复-T-M28.1-开发计划.md](./开发计划/20260815-1416-检查更新无反应与构建号不显示修复-T-M28.1-开发计划.md) | **T-M28.1 任务源**：检查更新按钮无反应（UpdateSection 按钮缺 ActionListener + 测试盲区）+ 内部构建号不显示（codeSource 在 2026.2.1 不可靠，改 classloader 类文件定位）+ 跨平台加固（目录守卫 Path 组件级比较） | 已交付（2026-08-15，792 测试全绿） |
| [20260815-1700-预览SQL与预期SQL不一致修复-M29-开发计划.md](./开发计划/20260815-1700-预览SQL与预期SQL不一致修复-M29-开发计划.md) | **M29 任务源**（T-M29-xx）：预览与预期 SQL 不一致修复——and/or/nested/not/func 布尔条件组静默丢弃（parseGroup 首参布尔被当 NestedArg）+ page/selectPage 分页 LIMIT 缺失（消费识别 + Page 实参解析 + LimitClause SqlValue 化） | 已交付（2026-08-15，782 测试全绿） |
| [20260815-1219-设置页检查更新与自动安装更新-M28-开发计划.md](./开发计划/20260815-1219-设置页检查更新与自动安装更新-M28-开发计划.md) | **M28 任务源**（T-M28-xx）：设置页检查更新 + 自动安装更新（Gitee 清单 + Release 附件下载、下载进度、安装后重启提示）+ 启动自动检查开关（默认开，发现新版弹通知） | 已交付（2026-08-15，766 测试全绿） |
| [20260815-1630-XML与注解SQL代码补全-M27-开发计划.md](./开发计划/20260815-1630-XML与注解SQL代码补全-M27-开发计划.md) | **M27 任务源**（T-M27-xx）：XML/注解 SQL 代码补全（方案 B 自研贡献器，关键字/表/列/别名感知/`#{}` 参数/动态标签；联表支持；方案 C 平台注入立项 P-立项-06 后期做） | 已交付（2026-08-15，714 测试全绿） |
| [20260814-1648-Heavy语义黄金用例测试-开发计划.md](./开发计划/20260814-1648-Heavy语义黄金用例测试-开发计划.md) | T-M23.13：Heavy 语义黄金用例（lambda 推断实证 + 场景 B 守卫回归；mp-core classpath 挂载待调研，不参与发版） | 已交付（509 测试全绿） |
| [20260814-1629-gutter双预览图标与分步selectAll锚点-开发计划.md](./开发计划/20260814-1629-gutter双预览图标与分步selectAll锚点-开发计划.md) | T-M23.11/12：gutter 锚点误标（selectAll 撞名双图标 → 内联/分步守卫；lambda 参数推断类型三图标 → 场景 B 排除） | 已交付（507 测试全绿） |
| [20260814-1556-MPJ联表主表逻辑删除条件别名-开发计划.md](./开发计划/20260814-1556-MPJ联表主表逻辑删除条件别名-开发计划.md) | T-M23.10：MPJ 联表主表逻辑删除注入条件缺主表别名（`deleted = 0` → `t.deleted = 0`；顺带 DeleteJoin/UpdateJoin 乐观锁同病） | 已交付（503 测试全绿） |
| [20260814-1130-多SQL选择弹窗代码行预览与定位-开发计划.md](./开发计划/20260814-1130-多SQL选择弹窗代码行预览与定位-开发计划.md) | T-M23.8：多 SQL 选择弹窗代码行预览 + 定位跳转（IDEA 搜索风格） | 已交付（499 测试全绿） |
| [20260814-1115-任意位置触发与多SQL选择-开发计划.md](./开发计划/20260814-1115-任意位置触发与多SQL选择-开发计划.md) | T-M23.7：任意位置触发（eq/list 调用处）+ 选择代码块多 SQL 选择 | 已交付（497 测试全绿） |
| [20260814-1100-弹窗提示一键关闭SQL面板-开发计划.md](./开发计划/20260814-1100-弹窗提示一键关闭SQL面板-开发计划.md) | T-M23.6：复制/执行 SQL 成功提示加「一键关闭 SQL 面板」按钮 | 已交付（492 测试全绿） |
| [20260814-1015-SQL关键字标识符引用-开发计划.md](./开发计划/20260814-1015-SQL关键字标识符引用-开发计划.md) | T-M23.4/5：SQL 保留字按方言引用（`table` → `` `table` ``，五方言保留字集合，列名/表名/批量模板/查询路径） | 已交付（T-M23.4 486 绿 + T-M23.5 490 绿） |
| [20260814-0945-字段顺序角色分组-开发计划.md](./开发计划/20260814-0945-字段顺序角色分组-开发计划.md) | T-M23.3：字段顺序角色分组（主键置顶/业务居中/fill 与逻辑删除沉底，组内声明顺序） | 已交付（472 测试全绿） |
| [20260814-0920-跟随系统语言不生效-开发计划.md](./开发计划/20260814-0920-跟随系统语言不生效-开发计划.md) | M23.1：设置「跟随系统」语言不生效根因分析与修复（任务源 T-M23.1-xx） | 立项（2026-08-14） |
| [20260814-0917-批量参数列可选勾选-开发计划.md](./开发计划/20260814-0917-批量参数列可选勾选-开发计划.md) | T-M23-04：批量参数面板列级勾选（INSERT id 默认不勾选 / UPDATE SET 可取消） | 已交付（465 测试全绿） |
| [20260814-0850-用户实测批量操作逻辑删除与参数修复-开发计划.md](./开发计划/20260814-0850-用户实测批量操作逻辑删除与参数修复-开发计划.md) | M23：批量 SQL 逻辑删除缺失 / save·removeById 参数无入口 / LocalDateTime 参数回填类型 | 已交付（452 测试全绿） |
| [20260814-0804-剩余立项-开发计划.md](./开发计划/20260814-0804-剩余立项-开发计划.md) | **剩余 ⬜ 按需立项清单**：P-立项-01~05（fill / 拦截器模拟 / XML customSqlSegment / Kotlin 侧 / 方言实测）+ 关闭项说明 | 待立项 |
| [20260813-1730-全量API预览支持-开发计划.md](./开发计划/20260813-1730-全量API预览支持-开发计划.md) | **M20~M22 任务源**（T-M20-xx/T-M21-xx/T-M22-xx）：全量 API 预览支持 | 已交付（M20/M21/M22-P2） |
| [20260813-1536-用户实测两类未识别形态修复-开发计划.md](./开发计划/20260813-1536-用户实测两类未识别形态修复-开发计划.md) | M19.5：服务链赋值形态锚点 + 实体单条 CRUD（save/getById 等） | 已交付 |
| [20260813-1338-参数面板布局与表名配置增强-开发计划.md](./开发计划/20260813-1338-参数面板布局与表名配置增强-开发计划.md) | M19.4：参数面板布局 + 表名推断策略/忽略字段/前缀后缀/@TableName 最高优先级 | 已交付 |
| [20260813-1257-语言切换写穿与执行SQL去弹框-开发计划.md](./开发计划/20260813-1257-语言切换写穿与执行SQL去弹框-开发计划.md) | M19：语言切换写穿持久化 + 执行 SQL 去弹框 | 已交付 |
| [20260813-1048-插件介绍页作者二维码-开发计划.md](./开发计划/20260813-1048-插件介绍页作者二维码-开发计划.md) | 插件介绍页作者二维码 | 已交付 |
| [20260813-1000-执行SQL按钮-开发计划.md](./开发计划/20260813-1000-执行SQL按钮-开发计划.md) | 执行 SQL 按钮（Database 控制台联动） | 已交付 |
| [20260813-0930-用户实测三处缺陷修复-开发计划.md](./开发计划/20260813-0930-用户实测三处缺陷修复-开发计划.md) | 用户实测三处缺陷修复 | 已交付 |
| [20260813-0900-SQL补全修复与表前缀配置-开发计划.md](./开发计划/20260813-0900-SQL补全修复与表前缀配置-开发计划.md) | SQL 补全修复 + 表前缀配置 | 已交付 |
| [20260813-0900-中英文切换失效修复-开发计划.md](./开发计划/20260813-0900-中英文切换失效修复-开发计划.md) | 中英文切换失效修复 | 已交付 |
| [20260813-0829-低频复杂API后期实现计划.md](./开发计划/20260813-0829-低频复杂API后期实现计划.md) | 低频复杂 API 后期实现计划（P1/P2/P3 分组背景；P2/P3-01 已并入全量 API 计划） | 已交付/并入 |
| [20260813-0812-SQL面板字段补全与注释提示-开发计划.md](./开发计划/20260813-0812-SQL面板字段补全与注释提示-开发计划.md) | M16：SQL 面板字段补全与注释提示 | 已交付 |
| [20260813-0216-真实语料兼容补全-开发计划.md](./开发计划/20260813-0216-真实语料兼容补全-开发计划.md) | M15：真实语料兼容补全（官方 API 增补系列） | 已交付 |
| [20260813-0122-配置可用性核验与补强-开发计划.md](./开发计划/20260813-0122-配置可用性核验与补强-开发计划.md) | 配置可用性核验与补强 | 已交付 |
| [20260813-0000-设置页修复与弹窗自适应-开发计划.md](./开发计划/20260813-0000-设置页修复与弹窗自适应-开发计划.md) | M14：设置页修复与弹窗自适应 | 已交付 |
| [20260812-2210-动态条件简化与参数值类型推断-开发计划.md](./开发计划/20260812-2210-动态条件简化与参数值类型推断-开发计划.md) | M13：动态条件简化 + 参数值类型推断 | 已交付 |
| [20260812-2107-动态条件复选框与空值跳过-开发计划.md](./开发计划/20260812-2107-动态条件复选框与空值跳过-开发计划.md) | M12：动态条件复选框 + 空值跳过 | 已交付 |
| [20260812-2034-移除侧边工具栏面板-开发计划.md](./开发计划/20260812-2034-移除侧边工具栏面板-开发计划.md) | M11：移除侧边工具栏面板（收敛为弹窗三入口） | 已交付 |
| [20260812-1844-代码左侧预览SQL图标-开发计划.md](./开发计划/20260812-1844-代码左侧预览SQL图标-开发计划.md) | M10：代码左侧预览 SQL 图标（gutter 入口 + 性能红线） | 已交付 |
| [20260812-1058-MP-SQL可视化-开发计划.md](./开发计划/20260812-1058-MP-SQL可视化-开发计划.md) | **总开发计划**（M0~M19，T-Mx 任务编号源）：核心引擎/展示交互/配置/发布路线图 | 已交付 |

## 调研报告（docs/调研报告/，2026-09-12 新建）

> 竞品分析、可行性分析、技术验证等调研性质文档，自 开发计划/ 拆分独立管理；命名规范与范围界定见 [文档命名规范-说明文档.md](./文档命名规范-说明文档.md)。

| 文档 | 用途 |
|------|------|
| [20260911-1700-DebugTools-agent字节码增强-调研报告.md](./调研报告/20260911-1700-DebugTools-agent字节码增强-调研报告.md) | 首个真落地 attach+agentmain 竞品源码分析；agentmain 只起 server 不注册 SQL transformer 是反面教材（M56 须 addTransformer+retransform）；动态代理包装 Driver.connect + 9 类驱动分库适配等四手法可照搬（M56 输入） |
| [20260911-1617-CoolRequest-agent字节码增强-调研报告.md](./调研报告/20260911-1617-CoolRequest-agent字节码增强-调研报告.md) | 竞品实为 premain 非 attach（agentmain 声明缺失是坑）；SQL 用「驱动自拼 SQL」绕 setXxx 还原；ByteBuddy RETRANSFORMATION 实证已加载驱动可插桩；relocate/多 JDK 三件套/端口自报/异步上报四工程化手法（M56 输入） |
| [20260902-1700-SqlSourceInterceptor自动注入可行性分析.md](./调研报告/20260902-1700-SqlSourceInterceptor自动注入可行性分析.md) | SqlSourceInterceptor 设置开关化（免手动配置）可行性分析，方案 A 确认可行（后续 1710 开发计划实施） |
| [20260830-0002-竞品横向对比与功能建议-调研报告.md](./调研报告/20260830-0002-竞品横向对比与功能建议-调研报告.md) | 子智能体并行调研（功能梳理 + Marketplace 实时数据抓取）横向对比同类产品，输出新一轮功能与优化建议——P-立项-06 竞品精华融合的触发源 |
| [20260828-2000-P-立项-05-方言输出真实验证-报告.md](./调研报告/20260828-2000-P-立项-05-方言输出真实验证-报告.md) | 方言输出真机验证报告（DBX MCP 通道七数据库家族零缺陷 + 测试环境清单与工作流陷阱） |
| [20260822-1800-官网设计借鉴与实现参考.md](./调研报告/20260822-1800-官网设计借鉴与实现参考.md) | 官网开发过程中借鉴的各大官网设计模式与实现方案记录 |
| [20260822-1500-竞品分析与优化建议.md](./调研报告/20260822-1500-竞品分析与优化建议.md) | 插件定位、已有功能、竞品格局全面分析 + 优化建议与新功能方向，M30+ 立项依据 |

## 经验总结（docs/经验总结/，2026-09-12 新建）

| 文档 | 用途 |
|------|------|
| 20260914-0907-ZCode仓库Wiki同步Gitee-经验总结.md | repo-wiki 本地 wiki.json 结构/导出脚本 dev-support/、Gitee wiki 落地页与排序机制实测/中文链接尖括号/refresh 后再同步流程（60 页已上线 `0b9d0dc`+`0490a98`） |
| 20260912-0855-插件免重启更新与跨版本签名核验-经验总结.md | 动态热替换平台机制/五入口反射范式/跨版本签名护栏/踩坑清单（vetoer 误判、签名漂移、配置缓存、Kotlin DSL 坑等）/本地热替换测试三步 |
| 20260902-1730-Kotlin转Java-经验总结.md | Kotlin→Java 全量改写的 Wave 策略、语法映射、验证链与 master 同步经验 |

## 发行说明（docs/发行说明/）

| 文档 | 用途 |
|------|------|
| [20260913-1239-合并回合与CRUD颜色标记-发行说明.md](./发行说明/20260913-1239-合并回合与CRUD颜色标记-发行说明.md) | v2026.9.14-beta.2 内测版发行说明（Gitee 抢先体验通道；dev 119 提交 + t-crudcolor 7 提交整体回合后的首次发行——T-CrudColor 颜色标记 + M37 双向深链（gutter 左右键/最近执行反查/EXPLAIN 含达梦金仓）+ T-ErrReport 上报 + T-ListMode 三显示模式 + M39/M44 ORM 扩展 + T-Perf 性能系列；changeNotes/preview 清单按 beta 规则只主标题累计块；全量 3880/0/6 绿） |
| [20260912-1700-右键CRUD生成与预览自动折叠-发行说明.md](./发行说明/20260912-1700-右键CRUD生成与预览自动折叠-发行说明.md) | v2026.9.14-beta.1 内测版发行说明（Gitee 抢先体验通道；右键生成 CRUD SQL T-CrudSql（实体类/数据库表双入口+JBPopup 浮层候选+BatchModel 复用）+ 预览字段自动折叠 T-AutoFold（FoldingModel 六点位）+ 全选全不选按钮合一 T-SelToggle + T-PickMpjErr/T-ReverseParenWira/HelpAction 三项修复；beta 系列后续增量届时并入正式版 2026.9.14 统一日志，对外清单只留最新 beta；全量 3353/0/6 绿） |
| [20260911-1552-自更新免重启与历史参数恢复-发行说明.md](./发行说明/20260911-1552-自更新免重启与历史参数恢复-发行说明.md) | v2026.9.11 发行说明（自更新免重启动态安装 T-DynUpdate（五入口热替换+脚本兜底+262 首参漂移弹性修复+跨版本签名护栏+卸载警告归因）+ SQL 历史页参数识别根治与字面量参数恢复 T-HistParam（历史文件 v2 pd 行+双面板识别通道统一+谓词语境数值识别）；全量 3305/0/0/6 绿） |
| [20260910-2240-预览加载与Hibernate捕获打磨-发行说明.md](./发行说明/20260910-2240-预览加载与Hibernate捕获打磨-发行说明.md) | v2026.9.10 发行说明（双击预览加载卡顿根治 T-PreviewSlow + 预览 EDT 读权限断言全库排雷 T-PreviewDocLock/2 + Hibernate 双份日志参数分裂根治 T-HibDualCapture + 竖向排列列名标注配置项 T-VPairAnno + IN 阈值 T-VStack·InThreshold + 顶部元信息压按钮行 T-PreviewTopOverlap + 执行静默不执行 T-ExecSilentNoop + 显示模式持久化 T-PreviewDisplayMode + 版本比较改内置比较器 T-VersionUtil + 行号两处续修 T-LogRowNumber；全量 3254/0/0/6 绿） |
| [20260909-1235-单击预览打磨与自动监听开关转正-发行说明.md](./发行说明/20260909-1235-单击预览打磨与自动监听开关转正-发行说明.md) | v2026.9.9 发行说明（单击预览四轮同色同源高亮/五轮复制对号反馈 T-ClickPreview-04/05 + 双击预览无数据源不抢占 T-DsCover + 262 verifyPlugin 字节码级兼容修复 T-Wia262 + 发现 SQL 自动开窗假开关复活 T-AutoShowMaster + dev 缺陷批量移植与假开关处置 T-PortLossBatch1 + 关窗停止监听开关转正 T-AutoShowRecover + 发行当日追补：行号 T-LogRowNumber/僵尸监听 T-RefreshDbSteal/执行失败可见化 T-ExecCopyFix/闪烁根治 T-PreviewFlash/弃用 API T-DepApi；发版前 verifyPlugin 三版本校验通过） |
| [20260908-1430-SQL日志链路大修与单击可编辑预览-发行说明.md](./发行说明/20260908-1430-SQL日志链路大修与单击可编辑预览-发行说明.md) | v2026.9.8 发行说明（日志列表 EDT 冻结 16 秒根治 T-LogListFreeze-04 + 单击预览三轮 T-ClickPreview（SQL 高亮可编辑编辑器）+ 多行 JdbcTemplate 捕获/去重与 MP 计数标记/双击预览硬抛修复 + 自研 DAO 字符串 SQL 预览 T-DaoSql + 设置页缓存口径 + 「自动开始监听」联动修复 + MBCHP-Pro 反编译对照） |
| [20260904-1545-JdbcTemplate预览贯通与JOIN原生图对齐-发行说明.md](./发行说明/20260904-1545-JdbcTemplate预览贯通与JOIN原生图对齐-发行说明.md) | v2026.9.4 发行说明（JdbcTemplate SQL 字符串预览 psource 系列 + JOIN 图原生框架/对齐 Database 显示图/内嵌原生图 B 路径 + 非 Wrapper 快捷键贯通 + @NamedQuery 实体级预览 + 自动保存无限递归/日志 EDT 冻结/SqlFormatter 修复链） |
| [20260902-0000-实时日志跳转可靠方案与全插件测试覆盖-发行说明.md](./发行说明/20260902-0000-实时日志跳转可靠方案与全插件测试覆盖-发行说明.md) | v2026.9.2 发行说明（SQL 预览面板原生 Database 控制台内嵌 M36 + 全插件 UI/集成测试覆盖 + SQL 日志合并主侧边栏 + 自动保存历史 + JOIN 自绘引擎 + 更名 SQL Visualizer） |
| [20260819-1400-废弃API清理与IService实体修复-发行说明.md](./发行说明/20260819-1400-废弃API清理与IService实体修复-发行说明.md) | v2026.8.19 发行说明（废弃 API 全量清理 T-M51+T-M52 + IService 实体修复 + 截图替换 + Marketplace Custom Page，1227 全绿） |
| [20260818-2125-SQL反向转换收尾修复与日志监听大修-发行说明.md](./发行说明/20260818-2125-SQL反向转换收尾修复与日志监听大修-发行说明.md) | v2026.8.18 发行说明（SQL→Java 反向收尾 T-M48~M50 + 日志监听生命周期大修 + UI/文档升级，链接用绝对路径） |
| [20260816-2320-SQL-Java反向转换增强与方言全兼容-发行说明.md](./发行说明/20260816-2320-SQL-Java反向转换增强与方言全兼容-发行说明.md) | v2026.8.17 发行说明（SQL→Java 反向链路大修 + 方言 UPSERT + 日志五项修复 + 图标统一，链接用绝对路径） |
| [20260816-1431-SQL日志工具窗口与SQL-Java双向转换-发行说明.md](./发行说明/20260816-1431-SQL日志工具窗口与SQL-Java双向转换-发行说明.md) | v2026.8.16 发行说明（SQL 日志工具窗口 + SQL→Java 双向转换 + 兼容 2024.3，链接用绝对路径） |
| [20260815-1535-XML注解动态SQL预览与更新链路-发行说明.md](./发行说明/20260815-1535-XML注解动态SQL预览与更新链路-发行说明.md) | v2026.8.15 发行说明（XML/注解动态 SQL 预览 + 代码补全 + 检查更新与自动安装，链接用绝对路径） |
| [20260814-1538-官方API全覆盖与批量增强-发行说明.md](./发行说明/20260814-1538-官方API全覆盖与批量增强-发行说明.md) | v2026.8.14 发行说明（官方 API 全覆盖 + 批量增强，链接用绝对路径） |
| [20260813-1511-首发-发行说明.md](./发行说明/20260813-1511-首发-发行说明.md) | 首发版本发行说明（链接用绝对路径） |
| [版本号规范-说明文档.md](./发行说明/版本号规范-说明文档.md) | 版本号规范（YYYY.M.D 一日一版） |
| [Gitee自动发行工作流-说明文档.md](./发行说明/Gitee自动发行工作流-说明文档.md) | **门禁→八处同步→发行提交→buildPlugin→标签→Gitee API 发行→回读校验全链路**（沉淀自 v2026.9.14-beta.1，2026-09-12）：建发行版描述参数是 `body` 非 `description`；附件上传端点 `POST /releases/{id}/attach_files`（multipart `file`+`access_token`，`upload_files`/GitHub 式 `assets` 均 404 勿再试）；`prerelease='true'` 即预览版本；附件名=下载 URL 尾段须与清单 downloadUrl 逐字一致；含 Python 无依赖参考实现 |

## 图片（docs/images/）

- [group-qr.png](./images/group-qr.png) / [wechat-qr.png](./images/wechat-qr.png) — 插件介绍页群二维码/微信二维码

## 归档记忆（docs/memory-archive/，2026-08-22）

> 从本地记忆系统归档的已完成/已交付内容（180 个文件，2026-09-14 第八次精简后），不再每次会话加载，按需查阅。
> 全局通用记忆（85 个）备份在 `~/.zcode/cli/memories/global/`。

| 文档 | 用途 |
|------|------|
| [README.md](./memory-archive/README.md) | 归档索引（已修复 Bug 123 个 + 已解决踩坑 54 个 + 方法论 1 个 + 用户环境 1 个） |
| [bug-fixes/](./memory-archive/bug-fixes/) | 已修复 Bug：exists/iscrud/reverse/jpa/sql-capture/p051 等 |
| [pitfalls/](./memory-archive/pitfalls/) | 已解决踩坑：IN 子句/HQL/JPA/gutter/selectCollection/startcapture 等 |
| [user-context/](./memory-archive/user-context/) | 用户环境：Grok API 中转 |

---

## 检索指引（按需求找文档）

| 想查什么 | 去哪查 |
|----------|--------|
| API 支持状态 / 官方全量覆盖 / 目标状态 | `架构设计/MP-MPJ官方API覆盖矩阵.md` |
| 真实项目语法形态 / 模式编号 P/Q/U/X / 测试语料 | `需求文档/MPJ用法收集与兼容性测试清单.md` |
| 功能需求定义（F-01~F-20） | `需求文档/20260812-1058-...-需求文档.md` |
| 任务编号 T-xx / 里程碑排期 / 实现思路 | 开发计划：总计划（M0~M19）/ 全量 API（M20~M22）/ 剩余立项（P-立项）/ M23 系列 |
| 版本记录 / 版本号规范 | `发行说明/` |
| 文档命名 / 格式规范 | `文档命名规范-说明文档.md` |
| 产品介绍 / 使用说明 | `README.md`（产品定位、功能特性、截图、已知差异） |
| 多智能体调度经验 / 踩坑指南 / 代码扩展点（本地私有） | `~/.zcode/cli/memories/projects/mybatis-plus-sql-visualizer-*/memory/`（重点：并行测试协调登记表脚本 `C:\Users\USER\.zcode\cli\parallel_tasks.py`，用法见文件头 🔴 提示块） |
| 已修复 Bug / 已解决踩坑（归档） | `docs/memory-archive/README.md` |
| 全局通用记忆（跨项目复用） | `~/.zcode/cli/memories/global/`（85 个文件，手动查阅） |
| 记忆精简规范 | `docs/记忆精简规范.md`（分类标准、归档流程、数量基线） |

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-14 | T-FeedbackDlg 报告对话框真机反馈三缺陷（宽度 1272px/无灰提示/不可拖拽）：根因=`JTextField(TITLE_MAX=200)` 上限误作列数（列数即首选宽语义）——JBTextField(30)+滚动面板 preferredSize 钳宽；标题/描述 getEmptyText 占位对齐官网 + doValidate 客户端校验闭环（手动形态短输入此前直吃服务端 400，Composer 常量升 internal 单源）；setResizable/DimensionService/weighty 拖拽伸缩；buildCenterPanel/validateForm 抽纯函数零 peer 可测，新测试 6 例。全量 3974/0/0/6 绿（worktree mpsv-feedbackdlg 隔离跑：并行会话未登记占默认沙盒，「用户映射区域」互锁复现台账坑，M28×M29 路线绕行）。verifyFull/buildPlugin 仅发版/大功能跑（用户口径，已沉淀记忆 feedback-verify-scope）。详见 docs/变更记录.md 同日行 |
| 2026-09-14 | 记忆第八次精简（31→21）：新增 8 个归档（bug-fixes：T-RollbackSql 预览回滚 SQL、T-CrudColor 颜色标记、dev×CrudColor 双合并交付、T-M56-00/T-M56-01 两期交付、dev→master 丢功能审计历史台账；pitfalls：runIde/hotswap 职责分离定案、K2J 陈旧产物劫持六犯实录——标准前置清 build 精华收编 pitfall-regression-false-failure-flakes）；9 个升级 global（WIRA 锁嵌套、WorkBuddy 沙盒吞嵌套 ref、能力验证对照实验、win32 中文乱码/tsx 验证、GLM/阿里云/dots-nova 线路、ZCode 模型配置位置——后 7 个来自文档站与管理后台仓库，两仓库同日各自完成首次记忆精简：文档站 28→23、管理后台 17→10）；精简前全量备份 `~/.zcode/cli/memories/projects/.../memory-backup-2026-09-14/`（三个项目同惯例）；本项目 memory 降至 21 个文件（含 MEMORY.md），archive 增至 180 个文件，global 增至 85 个文件 |
| 2026-09-14 | T-TestGuard / T-TestPerf 测试守卫补全与全量提速（分支 feature/t-testguard-perf）：三族约定守卫（bundle parity / plugin.xml 结构 / 禁用 API 扫描）+ 零覆盖补测 9 文件 + 渲染器冻结守卫 + exists/settings 守卫 + 14 死测试复活；守卫当场抓获 en/zh bundle 幽灵键×2、拦截器模板 `${'$'}{'$'}Proxy` 转义死代码、planner EOF 不收口、4 处 browse 缺沙盒短路（新增 SafeBrowser 收口）。提速：build cache 关闭、configuration cache 复活（两大防坑开关惯例作废）、分片自动分区双开终端真并行；纯逻辑并行分桶否决（68 例隐性平台依赖实证）。全量 `./gradlew test` 裸命令 3968/0/0/6 绿 4m03s（快 28%）。详见 docs/变更记录.md 同日行 |
| 2026-09-13 | dev 与 t-crudcolor 两分支合并 master（T-MergeDevCrudcolorIntoMaster，merge a5d9307 + 8d2c919）：先合 t-crudcolor（7 提交，仅 3 docs 冲突并集）再合 dev（119 提交，13 文件冲突按 merge-tree 串联预演逐块定案）。核心融合：两渲染器=dev 组件池骨架（T-ListMode 行数伸缩）× T-CrudColor 色条 WEST+动态描边（normalBorder 已含 matte 分隔线）；ConvertToSqlAction 取 dev T-Perf-14 全后台结构、调用换 master `Row.of`（displayText 已删）；T-AutoFold applyTo 移入 applyConversion（dev 把 computeConversion 重构为后台纯计算）；市场页文案取 master 定稿。适配修复：T-SelToggle 弹窗测试复选框改递归收集（CrudColor 图例行面板）。**踩坑：clean 全量首跑 503 假失败**=build cache 命中陈旧 compileTestKotlin 产物（ColumnInfo 8 参旧合成构造器 vs dev M39/T-J18 的 13 参新形态，NoSuchMethodError），删 build + `--no-build-cache` 即绿——K2J 陈旧类坑的 build-cache 变体。最终 clean 全量 3880/0/6 绿（303 类）；verifyPlugin 三目标 Compatible；buildPlugin 出 2026.9.14-beta.1.zip。特征符号终审双侧在位；备份锚点 master-backup-pre-dev-merge-20260913。详见 docs/变更记录.md 同日行 |
| 2026-09-13 | 记忆第七次精简（43→29）：新增 12 个归档（bug-fixes：T-DynUpdate 免重启热替换、T-PickMpjErr 与 T-SQL2Java 同笔双档、dev→r2 与 master→dev 两轮合并交付、T-AutoFold、T-CrudSql、产品标语定稿、JOIN 图原生框架历程、CoolRequest/DebugTools agent 调研、架构文档 dev 同步）；1 个通用方法论升级 global（pitfall-subagent-claims-verify 子代理断言先证伪）；2 个收编合并（分支目录抹除事故并入 WorkBuddy 事故记忆、架构文档分支同步定案并入 project-doc-conventions）；精简前全量备份 `~/.zcode/cli/memories/projects/.../memory-backup-2026-09-13/`；项目 memory 降至 29 个文件（含 MEMORY.md），archive 增至 172 个文件，global 增至 76 个文件 |
| 2026-09-12 | 预览/日志全选全不选合并单按钮（T-SelToggle）：预览两分区 + SqlLogPanel 类型弹窗「全选/全不选」双按钮合一为状态切换单按钮（全勾→「全不选」清空，否则「全选」补满），预览侧 renderSql 末尾单点文案收口、弹窗侧三处显式同步 + buildTypeFilterContent 纯提取，i18n 零新键；含前置清除按钮末位调整。全量 3353/0/6 绿；详见 docs/变更记录.md 同日行 |
| 2026-09-12 | **T-CrudColor：SQL 列表 CRUD 类型颜色标记（worktree `mpsv-crudcolor`，分支 `t-crudcolor`）**：SQL 日志页与「SQL 历史」页二级 SQL 列表支持按语句类型着色标记，**颜色与标记形态均可自定义**。选型（计划 §1.2 八维度对比）=左侧 3px 色条为默认；否决整行背景色的**首要理由=选中态被平台填充整块覆盖**（`background = if (isSelected) selectionBackground else background`，键盘上下浏览时丢色），用户提议「背景色样式下选中态用一圈边框替代整块填充」后该硬冲突消解、背景色升级为等价可选样式（默认仍留色条——暗色下背景色压 SQL 高亮对比度）。新增 `SqlListTypeColor`（样式归一化 / 类型→色 / `#RRGGBB` 容错解析 / **显式 half-up 混色**（Kotlin `round` 是 ties-to-even，`round(76.5)=76` 会致色值偏 1/255）/ 暗色描边提亮 `ensureOutlineContrast` / Color 实例缓存）+ `SqlListSelectionOutline`（`AbstractBorder` 描边：W 夹紧 1..4、可变 color 字段改色不重建、`fillRect` 四条边带而非嵌套 `drawRect`、内层 insets 四边 +W）；两渲染器（`LogEntryListCellRenderer` / `HistoryCellRenderer`）色条挂 `BorderLayout.WEST`（左内边距 5 + 色条 3 = 原 8，**文本左缘不位移**）+ 两套 Border 预建引用切换（热路径零分配）+ `applyCrudColorMark` 文件级 internal 单一收口两面板共用；`LogSqlEntry.statementType by lazy`（body 内属性不进 equals，消除 `classify` 每次编译 `Regex` 的渲染期开销，`filterByType` 一并切换）。**选中态三处定稿**：① 选中行内层分隔线改**透明占位**（否则灰线画在描边环内把底边割断）② 两状态 insets **逐边相等**（上5/左8/下6/右8）防文本位移抖动，单测按 W ∈ 1..4 参数化锁定 ③ 描边色过对比度保障提亮（暗色 `selectionBackground` `#2E436E` 压在加深后的 `#5A3C22` 行底上几乎不可见）。§3.5 类型过滤弹窗色块图例（off 样式不显示）。设置 5 字段（样式下拉 + 四色 `ColorPanel` + 恢复默认，接 isModified/apply/reset 三处）+ 中英 bundle 12 键（**不新增复选框**，保 25 台账）。**§七 性能复核**：发现并修复「渲染热路径逐格 Swing 属性赋值触发 repaint」隐患——实测 `setBackground(新 Color·同值)` → `repaint()+1`、同一实例 → 0，初版 `colorBar.background = tint` 每格新建 Color 实例 = 每格一次重绘调度（T-LogListFreeze 同类成因）；修复=Color 实例缓存 + `setBorderIfChanged`/`setBackgroundIfChanged`/`setOpaqueIfChanged` 变化才写（17 处调用点），并加 4 条**实例同一性**防回退用例。测试 44 例（SqlListTypeColorTest 18 + SqlListSelectionOutlineTest 10 + LogStatementClassifierTest +3 + SqlLogPanelRendererUiTest +6 + HistoryCellRendererTest +5 + PluginSettingsPanelBindingTest +2），**clean 全量 3393/0/0/6 绿**；同步修正受新增分组影响的 PluginSettingsPanelBuildInfoTest（分组 9→10）与 PluginSettingsPanelUiTest（SQL 日志段下拉 0→1）。工程坑：worktree 分支名**不能带斜杠**（本机 `.git/refs/heads/` 下新建子目录不落盘 → `invalid reference`，改用扁平名）；Gradle 需 JDK17+（shell 默认 JDK8，用 IDEA 发行包自带 JBR21）；外部 Gradle 守护持续锁 `journal-1.lock`/`javaCompile.lock` 致 15 次失败（Python 探测确认为真独占锁），经用户授权 `./gradlew --stop` 优雅释放（`2 Daemons stopped`，未强杀未删文件） |
| 2026-09-12 | master 合入 dev 增量合并第二轮（T-MergeMasterIntoDev2，merge 613eae6）：merge-base 2543203 后 master 独有 29 提交 / dev 独有 114 提交，5 文件 5 冲突块全解决。合入 v2026.9.11 发行/T-DynUpdate·热替换套件/T-HistParam/T-ReverseParenWira/T-PickMpjErr/262 签名护栏等；DataSourceSelector 取 master ReadAction 包裹 + dev T-Perf-13 异步链融合、SqlLogAutoSaver 取 dev 批量写 + master pd 行 union。全量 295 类 / 3788 用例 / 0 失败 / 6 跳过一次通过；verifyPlugin 三目标 Compatible；buildPlugin 出 2026.9.11.zip。详见 docs/变更记录.md 同日行与计划文档 20260912-0842 |
| 2026-09-12 | 调研报告独立成目录：`docs/开发计划/` 中 7 个调研性质文档（竞品分析与优化建议、官网设计借鉴、P-立项-05 验证报告、竞品横向对比调研报告、SqlSourceInterceptor 可行性分析、CoolRequest/DebugTools agent 调研报告）迁至 `docs/调研报告/`，9 处站内引用同步改链无死链；文档命名规范新增「调研报告」类型（含范围界定：调研立项计划仍归开发计划）；M37.5 调研计划经核实为立项计划留原处 |
| 2026-09-12 | 记忆第六次精简（79→27）：新增 40 个归档（bug-fixes +37：T-DynUpdate/T-HistParam/T-PerfSweep/DocLock 系列等已交付任务、分支合并与审计记录、JOIN 图自绘引擎旧档、竞品链接定稿；pitfalls +3：Swing HTML 列表渲染/VFS refresh 锁/测试沙盒弹浏览器）；8 个通用经验升级 global（hprof 快照工作流/并行索引级拆提交/合并冲突三规则/工具转义坑/TPS 装机/文档清单定例/API 代理/fernflower）；5 个小记忆收编进 doc-conventions 与 release-workflow（产品命名/临时截图/verifyPlugin 门禁/update 清单/Gitee 计数）；精简前全量备份 `~/.zcode/cli/memories/projects/.../memory-backup-2026-09-12/`；项目 memory 降至 28 个文件（含 MEMORY.md），archive 增至 160 个文件，global 增至 75 个文件 |
| 2026-09-11 | MPJ 括号条件反向转换退化 + 262 预览 attachConsole 读权限硬抛双修（T-ReverseParenWira，master dab3d3b）：详见 docs/变更记录.md 同日行。要点=parseGroup 单条件非取反括号组解包（MPJLambdaWrapper 形态恢复 + nested 去除）；attachConsole 包 WIRA（WIRA-in-WIRA 重入经 262 锁实现反编译实证合法）+ getDocument 包 ReadAction。全量 3308/0/0/6 绿；262 真机留人工 |
| 2026-09-11 | dev 录制期配置治理：`c91aa6d` 把构建基准常驻切到 262，致 dev 自身 test 编译失败 8 条（`DiagramRelationshipInfo.label` 移入 Database 插件 + `toLowerCase()` 硬弃用 + 1 处连带）→ `bc8069d` 显式 revert，`f73df8c` 收敛为 `-PgifRecord` 属性开关（默认 243 / true 则 262 + jcef + toolchain24 + 沙盒 MPSV-GifFresh；**四项须整体切换**）。另加误开闸门：`gifRecord=true` 与 buildPlugin/publishPlugin/publish 同用即配置期失败（写法同 `verifyIdes`，兼容 configuration-cache）。验证：默认态全量 290 类 / 3733 用例 / 0 失败 / 6 跳过；两态对照平台 243↔262、jcef 无↔有、字节码 61↔68。同步修订录制经验总结 §三.1/§五/§六/§八。 |
| 2026-09-11 | T-PerfBatch 第二轮交付（监听器分层+列表通知节流，`SqlLogEntryBuffer` 三出口拆分）+ T-PerfSweep 四项遗留热点清扫（重放守卫惰性化/热路径 Regex/dedupeCounts 4096 上限/CriteriaQuery 文件级门控），全量 3696/0/6 绿；性能分析 20260910-0345 复制进 dev 并附 dev 适用性补注，T-PerfSweep 计划立项（20260911-0617） |
| 2026-09-10 | master 合入 dev 增量合并（T-MergeMasterIntoDev，merge d5130ab）：merge-base 29fbf556 后 master 独有 22 提交 / dev 独有 98 提交，10 文件 11 冲突块全解决。**CHANGELOG.md 整文件伪冲突根因=dev 侧 blob 全 CRLF 而 master 与 merge-base 全 LF**（全库扫行尾仅 LICENSE 与 dev/r2 的 CHANGELOG 为 CRLF），处置=Unreleased 段并集 + 归一 LF（736 行 crlf=0）。逐块定案：DataSourceSelector 的 createTempPsiFile 取 master（T-ExecCopyFix + T-PreviewFlash 缓存 + WIRA，换掉 dev 的 EDT 空壳 allowSlowOperations），块外保留 dev 的 T-Perf-13 异步准备链与 T-Perf-16 releaseForPanel；SqlLogPanel 取 dev 片段管线 + 插 master 行号片段；HbmXmlEntityResolver 取 dev 结构；JpaGutter 取 dev 三锚点 + master 守卫收窄落派生分支（全锚点前置会误杀 T-J5/J20 调用处锚点；dev 的「接口名以 Repository 结尾」启发式是 M38.1 误报面，首跑全量即红）；collapsedGroups 按 master 删除。**踩坑：按文件整取一边会抹掉另一侧自动合并区成果**（releaseForPanel 悬空致编译失败），正解=merge-file 复现自动合并结果后只替换冲突块。全量 3693/0/0/6 绿、verifyPlugin 三目标 Compatible。计划文档 docs/开发计划/20260910-1211-T-MergeMasterIntoDev-master合入dev增量合并-开发计划.md |
| 2026-09-10 | IN 列表展开阈值——元素<5保持同行，≥5逐行（T-VStack·InThreshold，master dc308fc）：expandInList 加 IN_EXPAND_MIN_ELEMENTS=5（短清单竖排零增量纯花行高——占位符 ? 对应靠参数面板顺序；长清单横向溢出痛点保留），新增 3 条 + 回调 8 条，SqlFormatterTest 52/52、全量 3230/0/6 绿；K2J 陈旧输出劫持再犯（java/main 残留 783 旧类排前，编译进新类运行时吃旧类假红），字节码反汇编证伪后清目录复绿。计划文档：docs/开发计划/20260910-0939-T-VStack扩面-竖向排列覆盖SELECT UPDATE DELETE横向组-开发计划.md §追加交付 |
| 2026-09-10 | 预览打开时 EDT 内文档解析踩 262 读权限硬断言刷屏修复（T-PreviewDocLock）：真机日志每次双击预览刷 4 条 SEVERE `Read access is allowed from inside read-action only` + `Plugin to blame: SQL Visualizer`，同源 `createEditor:1538` 的单次 `PsiDocumentManager.getDocument(psi)`。根因=平台加固——243 `FileDocumentManagerBase.getDocument` 只有 `@RequiresReadLock` 注解、`EditorLockFreeTyping` 类不存在，262 改为首行 `softAssertReadAccess()` + `FileDocumentManager.getDocument` 顶部 `EditorLockFreeTyping.assertReadAccess`；`Logger.shouldRethrow` 只认控制流异常故生产不抛（仅刷屏 + FUS 上报），UsefulTestCase 模式抛 AssertionError。修复=该处包 `ReadAction.compute`（同文件 T-M42 先例，注释写明 lambda 内勿塞同步 refresh 边界）；全库排雷 DataSourceSelector（WriteAction 内）/WrapperLocator（ReadAction 内）/SqlInputEditor（日志零命中）三族不改。全量 3252/0/0/6 绿；262 真机日志零增量留人工。计划文档：docs/开发计划/20260910-1745-T-PreviewDocLock |
| 2026-09-10 | 点击「移除重复的sql」漏裁跨窗口重复修复（T-LogRowNumber，master 874f257）：dedupeExisting 手动全量裁剪去时间窗口（纯按 dedupeKey 判重），修复跨窗口同文本 SQL 点击去重后仍在列表；运行时 isDuplicate 窗口语义不变，全量 3242/0/0/6 绿 |
| 2026-09-10 | SQL 日志列表行号被非主进程启动重置修复（T-LogRowNumber，master 4c1940f）：processStarted 无条件清零→isModuleBasedLaunch 主配置 gating（仅 ModuleBasedConfiguration 子类清行号，HTTP/profiler/脚本跳过）；243 反编译实证 getRunProfile/ModuleBasedConfiguration；新增 testT29_NonModuleProcessStart，全量 3228/0/0/6 绿 |
| 2026-09-09 | SQL 日志列表行号——会话内捕获序号 + 项目启动清零（T-LogRowNumber，master 2467f94）：LogSqlEntry.sequenceNumber（0=历史文件/round-trip 无行号），SqlLogEntryBuffer.addEntry 入队临界区 AtomicLong 分配（去重丢弃/暂停/过滤关键字命中不占号），clear 一并归零，RunConfigurationProcessListener processStarted 无条件清零（绕开 onProjectProcessStarted 的 !autoStart 守卫——手动捕捉模式同样生效），渲染器元信息行最前 #N 灰色片段（片段管线零结构变更，固定行高不受影响）。附带修复 removeEntry 改同一性删除：入库为带号 copy 后 data class equals 含 sequenceNumber，按 equals 删外部原型永不命中（右键删除失效）。新增 14 用例，全量 3173/0/0/6 绿（sandbox-logrownum 隔离）；runIde 真机留人工（idea.log resetEntrySequence 锚点）。计划文档：docs/开发计划/20260909-1339-T-LogRowNumber-SQL日志列表行号-开发计划.md |
| 2026-09-08 | WriteIntentReadAction 重载 262 移除致 verifyPlugin 失败修复，插件市场发布受阻解除（T-Wia262，master）：发布前全量三版本兼容性校验 BUILD FAILED——IU-262 报唯一兼容性问题：SqlPreviewPanel.createEditor 的 invokestatic 引用 compute(ThrowableComputable)（T-PreviewWiLock 引入时仅 javap 核验 243 编译基准）在 262 平台（IDE 2026.2.1）已移除，双击预览回退分支真机 262 NoSuchMethodError 必崩。javap 三版本对照矩阵（243/253 在 lib/util-8.jar、262 在 intellij.platform.core.jar）实证后，换三版本交集重载 compute(Computable)（单类型实参消歧，锁语义/SlowOperations 包装零变化，全库唯一调用点）。全量 3131/0/0/6 绿 + verifyPlugin 复跑 BUILD SUCCESSFUL（262 兼容性问题清零，deprecated 40 持平、experimental 9→10 均 warning 级）。遗留不阻断：allowSlowOperations(String)/StdLanguages.JAVA/findElement(DasObject)/Notification.setListener 等 scheduled-for-removal 用法 262 仍在，后续立项迁移。计划文档：docs/开发计划/20260908-1857-T-Wia262；runIde/真机 262 双击预览人工跟进 |
| 2026-09-08 | 单击预览五轮优化（T-ClickPreview-05，master）：复制按钮点击无任何视觉反馈——复制回调成功后图标即时闪变为对号（AllIcons.Actions.Checked，javap 核验存在于 243 基线 AllIcons$Actions），1500ms 后一次性 Swing Timer 自动恢复复制图标（isRepeats=false 无常驻定时器）；改动集中 SqlClickPreview.buildContent 按钮监听 + 私有 flashCopied 助手，复制取编辑后当前文本与关闭/焦点语义零改动。SqlClickPreviewTest 增至 11 用例（新增点击后 icon===Checked 且异于初始图标断言）；插曲：首跑假失败为陈旧编译产物（javap 证 flashCopied 字节码正确 setIcon(Checked)，重跑即绿）。全量 3131/0/0/6 绿（基线 3130+1）；runIde 真机（对号闪变目检）人工跟进 |
| 2026-09-08 | 双击预览无数据源 Database 工具窗抢占侧边栏修复（T-DsCover，master）：预览总入口 showResult:699 无条件 prepareDefaultConsole → chooseAndPrepare 无数据源分支:268 每次 openDatabaseToolWindow（getToolWindow("Database")?.show()，等价手点条带按钮顶掉插件侧边栏）；全库 grep 确认唯一工具窗激活面。修复全落失败分支、成功路径零改动（不触 M36.5.25g 预览显 DBN 工具栏红线）：① show() 门控 executeAfterPrepare（仅显式执行引导开窗，预览静默）；② runRunner 加 silent 参数（预览路径失败不弹模态 + 清损坏数据源 LAST_RUNNER_KEY 记忆，PropertiesComponent.unsetValue util-8.jar javap 核验）。全量 3130/0/0/6 绿；runIde 真机人工跟进 |
| 2026-09-08 | 单击预览四轮优化（T-ClickPreview-04，master）：①预览编辑器高亮与列表同色——三轮走平台 SQL FileType 方言高亮（Database 插件接管）与列表自研 SqlLexer+SqlHighlightColors 两套体系颜色不同；新增 SqlListSyntaxHighlighter（SyntaxHighlighter 实现），token→配色键映射提为唯一权威 ATTR_BY_TYPE 供列表片段渲染与编辑器高亮共用，预览编辑器改 createEditor(document, project) 纯文档 + EditorEx.setHighlighter(LexerEditorHighlighter(highlighter, globalScheme))（243 javap 核验；EditorImpl 内部完成 setText/监听接线，不预调避免复用断言），不依赖 Database 插件；Layered 版不需要（自建高亮器即基准层）。②不可编辑修复——setRequestFocus(false) 致轻量弹窗无键盘焦点，改 true 弹出即聚焦可直接打字。SqlClickPreviewTest 增至 10 用例（新增同源同色断言），定向 10 绿 + SqlListFragmentRendererTest 6 绿 + log 包 560 绿；runIde 真机人工跟进 |
| 2026-09-08 | 单击预览三轮优化（T-ClickPreview-03，master）：预览内容升级 SQL 高亮可编辑编辑器——纯文档+SQL FileType 驱动词法高亮（四参 createEditor，**刻意无 PSI** 规避 EDT createFileFromText read access 硬抛陷阱，无 Database 插件降级纯文本），可临时改字段值后点复制取编辑后文本（软换行/紧凑外观/编辑器随弹窗 onClosed 释放，constructor 增 project）；SqlClickPreviewTest 9 用例适配（编辑后复制经 WriteCommandAction），定向+受影响套件绿；插曲：Kotlin 增量编译陈旧 ABI 假失败重跑即绿 |
| 2026-09-08 | 错误一键上报官网需求池（T-ErrReport-01~07）：errorHandler EP 直报（IdeErrorReportSubmitter + 报告确认框 + SHA-256 指纹 24h 去重 + 需求池 API 直连）+ Help 菜单/设置页手动入口 + 官网表单读参配套（docs 仓库 12727f9）；新增 4 测试类 34 用例，全量 3592/0/0/6 绿、verifySmoke(243) 过；计划 20260908-1259 |
| 2026-09-08 | 单击预览二轮优化（T-ClickPreview-02，master）：①点其他地方后未再单击 SQL 预览又出现——onClick 只看 clickCount，空白区点击同样重启定时器、300ms 后借残留选中行复活预览——排程严格绑定点到真实行（clickPoint 经 locationToIndex+getCellBounds.contains 命中才排程，点击时按行捕获 SQL 不依赖选中态，未命中立即停表关预览）+仅左键触发；②弹窗锚点改被点行下缘 2px 缝不遮行；③预览窗可编辑 + 头部复制/关闭按钮（i18n log.preview.copy/close en/zh）+ JBPopupListener.onClosed 清引用。SqlClickPreviewTest 重写 9 用例 + 受影响 5 套件绿；runIde 真机人工跟进 |
| 2026-09-08 | 多行 JdbcTemplate 日志漏捕获修复 + MyBatisCodeHelper-Pro 反编译精华移植（T-JdbcMultiline，master）：`[...]` 跨行语句（TLog TID 前缀用户样本，`Executing prepared SQL statement [SELECT COUNT(1)` 起头、`]` 独占末行）整条漏捕获——根因=JdbcTemplateLogParser 的 SQL 正则要求 `[...]` 同行闭合；修复=参照 HibernateLogParser 加 COLLECTING 收集态（string-aware 净括号计数归零闭合：单引号字符串内括号不计数、`''` 转义、字符串态跨行延续；动作短语/NamedParameter/参数行边界强制闭合残句不误挂参数；closePending 冲刷 → LogToSqlPanel 粘贴路径零改动受益）；SQL 行分支重构为动作短语正则 + 括号扫描（快路径闭合点由贪婪最后一个 `]` 改首个平衡 `]`，修复 `[SELECT arr[1]` 旧式误提取）。反编译（IDEA 自带 java-decompiler 须 JBR25 运行，135 类）结论：MBCHP-Pro（BatsLog 源流）仅支持 Preparing/Parameters 成对格式、用户样本同样不捕；精华采纳对照——其 ignoreSqlContains=既有 logFilterKeywords 等价（零代码）、consoleFilterProvider 稳定 EP/尾部公共后缀降噪/批量写 UI 列 P2 调研、druid 格式化与控制台回显不采纳。计划文档：docs/开发计划/20260908-1157-T-JdbcMultiline。测试 +9，全量 3121/0/0/6 绿（基线 3112+9）；runIde 真机人工跟进 |
| 2026-09-08 | SQL 日志去重失效与 MP 分页计数误标双根因修复（T-LogDedupeMpMark，master）：①JdbcTemplate 双路日志（named TRACE trim 过 / prepared DEBUG 不 trim）对同一语句产出仅差首尾空白的两副本，去重原文精确相等永不命中——`normalizeSqlWhitespace` 并入 T-Perf-19 指纹链（`dedupeKey = sqlFingerprint(normalizeSqlWhitespace(restoredSql))`），isDuplicate/dedupeExisting/计数/徽章四处同键，SqlLogPanel.kt 零改动（避开 T-LogListFreeze04 刚重构的渲染管线），大小写/分号/参数不同不合并语义均不变；②processConsoleLine 批级 `taken.any{COUNT}` 改逐条判定——同批闭合的数据查询不再被误标 MP 分页计数（连带恢复源码跳转），pendingSource 优先级不变。测试 +10（Buffer 空白判重 5 / JdbcParser 控制台序列回放 1 / BridgeMarker 同批两向序 3 / DedupeToggle 跨格式裁剪 1），全量 3112/0/0/6 绿；插曲：Kotlin 增量编译陈旧类假失败（javap 证字节码已新，重跑即绿）+ clean 首跑 worker 偶发崩溃，warm 重跑全绿。计划文档：docs/开发计划/20260908-1010-T-LogDedupeMpMark。runIde 真机（达梦项目）验证人工跟进 |
| 2026-09-08 | SQL 日志列表 HTML 逐条解析 EDT 冻结 16 秒根治（T-LogListFreeze-04，master）：Freeze 栈 BasicHTML.createHTMLView×逐条测量——①渲染器弃 JLabel-HTML 通道改双 SimpleColoredComponent 两行（SqlListFragmentRenderer 分词片段，配色/关键字加粗保留）+ fixedCellHeight 实测固定（WideSelectionListUI 短路逐条测量）+ HTML 缓存机制退役；②移植 dev 73b16b2 管线全套（T-Perf-03 差分增量刷新+代际守卫+drainEvictedCount/T-Perf-19 计数键指纹化/T-Perf-24 搜索防抖/T-Perf-15 dispose 注销+removeCapturingListener/T-Perf-06 CME/T-Perf-12 锁外回调/notifyUi 置脏合并）——同源代码后续 master→dev 合并无冲突；③历史面板两渲染器同型片段化。行为变更：条目恒定两行不再按内容折行（全文走单击/双击预览）。isDuplicate 仍原文精确相等（T-LogDedupeMpMark 修复空间不受占）。计划文档：docs/开发计划/20260908-0931-T-LogListFreeze04。全量 3102/0/0/6 绿；runIde 真机验证人工跟进 |
| 2026-09-08 | SQL 列表悬停预览卡顿改单击预览、双击弹窗（T-ClickPreview，master）：根因=列表渲染器 toolTipText=完整 SQL，悬停逐行弹气泡×可变行高重排；三列表统一改造（主日志/历史 tab 二级/F-7 历史窗）——移除 tooltip + 新增共享 SqlClickPreview（300ms 单发定时器区分单击/双击，单击出轻量预览弹窗锚定行，双击仍走完整弹窗）+ dispose 停表关弹窗；新增 SqlClickPreviewTest 7 用例，全量 3094/0/0/6 绿；runIde 真机交互验证留人工跟进 |
| 2026-09-08 | SQL 日志双击预览崩溃修复（T-PreviewWiLock，master）：createEditor 回退分支同步 VFS refresh 被包在 EDT ReadAction 内，262 平台 write-safe 断言硬抛（日志双击入口预热竞态必输恒走回退）——包装换 WriteIntentReadAction.compute（write-intent 隐含读权限），全量 3087/0/0/6 绿；runIde 真机双击验证留人工跟进 |
| 2026-09-07 | 03-开发与测试指南 §2 新增「runIde 沙盒版本切换（临时调试）」小节：隔离性说明 + 临时改 build.gradle.kts:64 切换版本跑 runIde + 还原步骤；注意事项（`-PplatformVersion` 未接线 / 沙盒按版本分目录互不影响 / testDatabase jdbc-drivers 耦合点） |
| 2026-09-07 | 记忆第五次精简（21→17）：新增 4 个归档（bug-fixes：R2 竞品响应 9 任务交付、T-DaoSql 字符串 SQL 锚点、M54 性能红线 24 任务、log-jump 跳转根因）；精简前全量备份 `~/.zcode/cli/memories/projects/.../memory-backup-2026-09-07/`；项目 memory 降至 18 个文件（含 MEMORY.md），archive 增至 120 个文件（含 README） |
| 2026-09-06 | 记忆第四次精简（27→21）：新增 6 个归档（bug-fixes：dev 全量分支合并、M37 竞品融合全链、M39 ORM 补全实施、M44 其他ORM第一批实施、ORM 四框架缺口调研定稿、候选 ORM 调研定稿——均已交付并合入 dev）；精简前全量备份 `~/.zcode/cli/memories/projects/.../memory-backup-2026-09-06/`；项目 memory 降至 22 个文件（含 MEMORY.md），archive 增至 116 个文件（含 README） |
| 2026-09-05 | M39 ORM 缺口补全三波实施完成（feature/m39-orm-coverage，3321 测试绿 + buildPlugin 通过，用户明确不合并 master）；新增两份矩阵索引：JPA-Hibernate官方API覆盖矩阵（M39 勾行 83✅39🟡）与其他ORM官方API覆盖矩阵（候选框架调研：Spring Data JDBC/jOOQ/Querydsl/Easy-Query/Jimmer/xbatis/MDS/Fluent MyBatis/dbVisitor/BeetlSQL/sqltoy/lazy，含立项决策表与任务号预分配 SD/JO/QD/EQ/JM/XB/DS/FM/DV） |
| 2026-09-04 | 记忆第三次精简（76→19）：新增 35 个归档（bug-fixes +22：M36 执行/收尾、F-44 跳转、HQL 别名/关联、JdbcTemplate 预览、设置页布局/树导航、Marketplace、方言验证、JOIN 原生图 B 路径等；pitfalls +13：gutter 接收者、SqlFormatter 两坑、EDT 冻结、设置页导航等），22 个归全局（跨项目平台陷阱 + 用户偏好，含 JCEF/SlowOperations/headless 测试/Gradle 锁等）；archive 增至 110 个文件（含 README），global 增至 63 个文件 |
| 2026-08-26 | 新增「记忆精简规范.md」：本地记忆文件管理标准（分类/归档/全局/流程/基线） |
| 2026-08-26 | 记忆第二次精简（31→14）：新增 16 个归档（M30-M33/M41/F-42/F-43/F-44/IDE 冻结/M28/M47/F-45/PlantUML/plugin-xml/设置页布局/设置页配置/tooltip + 3 个踩坑）；archive 增至 70 个文件 |
| 2026-08-22 | 记忆精简（107→12）：新增「归档记忆」分区（docs/memory-archive/，54 个文件）；全局通用记忆 41 个备份到 `~/.zcode/cli/memories/global/`；全局经验精炼入 `~/.zcode/AGENTS.md` |
| 2026-08-20 | 新增索引条目：20260820-2030-更新机制Windows路径解析缺陷修复-开发计划.md（插件自更新静默失败修复，✅ 已修复） |
| 2026-08-20 | 开发计划 17 个英文文件名重命名为中文（符合文档命名规范）；新增 19 条 20260820 系列索引条目（含总纲 + 17 项子计划 + 中文命名） |
| 2026-08-17 | 开发计划表格补录 T-M43/T-M44/T-M45 条目（含新建开发计划文档）；T-M44 状态更新为已交付 |
| 2026-08-16 | 新增索引条目：Marketplace发布流程-说明文档.md（首次发布全流程，含双仓库同步方案） |
| 2026-08-16 | 新增「架构设计」分区：docs/架构设计/01-项目架构总览 / 02-核心流程详解 / 03-开发与测试指南（Mermaid 架构图+流程图系列文档） |
| 2026-08-15 | 更新索引条目：20260815-1416-检查更新无反应与构建号不显示修复-T-M28.1-开发计划.md（T-M28.1 已交付 792 测试全绿） |
| 2026-08-15 | 新增索引条目：20260815-1416-检查更新无反应与构建号不显示修复-T-M28.1-开发计划.md（T-M28.1 任务源，计划待实施） |
| 2026-08-15 | 索引提示块补充：沙盒本身也冲突（prepareTestSandbox 互锁/残留测试 worker 映射锁定/共享 SDK 解压与 records.dat 互锁） |
| 2026-08-15 | 索引新增重点提示：文件头 🔴 并行测试协调登记表（parallel_tasks.py 位置/用法/场景原因——T-M26.1 互锁互杀 + M28×M29 实证） |
| 2026-08-15 | 更新索引条目：20260815-1219-设置页检查更新与自动安装更新-M28-开发计划.md（M28 已交付 766 测试全绿，T-M28-01~06） |
| 2026-08-15 | 新增索引条目：20260815-1219-设置页检查更新与自动安装更新-M28-开发计划.md（M28 任务源 T-M28-xx，计划定稿待开工） |
| 2026-08-15 | 更新索引条目：20260815-1630-XML与注解SQL代码补全-M27-开发计划.md（M27 已交付 714 测试全绿，T-M27-01~06） |
| 2026-08-15 | 新增索引条目：20260815-1630-XML与注解SQL代码补全-M27-开发计划.md（M27 任务源 T-M27-xx，方案 B 自研补全 + 联表支持；方案 C 立项 P-立项-06） |
| 2026-08-14 | 新增索引条目：20260814-1130-多SQL选择弹窗代码行预览与定位-开发计划.md（T-M23.8，已交付 499 测试全绿） |
| 2026-08-14 | 新增索引条目：20260814-1115-任意位置触发与多SQL选择-开发计划.md（T-M23.7，已交付 497 测试全绿） |
| 2026-08-14 | 新增索引条目：20260814-1100-弹窗提示一键关闭SQL面板-开发计划.md（T-M23.6，已交付 492 测试全绿） |
| 2026-08-14 | 更新索引条目：20260814-1015-SQL关键字标识符引用-开发计划.md（T-M23.5 增量交付，490 测试全绿；查询路径引用补齐） |
| 2026-08-14 | 新增索引条目：20260814-1015-SQL关键字标识符引用-开发计划.md（T-M23.4，已交付 486 测试全绿） |
| 2026-08-14 | 新增索引条目：20260814-0945-字段顺序角色分组-开发计划.md（T-M23.3，已交付 472 测试全绿） |
| 2026-08-14 | 初版：docs 目录检索索引（覆盖全部现存文档，含 M23/M23.1 系列）；元数据注明与 ~/.zcode 会话记忆的用途差异 |
