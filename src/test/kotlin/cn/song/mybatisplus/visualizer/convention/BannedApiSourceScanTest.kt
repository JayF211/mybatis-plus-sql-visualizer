package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * src/main 禁用 API / 平台约定源码扫描守卫（T-TestGuard-03）。
 *
 * 收录的每条规则都对应一个「编译通过、headless 测试全绿、只有真机/特定发行版才炸」的历史坑，
 * 见 docs/memory-archive/pitfalls/ 与全局平台陷阱清单：
 *  - ActionToolbar 刷新 API（updateActionsAsync/updateActionsImmediately/updateImmediately）：
 *    沙盒中不可靠，刷新必须走 UIUtil.uiTraverser + 反射 setIcon + repaint 路线；
 *  - JBIconButton / SmallIconButton / InlineIconButton / IconUtil：IPGP 发行版裁剪平台类
 *    （编译实证不可用，SqlToJavaPanel/IconScaler 注释即当年事故现场），须自建替代；
 *  - `BrowserUtil.browse(`：不走沙盒隔离直接 ShellExecute 拉起宿主机浏览器
 *    （pitfall-test-sandbox-browser-leak.md，定时回归任务期间周期性弹 Edge）——
 *    含该调用的文件必须自带 isUnitTestMode 短路（SqlLogPanel 帮助降级为先例），
 *    新调用一律走 util.SafeBrowser；
 *  - `createFileFromText(`：EDT 直调触发 262 read access 硬抛，必须处于 ReadAction
 *    保护链路（同文件含 ReadAction 约定，PsiFileFactory 陷阱）。
 *
 * 扫描前剥离 // 与 block 注释：历史事故现场常以注释形态留在代码里（JBIconButton 用法
 * 记录、updateActionsAsync 备注等），不剥会误报。
 *
 * k2j 移植注：生产树为 src/main/java（.java），扫描根与文件特征相应调整，守卫语义不变。
 */
class BannedApiSourceScanTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 block 注释与行注释（字符串字面量中的 // 会被连带截断——只可能产生漏报不产生误报，可接受）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun mainSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(it.readText()) }
            .toList()

    /** 全量源码中「禁用片段」命中清单：List<文件相对路径>。 */
    private fun hits(banned: String): List<String> =
        mainSources().filter { (_, code) -> code.contains(banned) }.map { (path, _) -> path }

    private fun assertNoHits(banned: String, why: String, fix: String) {
        val found = hits(banned)
        assertTrue(
            "src/main 出现禁用 API `$banned`（$why）。命中 ${found.size} 处：$found。$fix",
            found.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 禁用 API（真机/发行版才炸的坑）
    // ------------------------------------------------------------------

    @Test
    fun `禁用ActionToolbar沙盒不可靠刷新API`() {
        for (api in listOf("updateActionsAsync(", "updateActionsImmediately(", "updateImmediately(")) {
            assertNoHits(
                api,
                "沙盒中不可靠，真机表现不可复现",
                "刷新走 UIUtil.uiTraverser + 反射 getAction + setIcon/repaint 路线",
            )
        }
    }

    @Test
    fun `禁用IPGP发行版裁剪平台类`() {
        // 名单出处：SqlToJavaPanel.java / IconScaler.java 注释（编译实证）；发现新裁剪类请追加并留注释
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
}
