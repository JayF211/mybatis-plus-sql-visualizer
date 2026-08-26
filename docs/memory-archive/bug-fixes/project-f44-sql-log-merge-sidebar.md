---
name: project-f44-sql-log-merge-sidebar
description: F-44 已完成（2026-08-25）——SQL 日志合并到主侧边栏 + 源码定位 + 历史读取
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e386e7f2-eac0-4476-bc8f-1442ea2f7dda
---

F-44 已完成（2026-08-25），提交 `1c8bfe6`（功能）+ `8e072a1`（修正 sidebar 注册错误），已合并到 master（merge commit `028c262`），2497+ 测试全绿。

**核心设计**：
- **保留**主侧边栏 `cn.song.mybatisplus.visualizer.log`（plugin.xml 注册），**去除**独立 `cn.song.mybatisplus.visualizer.history` 侧边栏
- 主侧边栏 SqlLogPanel 新增第 4 个 tab "历史" 嵌入 HistorySqlLogPanel
- `LogSqlEntry` 增加 `sourceFile: String?` + `sourceLine: Int` 字段（默认 null/0，向后兼容）
- `SqlLogConsoleBridge` 捕获时从 `Thread.currentThread().stackTrace` 提取源码位置
- `SqlLogAutoSaver` 保存格式扩展为 `--- source: xxx | time: xxx | timestamp ---\nSQL\n`
- 新建 `SqlLogFileReader`——从 .idea 文件反解析，兼容新旧两种格式（逐行扫描，非 regex split）
- 新建 `HistorySqlLogPanel`——列表 + 搜索 + 双击预览 + 右键跳转源码
- `SqlLogManager` 移除 `maybeAutoOpenWindow()` 及相关自动打开逻辑

**最终 toolWindow 注册状态**：
- ✅ `cn.song.mybatisplus.visualizer.log` — 主侧边栏（4 tabs：SQL 日志 / 日志转SQL / SQL→Java / 历史）
- ❌ `cn.song.mybatisplus.visualizer.history` — 已注释（合并到主侧边栏）

**源码位置提取机制**：
- 从调用栈中第一个非 JDK/非 IntelliJ/非本插件的帧提取 `className + fileName + lineNumber`
- 转换为相对路径（`com.example.mapper.UserMapper` → `com/example/mapper/UserMapper.java`）
- UI 导航：`LocalFileSystem.findFileByPath()` → `OpenFileDescriptor` 跳转行号
- 局限：best-effort，项目结构变化时路径可能不匹配

**数据流**：
```
控制台日志 → SqlLogConsoleBridge → Parser → LogSqlEntry（附加 stackTrace 源码位置）
  → SqlLogEntryBuffer → onEvicted → SqlLogAutoSaver → .idea/.../sql-history/*.sql
  → SqlLogFileReader.readAll() → HistorySqlLogPanel（主侧边栏 tab）
```

**关键约束**：
- 实时捕获机制保留（驱动 F-43 自动保存），主侧边栏 Tab 0（实时 SQL 日志）仍通过 `addUiListener` 订阅
- `SqlLogToolWindowFactory` 和 `ToolWindowVisibilityListener` 类保留但未删除（避免破坏引用）
- 旧版 F-43 格式（`-- timestamp\nSQL`）仍可被 `SqlLogFileReader` 解析
- `TOOL_WINDOW_ID` 常量保留（其他代码可能引用）

**实施过程中的教训**：
- 最初错误地去除了主侧边栏（`cn.song.mybatisplus.visualizer.log`），保留了独立的 SQL 历史侧边栏——与用户意图相反
- 用户发现"侧边栏只有sql历史图标"后纠正：应保留主侧边栏 + 去除独立 SQL 历史侧边栏
- 修复提交 `8e072a1`

**Why**: 用户希望减少侧边栏按钮，将 SQL 历史合并到主侧边栏；同时支持查看历史 SQL 并跳转回产生该 SQL 的源码位置，便于调试。

**How to apply**: 实施已完成。`maybeAutoOpenWindow` 已删除，相关测试已移除。`SqlLogFileReader` 有 10 个单元测试覆盖。