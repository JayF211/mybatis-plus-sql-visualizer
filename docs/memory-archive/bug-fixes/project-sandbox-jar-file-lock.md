---
name: project-sandbox-jar-file-lock
description: runIde 沙盒插件 jar 被 Windows 内存映射锁定 → prepareSandbox 静默不刷新 →
  沙盒始终跑旧插件代码（新日志/新修复完全无痕迹，像代码 bug）；删沙盒插件目录重建解决
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c90388b1-ffe0-4a46-98e7-a620ff182919
---

2026-08-27 M36 排查「点击执行 SQL 无反应」时发现：代码正确、新提交已构建，但沙盒里一直是旧 jar——runIde 静默跑旧代码，现象与"代码 bug"完全一致，浪费多轮排查。

**症状链**：
1. 上一次 runIde 的沙盒 IDEA 已退出，但 Windows 残留 jar 的内存映射文件锁
2. `./gradlew prepareSandbox` 报 `Could not copy file '...jsqlparser-5.3.jar' ... (请求的操作无法在使用用户映射区域打开的文件上执行)`（FileNotFoundException 包装）
3. 沙盒 `plugins/<name>/lib/*.jar` 停留在旧构建 → runIde 加载旧类：新日志无痕迹、新修复不生效
4. 更隐蔽：有时 prepareSandbox 部分成功（主 jar 是新的但缺后续改动），仅靠 jar mtime 难判断

**诊断方法（验证沙盒 jar 内容）**：
- `unzip -o` 沙盒 jar 中的目标 class 到临时目录 → `grep -ao "标记字符串" xxx.class | wc -l`（日志前缀/常量串）；`javap -c` 看特定调用（如 setAccessible）是否进字节码
- 对比 git 提交时间（`git log --format="%h %ad %s" --date=format:"%H:%M:%S"`）与 jar mtime（`ls -la`）：jar 早于最新提交 = 旧构建

**修复**（跑前先 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check` 确认无活跃任务）：
```bash
rm -rf .intellijPlatform/sandbox/<sandbox>/IU-xxx/plugins/<plugin-name>
./gradlew prepareSandbox
```
删除后重建即成功，新 jar 立即可验证。

**预防**：每次 runIde 前确认上一个沙盒 IDEA 进程完全退出。进程甄别有**两处**（2026-08-27 第二次实证）：
1. `wmic process where "name='idea64.exe'" get ProcessId,CommandLine`——日常 IDE（AppData\Local\Programs 路径、无特殊参数）与 `stdioMcpServer` 进程**不能杀**，仅沙盒实例可等其退出
2. **沙盒 runIde 进程名是 `java.exe` 不是 idea64.exe**（第二次被坑）：命令行 = gradle cache 里 IDEA JBR 路径（`~/.gradle/caches/.../transformed/ideaIU-xxx-win/jbr/bin/java.exe`）且带 `-Dcompose.swing.render.on.graalvm` 类参数。甄别：`powershell Get-CimInstance Win32_Process -Filter "ProcessId=<pid>"` 看 CommandLine 是否含 gradle transforms/沙盒路径；杀进程用 Git Bash 双斜杠 `taskkill //PID <pid> //F`（先过并行任务表 check）

**变体三：无文件锁也会刷新滞后**（2026-08-27 第三次实证，M36.2b）。`compileKotlin` 与 `prepareSandbox` 均 BUILD SUCCESSFUL、无锁报错，但沙盒 jar 内容仍滞后源码（unzip 抽 class 二进制 grep 新代码标记为 0）。原因：增量构建未拾取 Kotlin 源码变更。修复：`./gradlew prepareSandbox --no-daemon --rerun-tasks -x test` 强制重建后标记进 jar。**铁律：每次刷新沙盒后、交给用户 runIde 验证前，必须 unzip 抽目标 class 二进制 grep 验证新代码标记存在**——单看 BUILD SUCCESSFUL / jar mtime 不可靠。

**变体四：堆栈形态错位诊断 + buildPlugin 才重打包**（2026-08-29 第四次实证，M36.5.12）。用户报错堆栈行号指向源码**注释行**、且出现 `executeOnConsole$lambda$0$0$0` 直接调用目标方法的旧 lambda 形态——新代码该调用已内联进 `$0$0$0$2`（invokeLater 的 Runnable）——即为沙盒跑旧 jar 的铁证，无需再怀疑代码本身。注意两点坑：① `compileKotlin` 只产出 build/classes，**不更新 build/libs 插件 jar**（jar mtime 不变）——必须跑 `buildPlugin` 才重打包；② 同步沙盒可 `cp build/libs/<plugin>.jar` 到沙盒 `plugins/<name>/lib/`（先确认文件未被锁定），随后重新 `javap` 沙盒 jar 验证新方法/新 lambda 结构存在（如 `$2` 方法、`projectExecFile`）才算完成。

