package cn.song.mybatisplus.visualizer.reverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

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

    /** 与 O147 同源策略：PathClassLoader 的 CodeSource 可为 null，资源 URL 才是可靠后备。
     * 不猜缓存版本；候选 JAR 必须含有与当前类资源逐字节一致的真实 class。 */
    private fun locateArtifact(anchor: Class<*>): File {
        val entry = anchor.name.replace('.', '/') + ".class"
        val resource = requireNotNull(anchor.getResource("/$entry")) { "Missing class resource: $entry" }
        val candidates = linkedSetOf<File>()
        fun candidate(url: URL?) {
            if (url?.protocol == "file") {
                runCatching { File(url.toURI()).canonicalFile }.getOrNull()?.let(candidates::add)
            }
        }
        candidate(runCatching { anchor.protectionDomain?.codeSource?.location }.getOrNull())
        val connection = resource.openConnection().apply { useCaches = false }
        (connection as? JarURLConnection)?.let { candidate(it.jarFileURL) }
        if (resource.protocol == "jar") {
            val archiveUrl = resource.toExternalForm().substringAfter("jar:").substringBefore("!/")
            candidate(URL(archiveUrl))
        }
        val expected = connection.getInputStream().use { it.readBytes() }
        return candidates.firstOrNull { file ->
            file.isFile && runCatching {
                JarFile(file).use { jar ->
                    val classEntry = jar.getJarEntry(entry) ?: return@use false
                    jar.getInputStream(classEntry).use { it.readBytes().contentEquals(expected) }
                }
            }.getOrDefault(false)
        } ?: error("Verified artifact unavailable: $entry; loader=${anchor.classLoader}; resource=$resource; candidates=$candidates")
    }

    /** JBR 不保证有 javac；与 O147 一样检查实际安装，不依赖 IDE loader 的 ToolProvider。 */
    private fun locateJavac(): File {
        val suffix = if (File.separatorChar == '\\') ".exe" else ""
        fun compiler(home: File): File? {
            val release = File(home, "release")
            val version = if (release.isFile) Regex("JAVA_VERSION=\"(\\d+)").find(release.readText())
                ?.groupValues?.get(1)?.toIntOrNull() else null
            return File(home, "bin/javac$suffix").takeIf {
                it.isFile && File(home, "bin/java$suffix").isFile && version != null && version >= 17
            }?.canonicalFile
        }
        System.getProperty("mpsv.reverse.jdkHome")?.let { explicit ->
            require(explicit.isNotBlank()) { "Empty mpsv.reverse.jdkHome" }
            return compiler(File(explicit)) ?: error("JDK17+ java/javac unavailable at explicit home: $explicit")
        }
        val configured = listOfNotNull(System.getProperty("java.home"), System.getenv("JAVA_HOME"))
            .filter { it.isNotBlank() }.map(::File)
        val installed = File(System.getProperty("user.home"), ".jdks").listFiles()
            ?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        return (configured + installed).firstNotNullOfOrNull(::compiler)
            ?: error("Verified JDK17+ java/javac unavailable; set mpsv.reverse.jdkHome; checked=${configured + installed}")
    }

    private fun compile(code: String, check: (Int, String, File, List<File>) -> Unit) {
        // 可固定到本机 3.5.7；默认使用项目测试依赖中的真实 MP（不是替身 API）。
        val anchors = listOf(
            "com.baomidou.mybatisplus.core.conditions.Wrapper",
            "com.baomidou.mybatisplus.annotation.TableName",
            "org.apache.ibatis.session.Configuration",
        )
        val classpath = System.getProperty("mpsv.reverse.mpClasspath")?.let { explicit ->
            explicit.split(File.pathSeparator).map { path ->
                require(path.isNotBlank()) { "Empty entry in mpsv.reverse.mpClasspath" }
                File(path).canonicalFile.also { require(it.isFile) { "Missing explicit artifact: $it" } }
            }.also { files ->
                anchors.forEach { name ->
                    val entry = name.replace('.', '/') + ".class"
                    check(files.any { file -> JarFile(file).use { it.getJarEntry(entry) != null } }) {
                        "Explicit MP classpath does not contain $entry: $files"
                    }
                }
            }
        } ?: anchors.map { locateArtifact(Class.forName(it)) }
        val javac = locateJavac()
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
            val process = ProcessBuilder(javac.absolutePath, "-J-Duser.language=en", "--release", "17", "-encoding", "UTF-8", "-proc:none",
                "-classpath", classpath.joinToString(File.pathSeparator) { it.absolutePath },
                "-d", root.absolutePath, source.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "javac timed out; output: $log" }
            check(process.exitValue(), log.readText(), root, classpath)
        } finally {
            root.deleteRecursively()
        }
    }
}
