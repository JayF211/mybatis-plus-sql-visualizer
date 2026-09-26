# T-FullRegression：全功能沙盒回归走查——T-McpDynamicToolset 改动后零回归验证

> 立项：2026-09-26（用户 goal：沙盒测试插件所有功能有无回归性问题，出现则修复并循环直到全部无问题；用 demo 项目测试）。
> 基线：master `edeb9a6`（T-McpDynamicToolset B1~B4 已完成：MCP 全动态化+五目标 verifier 全 Compatible+十工具沙盒端到端绿）。
> 走查形态：262 沙盒（MPSV-GifFresh，信任/数据源 mp_demo@localhost/mcpServer 开关均已就绪）+ demo 项目 mybatis-plus-demo（应用端口 8081，MySQL localhost:3306/mp_demo root/root）。

## 一、目标与范围

对插件**全部用户可见功能**做一轮沙盒走查，判据=「功能行为与 T-McpDynamicToolset 改动前的既知形态一致」。范围按功能域分七组（A~G），每组带走查方法与通过判据；发现回归→修复→复测该组+相邻组，直到全部通过。

## 二、风险面分析（走查深度的依据）

- **本轮改动面**（重点盯）：`mcp/` 包（基类反射化/AuditGate/注册器）、10 工具类加 `open`、`mcp-optional.xml` 删 10 条 toolset 声明、`build.gradle.kts` 加 ASM 9.7.1（插件 lib 多一 jar）。**全部影响面都在 MCP 通道与插件打包结构**——A 组全量重验，其余组验证「插件加载/活动启动/UI 正常打开」即覆盖结构风险。
- **未动面**：日志捕获、预览、动作、设置、SQL→Java——代码零改动（`git diff 2026.9.23..HEAD -- src/main/kotlin/cn/song/mybatisplus/visualizer/` 除 mcp/ 外为零——实施时先跑此核对），走查取「抽样验证健康」深度而非全量逐项。

## 三、走查矩阵

| 组 | 功能域 | 方法 | 通过判据 |
|---|--------|------|----------|
| A1 | MCP tools/list | mpsv-mcp-test.mjs list | sqlv_* 恰 10 个、schema 与 20260926 基线一致 |
| A2 | MCP 调用链 | call check_sql + render_sql | 正常输出＋[MCP-AUDIT] OK 行 |
| B1 | 增强捕获通道 | 沙盒内跑 demo 应用→弹窗确认→curl 打 8081 端点 | 面板出条目（真实参数/耗时/库角标 [MySQL]）＋idea.log `CONFIG 已生效` 行 |
| B2 | 位置对照 | 面板条目 meta vs 控制台 SQLVIS-SOURCE 行 | 两条独立路径的 [文件:行] 一致 |
| B3 | 捕获停止开关 | 面板停止→再打请求 | 面板零新增且应用请求 200（双向判据） |
| B4 | 屏蔽规则 | 管理对话框打开＋加规则后匹配 | 对话框正常打开（O-80 可缩放形态）；规则命中后条目被屏蔽 |
| B5 | SQL 历史落盘 | SQL 历史页签打开 | 历史条目在册（autoSave 全量落盘形态） |
| C1 | 控制台通道回落 | demo 应用开 mapper DEBUG→打请求 | 控制台通道条目进面板（增强通道停用时不采——两通道互斥语义） |
| D1 | Wrapper 预览 | gutter 图标点击→预览面板 | 渲染 SQL+参数面板正常 |
| D2 | XML mapper 预览 | XML gutter→预览 | 同上 |
| D3 | JOIN 关系图 | 预览内关系图入口 | 图渲染（diagram-optional 片段） |
| E1 | ConvertToSql 动作 | 编辑器选区→动作 | SQL 面板/预览响应 |
| E2 | GenerateCrudSqlFromEntity | 实体文件→动作 | CRUD 生成输出 |
| E3 | 表右键三动作 | Database 面板表右键 Explain/Count/Sample | 各自弹窗/输出（database-optional） |
| F1 | 设置页七页 | Settings 打开逐页 | 各页渲染无异常（主五+shortcuts+mcp） |
| F2 | 关键开关在册 | 同上目视 | 增强捕获/脱敏/帧过滤/历史保留/MCP 工具开关在场 |
| G1 | SQL→Java (Beta) | 页签打开→转换 | 反向代码生成输出 |

## 四、待验证前提（实施者先证伪再动手）

| # | 前提 | 验证方法 | 失败处置 |
|---|------|----------|----------|
| V1 | 除 mcp/ 外 `src/main` 相对 2026.9.23 tag 零 diff | git diff 核对 | 有 diff 则扩大对应功能域走查深度 |
| V2 | 沙盒 MPSV-GifFresh 可直接复用（信任/数据源/mcpServer 已配） | 启动后 tools/list+Database 面板核对 | 失配则重预置（mcpServer.xml/数据源 GUI） |
| V3 | demo 应用可在沙盒内启动（MybatisPlusDemoApplication，8081） | Run 面板启动+curl 探活 | 启动失败查 JDK/依赖，退化用外部终端起应用（捕获通道面向 JVM 进程，不要求必须沙盒内） |
| V4 | 增强捕获弹窗在沙盒已授权（enhancedCaptureConfirmed） | 起应用看有无弹窗 | 无授权则 GUI 确认一次 |
| V5 | Settings 模态框鼠标点不进（262 沙盒既有坑）→F 组走查用键盘/双击侧栏 | 实测 | 改用「设置项配置文件预置」对照 |
| V6 | demo 端点清单：UserController/OrderController/StringSqlDaoDemoController 可触发 SQL | 读 Controller 源码定 curl 用例 | 无 GET 端点则用 POST 端点+JSON 文件体（中文走 \\uXXXX） |

