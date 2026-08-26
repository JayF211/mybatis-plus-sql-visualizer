---
name: sandbox-deployment-pitfalls
description: 沙盒 IDE 构建部署陷阱（2026-08-25）—— prepareSandbox 文件锁失败、build/libs 陈旧 jar、手动复制注意事项
metadata:
  node_type: memory
  type: project
  originSessionId: sess_79d9d9ca-f51f-435d-a4d5-c2483a8b4dda
---

沙盒 IDE 构建部署陷阱（2026-08-25 持续调试 PlantUML 修复时发现）：

## 陷阱 1：prepareSandbox 文件锁失败

**现象**：`./gradlew clean buildPlugin` 报 `Could not copy file ... plantuml-8059.jar`，错误信息 `请求的操作无法在使用用户映射区域打开的文件上执行`。

**根因**：沙盒 IDE 进程（`java.exe` / `idea64.exe`）仍占用 sandbox 目录下的 jar 文件。`prepareSandbox` 任务尝试覆盖 jar 文件，但 Windows 不允许替换被占用的文件。

**修复**：构建前必须执行：
```powershell
taskkill /F /IM java.exe 2>nul
taskkill /F /IM idea64.exe 2>nul
sleep 3  # 等待进程完全释放
```

## 陷阱 2：build/libs 陈旧 jar

**现象**：构建 `BUILD SUCCESSFUL` 但 `build/libs/` 下的 jar 内容是旧的（缺少新修改的方法如 `renderWithFallback`）。

**根因**：
- 构建部分失败时 `build/libs/` 可能被跳过（cache 命中）
- 手动复制时误复制了旧 jar（先 rm 再 cp 才能确保更新）

**修复**：构建后验证 jar 内容：
```bash
JAR="build/libs/mybatis-plus-sql-visualizer-2026.8.21.jar"
# 验证新方法是否存在
unzip -l $JAR | grep -c 'renderWithFallback'
# 验证关键指令是否移除
unzip -p $JAR cn/song/mybatisplus/visualizer/sql/JoinDiagramGenerator.class | strings | grep -c '!theme plain'
```

## 陷阱 3：手动复制 jar 到 sandbox

**现象**：直接 `cp` 到 sandbox lib 目录失败（文件被占用）。

**根因**：sandbox IDE 仍在运行，jar 文件被锁。

**修复**：
1. 先停止沙盒 IDE：`taskkill /F /IM idea64.exe`
2. 先删除旧 jar：`rm -f sandbox/.../lib/mybatis-plus-sql-visualizer-*.jar`
3. 再复制新 jar：`cp build/libs/... jar sandbox/.../lib/`
4. 验证内容：`unzip -p sandbox/.../lib/jar.jar cn/.../Class.class | javap -p | grep method`

## 陷阱 4：测试环境混淆

**现象**：用户在主 IDE（IntelliJIdea2026.2）测试，看到旧行为（`diagram.render.loading` 键未解析）。

**根因**：主 IDE 的 `plugins/` 目录下的 jar 是 8 月 22 日的旧版本（未更新）。沙盒 jar 是最新的。

**修复**：
- 测试 PlantUML 修复必须在**沙盒 IDE**（`./gradlew runIde`）进行
- 主 IDE 更新需通过 Marketplace 或手动替换 jar

## 正确构建流程

```powershell
# 1. 停止所有 IDE 进程
taskkill /F /IM java.exe 2>nul
taskkill /F /IM idea64.exe 2>nul
sleep 3

# 2. 清理沙盒（可选，彻底重建）
rm -rf ".intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1"

# 3. 构建插件
cd F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer
./gradlew clean buildPlugin --rerun-tasks 2>&1 | grep -E "BUILD|error|FAILED"

# 4. 验证构建产物
JAR="build/libs/mybatis-plus-sql-visualizer-2026.8.21.jar"
unzip -l $JAR | grep -c 'renderWithFallback'  # 应 >= 1
unzip -p $JAR cn/song/mybatisplus/visualizer/sql/JoinDiagramGenerator.class | strings | grep -c '!theme plain'  # 应 = 0

# 5. 手动复制到 sandbox（如果 prepareSandbox 失败）
rm -f ".intellijPlatform/sandbox/.../lib/mybatis-plus-sql-visualizer-*.jar"
cp $JAR ".intellijPlatform/sandbox/.../lib/"

# 6. 启动沙盒测试
./gradlew runIde
```

## 陷阱 5：沙盒 autoReload 后旧 UI 状态残留（F-42 实证，2026-08-25）

**现象**：`autoReload = true` 下 jar 已更新（时间戳新、batik 全套部署），`runIde` 日志也确认新代码加载无异常，但用户打开的 JOIN 图 tab 仍卡在旧运行残留的"渲染中"状态。

**根因**：`autoReload` 替换插件 jar，但**沙盒已打开的 toolwindow tab 不会重建**。旧 tab 的 DiagramPanel 实例 dispose 后，旧 `loadingPanel` 状态留在 EDT 上。用户视角：jar 是新的、日志是干净的，但 UI 看起来"还卡着"。

**修复**：测试渲染流程时不要复用旧 tab——**先彻底清空沙盒再启动**：
```bash
./gradlew --stop
rm -rf .intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1
./gradlew runIde
```
新开的项目窗口才能看到新代码效果。

**实证**：日志显示 14:19 新 jar 加载后零异常，但旧 13:00 运行的 `DiagramRenderTask.kt:45` `IllegalStateException`（已修复的 `setFraction`）仍残留显示在用户打开的旧 tab 里。清掉 sandbox 后重新 `runIde` 即正常。

## 验证清单

- [x] `renderWithFallback` 方法在 jar 中（javap 验证）
- [x] `!theme plain` 已从 JoinDiagramGenerator 移除
- [x] `diagram.render.loading` 键在中英文 bundle 中
- [x] `panel.copyExecutableSql` 中文键存在
- [x] 性能警告 23 条中文翻译完整
- [x] F-42 batik 全套进 lib/，xml-apis 排除，243 沙盒零异常