**Kotlin lambda 反编译注意**（M36.5.12 附加）：lambda 编译为**外层类的私有静态方法**（如 `DataSourceSelector$executeOnConsole$lambda$0$0$0`），**不生成独立 .class 文件**——`unzip -l | grep lambda` 查不到 ≠ lambda 不存在，不能以此判新旧；必须 `javap -p -c 外层类.class` 看方法列表与方法体，对比调用形态（旧：`$0$0$0` 直调目标方法；新：经 `$0$0$0$2` invokeLater 包装）才是铁证。

**变体五：buildPlugin jar 任务 stale——`--rerun-tasks` 也不奏效**（2026-08-29 第五次实证，M36.5.15）。`compileKotlin` 产物（build/classes/kotlin/main）含新代码标记，但 `buildPlugin` 乃至 `--rerun-tasks` 打出的 build/libs jar **内容仍是上一版**（grep 字符串：含上一轮标记、缺本轮标记；疑似打包链输入快照/中间目录 stale）。可靠流程（绕过 Gradle 打包链）：
```bash
./gradlew compileKotlin   # 确保 build/classes 最新
python3 - <<'EOF'         # zipfile 手动替换：jar 内全部 class ← build/classes，META-INF/资源保留
import zipfile, os
jar='build/libs/<plugin>.jar'; src='build/classes/kotlin/main'; tmp=jar+'.tmp'
new={os.path.relpath(os.path.join(r,f),src).replace(os.sep,'/'):os.path.join(r,f)
     for r,_,fs in os.walk(src) for f in fs if f.endswith('.class')}
with zipfile.ZipFile(jar) as zin, zipfile.ZipFile(tmp,'w',zipfile.ZIP_DEFLATED) as zout:
    names=set(zin.namelist())
    for it in zin.infolist():
        zout.writestr(it, open(new[it.filename],'rb').read() if it.filename in new else zin.read(it.filename))
    for rel,full in new.items():
        if rel not in names: zout.writestr(rel, open(full,'rb').read())
os.replace(tmp,jar)
EOF
grep -ac "新标记" <(unzip -p build/libs/<plugin>.jar path/Class.class)   # 验证后才 cp 到沙盒
```
铁律不变：**交给用户 runIde 前，grep jar 内 class 验证本轮新标记存在**——`--rerun-tasks`/BUILD SUCCESSFUL/jar mtime 均不可靠。

关联：与全局 `pitfall-sandbox-corruption`（沙盒损坏）不同机制——这是文件锁/增量构建导致的**旧代码静默运行**；候选提升为全局 pitfall。M36 主记忆见 [[project-m36-direct-execute]]。

**2026-09-04 增补（T-Embed-10 沙盒重建实证）**：
- `prepareSandbox` 输出跟随 build.gradle.kts **当时生效的** sandboxContainer——兄弟会话隔离期间（如 gutter-dedup）跑 prepareSandbox 会写 `build/<their-sandbox>/`，共享沙盒（`.intellijPlatform/sandbox`，插件 2.x 默认根=项目根 .intellijPlatform）不会更新；删了共享沙盒插件目录也不会被重建。
- 共享沙盒手动恢复配方：`plugins/<plugin>/lib/` 只需 **4 依赖 jar（commons-math3/jmh-core/jopt-simple/jsqlparser，可从 plugins-test 测试沙盒拷）+ build/libs 主 jar**。
- Kotlin IC 缓存吞源码改动实证：Edit 源码后跑 prepareSandbox 两次 compileKotlin 均 up-to-date（jar 无新代码）——删 `build/classes/kotlin/main` 对应 class 强制重编即可，不必 --rerun-tasks。
- **验证 jar 内容勿用 `javap -c` 搜字符串模板**：Kotlin 字符串模板走 invokedynamic makeConcatWithConstants，recipe 在 BootstrapMethods，`-c` 不显示——须 `javap -v`；纯常量（非模板）串 `-c` 才可见。误判「改动没进包」浪费两轮重建。
