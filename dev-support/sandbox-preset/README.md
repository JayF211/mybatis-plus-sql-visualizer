# 沙盒配置预置（runIde 真机走查免 GUI）——键名/文件/步骤

来源：20260926 T-FullRegression 走查轮（反复重启实证）。

## 沙盒位置

- 常规（243）：`.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config/`
- 262 录制/走查态（`-PgifRecord=true`）：`build/MPSV-GifFresh/mybatis-plus-sql-visualizer/IU-2026.2.1/config/`

> **改配置必须 IDE 未运行**（运行中改会被内存态覆盖写回）；强杀 IDE（taskkill）不 flush
> 最近改动——Trust 状态等会丢，改完配置后启动即可。

## 预置项与生效证据

### 1. 插件设置（`config/options/mp-sql-visualizer.xml`）

```xml
<application>
  <component name="MpSqlVisualizerSettings">
    <option name="enhancedCaptureConfirmed" value="true" />  <!-- 免「注入确认」弹窗；缺省 false 时 AgentManager 跳过注入（idea.log: premain 注入跳过：enhanced-unconfirmed） -->
    <option name="enhancedCaptureEnabled" value="true" />
    <option name="logAutoCaptureOnStart" value="true" />     <!-- 应用启动即开捕（缺省 false 时 CONFIG disabled=true，面板零条目） -->
    <option name="logAutoSaveEnabled" value="true" />        <!-- 历史落盘 .idea/sql-visualizer/sql-history/（缺省 false，目录不建——是默认行为非回归） -->
  </component>
</application>
```

键名权威=`PluginSettings.kt`（`@State(name="MpSqlVisualizerSettings", Storage("mp-sql-visualizer.xml"))`）。

### 2. 平台 MCP Server 开关（`config/options/mcpServer.xml`）

```xml
<application>
  <component name="McpServerSettings">
    <option name="enableMcpServer" value="true" />
  </component>
</application>
```

生效判据：`netstat -ano | findstr 64342` LISTENING。

### 3. Trust and Open Project（首启模态框）

无配置文件预置法——只能 GUI 点一次（勾「Trust all projects in … folder」+ Trust）。
**强杀 IDE 不写盘，重启会再问一次**；点了后正常退出（或保持运行数分钟）才持久。

## 启动与驱动

```bash
./gradlew runIde -PgifRecord=true --args="<demo工程绝对路径>" -Pkotlin.compiler.execution.strategy=in-process
```

- **应用启动免 GUI**：平台 MCP Server 的 `execute_run_configuration` 工具可触发 IDE Run 配置
  （进程走 Run 面板 → AgentManager.onProcessAppeared → 注入链成立；外部 mvn/java 起的 JVM
  **不会**被注入——增强捕获是事件驱动）。首次触发弹「Confirm Command Execution」模态框
  （鼠标可能无效、键盘 Enter 可达；勾 Brave mode 后不再弹）。调用客户端见
  `dev-support/mcp-test/mpsv-mcp-test.mjs`（长执行必须显式大超时）。
- **窗口置顶防干扰**：`mpsv_walk.py pin <标题关键字>`（pygetwindow+SetWindowPos TOPMOST）；
  注意用户自己的浏览器/截图工具若也置顶会反复抢 Z 序，走查前先截图确认前台。
- **收工**：强杀 gradle 任务不会带走 IDE 的 JBR java.exe 与 fsnotifier——必须 taskkill
  进程并确认 `jps` 无 idea.Main、`tasklist` 无 fsnotifier，否则下次 runIde 锁 transforms 失败。

## demo 工程

`$MPSV_DEMO_ROOT/mybatis-plus-demo`（demo 根由环境变量 `MPSV_DEMO_ROOT` 提供，见
`.local/local.env.example`；2026-10-10 T-LocalEnvOverride 起不再写死本机路径）
（Spring Boot 8081；MySQL localhost:3306/mp_demo root/root；GET /user/list 触发 SQL；
mapper DEBUG 已开——控制台通道素材 `<== Row:` 在应用输出）。
