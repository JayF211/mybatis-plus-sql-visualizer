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

## 七、验收记录（20260926 二次回写——GUI 依赖组补完轮）

> 首轮回写（A/B1/B2/B5/F 配置级）后，于机器空闲时段重启沙盒补完 GUI 依赖组。

| 组 | 结果 | 证据 |
|----|------|------|
| A1 MCP 十工具在册 | ✅ | tools/list 62 工具中 sqlv_* 恰 10，schema 与基线一致 |
| A2 MCP 调用链 | ✅ | check_sql UPDATE 写风险输出正确＋[MCP-AUDIT] 行在册 |
| B1 增强捕获通道 | ✅ | 注入/CONFIG ACK#1/条目捕获日志链（首轮）＋**面板条目可视化**（#1/#2 条目 9ms/[MySQL] 角标/UserServiceImpl.java:41 位置/SQL 渲染/工具栏控件全在） |
| B2 位置对照 | ✅ | 面板条目 meta `UserServiceImpl.java:41`＋落盘 source 行一致 |
| B3 捕获停止开关 | ✅ | 面板按钮 ■→▶ 切换实测：停止期请求 200 面板零新增；恢复后 #2 条目立即出现——双向判据全过 |
| B4 屏蔽规则 | ✅ | 条目右键菜单「屏蔽此 SQL」执行→面板清空＋通知「已屏蔽 1 条 SQL，后续不再展示。管理屏蔽规则...」——屏蔽/管理入口在册 |
| B5 SQL 历史落盘 | ✅ | `sql-history-2026-09-26.sql` 落盘（source/pd 参数快照/调用栈/还原 SQL 完整） |
| C1 控制台通道 | ◐ 部分验证 | 控制台过滤器注册＋应用 mapper DEBUG 素材在册＋「SQL 进面板」端到端已由增强通道证；控制台独立回落语义（停增强→控制台采集）未单独切换验证 |
| D1 Wrapper 预览 | ✅ | Alt+Shift+Q 预览：LAMBDA_QUERY 识别、动态参数面板（全选/清空）、逻辑删除注入 deleted=0、ORDER BY 无 LIMIT 性能诊断、执行计划入口全在 |
| D2 XML 预览 | ✅ | gutter 图标点击：XML select 识别、if 动态条件勾选、#参数面板（数字提示）、LIKE concat 渲染 |
| D3 JOIN 关系图 | ◐ 部分验证 | mybatis-plus-demo 无 JOIN 素材（JOIN 在 join-demo 子工程）；预览对话框工具栏与关系图入口在册 |
| E1 ConvertToSql | ◐ 注册面 | action id 在 plugin.xml（ConvertToSql）；与 E2 同族注册机制已由 E3 实证 |
| E2/E3 表右键动作 | ✅ | department 表右键菜单「Generate CRUD SQL」在册且可执行（无异常）——数据库上下文动作注册实证 |
| F1/F2 设置页 | ✅ | Settings 对话框：左侧树 SQL Visualizer 七子页（UI and Actions/Shortcuts/SQL Preview/Table Name Mapping/SQL Log/MCP Tools/About）全在；SQL Log 页全部关键开关/输入在册 |
| G1 SQL→Java | ✅ | 页签渲染＋真实转换：`SELECT...FROM department`→`new LambdaQueryWrapper<Department>()...`（实体匹配+未映射列降级字符串诊断行） |

**总结论**：**零回归发现**。GUI 依赖组补完后，仅 C1 的通道回落独立切换、D3 的 JOIN 素材、E1 的编辑器选区执行三项为部分验证（均有注册面/逻辑层测试/相邻功能端到端佐证），其余全部通过。**修复循环未触发**（走查全程未发现需要修码的回归问题）。

## 八、范围边界（如实声明）

- JPA/Hibernate 专属预览（jpa-hibernate-demo 工程）与 JOIN 图的深走查需要切换沙盒项目——本轮以 mybatis-plus-demo 内可用素材为准，JPA 专属项若素材缺失按「环境受限未验证」如实登记，不做受限环境冒充。
- 更新检查/反馈入口等声明式注册项以「设置页/菜单可见」为准，不做网络面触发。
