package cn.song.mybatisplus.visualizer.context

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import cn.song.mybatisplus.visualizer.log.SourcePathResolver
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

class ModuleIdentityResolverTest : HeavyPlatformTestCase() {

    override fun setUp() {
        super.setUp()
        createTestProjectStructure()
    }

    private val defaultModule: Module
        get() = ModuleManager.getInstance(project).modules.first()

    fun test同一文件落入两个模块时返回Ambiguous而非首模块() {
        val sharedDir = File(project.basePath!!, "shared-root").apply { mkdirs() }
        val source = File(sharedDir, "src/Demo.java").apply {
            parentFile.mkdirs()
            writeText("class Demo {}\n")
        }
        VfsUtil.markDirtyAndRefresh(false, true, true, sharedDir)
        val sharedRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(sharedDir)!!
        val sourceFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(source)!!
        val second = createAdditionalModule("shared-second")
        runWriteAction {
            // Heavy 项目的默认模块已覆盖 project.basePath；第二模块再挂同一 root，形成共享归属。
            ModuleRootManager.getInstance(second).modifiableModel.apply {
                addContentEntry(sharedRoot)
                commit()
            }
        }

        // 243 headless workspace model 会把重复 root 规范化成单归属；先实测并记录该环境限制。
        assertTrue(ModuleIdentityResolver.resolve(project, sourceFile) is ModuleIdentityResolution.Unique)

        // 再对“ModuleUtilCore 对两个模块均判 true”的真实生产输入形态做收口测试，禁止取首个。
        val sharedCandidates = listOf(defaultModule, second)
        val result = ModuleIdentityResolver.resolveCandidates(sharedCandidates) { it in sharedCandidates }
        assertTrue("共享模块候选必须保留歧义：$result", result is ModuleIdentityResolution.Ambiguous)
        val names = (result as ModuleIdentityResolution.Ambiguous).identities.map { it.module.name }.toSet()
        assertEquals(setOf(defaultModule.name, second.name), names)
    }

    fun test外部系统linkedProjectId优先且模块改名后key不变() {
        val systemId = ProjectSystemId("GRADLE")
        val root = project.basePath!!
        val manager = ExternalSystemModulePropertyManager.getInstance(defaultModule)
        val moduleData = ModuleData(
            ":order-service",
            systemId,
            "JAVA_MODULE",
            "order-service",
            root,
            root,
        )
        val projectData = ProjectData(systemId, "demo", root, root)
        runWriteAction { manager.setExternalOptions(systemId, moduleData, projectData) }

        val before = ModuleIdentityResolver.identityOf(defaultModule)
        val model = ModuleManager.getInstance(project).getModifiableModel()
        runWriteAction {
            model.renameModule(defaultModule, "renamed-order-service")
            model.commit()
        }
        val after = ModuleIdentityResolver.identityOf(defaultModule)

        assertEquals(ModuleKeySource.EXTERNAL_SYSTEM_ID, before.keySource)
        assertEquals("external:GRADLE::order-service", before.canonicalKey)
        assertEquals("模块展示名变化不应破坏外部系统稳定 key", before.canonicalKey, after.canonicalKey)
    }

    fun test同一包式路径命中两个模块时多候选全返且旧导航仍取首项() {
        val first = writeRealSource("mod-a/src/main/java/cn/song/shared/S.java")
        val second = writeRealSource("mod-b/src/main/java/cn/song/shared/S.java")
        val firstRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(
            File(project.basePath!!, "mod-a/src/main/java").absolutePath,
        )!!
        val secondRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(
            File(project.basePath!!, "mod-b/src/main/java").absolutePath,
        )!!
        val secondModule = createAdditionalModule("mod-b")
        runWriteAction {
            PsiTestUtil.addSourceRoot(defaultModule, firstRoot)
            PsiTestUtil.addSourceRoot(secondModule, secondRoot)
        }

        val candidates = SourcePathResolver.resolveVirtualFiles(project, "cn/song/shared/S.java")
        val paths = candidates.map { it.path.replace('\\', '/') }
        assertEquals(
            setOf(first.absolutePath.replace('\\', '/'), second.absolutePath.replace('\\', '/')),
            paths.toSet(),
        )
        assertEquals(2, paths.size)
        assertEquals(
            "旧导航必须保持三级策略下的首命中",
            candidates.first(),
            SourcePathResolver.resolveVirtualFile(project, "cn/song/shared/S.java"),
        )
    }

    fun test无外部系统属性时退到模块文件而非只用模块名() {
        val standalone = createAdditionalModule("standalone")
        val identity = ModuleIdentityResolver.identityOf(standalone)

        assertEquals(ModuleKeySource.MODULE_FILE, identity.keySource)
        assertTrue(identity.canonicalKey.startsWith("module-file:"))
        assertTrue(identity.canonicalKey.endsWith("standalone.iml"))
    }

    fun testDisposedModuleIsNotReturnedAsSourceOwner() {
        val source = writeRealSource("disposable/src/main/java/cn/song/shared/S.java")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(
            File(project.basePath!!, "disposable/src/main/java").absolutePath,
        )!!
        val disposableModule = createAdditionalModule("disposable")
        runWriteAction { PsiTestUtil.addSourceRoot(disposableModule, root) }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(source)!!
        val before = ModuleIdentityResolver.resolve(project, file)
        assertTrue(before is ModuleIdentityResolution.Unique)
        assertEquals(disposableModule, (before as ModuleIdentityResolution.Unique).identity.module)
        runWriteAction { ModuleManager.getInstance(project).disposeModule(disposableModule) }
        assertTrue(disposableModule.isDisposed)
        val after = ModuleIdentityResolver.resolve(project, file)
        if (after is ModuleIdentityResolution.Unique) {
            assertTrue("disposed owner must not survive", after.identity.module !== disposableModule)
            assertTrue(!after.identity.module.isDisposed)
        }
    }

    private fun writeRealSource(relativePath: String): File {
        val file = File(project.basePath!!, relativePath)
        file.parentFile.mkdirs()
        file.writeText("public class S {}\n")
        VfsUtil.markDirtyAndRefresh(false, true, true, file)
        return file
    }

    private fun createAdditionalModule(name: String): Module {
        val moduleFile = File(project.basePath!!, "$name/$name.iml")
        moduleFile.parentFile.mkdirs()
        return runWriteAction {
            ModuleManager.getInstance(project).newModule(moduleFile.absolutePath, "JAVA_MODULE")
        }
    }
}
