package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BannedApiSourceScanTest {

    // ------------------------------------------------------------------
    // 禁用 API（真机/发行版才炸的坑）
    // ------------------------------------------------------------------

    @Test
    fun `禁用ActionToolbar旧式刷新API与反射强改按钮hack`() {
        // T-UiNative T-UN-11（2026-09-16）方向反转：原规则钦定的「uiTraverser+反射 getAction+
        // setIcon/repaint」路线是沙盒 workaround，随反射 hack 退役改为禁用对象——真实 IDE 中
        // 工具栏可见，原生更新机制可靠；刷新统一走 updateActionsImmediately()。
        for (api in listOf("updateActionsAsync(", "updateImmediately(")) {
            assertNoHits(
                api,
                "旧式刷新 API 沙盒中不可靠，真机表现不可复现",
                "刷新走 ActionToolbar.updateActionsImmediately()，图标/灰置由 AnAction.update() 声明",
            )
        }
        val hack = mainSources()
            .filter { (_, code) -> code.contains("\"ActionButton\"") || code.contains("getMethod(\"getAction\")") }
            .map { (path, _) -> path }
        assertTrue(
            "src/main 出现 ActionToolbar 反射强改按钮 hack（T-UN-11 已退役）：$hack。" +
                "刷新改走 toolbar.updateActionsImmediately()，动作外观由 AnAction.update() 声明",
            hack.isEmpty(),
        )
    }

    @Test
    fun `禁用IPGP发行版裁剪平台类`() {
        // 名单出处：SqlToJavaPanel.kt / IconScaler.kt 注释（编译实证）；发现新裁剪类请追加并留注释
        for (banned in listOf("JBIconButton", "SmallIconButton", "InlineIconButton", "com.intellij.util.ui.IconUtil")) {
            assertNoHits(
                banned,
                "IPGP 发行版被裁剪，编译/运行期不可用",
                "自建替代（参考 IconScaler 的纯 Swing 实现）",
            )
        }
    }

    @Test
    fun `BrowserUtil_browse调用点必须自带isUnitTestMode短路`() {
        val bad = mainSources()
            .filter { (_, code) -> code.contains("BrowserUtil.browse(") }
            .filter { (_, code) -> !code.contains("isUnitTestMode") }
            .map { (path, _) -> path }
        assertTrue(
            "含 BrowserUtil.browse( 的文件缺 isUnitTestMode 短路（测试沙盒会弹宿主机浏览器）：" +
                "$bad。新调用一律改走 util.SafeBrowser",
            bad.isEmpty(),
        )
    }

    @Test
    fun `测试代码禁直调BrowserUtil_browse统一走SafeBrowser`() {
        // T-BrowserLeak2（2026-09-18）：SafeBrowserTest 直调真实 URL 曾致定时回归每次真开 Edge
        // （守卫「app != null && isUnitTestMode」在无 Application 环境放行）。src/test 同样禁直调，
        // 统一走 util.SafeBrowser——其 fail-safe 守卫在无 Application 环境恒短路。
        // T-TestPerf6：读 src/test 快照（过滤链原样：自豁免本守卫文件）
        val bad = srcTestKotlinTree
            .filter { (f, _) -> f.extension == "kt" }
            .filter { (f, _) -> f.name != "BannedApiSourceScanTest.kt" } // 本守卫自身的报错文案含该片段，须自豁免
            .filter { (_, text) -> stripComments(text).contains("BrowserUtil.browse(") }
            .map { (f, _) -> f.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "src/test 出现 BrowserUtil.browse( 直调（$bad）——测试进程触达即弹宿主机浏览器，" +
                "统一改走 util.SafeBrowser",
            bad.isEmpty(),
        )
    }

    @Test
    fun `测试代码禁碰真实系统剪贴板统一走内存假实现`() {
        // T-ClipboardIso（2026-09-18 用户真机反馈）：Windows 剪贴板是全机唯一互斥资源
        // （WClipboard.openClipboard）——用户在跑测试时复制内容、或并行会话/分片 JVM 抢占，
        // 测试里的 systemClipboard 读写即抛 `cannot open system clipboard`（实证：
        // SqlPreviewPanelTest setUp 快照处单类 10 例全红），且测试写入会覆盖用户自己的剪贴板。
        // 故 src/test 禁直连系统剪贴板：一律 installFakeClipboard（testutil）替换平台服务。
        // 唯一豁免 = testutil 假实现本体（不含系统剪贴板访问，仅作类型引用）。
        // T-TestPerf6：读 src/test 快照（过滤链原样：双豁免本守卫与假实现本体，原文口径判读）
        val bad = srcTestKotlinTree
            .filter { (f, _) -> f.extension == "kt" }
            .filter { (f, _) -> f.name != "BannedApiSourceScanTest.kt" } // 本守卫自身的报错文案含该片段，须自豁免
            .filter { (f, _) -> f.name != "FakeClipboard.kt" } // 假实现本体（不含 systemClipboard 访问）
            .filter { (_, text) -> text.contains("systemClipboard") || text.contains("getSystemClipboard") }
            .map { (f, _) -> f.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "src/test 出现系统剪贴板直连（$bad）——用户复制/并行会话占用时必抛 cannot open system " +
                "clipboard（整类用例假失败），且会覆盖用户剪贴板；改走 testutil.installFakeClipboard",
            bad.isEmpty(),
        )
    }

    @Test
    fun `SafeBrowser守卫语义_无Application必短路锚点`() {
        // T-BL2-03：守卫语义锚点防回退——isTestEnvironment 必须是「app == null || isUnitTestMode」
        // fail-safe 形态；若有人改回「app != null &&」（白名单放行），无 Application 测试进程会真开浏览器。
        val code = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/util/SafeBrowser.kt").readText(),
        )
        assertTrue(
            "SafeBrowser.kt 丢失 fail-safe 守卫形态（app == null || isUnitTestMode）——" +
                "无平台 Application 的测试进程会放行 BrowserUtil 并弹宿主机浏览器（T-BrowserLeak2 实录）",
            code.contains("app == null || app.isUnitTestMode"),
        )
    }

    @Test
    fun `createFileFromText调用点文件必须含ReadAction保护`() {
        val bad = mainSources()
            .filter { (_, code) -> code.contains("createFileFromText(") }
            .filter { (_, code) -> !code.contains("ReadAction") }
            .map { (path, _) -> path }
        assertTrue(
            "含 createFileFromText( 的文件无 ReadAction 保护（EDT 直调 262 read access 硬抛）：$bad",
            bad.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 第二轮守卫（T-TestGuard2，2026-09-17）：0914 后新增坑档的结构断言
    // ------------------------------------------------------------------

    @Test
    fun `模态弹窗只允许白名单内既有场景`() {
        // 弹窗克制红线（docs/开发计划/20260906-0180）+ T-UiNative「模态轻提示改平台通知」定案：
        // 轻提示/进度/结果反馈一律走平台 Notification（无打断、可聚合），模态 Messages/JOptionPane
        // 只保留「用户必须做选择或知情确认」的场景。白名单 = 现存合法场景快照（T-TestGuard2 实测 3 文件，
        // T-MergeDev2Master 合流后补 2 文件——dev 侧早于本守卫落地的同类别场景），
        // 新增模态弹窗须在此显式加白并在调用点注释场景依据，否则守卫红——拦截「顺手弹个窗」回潮。
        // SourceCaptureGuide：跳转引导三按钮弹框（用户决策点）+ 引导结果 info/warning；
        // SqlPreviewPanel：执行前 YesNo 确认；PluginSettingsPanel：配置异常 warning；
        // LogToSqlPanel：DML 执行前 YesNo 确认（与预览面板同交互，ExecuteConfirmMessages 同款）；
        // McpConfigurable：「前往 MCP Server 设置」目标不可用时 warning（与 PluginSettingsPanel 配置不可用同类）；
        // SqlSuppressManagerDialog（O-69）：清空全部规则前 YesNo 知情确认（破坏性、不可恢复；
        //   对话框本体非模态 MODELESS，仅此一处确认弹窗，20260924 收口门禁守卫命中后按红线加白）。
        val whitelist = setOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SourceCaptureGuide.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/McpConfigurable.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlSuppressManagerDialog.kt",
            // R4（导出格式三选）：用户必须选择导出格式（CSV/TSV/TXT）才能继续——知情选择场景加白
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt",
            // ParamSchemeManagerDialog（R22 T-04）：新建/重命名方案 showInputDialog（用户必须输入
            //   方案名才能继续，与 R4 导出格式三选同类）+ 删除方案前 showYesNoDialog 知情确认
            //   （破坏性、不可恢复，与 SqlSuppressManagerDialog 清空前确认同类）；对话框本体
            //   非模态 MODELESS（DialogWrapper(project, false, MODELESS)，与 SqlSuppressManagerDialog
            //   逐字段同形态），20260929 收口门禁守卫命中后按红线加白。
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ParamSchemeManagerDialog.kt",
        )
        val found = mainSources()
            .filter { (_, code) ->
                code.contains("Messages.show") || Regex("JOptionPane\\.show\\w+Dialog").containsMatchIn(code)
            }
            .map { (path, _) -> path }
        val extra = found - whitelist
        assertTrue(
            "src/main 出现白名单外的模态弹窗（$extra）。弹窗克制红线：轻提示/进度/结果走平台 Notification，" +
                "确属「用户必须选择/知情确认」场景才可加白本守卫并在调用点注释依据",
            extra.isEmpty(),
        )
    }

    @Test
    fun `buildSrc脚本禁用fileContents的asText读取`() {
        // T-BS-01 实录（pitfall-filecontents-charset）：providers.fileContents().asText 按 JVM
        // 平台默认字符集解码，本机 GBK daemon 把 UTF-8 的 change-notes 读成乱码（集合→闆嗗悎）。
        // 正确形态 = asBytes + toString(Charsets.UTF_8) 显式解码（mpv-release.gradle.kts 先例）。
        // T-TestPerf6：读 buildSrc 快照
        val bad = buildSrcKotlinTree
            .filter { (f, _) -> f.extension == "kts" }
            .filter { (_, text) -> stripComments(text).contains(".asText") }
            .map { (f, _) -> f.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "buildSrc 脚本出现 fileContents().asText（按 daemon 平台字符集解码，GBK 机器读 UTF-8 文件乱码）：$bad。" +
                "改 asBytes().toString(Charsets.UTF_8) 显式解码",
            bad.isEmpty(),
        )
    }

    @Test
    fun `网络出口收口锚点_FeedbackApi须含errorStream与ContentEncoding处理`() {
        // HttpRequests 两坑（全局 pitfall-http-error-body-swallowed）：① readString 只读成功流，
        // 4xx 正文被丢，须自读 errorStream；② myGzip 默认 true 自动协商 gzip，手写读取须按
        // Content-Encoding 解压。两者收口在 FeedbackApi 的 HttpRequestsSender（postJsonWithBody +
        // readBody）。此守卫防收口被拆散/退化——任何一侧锚点消失即红。
        val feedback = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/feedback/FeedbackApi.kt").readText(),
        )
        assertTrue(
            "FeedbackApi.kt 丢失 errorStream 读取（4xx 正文将被 readString 丢弃，用户只看到裸状态码）——" +
                "网络出口收口被拆散，恢复 HttpRequestsSender 的错误正文读取",
            feedback.contains("errorStream"),
        )
        assertTrue(
            "FeedbackApi.kt 丢失 gzip 解压处理（myGzip 默认协商 gzip，手写读取不解压得二进制乱码）——" +
                "恢复 readBody 的 contentEncoding 判别 + GZIPInputStream 解压分支",
            feedback.contains("contentEncoding") && feedback.contains("GZIPInputStream"),
        )
        // UpdateApi 走平台 readString（成功流+自动解压，GET manifest 场景定案合法）——
        // 禁手写原始响应流，防止有人绕开收口自造读取。
        val update = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/update/UpdateApi.kt").readText(),
        )
        val raw = listOf("getInputStream(", "openStream(").filter { update.contains(it) }
        assertTrue(
            "UpdateApi.kt 出现手写原始响应流读取（$raw）——绕开平台 readString 会同时踩 4xx 正文丢失与 gzip 不解压两坑。" +
                "清单/下载统一走平台 readString/readBytes",
            raw.isEmpty(),
        )
    }

    @Test
    fun `剪贴板写入只允许收口与白名单直用`() {
        // T-UiNative P0：剪贴板统一走 util.UiTextClipboard（进平台复制栈，Ctrl+Shift+V 可回贴），
        // 此前直写 Toolkit.systemClipboard 与 CopyPasteManager 双轨。直用 CopyPasteManager 的文件
        // 快照 = 白名单（UiTextClipboard 收口本体 + 两个既有面板调用点）；systemClipboard 直写零容忍。
        val whitelist = setOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/util/UiTextClipboard.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/components/CopyExecutableSqlAction.kt",
        )
        val direct = mainSources()
            .filter { (_, code) -> code.contains("CopyPasteManager") }
            .map { (path, _) -> path }
        val extra = direct - whitelist
        assertTrue(
            "src/main 出现白名单外的 CopyPasteManager 直用（$extra）——剪贴板写入统一走 util.UiTextClipboard.copy，" +
                "确属特殊场景（如编辑器复制栈交互）才可加白本守卫并注释依据",
            extra.isEmpty(),
        )
        assertNoHits(
            "getSystemClipboard",
            "直写系统剪贴板绕开平台复制栈（与 CopyPasteManager 双轨，T-UiNative P0 已收口）",
            "改走 util.UiTextClipboard.copy",
        )
    }

    @Test
    fun `方言setMapping反射只允许DialectPreMapping单入口`() {
        // T-ExecSlowOps F1：SqlDialectMappings.setMapping 平台硬要求 EDT（handleMappingChange →
        // reparseFiles → runWriteAction → VcsVFSListener.beforeContentsChange 断言），262 真机
        // 后台预热线程反射直调必炸 "Access is allowed from EDT only"。方言映射唯一入口是
        // DialectPreMapping.ensureMapped（BGT 读探测 + invokeLater 派 EDT），新调用点禁止绕开。
        val allowed = "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DialectPreMapping.kt"
        val bad = mainSources()
            .filter { (_, code) -> code.contains("\"setMapping\"") }
            .map { (path, _) -> path }
            .filter { it != allowed }
        assertTrue(
            "setMapping 反射出现在 $allowed 之外（线程语义收口被绕开，BGT 直调 EDT-only API 必抛）：$bad。" +
                "方言映射统一走 DialectPreMapping.ensureMapped",
            bad.isEmpty(),
        )
    }

    @Test
    fun `禁止在any模态回调里做文档写`() {
        // T-WriteSafeCtx（2026-09-17 真机堆栈：Write-unsafe context! current modality=NON_MODAL，
        // 栈底 SqlPreviewPanel.showSql:1711-1712）。平台契约（243 源码实证）：`ModalityState.nonModal()`
        // 恒 write-safe、`any()` 恒 write-unsafe（TransactionGuard.java:73-76 原文）——posted runnable
        // 不算用户活动（IdeEventQueue.kt:1054），`wrapLaterInvocation` 对非 write-safe 模态走 else 分支
        // （TransactionGuardImpl.java:201）不套 runWithWritingAllowed，于是写文档必触发
        // TransactionGuardImpl:141-145 的 LOG.error（生产 SEVERE + 「Plugin to blame」+ FUS 上报，
        // 单测 logger 直接抛）。
        //
        // 修法三选一：① 写入类回调改 ModalityState.nonModal()；② 幂等重算的入口经 DocWriteContext
        // （runWriteSafe / invokeLaterWriteSafe）自愈投递；③ 确属纯 UI/只读，在调用行上方加
        // `// write-safe: <理由>` 标注（守卫按「调用行向上连续的注释块」识别豁免）。
        //
        // T-FS-06 缺口一：站点判定改为「实参区含 any()」，写体扫描面 = 实参区 + **尾随 lambda 体**——
        // `invokeLater({ editor.document.setText(…) }, ModalityState.any())` 的写体在实参区。
        val offenders = anyModalityWriteOffenders(rawMainSources())
        assertTrue(
            "在 ModalityState.any()（恒 write-unsafe）排出的回调里做文档写，命中 ${offenders.size} 处：$offenders。" +
                "改 ModalityState.nonModal()、或经 DocWriteContext 投递、或对纯 UI 站点加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `update包派发平台卸载装载禁用any模态`() {
        // T-DynUpdateWriteSafe（2026-09-22 真机：`Write-unsafe context!` current modality=
        // {[设置对话框, APPLICATION_MODAL], [Unloading of SQL Visualizer]}）。平台契约（262 源码实证）：
        // 模态的写安全资格是「进入那一刻 myWritingAllowed 的快照」，表以模态实例为键
        // （LaterInvocator.enterModal:204-207 → TransactionGuardImpl.enteredModality:199-201）；
        // 平台卸载会压入 ad-hoc 模态（DynamicPluginsLegacyImpl:509），any() 排出的是 posted runnable
        // （IdeEventQueue:1106-1122 只把输入/Item/Focus 事件算用户活动）→ 快照恒 false → 卸载窗口内
        // 平台自身的 commit/落盘/dispose 全部记 SEVERE。
        // 本规则不看写令牌（写发生在平台卸载内部，词法上永远看不见），故按「包 + 令牌」封口：
        // update 包任何形态的 any() 都不该出现（直接实参 / 变量中转 / 决策函数返回，三种形态实测都命中）。
        val offenders = anyModalityInUpdatePackageOffenders(rawMainSources())
        assertTrue(
            "update 包出现 ModalityState.any()（命中 ${offenders.size} 处：$offenders）。" +
                "平台动态卸载会在该模态下压入 Unloading of <plugin>，其写安全资格取派发时刻快照，" +
                "any() 下恒 false（卸载窗口内平台自身模型改动全部记 Write-unsafe context!）。" +
                "派发一律走 DynamicInstallSupport.dispatchModalityForUnload()；" +
                "确属只读跳板请在该行上方加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `update包any模态守卫自证_合成样本必红`() {
        // T-DynUpdateWriteSafe：新规则的负向自证（对齐 T-TS-04 惯例）——违规必报 / 带标注放行 /
        // 他包不误伤，三态缺一即「规则看不见真实形态」的空绿。
        val violating = listOf(
            "src/main/kotlin/cn/x/update/Bad.kt" to """
                class Bad {
                    fun go() {
                        ApplicationManager.getApplication().invokeAndWait({ unloadPlugin() }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "update 包内 any() 派发（含 invokeAndWait 形态）必须被命中，实际" +
                " ${anyModalityInUpdatePackageOffenders(violating)}",
            1,
            anyModalityInUpdatePackageOffenders(violating).size,
        )

        val annotated = listOf(
            "src/main/kotlin/cn/x/update/Ok.kt" to """
                class Ok {
                    fun go() {
                        // write-safe: UI-only（只读跳板，无模型写）
                        ApplicationManager.getApplication().invokeAndWait({ readModality() }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 write-safe 标注的只读站点应放行（豁免不得变成一律放行）",
            0,
            anyModalityInUpdatePackageOffenders(annotated).size,
        )

        val otherPackage = listOf(
            "src/main/kotlin/cn/x/ui/Else.kt" to """
                class Else {
                    fun go() {
                        ApplicationManager.getApplication().invokeLater({ refreshUi() }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals("规则只封 update 包，他包同形态不误伤", 0, anyModalityInUpdatePackageOffenders(otherPackage).size)
    }

    @Test
    fun `尾随lambda写文档守卫自证_合成样本必红`() {        // T-TS-04：新规则的**负向自证**。旧提取器只认 `invokeLater(`，而线上四处真实站点
        // （SqlInputEditor:142 / SqlPreviewPanel:2486 / SqlToJavaPanel:820,881）全是省略括号、
        // 隐式 defaultModalityState 的尾随 lambda 形态——规则看不见它们等于空绿。
        // 用合成源码钉住三态：违规必报 / 过闸放行 / 带理由标注放行（豁免不得变成「一律放行」）。
        val violating = listOf(
            "src/main/kotlin/x/Bad.kt" to """
                class Bad {
                    fun go() {
                        ApplicationManager.getApplication().invokeLater {
                            editor.document.setText("boom")
                        }
                    }
                }
            """.trimIndent(),
        )
        val found = defaultModalityWriteOffenders(violating)
        assertEquals(
            "尾随 lambda（隐式 defaultModalityState）里写文档必须被新规则命中，实际命中 ${found.size} 处：$found",
            1,
            found.size,
        )

        val gated = listOf(
            "src/main/kotlin/x/Gated.kt" to """
                class Gated {
                    fun go() {
                        app.invokeLater {
                            DocWriteContext.runWriteSafe(project, "reason") { editor.document.setText("ok") }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "写体已过 DocWriteContext 闸门的站点不得误判（闸门感知豁免）：${defaultModalityWriteOffenders(gated)}",
            0,
            defaultModalityWriteOffenders(gated).size,
        )

        val annotated = listOf(
            "src/main/kotlin/x/Annotated.kt" to """
                class Annotated {
                    fun go() {
                        // write-safe: 用户输入触发的补全前置提交（PSI 同步，不改文档文本）
                        app.invokeLater {
                            PsiDocumentManager.getInstance(project).commitDocument(doc)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// write-safe: <理由>` 标注的站点应豁免：${defaultModalityWriteOffenders(annotated)}",
            0,
            defaultModalityWriteOffenders(annotated).size,
        )
    }

    @Test
    fun `禁止在默认模态尾随lambda里做文档写`() {
        // T-TS-04：`invokeLater { … }`（省略模态实参）取 `ModalityState.defaultModalityState()`，
        // 平台侧 `defaultModalityImpl()` 依次取 ProgressManager.currentProgressModality → 线程/协程
        // 上下文 modality → NON_MODAL（DataSourceSelector T-FixWriteSafe 已逐帧实证）：**常态**落
        // NON_MODAL 恒 write-safe，但**在模态进度/带 modality 上下文里**取到该模态——若该模态是
        // 「写不安全进入」的，排队的 runnable 就在该模态事件泵内以 write-unsafe 身份执行，改文档
        // 即 `Write-unsafe context!`。故写入类回调只有两条合规出路：写体过 DocWriteContext 闸门，
        // 或调用行上方带理由的 `// write-safe:` 标注（用户输入活动内创建的拍属后者）。
        //
        // T-FS-06 补口：显式 `invokeLater({ … }, ModalityState.defaultModalityState())` 与省略实参的
        // 尾随 lambda 是**同一语义**（同一份 defaultModalityImpl 取值链），此前只认后者。
        val offenders = defaultModalityWriteOffenders(rawMainSources())
        assertTrue(
            "在默认模态（defaultModalityState）排出的回调里做文档写，命中 ${offenders.size} 处：$offenders。" +
                "写体过 DocWriteContext.runWriteSafe / invokeLaterWriteSafe 闸门，或对纯 UI/用户输入触发的站点" +
                "加 `// write-safe: <理由>` 标注",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `TFS06自证_显式模态实参写法必报且配平不越界`() {
        // ---- 缺口一：显式默认模态 + 括号内 lambda 此前两条规则都不覆盖 ----
        // 旧默认模态规则要求 `invokeLater` 后紧跟 `{`、any() 规则只扫含 any() 的实参区，于是
        // `invokeLater({ … }, ModalityState.defaultModalityState())` 整片漏网（当前仓内 0 处，属预防性
        // 守卫——必须用合成样本把「新规则看得见它」钉住，否则将来落地即静默）。
        // 样本一律用**可编译**的真实写法（平台无 `(ModalityState, Runnable)` 重载，见类 KDoc）。
        val anyArgLambda = listOf(
            "src/main/kotlin/x/ChainedAny.kt" to """
                class ChainedAny {
                    fun go(doc: Document) {
                        ApplicationManager.getApplication().invokeLater({ doc.setText("boom") }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "any() 实参的写文档站点必须被 any() 规则命中（链式前缀 ApplicationManager.getApplication(). " +
                "一并覆盖）：${anyModalityWriteOffenders(anyArgLambda)}",
            1,
            anyModalityWriteOffenders(anyArgLambda).size,
        )
        assertEquals(
            "同一站点归 any() 规则，不得被默认模态规则重复计入：" +
                "${defaultModalityWriteOffenders(anyArgLambda)}",
            0,
            defaultModalityWriteOffenders(anyArgLambda).size,
        )

        val nonModalArg = listOf(
            "src/main/kotlin/x/NonModalArg.kt" to """
                class NonModalArg {
                    fun go(doc: Document) {
                        app.invokeLater({ doc.setText("ok") }, ModalityState.nonModal())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "nonModal() 实参恒 write-safe，两条规则都不得报：" +
                "${anyModalityWriteOffenders(nonModalArg) + defaultModalityWriteOffenders(nonModalArg)}",
            0,
            anyModalityWriteOffenders(nonModalArg).size + defaultModalityWriteOffenders(nonModalArg).size,
        )

        // 显式 defaultModalityState 实参 = 与省略实参的尾随 lambda 同一语义（T-FS-06 顺带补口）
        val explicitDefault = listOf(
            "src/main/kotlin/x/ExplicitDefault.kt" to """
                class ExplicitDefault {
                    fun go(doc: Document) {
                        app.invokeLater({ doc.setText("boom") }, ModalityState.defaultModalityState())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "显式 defaultModalityState 实参 + 尾随 lambda 必须被默认模态规则命中：" +
                "${defaultModalityWriteOffenders(explicitDefault)}",
            1,
            defaultModalityWriteOffenders(explicitDefault).size,
        )

        // ---- 缺口二：配平必须先剥注释/涂白字符串，否则过报（body 越界）或漏报（body 被截断）----
        val unbalancedOpenInComment = listOf(
            "src/main/kotlin/x/UnbalancedOpen.kt" to """
                class UnbalancedOpen {
                    fun go(doc: Document) {
                        app.invokeLater {
                            // 注释里的不成对左花括号 {
                            touchUi()
                        }
                        WriteAction.compute<Unit, Throwable> { doc.setText("outside") }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "lambda 体注释里不成对 `{` 不得让 body 越过 lambda 边界（否则 lambda 外 WriteAction 里的" +
                "合法写被过报——实测原状即红）：${defaultModalityWriteOffenders(unbalancedOpenInComment)}",
            0,
            defaultModalityWriteOffenders(unbalancedOpenInComment).size,
        )

        val unbalancedCloseInComment = listOf(
            "src/main/kotlin/x/UnbalancedClose.kt" to """
                class UnbalancedClose {
                    fun go(doc: Document) {
                        app.invokeLater {
                            // 注释里的不成对右花括号 }
                            doc.setText("boom")
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "注释里不成对 `}` 不得提前截断 body 而漏掉其后的真违规：" +
                "${defaultModalityWriteOffenders(unbalancedCloseInComment)}",
            1,
            defaultModalityWriteOffenders(unbalancedCloseInComment).size,
        )

        val braceInString = listOf(
            "src/main/kotlin/x/BraceInString.kt" to """
                class BraceInString {
                    fun go(doc: Document) {
                        app.invokeLater {
                            val s = "} 字符串里的花括号不算花括号"
                            doc.setText(s)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "字符串字面量里的 `}` 不得提前截断 body（同类错配源，一并涂白）：" +
                "${defaultModalityWriteOffenders(braceInString)}",
            1,
            defaultModalityWriteOffenders(braceInString).size,
        )

        // ---- 字符串模板 ${…} 内嵌字符串（T-FS-06 独立复核阻断项）：掩码器 in-string 状态不得翻转 ----
        // 反例原状：遇首个未转义 `"` 即结束外层字符串 → 正文被当代码、模板内代码被涂白 → 后续站点的
        // 实参区被正文里的 `)` 提前配平到 0（实测 execute/DataSourceSelector.kt:1009 实参区被截断到
        // 9414/13999 字符而不再含 ModalityState.any()、:2684 整条站点被丢弃）。
        //
        // 样本形态（`9f8607b` 复核实测校准）：**模板必须跨行、且其后正文里带一个 `)`** 才能区分修复前后。
        // 单行简单形态（如 `"a${b("c")}d"`）**不能**钉住——掩码输出虽然不同，但误判在同一行内自行
        // 重新同步、括号得失对称，两版判定结果一致（`9f8607b` 提交报文曾误称该形态「修复前必红」，
        // 经复核实测更正）。本形态 pre-fix 实测 any 规则漏报（`[]`）、post-fix 报 1 处。
        val templateNestedString = listOf(
            "src/main/kotlin/x/TemplateNested.kt" to """
                class TemplateNested {
                    fun go(doc: Document) {
                        app.invokeLater({
                            LOG.info("a=${'$'}{
                                f("x")
                            }) b)")
                            doc.setText("z")
                        }, ModalityState.any())
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "模板表达式内嵌字符串不得翻转掩码器状态（否则其后带 any() 实参的站点会脱离覆盖）：" +
                "${anyModalityWriteOffenders(templateNestedString)}",
            1,
            anyModalityWriteOffenders(templateNestedString).size,
        )
        assertEquals(
            "该站点归 any() 规则，不得被默认模态规则重复计入：" +
                "${defaultModalityWriteOffenders(templateNestedString)}",
            0,
            defaultModalityWriteOffenders(templateNestedString).size,
        )

        // ---- 工具函数自证：stripComments 不保行 / maskNonCode 保行（行号错位=豁免标注认错行）----
        val multilineBlockComment = "a\n/* x\ny */\nb\nc\n"
        assertEquals(
            "stripComments 把多行 block 注释整段删除（含其中的换行）→ 其后行号前移，不可用于回推行号",
            2,
            stripComments(multilineBlockComment).lines().indexOfFirst { it.trim() == "b" },
        )
        assertEquals(
            "maskNonCode 逐行保行（注释涂白、换行保留）——配平提取的行号才能回喂 leadingCommentBlock",
            3,
            maskNonCode(multilineBlockComment).lines().indexOfFirst { it.trim() == "b" },
        )
        assertEquals(
            "maskNonCode 不增删行（原文 5 行 + 末尾空串 = 6 项）",
            multilineBlockComment.lines().size,
            maskNonCode(multilineBlockComment).lines().size,
        )
    }

    @Test
    fun `catch_Throwable块必须判fatal或登记豁免`() {
        // T-FatalPassthrough T-FP-03：JVM 级致命错误（VirtualMachineError / LinkageError /
        // ThreadDeath）被 catch (…: Throwable) 吞成一条 warn 后，崩溃现场只剩一行日志、流程带病
        // 继续（第八轮报告 §7.2 Z-21）。本规则要求每个 `catch (…: Throwable)` 块**要么**判 fatal
        // （形态 A：块内 `FatalErrors.isFatal(`，见 util/FatalErrors.kt），**要么**登记豁免理由。
        // 未收口的 42 处（16 文件）豁免见 [throwableCatchExemptions]；已落地的 33 处（5 文件）见
        // [landedFatalPassthroughFiles]，其中任何一处退回无判定即红。
        val sources = rawMainSources()
        val offenders = fatalPassthroughOffenders(sources)
        assertTrue(
            "catch (…: Throwable) 块未判 fatal 且未登记豁免，命中 ${offenders.size} 处：$offenders。" +
                "块内加 `if (FatalErrors.isFatal(t)) throw t`（反射链先解包 InvocationTargetException），" +
                "确属「失败即降级」语义的在 [throwableCatchExemptions] 登记文件 + 站点数 + 理由",
            offenders.isEmpty(),
        )

        // 已落地文件：不得被豁免掩盖，且确实有站点、有判定（防「加白名单把落地缺失藏掉」）
        for (path in landedFatalPassthroughFiles) {
            val code = sources.firstOrNull { it.first == path }?.second
            assertTrue("已落地文件不在扫描面内（路径写错？）：$path", code != null)
            assertTrue("已落地文件不得出现在豁免表中：$path", !throwableCatchExemptions.containsKey(path))
            val sites = throwableCatchSites(maskNonCode(code!!))
            assertTrue("已落地文件应含 catch (…: Throwable) 站点：$path", sites.isNotEmpty())
            assertTrue(
                "已落地文件的站点应全部带 fatal 判定：$path",
                sites.all { it.body.contains(fatalCheckToken) },
            )
        }

        // 反射解包锚点（原生执行链）：Method.invoke 把目标异常（含 Error）包进
        // InvocationTargetException（普通异常），只判外层会漏判——须对解包后的 cause 判定。
        val nativeRunChain = maskNonCode(
            sources.first { it.first.endsWith("execute/DataSourceSelector.kt") }.second,
        )
        val unwrapSites = throwableCatchSites(nativeRunChain).filter { it.body.contains("InvocationTargetException") }
        assertTrue("原生执行链的反射捕获块应存在（含 InvocationTargetException 解包）", unwrapSites.isNotEmpty())
        assertTrue(
            "反射捕获块须对解包后的 cause 判 fatal（`FatalErrors.isFatal(cause)`）——" +
                "只判外层 InvocationTargetException 会漏判目标方法抛出的 fatal",
            unwrapSites.all { it.body.contains("FatalErrors.isFatal(cause)") },
        )

        // 豁免表保鲜：路径必须在扫描面内、站点数与登记一致、理由非空
        for ((path, exemption) in throwableCatchExemptions) {
            val code = sources.firstOrNull { it.first == path }?.second
            assertTrue("豁免表条目指向不存在的源文件（改名/删除后未同步）：$path", code != null)
            val actual = throwableCatchSites(maskNonCode(code!!)).size
            assertEquals(
                "豁免文件的 catch (…: Throwable) 站点数与登记不符（新增/删除捕获点须补判定或重新登记理由）：$path",
                exemption.sites,
                actual,
            )
            assertTrue("豁免理由不得为空：$path", exemption.reason.isNotBlank())
        }
    }

    @Test
    fun `TFP03自证_无判定必红_有判定与豁免放行`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：三态 + 边界。
        val violating = listOf(
            "src/main/kotlin/x/NoCheck.kt" to """
                class NoCheck {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            LOG.warn("swallowed", t)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "无判定的 catch (…: Throwable) 必须命中：${fatalPassthroughOffenders(violating)}",
            1,
            fatalPassthroughOffenders(violating).size,
        )

        val checked = listOf(
            "src/main/kotlin/x/Checked.kt" to """
                class Checked {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            if (FatalErrors.isFatal(t)) throw t
                            LOG.warn("degraded", t)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "已判 fatal 的站点不得误报：${fatalPassthroughOffenders(checked)}",
            0,
            fatalPassthroughOffenders(checked).size,
        )

        // 嵌套块：外层判了不代表内层判了（内层独立成站点）
        val nestedInnerUnchecked = listOf(
            "src/main/kotlin/x/Nested.kt" to """
                class Nested {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            if (FatalErrors.isFatal(t)) throw t
                            try {
                                alsoRisky()
                            } catch (e: Throwable) {
                                LOG.warn("inner swallowed", e)
                            }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "嵌套 catch 的内层块须独立判定（外层判定不得替内层豁免）：${fatalPassthroughOffenders(nestedInnerUnchecked)}",
            1,
            fatalPassthroughOffenders(nestedInnerUnchecked).size,
        )

        // 注释里的判定字样不算判定（令牌只看代码）；注释里的花括号不得截断块体
        val tokenOnlyInComment = listOf(
            "src/main/kotlin/x/CommentOnly.kt" to """
                class CommentOnly {
                    fun go() {
                        try {
                            risky()
                        } catch (t: Throwable) {
                            // FatalErrors.isFatal(t) 只是注释，不算判定 } {
                            LOG.warn("swallowed", t)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "注释里的 isFatal 字样不算判定，且注释里的花括号不得截断块体：${fatalPassthroughOffenders(tokenOnlyInComment)}",
            1,
            fatalPassthroughOffenders(tokenOnlyInComment).size,
        )

        // `catch (…: Exception)` 不是本规则对象（捕获面不含 Error）
        val exceptionOnly = listOf(
            "src/main/kotlin/x/ExceptionOnly.kt" to """
                class ExceptionOnly {
                    fun go() {
                        try {
                            risky()
                        } catch (e: Exception) {
                            LOG.warn("degraded", e)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "catch (…: Exception) 捕获面不含 Error，不属本规则：${fatalPassthroughOffenders(exceptionOnly)}",
            0,
            fatalPassthroughOffenders(exceptionOnly).size,
        )

        // Java 模板字符串里的 `catch (Throwable t)`（注入 agent 的源码模板）被涂白，不是 Kotlin 站点
        val javaTemplateInString = listOf(
            "src/main/kotlin/x/JavaTemplate.kt" to """
                object JavaTemplate {
                    val SOURCE = ""${'"'}
                        try { run(); } catch (Throwable t) { log(t); }
                    ""${'"'}
                }
            """.trimIndent(),
        )
        assertEquals(
            "字符串里的 Java catch 写法不得被当站点：${fatalPassthroughOffenders(javaTemplateInString)}",
            0,
            fatalPassthroughOffenders(javaTemplateInString).size,
        )
    }

    @Test
    fun `池化线程内读PSI须走两段式快照校验或登记豁免`() {
        // T-PsiCaptureProtocol T-PCP-04：EDT 捕获 PsiElement → `executeOnPooledThread` 内读该元素
        // （containingFile / viewProvider.document / textRange）这条链，捕获与读取之间的写操作会让元素
        // 失效——只包 ReadAction 只解决「读权限」，不解决「元素是否仍有效」。本规则要求这类读取
        // 走 T-PCP-01 的 PsiSnapshot 校验（或 `psi-capture:` 标注 / 豁免登记）。
        // 落地形态：两个 gutter provider 各抽一个校验收口助手（`validatedRecentAnchor` /
        // `validatedMethodRows`，池化块只调它），`actions/ConvertToSqlAction.kt` 4 处登记豁免。
        val sources = rawMainSources()
        val offenders = psiCaptureOffenders(sources)
        assertTrue(
            "池化线程内读捕获的 PSI 却无快照校验，命中 ${offenders.size} 处：$offenders。" +
                "改走 PsiSnapshot.capture（EDT 侧）+ validate()（后台侧），或对确属「文件级捕获 + 块内重取」" +
                "的站点在 [psiCaptureExemptions] 登记文件 + 站点数 + 理由，或加 `// psi-capture: <理由>` 标注",
            offenders.isEmpty(),
        )

        // 豁免表保鲜：路径在扫描面内、命中站点数与登记一致、理由非空
        for ((path, exemption) in psiCaptureExemptions) {
            val source = sources.firstOrNull { it.first == path }
            assertTrue("豁免表条目指向不存在的源文件（改名/删除后未同步）：$path", source != null)
            val actual = psiCaptureOffenders(listOf(source!!), skipExemptions = false).size
            assertEquals(
                "豁免文件的命中站点数与登记不符（新增/删除跨线程 PSI 读须补校验或重新登记理由）：$path",
                exemption.sites,
                actual,
            )
            assertTrue("豁免理由不得为空：$path", exemption.reason.isNotBlank())
        }
    }

    @Test
    fun `TPCP04自证_池化块读PSI无校验必红_三种放行形态`() {
        // 负向自证（规则「看不见某形态」= 空绿，只能靠合成样本钉住）：违规必报 + 校验/标注/豁免三态放行。
        val violating = listOf(
            "src/main/kotlin/x/BgRead.kt" to """
                class BgRead {
                    fun go(elt: PsiElement) {
                        app.executeOnPooledThread {
                            ReadAction.compute<Int, Throwable> { elt.containingFile.textRange.startOffset }
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "池化块内读捕获元素的 PSI 属性必须命中：${psiCaptureOffenders(violating)}",
            1,
            psiCaptureOffenders(violating).size,
        )

        val validated = listOf(
            "src/main/kotlin/x/Validated.kt" to """
                class Validated {
                    fun go(elt: PsiElement, project: Project, snapshot: PsiSnapshot?) {
                        app.executeOnPooledThread {
                            val anchor = ReadAction.compute<Pair<String, Int>?, Throwable> { validatedRecentAnchor(project.basePath, snapshot) }
                            log(anchor.toString() + elt.containingFile.name)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "已走校验收口助手的站点不得误报：${psiCaptureOffenders(validated)}",
            0,
            psiCaptureOffenders(validated).size,
        )

        val inlineSnapshotCheck = listOf(
            "src/main/kotlin/x/InlineCheck.kt" to """
                class InlineCheck {
                    fun go(elt: PsiElement, snapshot: PsiSnapshot?) {
                        app.executeOnPooledThread {
                            val valid = snapshot != null && snapshot.validate() is PsiSnapshot.Validity.Valid
                            if (valid) elt.containingFile.textRange.startOffset else 0
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "块内直接用 PsiSnapshot 判定的站点不得误报：${psiCaptureOffenders(inlineSnapshotCheck)}",
            0,
            psiCaptureOffenders(inlineSnapshotCheck).size,
        )

        val annotated = listOf(
            "src/main/kotlin/x/Annotated.kt" to """
                class Annotated {
                    fun go(elt: PsiElement) {
                        // psi-capture: 该元素在块内重新解析（非 EDT 捕获），失效面由平台 reparse 承担
                        app.executeOnPooledThread {
                            elt.containingFile.textRange.startOffset
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "带 `// psi-capture: <理由>` 标注的站点应豁免：${psiCaptureOffenders(annotated)}",
            0,
            psiCaptureOffenders(annotated).size,
        )

        // 豁免表生效（用真实豁免路径喂合成样本，证明表被真正查过而不是白名单空转）
        val exemptedPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt"
        val exempted = listOf(
            exemptedPath to """
                class Exempted {
                    fun go(tag: XmlTag) {
                        app.executeOnPooledThread {
                            tag.containingFile.viewProvider.document?.getLineNumber(tag.textRange.startOffset)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "豁免路径的命中站点须被计数（否则豁免表空转、漂移检测失效）：" +
                "${psiCaptureOffenders(exempted, skipExemptions = false)}",
            1,
            psiCaptureOffenders(exempted, skipExemptions = false).size,
        )
        assertEquals(
            "豁免表内的路径不得报违规：${psiCaptureOffenders(exempted)}",
            0,
            psiCaptureOffenders(exempted).size,
        )

        // 池化块内不读 PSI（纯数据/纯 IO）不得命中
        val noPsiRead = listOf(
            "src/main/kotlin/x/NoPsi.kt" to """
                class NoPsi {
                    fun go(path: String) {
                        app.executeOnPooledThread {
                            val text = java.io.File(path).readText()
                            log(text)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "池化块内无 PSI 读不得命中：${psiCaptureOffenders(noPsiRead)}",
            0,
            psiCaptureOffenders(noPsiRead).size,
        )

        // 工具函数自证：池化站点提取覆盖「尾随 lambda」与「括号实参 + 尾随 lambda」两种写法
        val sites = pooledThreadSites(maskNonCode(violating[0].second))
        assertEquals("executeOnPooledThread 尾随 lambda 形态应被提取", 1, sites.size)
        assertEquals("提取行号须与原文对齐（保行掩码）", 3, sites[0].line)
    }

    @Test
    fun `跳转族调用点须持读权限_后台调用须包ReadAction`() {
        // T-PCP-03：`EntryJumpResolver`/`SourcePathResolver` 一族的 KDoc 约定（「调用方保证持有读权限」）
        // 原先无工具保障——一旦被后台线程裸调，读 ModuleManager 的违约只在真机偶发。本规则把约定固化为
        // 「文件内出现 ReadAction（自包权限）或在 EDT 调用者白名单内」；配套运行期硬断言见下一段。
        val offenders = edtReadContractOffenders(mainSources())
        assertTrue(
            "跳转族调用点缺读权限（文件内无 ReadAction 且不在 EDT 白名单），命中 ${offenders.size} 处：$offenders。" +
                "后台调用须显式包 ReadAction，EDT 调用点加入 [jumpFamilyReadAllowedFiles] 并注明依据",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `TEDTRC自证_跳转族调用点无ReadAction必红_白名单与自包权限放行`() {
        // 独立复核㉗：`edtReadContractOffenders` 此前**只有主用例直接引用、无合成样本**（被元守卫登记为
        // 「受机器保护的豁免」——缺口因此不可见）。此处关闭该缺口：三态各归其位。
        val violating = listOf(
            "src/main/kotlin/cn/x/BadJump.kt" to """
                class BadJump {
                    fun go() {
                        SourcePathResolver.resolveVirtualFile(project, module)
                    }
                }
            """.trimIndent(),
        )
        assertEquals(
            "文件内无 ReadAction 的跳转族调用点必须被命中：${edtReadContractOffenders(violating)}",
            listOf("src/main/kotlin/cn/x/BadJump.kt"),
            edtReadContractOffenders(violating),
        )

        val wrapped = listOf(
            "src/main/kotlin/cn/x/WrappedJump.kt" to """
                class WrappedJump {
                    fun go() {
                        ReadAction.compute<Unit, Throwable> {
                            EntryJumpResolver.resolve(project, element)
                        }
                    }
                }
            """.trimIndent(),
        )
        assertTrue(
            "自包 ReadAction 的调用点应放行：${edtReadContractOffenders(wrapped)}",
            edtReadContractOffenders(wrapped).isEmpty(),
        )

        val whitelisted = listOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt" to """
                class SqlLogPanel {
                    fun go() {
                        EntryJumpResolver.resolveCandidates(project, element)
                    }
                }
            """.trimIndent(),
        )
        assertTrue(
            "EDT 白名单文件应放行（白名单不得失效，否则主用例会因过报而红）：${edtReadContractOffenders(whitelisted)}",
            edtReadContractOffenders(whitelisted).isEmpty(),
        )
    }

    @Test
    fun `跳转族读权限运行期断言锚点`() {
        // 运行期硬断言锚点（T-PCP-03）：`SourcePathResolver.resolveVirtualFile` 入口必须保留
        // `assertReadAccessAllowed()`——源码级规则只管「调用点有没有 ReadAction 字样」，
        // 断言把违约暴露在实际调用链上（后台裸调即抛，不静默读模型）。
        val code = stripComments(
            repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/log/EntrySourceMatcher.kt").readText(),
        )
        assertTrue(
            "SourcePathResolver.resolveVirtualFile 丢失 assertReadAccessAllowed() 入口断言——" +
                "跳转族的读权限约定退回「只写在 KDoc 里」的靠约定状态（T-PCP-03）",
            code.contains("assertReadAccessAllowed()"),
        )
    }
}
