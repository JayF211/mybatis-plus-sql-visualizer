package cn.song.mybatisplus.visualizer.reverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 编译生产生成的 Java，并在真实 MP 上检查最终投影；不以文本匹配替代 API/覆盖语义验证。 */
internal object GeneratedProjectionCompiler {
    fun assertProjection(code: String, projection: String, vararg segments: String) {
        compile(code) { exit, diagnostics, output, classpath ->
            assertEquals("Generated Java must compile:\n$code\n$diagnostics", 0, exit)
            URLClassLoader((listOf(output) + classpath).map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val actual = loader.loadClass("GeneratedProjection").getMethod("inspect").invoke(null) as Array<*>
                assertEquals(code, projection, actual[0])
                segments.forEach { assertTrue("$code\n${actual[1]}", actual[1].toString().contains(it)) }
            }
        }
    }

    fun assertDoesNotCompile(code: String) {
        compile(code) { exit, diagnostics, _, _ ->
            assertTrue("Expected an API compilation error:\n$code\n$diagnostics", exit != 0)
            assertTrue(diagnostics, diagnostics.contains("error:"))
        }
    }

    private fun compile(code: String, check: (Int, String, File, List<File>) -> Unit) {
        // 可固定到本机 3.5.7；默认使用项目测试依赖中的真实 MP（不是替身 API）。
        val classpath = System.getProperty("mpsv.reverse.mpClasspath")?.split(File.pathSeparator)?.map(::File)
            ?: listOf(
                "com.baomidou.mybatisplus.core.conditions.Wrapper",
                "com.baomidou.mybatisplus.annotation.TableName",
                "org.apache.ibatis.session.Configuration",
            ).map { File(Class.forName(it).protectionDomain.codeSource.location.toURI()) }
        val home = File(System.getProperty("mpsv.reverse.jdkHome", System.getProperty("java.home")))
        val javac = File(home, "bin/javac${if (File.separatorChar == '\\') ".exe" else ""}")
        check(javac.isFile) { "A JDK with javac is required: $javac (set mpsv.reverse.jdkHome)" }
        val root = Files.createTempDirectory("mpsv-projection-").toFile()
        try {
            val source = File(root, "GeneratedProjection.java")
            source.writeText("""
                import com.baomidou.mybatisplus.core.conditions.Wrapper;
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
                import com.baomidou.mybatisplus.core.toolkit.Wrappers;
                public class GeneratedProjection {
                    public static class User {
                        public Long getId() { return 1L; }
                        public Integer getStatus() { return 1; }
                        public String getName() { return "x"; }
                    }
                    public static class UiProbe extends User {}
                    public static String[] inspect() {
                        Wrapper<?> wrapper =
                        $code
                        return new String[] {wrapper.getSqlSelect(), wrapper.getSqlSegment()};
                    }
                }
            """.trimIndent())
            val log = File(root, "javac.log")
            val process = ProcessBuilder(javac.absolutePath, "-J-Duser.language=en", "-encoding", "UTF-8", "-proc:none",
                "-classpath", classpath.joinToString(File.pathSeparator) { it.absolutePath },
                "-d", root.absolutePath, source.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "javac timed out; output: $log" }
            check(process.exitValue(), log.readText(), root, classpath)
        } finally {
            root.deleteRecursively()
        }
    }
}
