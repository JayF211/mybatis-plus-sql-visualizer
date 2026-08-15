package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * 插件目录守卫测试（T-M28.1 跨平台加固）：
 * `isUnderPluginsDir` 决定更新时是否调度卸载旧版——误拒会导致更新失败
 * （用户要求 Win/Mac 目录差异不得影响更新）。
 * 覆盖：null/空白拒绝、插件目录内（真实文件 toRealPath 路径 + 未创建子路径兜底路径）、
 * 目录外拒绝；大小写/符号链接差异由 Path 组件比较 + toRealPath 保证（Windows 组件
 * 比较大小写不敏感、Mac 解析后两侧均为真实路径）。
 */
class PlatformPluginInstallerTest : BasePlatformTestCase() {

    fun testBlankDescriptorRejected() {
        assertFalse(PlatformPluginInstaller.isUnderPluginsDir(null))
        assertFalse(PlatformPluginInstaller.isUnderPluginsDir(""))
        assertFalse(PlatformPluginInstaller.isUnderPluginsDir("   "))
    }

    fun testPathInsidePluginsDirAccepted() {
        val root = PathManager.getPluginsDir()
        // 真实文件（toRealPath 分支）
        val real = Files.createTempFile(root, "guard-check-", ".tmp")
        try {
            assertTrue(PlatformPluginInstaller.isUnderPluginsDir(real.toString()))
        } finally {
            Files.deleteIfExists(real)
        }
        // 未创建子路径（兜底字符串比较分支——插件安装目录形态 `<plugins>/<id>/META-INF/plugin.xml`）
        val virtual = root.resolve("cn.song.test").resolve("META-INF").resolve("plugin.xml").toString()
        assertTrue(PlatformPluginInstaller.isUnderPluginsDir(virtual))
    }

    fun testPathOutsidePluginsDirRejected() {
        // 系统临时目录（真实存在，toRealPath 分支）
        val outside = Files.createTempDirectory("guard-outside-")
        try {
            assertFalse(PlatformPluginInstaller.isUnderPluginsDir(outside.toString()))
            // 不存在路径（兜底分支）
            assertFalse(PlatformPluginInstaller.isUnderPluginsDir(Path.of(outside.toString(), "no-such-plugin").toString()))
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    fun testJarPrefixedDescriptorHandled() {
        // zip 形态 descriptorPath 带 jar:file:/! 前缀：剥离后按插件目录判定
        val root = PathManager.getPluginsDir().resolve("cn.song.test").toString()
        assertTrue(PlatformPluginInstaller.isUnderPluginsDir("jar:file:$root.zip!/META-INF/plugin.xml"))
        assertTrue(PlatformPluginInstaller.isUnderPluginsDir("$root.zip!/META-INF/plugin.xml"))
    }
}
