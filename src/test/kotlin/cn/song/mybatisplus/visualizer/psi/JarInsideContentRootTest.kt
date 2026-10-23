package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * T-ShadowEntityResolve W4 护栏：**jar 放在模块内容根内**（`src/main/resources/lib/`，system scope 引用）。
 *
 * 用户 20261023 把 jar 放进 `shadow-entity-demo/src/main/resources/lib/` 后仍见降级——需要确认这是不是
 * 第四种未覆盖形态。取证读数（本类断言的就是这些）：
 *
 *  - 该 jar 的类**同时**满足 `isInContent == true` 与 `isInLibraryClasses == true`（内容根与库根身份叠加）；
 *  - 宿主元素因此**拿得到模块上下文**（不像纯库文件那样恒 null）；
 *  - 解析结果仍是**工程源码那份**（候选 1、非歧义）——P0a「工程内容优先」按 FQN 组内收窄，不因 jar 也在
 *    内容根内而误判成「两份都是工程内容」；
 *  - 端到端：从**反编译 PSI**（桩 PSI 无方法体）取调用点，预览产出 `wms_task_project` 且**不降级**。
 *
 * 即该形态已被现有修复覆盖，无需额外改动；本类的作用是防止将来回归。
 */
class JarInsideContentRootTest : HeavyPlatformTestCase() {

    private val dirs = mutableListOf<File>()
    private val roots = mutableMapOf<Module, VirtualFile>()

    override fun setUp() {
        super.setUp()
        EntityClassResolver.clearAmbiguityNotifiedForTest(project)
    }

