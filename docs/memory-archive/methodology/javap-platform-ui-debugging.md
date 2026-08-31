---
name: javap-platform-ui-debugging
description: 反编译平台 jar 定位 IntelliJ 平台私有布局/Swing UI 异常的完整流水线——一次 javap 顶十轮组件树探针；含沙盒 jar 定位、进程识别、部署与 stamp 校验
metadata:
  node_type: memory
  type: methodology
  originSession: M36.5 方案 A（2026-08-31）
---

# javap 反编译平台 jar 调试 UI 布局问题

**适用场景**：插件嵌入/复用平台（或 DBN 等三方插件）的 Swing 组件时出现布局异常
（塌缩、错位、滚动异常），且组件树探针 dump 无法定位是哪一层在算尺寸。

**核心心法**：布局异常时先问"**是谁在算这个尺寸**"——答案几乎总在某个
`LayoutManager.layoutContainer` 里。平台/三方类的布局管理器是私有类，源码不在本地，
但字节码就在沙盒 IDE 的 jar 里，javap 直接可读。**一次反编译顶十轮探针**
（实证：M36.5.60→73 十几轮探针没定位的根因，javap 半小时确诊，
见 [pitfall-dbn-output-console-layout-collapse.md](../pitfalls/pitfall-dbn-output-console-layout-collapse.md)）。

## 流水线

### 1. 定位沙盒 IDE 安装目录

gradle-intellij-platform 插件下载的 IDE 分布在 gradle transforms 缓存：

```bash
# 在项目 .intellijPlatform/sandbox/<name>/<IDE-BUILD>/log/ 确认沙盒版本号（如 IU-2024.3.7.1）
find "C:\Users\USER\.gradle\caches" -maxdepth 7 -type d -name "ideaIU-2024.3*"
# → C:/Users/USER/.gradle/caches/9.7.0/transforms/<hash>/transformed/ideaIU-2024.3.7.1-win
```

用 `product-info.json` / `build.txt` 核对版本与沙盒一致。

### 2. 找到目标类所在 jar

2024.3+ 的平台类分散在 `lib/app-client.jar`、`lib/app.jar`、`lib/modules/*.jar`。
用 python zipfile 扫描（比 unzip -l 循环快且稳）：

```python
import zipfile, glob, os
ide = r"<IDE_DIR>"
for j in [os.path.join(ide, "lib", "app-client.jar")] + glob.glob(os.path.join(ide, "lib", "modules", "*.jar")):
    with zipfile.ZipFile(j) as z:
        hits = [n for n in z.namelist() if "LanguageConsoleImpl" in n]
    if hits: print(os.path.basename(j), hits)
```

### 3. javap 反汇编布局管理器

```bash
# 解出 .class 后（注意 Windows 下 $ 内部类要转义）：
javap -p -c "com/intellij/execution/console/LanguageConsoleImpl.class" > lci.txt
javap -p -c "com/intellij/execution/console/EditorMergedHorizontalScrollBarLayout.class"
```

重点读：
- **构造参数**：布局管理器的字段（谁是 first/second、minLineCount 等）即分配策略的输入；
- **`layoutContainer`**：分支条件 + `setBounds` 调用——高度给谁、什么条件下给满；
- **`getMinXxx`/`getXxxCount` 等 hard-code 方法**：`iconst_2; ireturn` 这类常量返回
  就是"为什么恰好是 44px/2 行"的直接答案；
- **豁免分支**：如"子组件不可见→占满"，并核对该分支运行时是否可达。

### 4. 与运行时证据对照

沙盒日志（`.intellijPlatform/sandbox/.../log/idea.log`）中的自证 dump
（bounds/pref/min/layout 类名）与字节码推演互相印证：运行时给"是什么"，
字节码给"为什么"。

## 修复模式

- **替换布局管理器**（自研 LayoutManager，每次 validate 幂等重跑），不要 setSize/组件监听强改；
- 目标组件类名精确匹配防误伤；**搜索范围收紧到目标子树**（整树 DFS 会命中同类名的
  其他实例——M36.5.75 教训）；
- 三类自证日志：安装打点、运行修正打点、失败祖先链诊断（节流）。

## 沙盒部署注意事项（配套流水线）

1. **jar 锁**：runIde 沙盒运行时锁插件 jar，`prepareSandbox` 报
   `Could not copy file ...`。先杀沙盒进程再 build。
2. **精确识别沙盒进程，勿杀主 IDE**：用 PowerShell
   `Get-CimInstance Win32_Process` 看命令行——runIde 实例带
   `-agentlib:jdwp` + `-javaagent:` 且 JBR 路径为 `jbrsdk_jcef-17`；
   主 IDE 是 `idea64.exe`（命令行无 sandbox）。按 **PID** taskkill，绝不按映像名全杀。
3. **构建产物 stamp 校验**（防 stale jar）：
   ```python
   import zipfile, re
   with zipfile.ZipFile(jar) as z:
       data = z.read([n for n in z.namelist() if n.endswith("Xxx.class")][0])
   print(re.search(rb"M36\.\d+\.[\w-]+", data).group(0))
   ```
   build/libs 和沙盒 plugins/lib 两处 jar 都要验。
