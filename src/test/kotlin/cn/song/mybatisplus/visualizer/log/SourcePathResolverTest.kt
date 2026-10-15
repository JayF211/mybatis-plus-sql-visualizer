package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-JumpPath-02：SourcePathResolver 第三级 sourceRoots 策略行为测试。
 *
 * 真机根因（计划 §RC2）：拦截器标记路径是包式相对**源码根**（`cn/song/a/S.java`，
 * 不含 `src/main/java` 段），Maven/Gradle 标准布局下项目根直拼与 content root 直拼
 * 均缺该段 → 解析恒 null → 「源码文件未找到」。旧 SqlLogPanel.findSourceFile 有
 * sourceRoots 语义，T-JumpCapture 删死代码时未收编，本测试锁定收编后三级策略。
 */
class SourcePathResolverTest : BasePlatformTestCase() {

    /** 真实磁盘写文件 + VFS 刷新（LocalFileSystem 解析要求落盘）。 */
    private fun writeRealSource(relativePath: String): File {
        val f = File(project.basePath!!, relativePath)
        f.parentFile?.mkdirs()
        f.writeText("public class S {}\n")
        VfsUtil.markDirtyAndRefresh(false, true, true, f)
        return f
    }

    fun `test包式源码根相对路径_经sourceRoots第三级命中`() {
        val file = writeRealSource("mod/src/main/java/cn/song/demo/S.java")
        val srcRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(
            File(project.basePath!!, "mod/src/main/java").absolutePath,
        )!!
        com.intellij.openapi.application.runWriteAction {
            PsiTestUtil.addSourceRoot(module, srcRoot)
        }
        val resolved = SourcePathResolver.resolveVirtualFile(project, "cn/song/demo/S.java")
        assertNotNull("包式路径应经模块 source root 命中（第三级策略）", resolved)
        assertEquals(file.absolutePath.replace('\\', '/'), resolved!!.path.replace('\\', '/'))
    }

    fun `test项目根相对形态_第一级直拼命中`() {
        writeRealSource("legacy/src/main/java/cn/song/legacy/Old.java")
        val resolved = SourcePathResolver.resolveVirtualFile(
            project, "legacy/src/main/java/cn/song/legacy/Old.java",
        )
        assertNotNull("项目根相对形态应走第一级（basePath 直拼）命中", resolved)
        assertTrue(resolved!!.path.endsWith("Old.java"))
    }

    fun `test全不存在返回null`() {
        assertNull(SourcePathResolver.resolveVirtualFile(project, "cn/song/no/such/File.java"))
        assertTrue(SourcePathResolver.resolveVirtualFiles(project, "cn/song/no/such/File.java").isEmpty())
    }

    fun `test空白路径返回null`() {
        assertNull(SourcePathResolver.resolveVirtualFile(project, "   "))
        assertTrue(SourcePathResolver.resolveVirtualFiles(project, "   ").isEmpty())
    }
}
