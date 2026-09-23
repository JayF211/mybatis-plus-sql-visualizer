# T-McpRenderDialectFix（O-67）：MCP 实测双缺陷修复

## 一、立项背景（2026-09-23 真机实测）

MCP 十工具真机走查（IU 2026.2.3 内置 MCP Server `127.0.0.1:64342` Streamable HTTP 直连，绑定 demo 项目 mybatis-plus-sql-visualizer-demo，插件为当日 2026.9.23 构建）：9 工具全绿，抓出两个 master 在册缺陷。

1. **sqlv_render_sql snippet 模式硬抛 read access**：`McpSourceLocator.renderXmlSnippet` 未包 ReadAction；text 链传 project 后会走跨 ns include（T-IC-02）/静态常量（T-M25.2）/mapper 参数类型（M29.5）三处 PSI 读，平台协程线程直接硬抛 "Read access is allowed from inside read-action only"（真机读数 isError=true，线程 DefaultDispatcher-worker-50）。file+line 模式因 `renderAtLine` 全程包了 ReadAction 不受影响——同工具两模式一好一坏。
2. **sqlv_explain 省略 dialect 必报 INVALID_ARGUMENT**：平台 JSON 绑定把省略的可选参数落成 Kotlin 默认值 `""`，`McpDialectDetector.isRecognized("")` 判 false（`normalize` 对空串返回 null），与工具描述与错误文案「omit to auto-detect」自相矛盾。真机读数：`[INVALID_ARGUMENT] dialect '' is not recognized`；同一语句显式传 `dialect=mysql` 后绿路全出（EXPLAIN 表 + notices + 索引提示）。

## 二、改动清单

- `mcp/McpSourceLocator.kt`：`renderXmlSnippet` 整链包 `ReadAction.compute`（与 `renderAtLine` 同口径）。
- `mcp/McpDialectDetector.kt`：`isRecognized` 对 null/空白一律放行（「未指定」语义）；`resolve` 链原有「显式名 > URL 推断 > 默认 MySQL」兜底不变，无需改。

## 三、测试与反向验证

- `McpDialectDetectorTest`（位于 McpRenderInputTest.kt，testFast 纯逻辑桶）新增空白放行断言——修复前必红。
- 新增 `McpRenderSnippetReadActionTest`（BasePlatformTestCase）：调用放在独立 worker 线程而非测试主线程——夹具若给测试线程授了读权限，主线程直调会「摘掉修复也绿」；worker 线程与平台协程线程同样无读权限，判别力不依赖夹具授锁形态。修复前必红。
- 反向验证（摘掉修复→必红）两次读数见 §五 实施记录。

## 四、待验证前提（实施者先证伪）

1. 262 测试 JVM 对 worker 线程同样执行 read-access 断言——若红读数不是 read-access 文案而是别的失败，先查夹具形态再下结论。
2. `renderXmlSnippet` 在空项目夹具下能走通渲染：PagesXmlPreviewServiceTest 已证 text 链纯逻辑部分可跑；project 相关三增强在空项目（无索引无类）应空结果不崩。若崩，判断是测试环境局限还是生产缺陷，回填本节。

## 五、实施记录

- 测试先行两件已落（方言空白断言 + snippet 读锁夹具测试），修复前先跑出两份红读数：
  - 红读数 1（testFast，修复前）：`McpDialectDetectorTest > isRecognized_空白视为未指定放行 FAILED — AssertionError at McpRenderInputTest.kt:68`（期望 true 实得 false；同类其余 5 例绿）。
  - 红读数 2（test，修复前）：`McpRenderSnippetReadActionTest FAILED — TestLoggerAssertionError`，报文即「Read access is allowed from inside read-action only」，触发线程 worker Thread-80 非 EDT——与真机 MCP 通道报文一致，判别力成立（待验证前提 1 证实）。
- 修复落盘：两文件按 §二 改动。
- 绿读数（修复后，同参数复跑）：testFast `McpDialectDetectorTest` 6/6 绿、BUILD SUCCESSFUL；test `McpRenderSnippetReadActionTest` 1/1 绿（tests=1 failures=0 errors=0）、BUILD SUCCESSFUL。
- 待验证前提 2 证实：空项目夹具下 snippet 渲染走通，sourceType=mapper XML (snippet)、SQL 含表名。
- 合并：等待主树 T-Release20260923 门禁（testShards shard3）跑完后合入 master，主树收口复跑两定向；发行会话在途未提交的发行八处文件（CHANGELOG/变更记录/plugin.xml 等）全程未触碰，本任务 CHANGELOG/变更记录条目以未提交追加方式落主树（避免裹挟他会在途产物）。
