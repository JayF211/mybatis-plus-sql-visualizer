package cn.song.mybatisplus.visualizer.psi

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Real disk modules and javac-produced library CLASSES roots, not synthetic PSI scope mocks. */
class EntityClassResolverHeavyTest : HeavyPlatformTestCase() {
    private val roots = mutableMapOf<Module, VirtualFile>()
    private val temporaryDirs = mutableListOf<File>()
    private val notices = mutableListOf<Pair<String, List<String>>>()

    override fun setUp() {
        super.setUp()
        EntityClassResolver.clearAmbiguityNotifiedForTest(project)
        EntityClassResolver.ambiguitySinkForTest = { _, _, scope, candidates -> notices += scope to candidates }
    }

    override fun tearDown() {
        try {
            EntityClassResolver.ambiguitySinkForTest = null
            EntityClassResolver.clearAmbiguityNotifiedForTest(project)
            roots.clear()
            notices.clear()
            super.tearDown()
        } finally {
            temporaryDirs.forEach { it.deleteRecursively() }
            temporaryDirs.clear()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("entity-resolver-").toFile().also(temporaryDirs::add)

    private fun source(module: Module, fqn: String, annotation: String = ""): PsiClass {
        val root = roots.getOrPut(module) {
            val dir = tempDir()
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
            PsiTestUtil.addSourceRoot(module, vf)
            vf
        }
        val file = WriteCommandAction.runWriteCommandAction(project, Computable {
            val vf = VfsUtil.createDirectoryIfMissing(root, fqn.substringBeforeLast('.').replace('.', '/'))!!
                .createChildData(this, fqn.substringAfterLast('.') + ".java")
            VfsUtil.saveText(vf, "package ${fqn.substringBeforeLast('.')}; $annotation public class ${fqn.substringAfterLast('.')} {}")
            PsiManager.getInstance(project).findFile(vf) as PsiJavaFile
        })
        return file.classes.single()
    }

    private fun ready() = IndexingTestUtil.waitUntilIndexesAreReady(project)

    /** External javac works even when the platform's test JVM has no jdk.compiler module. */
    private fun library(module: Module, fqn: String, entityName: String? = null): PsiClass {
        val dir = tempDir()
        val sourceRoot = File(dir, "sources").apply { mkdirs() }
        val classes = File(dir, "classes").apply { mkdirs() }
        val annotation = File(sourceRoot, "javax/persistence/Entity.java").apply {
            parentFile.mkdirs()
            writeText("package javax.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Entity { String name() default \"\"; }")
        }
        val java = File(sourceRoot, fqn.replace('.', '/') + ".java").apply {
            parentFile.mkdirs()
            val args = entityName?.let { "(name=\"$it\")" }.orEmpty()
            writeText("package ${fqn.substringBeforeLast('.')}; @javax.persistence.Entity$args public class ${fqn.substringAfterLast('.')} {}")
        }
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac"
        val homes = listOfNotNull(System.getProperty("mpv.entityResolver.javacHome"), System.getenv("JAVA_HOME"),
            System.getProperty("java.home"), "D:/HuanJing/Java/jdk1.8.0_181")
        val javac = homes.map { File(it, "bin/$executable") }.firstOrNull(File::isFile)
        assertNotNull("A real external JDK is required; set mpv.entityResolver.javacHome", javac)
        val log = File(dir, "javac.log")
        val process = ProcessBuilder(javac!!.absolutePath, "-source", "8", "-target", "8", "-d", classes.absolutePath,
            annotation.absolutePath, java.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
        assertTrue("javac exceeded 30 seconds", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(log.readText(), 0, process.exitValue())
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(classes)!!
        vf.refresh(false, true)
        ModuleRootModificationUtil.addModuleLibrary(module, vf.url)
        ready()
        val clazz = JavaPsiFacade.getInstance(project).findClass(fqn,
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module))!!
        assertTrue("Must read compiled PSI, not source", clazz.containingFile.virtualFile.extension == "class")
        println("[ENTITY-RESOLVER] binary fixture ${clazz.qualifiedName}: ${clazz.containingFile.virtualFile.path}")
        return clazz
    }

    fun testHostSeesDependencyBButNotUnrelatedC() {
        val b = createModule("B")
        val c = createModule("C")
        val context = source(myModule, "host.Query")
        source(b, "visible.WmsInventoryEntity", "@javax.persistence.Entity")
        source(c, "unrelated.WmsInventoryEntity", "@javax.persistence.Entity")
        ModuleRootModificationUtil.addDependency(myModule, b)
        ready()
        val result = EntityClassResolver.resolveByName(project, "WmsInventoryEntity", context)
        assertEquals("visible.WmsInventoryEntity", result.psiClass?.qualifiedName)
        assertEquals(1, result.candidates.size)
        assertTrue(notices.isEmpty())
    }

    fun testInvisibleOnlyCandidateDoesNotEscapeModuleScopeEvenForFqn() {
        val c = createModule("C")
        val context = source(myModule, "host.Query")
        source(c, "unrelated.WmsInventoryEntity")
        ready()
        assertNull(EntityClassResolver.resolveClassByName(project, "WmsInventoryEntity", context))
        assertNull(EntityClassResolver.resolveClassByName(project, "unrelated.WmsInventoryEntity", context))
    }

    fun testRealLibraryIsVisibleDespiteUnrelatedSourceAndFqnResolves() {
        val c = createModule("C")
        val context = source(myModule, "host.Query")
        source(c, "unrelated.WmsInventoryEntity")
        val binary = library(myModule, "lib.WmsInventoryEntity")
        val result = EntityClassResolver.resolveByName(project, "WmsInventoryEntity", context)
        assertEquals(binary, result.psiClass)
        assertEquals(binary, EntityClassResolver.resolveClassByName(project, "lib.WmsInventoryEntity", context))
        assertTrue(notices.isEmpty())
    }

    fun testSameFqnInvisibleSourceDoesNotDisplaceVisibleBinary() {
        val c = createModule("C")
        val context = source(myModule, "host.Query")
        source(c, "same.WmsInventoryEntity")
        val binary = library(myModule, "same.WmsInventoryEntity")
        assertEquals(binary, EntityClassResolver.resolveClassByName(project, "WmsInventoryEntity", context))
        assertEquals(binary, EntityClassResolver.resolveClassByName(project, "same.WmsInventoryEntity", context))
    }

    fun testDuplicateVisibleFqnUsesPlatformClasspathNotIterationOrder() {
        val context = source(myModule, "host.Query")
        val binary = library(myModule, "same.Inventory")
        val source = source(myModule, "same.Inventory")
        ready()
        val expected = JavaPsiFacade.getInstance(project).findClass("same.Inventory", EntityClassResolver.searchScope(project, context))
        val resolution = EntityClassResolver.resolveWithAliasCandidates(project, "Inventory", context, listOf(binary, source, binary))
        assertNotNull(expected)
        assertEquals(expected, resolution.psiClass)
        assertEquals(1, resolution.candidates.size)
    }

    fun testVisibleSameLevelCandidatesFailClosedAndFqnDisambiguates() {
        val context = source(myModule, "host.Query")
        source(myModule, "z.Inventory")
        source(myModule, "a.Inventory")
        ready()
        val result = EntityClassResolver.resolveByName(project, "Inventory", context)
        assertNull(result.psiClass)
        assertTrue(result.ambiguous)
        assertEquals(listOf("a.Inventory", "z.Inventory"), result.candidates.map { it.qualifiedName })
        assertEquals("z.Inventory", EntityClassResolver.resolveClassByName(project, "z.Inventory", context)?.qualifiedName)
        assertEquals(listOf("a.Inventory", "z.Inventory"), notices.single().second)
    }

    fun testContextlessLookupRequiresUniqueAcrossSourceAndLibrary() {
        val b = createModule("B")
        source(b, "source.Inventory")
        library(myModule, "binary.Inventory")
        val result = EntityClassResolver.resolveByName(project, "Inventory", null)
        assertNull(result.psiClass)
        assertEquals(2, result.candidates.size)
        assertEquals("source.Inventory", EntityClassResolver.resolveClassByName(project, "source.Inventory", null)?.qualifiedName)
    }

    fun testContextlessDuplicateFqnIsNotArbitrarilyDeduplicated() {
        val b = createModule("B")
        val c = createModule("C")
        val first = source(b, "same.Inventory")
        val second = source(c, "same.Inventory")
        ready()
        val result = EntityClassResolver.resolveWithAliasCandidates(project, "Inventory", null, listOf(first, second))
        assertNull(result.psiClass)
        assertEquals(2, result.candidates.size)
        val direct = EntityClassResolver.resolveByName(project, "Inventory", null)
        assertNull(direct.psiClass)
        assertEquals(2, direct.candidates.size)
    }

    fun testExplicitEntityNameIsCaseSensitiveAndReplacesDefaultName() {
        val context = source(myModule, "host.Query")
        val renamed = source(myModule, "model.Inventory", "@javax.persistence.Entity(name=\"Stock\")")
        ready()
        assertNull(EntityClassResolver.resolveClassByName(project, "Inventory", context))
        assertEquals(renamed, EntityClassResolver.resolveWithAliasCandidates(project, "Stock", context, listOf(renamed)).psiClass)
        assertNull(EntityClassResolver.resolveWithAliasCandidates(project, "stock", context, listOf(renamed)).psiClass)
        assertEquals(renamed, EntityClassResolver.resolveClassByName(project, "model.Inventory", context))
    }

    fun testExplicitBinaryAliasAndDefaultOwnerAreAmbiguous() {
        val context = source(myModule, "host.Query")
        val alias = library(myModule, "lib.DifferentJavaName", "Inventory")
        source(myModule, "model.Inventory", "@javax.persistence.Entity")
        ready()
        val result = EntityClassResolver.resolveWithAliasCandidates(project, "Inventory", context, listOf(alias))
        assertNull(result.psiClass)
        assertEquals(2, result.candidates.size)
    }

    fun testAliasCandidatesAreFilteredByScopeAndNotByInputOrder() {
        val b = createModule("B")
        val c = createModule("C")
        val context = source(myModule, "host.Query")
        val visible = source(b, "b.DifferentJavaName", "@javax.persistence.Entity(name=\"Stock\")")
        val invisible = source(c, "c.OtherJavaName", "@javax.persistence.Entity(name=\"Stock\")")
        ModuleRootModificationUtil.addDependency(myModule, b)
        ready()
        assertEquals(visible, EntityClassResolver.resolveWithAliasCandidates(project, "Stock", context, listOf(invisible, visible)).psiClass)
    }

    fun testAlternatingModulesDoNotLeakResolutionOrNotificationDedup() {
        val b = createModule("B")
        val contextA = source(myModule, "host.Query")
        val contextB = source(b, "other.Query")
        source(myModule, "a.Inventory")
        source(b, "b.Inventory")
        ready()
        repeat(3) {
            assertEquals("a.Inventory", EntityClassResolver.resolveClassByName(project, "Inventory", contextA)?.qualifiedName)
            assertEquals("b.Inventory", EntityClassResolver.resolveClassByName(project, "Inventory", contextB)?.qualifiedName)
        }
        source(myModule, "a2.Inventory")
        source(b, "b2.Inventory")
        ready()
        repeat(3) {
            assertNull(EntityClassResolver.resolveClassByName(project, "Inventory", contextA))
            assertNull(EntityClassResolver.resolveClassByName(project, "Inventory", contextB))
        }
        assertEquals(2, notices.size)
        assertEquals(setOf(myModule.name, b.name), notices.map { it.first }.toSet())
    }

    /**
     * F102（T-PerfSweep26）：歧义提示去重集有界。
     *
     * 造 [max] + 5 个互不相同的歧义实体名，逐一触发提示后断言去重集条目数不超过上界
     * （修复前是无界 Set，条目数会随实体数线性增长）。
     */
    fun testAmbiguityNoticeDedupSetIsBounded() {
        val max = EntityClassResolver.ambiguityNoticeMaxForTest()
        val context = source(myModule, "host.Query")
        val count = max + 5
        for (i in 0 until count) {
            // 同名双份 → 每次解析都是歧义；不同序号 → 不同的去重键
            source(myModule, "a$i.Amb$i")
            source(myModule, "b$i.Amb$i")
        }
        ready()
        for (i in 0 until count) {
            assertNull(
                "同名双份应 fail closed（$i）",
                EntityClassResolver.resolveClassByName(project, "Amb$i", context),
            )
        }
        val size = EntityClassResolver.ambiguityNoticeCountForTest(project)
        assertTrue("去重集应有条目（实际 $size）", size > 0)
        assertTrue("去重集条目数 $size 不得超过上界 $max（F102 有界 LRU）", size <= max)
    }
}