    override fun tearDown() {
        try {
            EntityClassResolver.ambiguitySinkForTest = null
            EntityClassResolver.clearAmbiguityNotifiedForTest(project)
            roots.clear()
        } finally {
            dirs.forEach { it.deleteRecursively() }
            super.tearDown()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("jar-in-content-").toFile().also(dirs::add)

    private fun externalJavac(): File {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac"
        return listOfNotNull(
            System.getProperty("mpv.entityResolver.javacHome"), System.getenv("JAVA_HOME"),
            System.getProperty("java.home"), "D:/HuanJing/Java/jdk1.8.0_181",
        ).map { File(it, "bin/$exe") }.firstOrNull(File::isFile) ?: error("需要真实外部 JDK")
    }

    /** 造 jar（实体 + DAO + 带 HQL 的 service），解包到模块内容根下的 `resources/lib/classes` 并挂为库根。 */
    private fun jarInsideContentRoot(): PsiClass {
        val work = tempDir()
        val src = File(work, "src").apply { mkdirs() }
        val classes = File(work, "classes").apply { mkdirs() }
        listOf(
            "Entity" to "public @interface Entity { String name() default \"\"; }",
            "Table" to "public @interface Table { String name(); }",
            "Column" to "public @interface Column { String name(); }",
            "Id" to "public @interface Id { }",
        ).forEach { (name, body) ->
            File(src, "javax/persistence/$name.java").apply {
                parentFile.mkdirs()
                writeText("package javax.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) $body")
            }
        }
        File(src, "com/acme/WmsTask.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme;
                @javax.persistence.Entity
                @javax.persistence.Table(name="wms_task_jar")
                public class WmsTask {
                    @javax.persistence.Id public long id;
                    @javax.persistence.Column(name="status") public String status;
                    @javax.persistence.Column(name="type") public String type;
                }
                """.trimIndent(),
            )
        }
        File(src, "com/acme/CommonDao.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme;
                import java.util.List;
                import java.util.Collections;
                public class CommonDao {
                    public List<?> findByQuery(String hql, String[] paramNames, Object[] values) {
                        return Collections.emptyList();
                    }
                }
                """.trimIndent(),
            )
        }
        File(src, "com/acme/WmsTaskJarService.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme;
                import java.util.List;
                public class WmsTaskJarService {
                    private final CommonDao commonDao = new CommonDao();
                    public List<?> beAllocateSuccess() {
                        String hql = "FROM WmsTask task WHERE task.status = :status AND task.type = :type";
                        return this.commonDao.findByQuery(hql, new String[]{"status", "type"}, new Object[]{"OP", "PU"});
                    }
                }
                """.trimIndent(),
            )
        }
        val javac = externalJavac()
        val stubs = listOf("Entity", "Table", "Column", "Id").map { File(src, "javax/persistence/$it.java").absolutePath }
        val log = File(work, "javac.log")
        val p = ProcessBuilder(
            listOf(javac.absolutePath, "-source", "8", "-target", "8", "-d", classes.absolutePath) + stubs +
                File(src, "com/acme/WmsTask.java").absolutePath +
                File(src, "com/acme/CommonDao.java").absolutePath +
                File(src, "com/acme/WmsTaskJarService.java").absolutePath,
        ).redirectErrorStream(true).redirectOutput(log).start()
        assertTrue(log.readText(), p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(log.readText(), 0, p.exitValue())

        // 打成 jar（JarOutputStream：不依赖外部 jar 命令）
        val jarFile = File(work, "shadow-entity-lib-1.0.0.jar")
        val classFiles = classes.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        assertTrue("javac 应产出 class 文件", classFiles.isNotEmpty())
        java.util.jar.JarOutputStream(jarFile.outputStream()).use { jar ->
            jar.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\r\n\r\n".toByteArray())
            jar.closeEntry()
            for (cf in classFiles) {
                jar.putNextEntry(java.util.zip.ZipEntry(cf.relativeTo(classes).path.replace('\\', '/')))
                jar.write(cf.readBytes())
                jar.closeEntry()
            }
        }

        // 放进模块**内容根**下的 resources/lib/，解包后挂为库根（复刻 system scope jar 的双重身份）
        val contentRoot = roots.getOrPut(myModule) {
            val dir = tempDir()
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!.also { PsiTestUtil.addSourceRoot(myModule, it) }
        }
        val libDir = File(contentRoot.path, "resources/lib").apply { mkdirs() }
        val unpacked = File(libDir, "classes").apply { mkdirs() }
        ZipFile(jarFile).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                File(unpacked, e.name).apply { parentFile?.mkdirs() }.outputStream().use { out ->
                    zip.getInputStream(e).use { it.copyTo(out) }
                }
            }
        }
        val libVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(unpacked)!!
        libVf.refresh(false, true)
        ModuleRootModificationUtil.addModuleLibrary(myModule, "shadow-entity-lib", listOf(libVf.url), emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return JavaPsiFacade.getInstance(project)
            .findClass("com.acme.WmsTaskJarService", GlobalSearchScope.allScope(project))
            ?: error("jar 里的 WmsTaskJarService 解析不到")
    }

    private fun projectShadow() {
        val root = roots.getOrPut(myModule) {
            val dir = tempDir()
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!.also { PsiTestUtil.addSourceRoot(myModule, it) }
        }
        File(root.path, "src/com/acme").apply { mkdirs() }
        File(root.path, "src/com/acme/WmsTask.java").writeText(
            """
            package com.acme;
            @javax.persistence.Entity
            @javax.persistence.Table(name="wms_task_project")
            public class WmsTask {
                @javax.persistence.Id public long id;
                @javax.persistence.Column(name="status") public String status;
                @javax.persistence.Column(name="task_type") public String type;
                @javax.persistence.Column(name="remark") public String remark;
            }
            """.trimIndent(),
        )
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root.path, "src/com/acme/WmsTask.java"))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /**
     * jar 在内容根内时：解析到工程影子、不歧义；端到端（走反编译 PSI）产出 `wms_task_project` 且不降级。
     */
    fun testJarInsideContentRootResolvesProjectShadow() {
        val serviceClass = jarInsideContentRoot()
        projectShadow()

        val fileIndex = ProjectFileIndex.getInstance(project)
        // jar 内实体类文件（注意：此时同 FQN 的工程影子已存在，findClass 会返回影子——必须按路径直接取 jar 那份）
        val jarClassVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(
            File(roots.getValue(myModule).path, "resources/lib/classes/com/acme/WmsTask.class"),
        )!!
        // 双重身份：既在内容根之下、又是库类（这正是本形态的特征）
        assertTrue("jar 类应位于内容根之下", fileIndex.isInContent(jarClassVf))
        assertTrue("jar 类同时应被当作库类", fileIndex.isInLibraryClasses(jarClassVf))

        val stubMethod = serviceClass.findMethodsByName("beAllocateSuccess", false).single()
        val resolution = EntityClassResolver.resolveByName(project, "WmsTask", stubMethod)
        assertEquals("应唯一解析到工程影子（jar 版不参与歧义）", 1, resolution.candidates.size)
        assertFalse(resolution.ambiguous)
        assertEquals("com.acme.WmsTask", resolution.candidates.single().qualifiedName)
        assertTrue(
            "应取工程源码那份：${resolution.candidates.single().containingFile.virtualFile.path}",
            resolution.candidates.single().containingFile.virtualFile.extension == "java",
        )

        // 桩 PSI 无方法体 ⇒ IDE 点图标时走反编译 PSI，预览必须在这条链上正确
        assertNull("桩 PSI 不应有方法体", stubMethod.body)
        val mirror = (stubMethod.containingFile as PsiCompiledFile).getDecompiledPsiFile()
        val mirrorMethod = (mirror as PsiJavaFile).classes.single()
            .findMethodsByName("beAllocateSuccess", false).single()
        assertNotNull("反编译 PSI 应有方法体", mirrorMethod.body)
        val call = mirrorMethod.body!!
            .let { PsiTreeUtil.collectElementsOfType(it, PsiMethodCallExpression::class.java) }
            .first { it.methodExpression.referenceName == "findByQuery" }

        val preview = SqlPreviewService(project).previewCustomDaoHql(call)!!
        assertEquals("wms_task_project", preview.tableName)
        assertTrue(preview.sql, preview.sql.contains("task_type"))
        assertTrue(preview.sql, preview.sql.contains("remark"))
        assertFalse("jar 在内容根内不应降级：${preview.sql}", preview.sql.contains("实体不可解析"))
        assertFalse(preview.sql, preview.sql.contains("unknown_table"))
    }
}
