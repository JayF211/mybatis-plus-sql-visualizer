package cn.song.mybatisplus.visualizer.update

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

/**
 * 安装守卫测试（T-M28.1/28.3 跨平台）：
 * `isDevInstallation` 决定更新时是否调度卸载旧版——**黑名单式**判定（构建产物特征段），
 * 真实安装任意形态一律放行（白名单式依赖 descriptorPath 精确格式，2026.2 平台 URL 风格
 * 形态多变，用户日常 IDE 两次实证误拒——T-M28.3 二修）。
 * 覆盖：null/空白保守拒绝、开发态特征路径拒绝、真实安装（Win/Mac/URL 风格/jar 前缀）放行、
 * 归一化各形态。
 */
class PlatformPluginInstallerTest : BasePlatformTestCase() {

    fun testBlankDescriptorRejected() {
        // null/空白：2026.2 已安装插件 descriptorPath 实证返回 null——无法判断时**放行**
        // （不得误拒正常安装；开发态防护由非 null 路径特征段承担）
        assertFalse(PlatformPluginInstaller.isDevInstallation(null))
        assertFalse(PlatformPluginInstaller.isDevInstallation(""))
        assertFalse(PlatformPluginInstaller.isDevInstallation("   "))
    }

    fun testDevBuildPathsRejected() {
        // 开发态特征段（build/classes 等）→ 拒绝（卸载调度会误删构建产物）
        assertTrue(PlatformPluginInstaller.isDevInstallation("F:/repo/build/classes/kotlin/main/META-INF/plugin.xml"))
        assertTrue(PlatformPluginInstaller.isDevInstallation("F:/repo/build/classes/java/main/META-INF/plugin.xml"))
        assertTrue(PlatformPluginInstaller.isDevInstallation("F:/repo/out/production/classes/META-INF/plugin.xml"))
        assertTrue(PlatformPluginInstaller.isDevInstallation("C:\\repo\\.gradle\\classes\\META-INF\\plugin.xml"))
    }

    fun testRealInstallationsAccepted() {
        // 真实安装（插件目录任意形态）一律放行——不依赖路径精确格式
        assertFalse(PlatformPluginInstaller.isDevInstallation("C:/Users/me/AppData/Roaming/JetBrains/IntelliJIdea2026.2/plugins/mybatis-plus-sql-visualizer/lib/xxx.jar"))
        assertFalse(PlatformPluginInstaller.isDevInstallation("C:\\Users\\me\\AppData\\Roaming\\JetBrains\\IntelliJIdea2026.2\\plugins\\mybatis-plus-sql-visualizer\\META-INF\\plugin.xml"))
        assertFalse(PlatformPluginInstaller.isDevInstallation("/Users/me/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/mybatis-plus-sql-visualizer/lib/xxx.jar"))
        // URL 风格（2026.2 平台形态）+ jar 前缀
        assertFalse(PlatformPluginInstaller.isDevInstallation("jar:file:/C:/Users/me/plugins/mybatis-plus-sql-visualizer/lib/xxx.jar!/META-INF/plugin.xml"))
        assertFalse(PlatformPluginInstaller.isDevInstallation("JAR:FILE:/Users/me/plugins/a.zip!/META-INF/plugin.xml"))
    }

    // ---------------- T-M28.3 路径归一化（2026.2 descriptorPath URL 风格实证） ----------------

    fun testNormalizeDescriptorPathStripsJarUrlForms() {
        // 2026.2 平台实际形态：jar:file:/C:/…（驱动字母前导斜杠）——T-M28.3 用户实测守卫误拒根因
        assertEquals(
            "C:/Users/me/plugins/a.jar",
            PlatformPluginInstaller.normalizeDescriptorPath("jar:file:/C:/Users/me/plugins/a.jar!/META-INF/plugin.xml"),
        )
        // 大小写前缀
        assertEquals(
            "C:/Users/me/plugins/a.jar",
            PlatformPluginInstaller.normalizeDescriptorPath("JAR:FILE:/C:/Users/me/plugins/a.jar!/META-INF/plugin.xml"),
        )
        // file: 前缀
        assertEquals(
            "C:/Users/me/plugins/a.jar",
            PlatformPluginInstaller.normalizeDescriptorPath("file:/C:/Users/me/plugins/a.jar"),
        )
    }

