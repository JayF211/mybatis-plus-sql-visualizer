---
name: pitfall-test-sandbox-browser-leak
description: 测试沙盒里 BrowserUtil.browse 不隔离会弹真机默认浏览器；定时任务跑全量测试导致 Edge
  周期性自动打开帮助页的归因实录与修复方案（两轮均已实施）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f8a3ed68-0801-4187-b223-3cf5ccdc94c9
---

2026-09-11 归因：用户真机默认浏览器 Edge「每隔一段时间自动打开 https://sql-visualizer.jvtools.cn/features/wrapper-preview.html」。完整因果链 = ZCode 定时自动化任务每天跑全量测试（runIde/测试沙盒）→ 测试触发 `HelpAction.actionPerformed`（HelpUiTest 等，沙盒 idea.log 有 `SqlLogPanel$HelpAction.openLocalHtml/actionPerformed` 堆栈帧）→ 沙盒内 JCEF/HTMLEditorProvider 不可用走降级分支 → `BrowserUtil.browse(HELP_DOCUMENTATION_URL)` **不隔离，直接 ShellExecute 弹真机默认浏览器**。k2j 测试沙盒日志此类 fallback 38 次，时间点与定时任务运行窗口吻合。

**坑本质**：`BrowserUtil.browse` 在测试/沙盒进程里是 OS 级操作，任何「降级打开在线文档」路径都必须在入口加 `ApplicationManager.getApplication().isUnitTestMode` 守卫。**已实施（master 56df6d6，2026-09-11，全量 3308/0/0/6 绿）**：`SqlLogPanel.HelpAction` 三处降级出口（JCEF 不支持 / 帮助 HTML 为空 / openEditor 抛异常）收敛为私有 `browseOnlineDoc()`，单测模式短路只记日志；真机行为零改动；CHANGELOG Unreleased 已记。K2J（feature/kotlin-to-java）镜像类 `SqlLogPanel.java` 同款三处出口，待下轮同步带上。

## 归因方法论（浏览器自动打开类问题通用）

1. **浏览器 History sqlite**：copy `History`（+`-wal`/`-shm` 若存在）到 Temp 再 python sqlite3 查 `urls`/`visits`（Chrome 表间 join 列名是 `visits.url`，Edge 同构）；用户默认浏览器=Edge，Chrome 历史会漏。
2. **transition 位判来源**：`0x10000006` = 外部进程拉起（GENERATED|FROM_OUTSIDE_APP），非页面内导航/用户键入——区分「谁打开的」的决定性证据。
3. **旧域名 301**：代码常量 HELP_DOCUMENTATION_URL 仍是 `mybatis-plus-sql-visualizer.jvtools.cn`，已 curl 实测 301 → `sql-visualizer.jvtools.cn`（用户看到的地址即重定向后）；Edge 该旧域名条 visit_count=220。
4. **沙盒日志 grep `[HelpAction]`**：fallback WARN 行的「第一个堆栈帧」直接钉死触发行号。
5. 排除项工作流：schtasks/Run 注册表/zTasker 逐项查过均不符（`MPSqlVisualizer-WeeklyVerify` 只跑 verify 脚本不开浏览器）。

排查坑：Git Bash 下 `cp` Chrome History 得空 sqlite（无 urls 表），须 python shutil.copy 三件套到 Windows Temp 路径才有效。

## 第二轮实录（T-BrowserLeak2，2026-09-18 实施）

第一轮修复后用户真机 Edge 仍周期性弹**两个**页面： 与 。归因钉死：**SafeBrowserTest 自己**——它为验证「测试环境不弹浏览器」直调这两个真实 URL（SafeBrowserTest.kt:18-19），但 SafeBrowser 守卫写成「app != null && isUnitTestMode 才短路」，纯 JUnit 无平台 Application 环境（app==null）守卫反而**放行**，BrowserUtil 降级 Desktop.browse 直接 ShellExecute 弹真机 Edge。且该测试断言「不抛异常即没弹」不可观测——BrowserUtil 在无 Application 进程里不抛异常，每次跑都真开一次。

**第二轮修复定案**：①守卫语义反转为 fail-safe 默认拦——，判定=，真机 IDE 宿主（Application 恒非 null 且非 unit mode）零变化；②测试断言换形——拆出  直接断言，移除真实 URL 直调；③守卫补位——BannedApiSourceScanTest 的 BrowserUtil.browse 禁扫扩展到 src/test + SafeBrowser fail-safe 形态锚点防回退。

**方法论增补**：写「环境守卫」时语义方向必须是 fail-safe（默认拦、确认真机才放行）；「app != null && xxx 才拦」是白名单放行形态，任何新环境形态（无 Application 的 runner、mock 形态）都会静默漏网。测试验证守卫生效不能用「跑下去没弹」这种不可观测副作用做断言。

## 第二轮实录（T-BrowserLeak2，2026-09-18 实施）

第一轮修复后用户真机 Edge 仍周期性弹**两个**页面：`sql-visualizer.jvtools.cn/about/feedback` 与 `example.invalid/never-opened`。归因钉死：**SafeBrowserTest 自己**——它为验证「测试环境不弹浏览器」直调这两个真实 URL（SafeBrowserTest.kt:18-19），但 SafeBrowser 守卫写成「app != null && isUnitTestMode 才短路」，纯 JUnit 无平台 Application 环境（app==null）守卫反而**放行**，BrowserUtil 降级 Desktop.browse 直接 ShellExecute 弹真机 Edge。且该测试断言「不抛异常即没弹」不可观测——BrowserUtil 在无 Application 进程里不抛异常，每次跑都真开一次。

**第二轮修复定案**：①守卫语义反转为 fail-safe 默认拦——`if (isTestEnvironment()) return`，判定=`app == null || app.isUnitTestMode`，真机 IDE 宿主（Application 恒非 null 且非 unit mode）零变化；②测试断言换形——拆出 `isTestEnvironment()` 直接断言，移除真实 URL 直调；③守卫补位——BannedApiSourceScanTest 的 BrowserUtil.browse 禁扫扩展到 src/test + SafeBrowser fail-safe 形态锚点防回退。

**方法论增补**：写「环境守卫」时语义方向必须是 fail-safe（默认拦、确认真机才放行）；「app != null && xxx 才拦」是白名单放行形态，任何新环境形态（无 Application 的 runner、mock 形态）都会静默漏网。测试验证守卫生效不能用「跑下去没弹」这种不可观测副作用做断言。