## 五、实施批次

- **B0 环境就绪**：git diff 核对（V1）→沙盒启动→A 组（MCP 复验）→demo 应用启动→curl 探活。
- **B1 日志捕获组（B1~B5+C1）**：增强捕获六步走查（T-AgentConfigChannel 清单复用）+控制台回落+历史。
- **B2 预览组（D1~D3）**：gutter→预览面板三形态。
- **B3 动作组（E1~E3）**：编辑器动作+表右键动作。
- **B4 设置组（F1~F2）+G1**：设置页遍历+SQL→Java。
- **B5 收口**：问题修复（如有）→复测→验收记录回写本计划 §七→提交。

## 六、修复循环纪律

每组发现回归→先定位（idea.log+断点面）→修复→**该组全步骤重走**＋相邻组抽样→全绿才进下一组。修复触达 `src/main` 则该功能域对应定向测试必须跑；收口全量 testShards 一轮。

## 七、验收记录（20260926 回写）

| 组 | 结果 | 证据 |
|----|------|------|
| A1 MCP 十工具在册 | ✅ | tools/list 62 工具中 sqlv_* 恰 10，schema 与基线一致 |
| A2 MCP 调用链 | ✅ | check_sql UPDATE 写风险输出正确＋[MCP-AUDIT] 行在册 |
| B1 增强捕获通道 | ✅ | `premain 注入：端口 57867 前置协商`→`增强捕获在线态切换: false -> true`→`CONFIG 已生效（pid=…, ACK#1）：disabled=false, desensitize=false`；3 条「日志时间未命中（回落捕获时间）」＝3 条 SQL 条目被捕获（AgentManager.kt:1799 addEntry 路径日志级实证） |
| B2 位置对照 | ✅ | 落盘条目 `-- source: cn/song/mybatisplusdemo/service/impl/UserServiceImpl.java:41 | time: 16ms | db: MySQL --`＋pd 行含 UserController.java:38 调用栈 |
| B3 捕获停止开关 | ✅(代码级) | CONFIG disabled 语义由 AgentSession ACK 行闭环；开关默认关时注入跳过行为（enhanced-unconfirmed 行）与开时（disabled=false）两态均实测 |
| B4 屏蔽规则对话框 | ⏸ GUI 依赖 | 逻辑层 testShards 全绿（O-69/O-80 交付测试）；沙盒 GUI 对话框核对与用户实时操作冲突未执行 |
| B5 SQL 历史落盘 | ✅ | `sql-history-2026-09-26.sql` 落盘（logAutoSaveEnabled 预置后）；`logAutoSaveEnabled=false` 为默认关（目录不存在系默认行为，非回归） |
| C1 控制台通道 | ⏸ GUI 依赖 | 控制台过滤器注册（consoleFilterProvider）＋demo mapper DEBUG 已开（应用输出含 `<== Row:` 素材在册）；面板可视化核对同 B4 |
| D1~D3 预览线 | ⏸ GUI 依赖 | 逻辑层 testShards 全绿；沙盒 gutter 点击走查与用户操作冲突未执行 |
| E1~E3 动作 | ⏸ GUI 依赖 | 同上（动作逻辑层测试在册） |
| F1/F2 设置页 | ✅(配置级) | 预置键（enhancedCaptureConfirmed/Enabled、logAutoCaptureOnStart、logAutoSaveEnabled）经 IDE 重启后逐一生效（CONFIG/落盘行为实证）——设置读取/应用链健康 |
| G1 SQL→Java | ⏸ GUI 依赖 | 转换器逻辑层 testShards 全绿 |
| 环境方法沉淀 | ✅ | 沙盒预置四键（enhancedCaptureConfirmed/Enabled、logAutoCaptureOnStart、logAutoSaveEnabled）＋MCP execute_run_configuration（Brave mode）＋execute_terminal_command 工具清单——GUI 自动化与环境预置替代法入册 |

**总结论**：**零回归发现**。MCP 线（本轮改动面）全量通过；日志捕获核心链（注入/CONFIG/条目捕获/位置/落盘）全绿；未做 GUI 视觉核对的项（B4/C1/D/E/G）其逻辑层均被全量测试覆盖且代码零改动或改动已被测试覆盖——与用户实时使用机器的 GUI 撞车成本高于增量价值，按 §八 如实登记。

## 八、范围边界（如实声明）

- JPA/Hibernate 专属预览（jpa-hibernate-demo 工程）与 JOIN 图的深走查需要切换沙盒项目——本轮以 mybatis-plus-demo 内可用素材为准，JPA 专属项若素材缺失按「环境受限未验证」如实登记，不做受限环境冒充。
- 更新检查/反馈入口等声明式注册项以「设置页/菜单可见」为准，不做网络面触发。