    fun testNormalizeDescriptorPathKeepsOrdinaryPaths() {
        // Windows 普通路径（反斜杠）不变
        assertEquals("C:\\Users\\me\\plugins\\a.jar", PlatformPluginInstaller.normalizeDescriptorPath("C:\\Users\\me\\plugins\\a.jar"))
        // Mac 绝对路径（无盘符，前导斜杠保留）
        assertEquals("/Users/me/plugins/a.jar", PlatformPluginInstaller.normalizeDescriptorPath("/Users/me/plugins/a.jar"))
        // 目录形态（plugin.xml 直路径 + ! 后缀剥离）
        assertEquals(
            "C:/Users/me/plugins/p/META-INF/plugin.xml",
            PlatformPluginInstaller.normalizeDescriptorPath("C:/Users/me/plugins/p/META-INF/plugin.xml"),
        )
        // 空白与空
        assertEquals("", PlatformPluginInstaller.normalizeDescriptorPath(""))
        assertEquals("x", PlatformPluginInstaller.normalizeDescriptorPath("  x  "))
    }

    // ---------------- T-M28.3 权限加固（Windows 静默跳过不抛） ----------------

    fun testEnsureReadableDoesNotThrowOnWindows() {
        // Windows 无 POSIX API（UnsupportedOperationException 静默跳过）；POSIX 语义由实现保证
        val temp = Files.createTempFile("mpvs-perm-", ".zip")
        try {
            PlatformPluginInstaller.ensureReadable(temp)  // 不抛即通过
            assertTrue(Files.exists(temp))
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    // ---------------- T-M28.5 解压目标（zip 顶层含插件名目录，必须解到插件目录根） ----------------

    fun testUnzipTargetIsPluginsRoot() {
        // 发布 zip 顶层自带插件名目录（IPGP buildPlugin 布局），UnzipCommand 不剥顶层——
        // 目标必须是插件目录根，否则多嵌套一层平台扫描不到（用户实测重启后插件消失）
        assertEquals(
            com.intellij.openapi.application.PathManager.getPluginsDir(),
            PlatformPluginInstaller.unzipTargetDir(),
        )
    }

    // ---------------- getPluginJarPath Windows 路径归一化（2026-08-20 修复） ----------------

    fun testNormalizeForWindowsFilePrefixWithLeadingSlash() {
        // Windows 真实形态：file:/C:/Users/…（uri.schemeSpecificPart 含 file: 前缀 + 驱动字母前导斜杠）——
        // 未归一化时 Path.of("file:/C:/…") 抛 InvalidPathException → getPluginJarPath 返回 null → DeleteCommand 不调度
        assertEquals(
            "C:/Users/USER/plugins/p/lib/xxx.jar",
            PlatformPluginInstaller.normalizeDescriptorPath("file:/C:/Users/USER/plugins/p/lib/xxx.jar"),
        )
    }

    fun testNormalizeForWindowsJarFilePrefixWithInternalPath() {
        // jar:file: 前缀 + 内部路径（substringBefore('!') 后由 normalizeDescriptorPath 处理）
        assertEquals(
            "C:/Users/USER/plugins/p/lib/xxx.jar",
            PlatformPluginInstaller.normalizeDescriptorPath(
                "jar:file:/C:/Users/USER/plugins/p/lib/xxx.jar!/com/example/Class.class"
            ),
        )
    }

    fun testNormalizeForWindowsTripleSlashFilePrefix() {
        // 部分 JVM 返回 file:///C:/…（三斜杠）—— 必须正确剥离为 C:/…
        assertEquals(
            "C:/Users/USER/plugins/p/lib/xxx.jar",
            PlatformPluginInstaller.normalizeDescriptorPath("file:///C:/Users/USER/plugins/p/lib/xxx.jar"),
        )
    }

    fun testNormalizePreservesMacAbsolutePath() {
        // Mac 绝对路径 /Users/… —— 不得剥离前导斜杠
        assertEquals(
            "/Users/me/plugins/p/lib/xxx.jar",
            PlatformPluginInstaller.normalizeDescriptorPath("/Users/me/plugins/p/lib/xxx.jar"),
        )
    }

    // ---------------- pluginDirPath 回退方案（2026-08-20） ----------------

    fun testPluginDirPathUsesPluginsDirAndInstallDirName() {
        // 262 平台 codeSource.location 实证为 null —— 回退到 PathManager.getPluginsDir() / zip 顶层目录名
        // （实测安装目录为短名 mybatis-plus-sql-visualizer，非 <id> FQN）
        val expected = com.intellij.openapi.application.PathManager.getPluginsDir()
            .resolve("mybatis-plus-sql-visualizer")
        assertEquals(expected, PlatformPluginInstaller.pluginDirPath())
    }
}
