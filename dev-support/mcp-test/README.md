# mpsv-mcp-test.mjs — 平台 MCP 服务器最小客户端（Streamable HTTP）

来源：`%TEMP%\mpsv-mcp-test.mjs`（Temp 会被清，正式沉淀于本目录；20260926 补 `call` 超时参数）。

## 用途

对 IDEA 内置 MCP Server（端口 64342，Streamable HTTP 协议）做 `initialize → tools/list → tools/call`，
验证插件经 `mcpServer.mcpToolset` EP 暴露的 `sqlv_*` 工具（注册形态无关——XML 声明或
T-McpDynamicToolset 动态壳注册均适用）。每次调用的成败可经插件 `McpAuditLog` 的
`[MCP-AUDIT]` 行（idea.log）对账。

## 前置

- IDEA 运行中且「工具 → MCP 服务器」开关开启（沙盒预置见 `dev-support/sandbox-preset/`）；
- Node.js（自带 fetch）。

## 用法

```bash
node mpsv-mcp-test.mjs list                       # 列出全部工具（含 inputSchema 必选/可选参数）
node mpsv-mcp-test.mjs list | grep sqlv_          # 断言插件十工具在册
node mpsv-mcp-test.mjs call sqlv_check_sql '{"sql":"DELETE FROM t","projectPath":"<项目绝对路径>"}'
node mpsv-mcp-test.mjs call execute_run_configuration "@args.json" 240000   # 第 5 参=超时毫秒（长执行工具必给）
node mpsv-mcp-test.mjs suite                      # 批量连通用例（脚本内置）
```

- `@文件` 传参避开 shell 转义；JSON 内中文一律 `\uXXXX`（Git Bash 陷阱）。
- MCP 工具触发 `execute_run_configuration` 首次会弹「Confirm Command Execution」模态框
  （鼠标点击可能无效，键盘 Enter 确认 Allow），勾选 Brave mode 后不再弹。
- **平台工具长执行注意**：客户端超时断开后平台会清会话并取消执行——`execute_run_configuration`
  等长动作必须显式给大超时（如 180000~600000）。

## 历史读数基线（20260926，T-McpDynamicToolset 动态壳注册形态）

- tools/list 共 62 工具，其中 `sqlv_*` 恰 10 个；`inputSchema` 参数名/必选集与 XML 声明形态逐字一致
  （kotlin-reflect 对运行期生成壳类的继承方法解析正常）。
- 九工具调用 OK＋两预期错误正确（check_sql 空参 `[INVALID_ARGUMENT]`、schema_context 无数据源
  `[DATASOURCE_NOT_FOUND]`），`[MCP-AUDIT]` OK/EXPECTED_ERROR 全对账。
