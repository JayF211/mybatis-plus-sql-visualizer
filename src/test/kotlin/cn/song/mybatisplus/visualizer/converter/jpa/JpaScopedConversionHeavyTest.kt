package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.psi.EntityClassResolver
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.nio.file.Files

/** Real indexed modules: no preselected TableInfo is injected into either conversion entry point. */
class JpaScopedConversionHeavyTest : HeavyPlatformTestCase() {
    private val roots = mutableMapOf<Module, VirtualFile>()
    private val dirs = mutableListOf<File>()

    override fun setUp() {
        super.setUp()
        EntityClassResolver.ambiguitySinkForTest = { _, _, _, _ -> }
    }

    override fun tearDown() {
        try {
            EntityClassResolver.ambiguitySinkForTest = null
            EntityClassResolver.clearAmbiguityNotifiedForTest(project)
            roots.clear()
            super.tearDown()
        } finally {
            dirs.forEach { it.deleteRecursively() }
        }
    }

    private fun source(module: Module, fqn: String, body: String = "", annotations: String = ""): PsiJavaFile {
        val root = roots.getOrPut(module) {
            val dir = Files.createTempDirectory("jpa-converter-scope-").toFile().also(dirs::add)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!.also { PsiTestUtil.addSourceRoot(module, it) }
        }
        return WriteCommandAction.runWriteCommandAction(project, Computable {
            val vf = VfsUtil.createDirectoryIfMissing(root, fqn.substringBeforeLast('.').replace('.', '/'))!!
                .createChildData(this, fqn.substringAfterLast('.') + ".java")
            VfsUtil.saveText(vf, "package ${fqn.substringBeforeLast('.')}; $annotations public class ${fqn.substringAfterLast('.')} { $body }")
            PsiManager.getInstance(project).findFile(vf) as PsiJavaFile
        })
    }

    private fun entities(module: Module, pkg: String, prefix: String) {
        source(module, "$pkg.Inventory", """
            @javax.persistence.Id @javax.persistence.Column(name="${prefix}_id") long id;
            @javax.persistence.Column(name="${prefix}_label") String displayName;
            @javax.persistence.ManyToOne @javax.persistence.JoinColumn(name="${prefix}_dept_fk", referencedColumnName="${prefix}_dept_key") Department department;
        """.trimIndent(), "@javax.persistence.Entity @javax.persistence.Table(name=\"${prefix}_inventory\")")
        source(module, "$pkg.Department", "@javax.persistence.Id @javax.persistence.Column(name=\"${prefix}_dept_key\") long id;",
            "@javax.persistence.Entity @javax.persistence.Table(name=\"${prefix}_department\")")
        source(module, "$pkg.Detail", """
            @javax.persistence.Id long id;
            @javax.persistence.Column(name="${prefix}_owner") long ownerId;
            @javax.persistence.Column(name="${prefix}_detail_label") String displayName;
            @javax.persistence.ManyToOne @javax.persistence.JoinColumn(name="${prefix}_detail_dept_fk", referencedColumnName="${prefix}_dept_key") Department department;
        """.trimIndent(), "@javax.persistence.Entity @javax.persistence.Table(name=\"${prefix}_detail\")")
    }

    private fun sqls(query: String, host: PsiJavaFile): List<String> = listOf(
        HqlConverter.convert(query, null, project, null, host).sql,
        JpqlConverter.convert(query, null, project, host).sql,
    )

    fun testAlternatingModuleHostsResolveRootSubqueryExplicitAndAssociationJoins() {
        val other = createModule("other")
        val hostA = source(myModule, "hosta.Query")
        val hostB = source(other, "hostb.Query")
        entities(myModule, "a", "a")
        entities(other, "b", "b")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val query = "select i.displayName, d.displayName from Inventory i join Detail d on d.ownerId=i.id " +
            "join i.department dep where exists (select s.id from Detail s join s.department sd where s.ownerId=i.id)"
        repeat(2) {
            for ((host, prefix) in listOf(hostA to "a", hostB to "b")) {
                for (sql in sqls(query, host)) {
                    assertTrue(sql, sql.contains("from ${prefix}_inventory i"))
                    assertTrue(sql, sql.contains("join ${prefix}_detail d"))
                    assertTrue(sql, sql.contains("from ${prefix}_detail s"))
                    assertTrue(sql, sql.contains("i.${prefix}_label"))
                    assertTrue(sql, sql.contains("d.${prefix}_detail_label"))
                    assertTrue(sql, sql.contains("s.${prefix}_owner"))
                    assertTrue(sql, sql.contains("JOIN ${prefix}_department dep ON dep.${prefix}_dept_key = i.${prefix}_dept_fk"))
                    assertTrue(sql, sql.contains("JOIN ${prefix}_department sd ON sd.${prefix}_dept_key = s.${prefix}_detail_dept_fk"))
                }
            }
        }
    }

    fun testQueryHostNotRootEntityModuleControlsSubqueryVisibility() {
        val model = createModule("model")
        val host = source(myModule, "host.Query")
        source(model, "model.Inventory", "@javax.persistence.Id long id;", "@javax.persistence.Entity @javax.persistence.Table(name=\"model_inventory\")")
        source(myModule, "host.Detail", "@javax.persistence.Column(name=\"host_owner\") long ownerId;", "@javax.persistence.Entity @javax.persistence.Table(name=\"host_detail\")")
        ModuleRootModificationUtil.addDependency(myModule, model)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        for (sql in sqls("select i.id from Inventory i where exists (select d.ownerId from Detail d)", host)) {
            assertTrue(sql, sql.contains("from model_inventory i"))
            assertTrue(sql, sql.contains("from host_detail d"))
            assertTrue(sql, sql.contains("d.host_owner"))
        }
    }

    fun testNamedEntityWithDifferentJavaNameAndLowerCaseSubqueryName() {
        val host = source(myModule, "host.Query")
        source(myModule, "model.ActualInventory", """
            @javax.persistence.Id @javax.persistence.Column(name="stock_key") long id;
            @javax.persistence.ManyToOne @javax.persistence.JoinColumn(name="stock_detail_fk", referencedColumnName="detail_key") ActualDetail detail;
        """.trimIndent(), "@javax.persistence.Entity(name=\"Stock\") @javax.persistence.Table(name=\"named_stock\")")
        source(myModule, "model.ActualDetail", "@javax.persistence.Column(name=\"stock_owner\") long ownerId;", "@javax.persistence.Entity(name=\"stockDetail\") @javax.persistence.Table(name=\"named_detail\")")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        for (sql in sqls("select s.id from Stock s join s.detail joined where exists (select d.ownerId from stockDetail d where d.ownerId=s.id)", host)) {
            assertTrue(sql, sql.contains("from named_stock s"))
            assertTrue(sql, sql.contains("s.stock_key"))
            assertTrue(sql, sql.contains("JOIN named_detail joined ON joined.detail_key = s.stock_detail_fk"))
            assertTrue(sql, sql.contains("from named_detail d"))
            assertTrue(sql, sql.contains("d.stock_owner"))
        }
    }

    fun testFqnDoesNotCollapseDifferentEntitiesWithSameSimpleName() {
        val host = source(myModule, "host.Query")
        entities(myModule, "a", "a")
        entities(myModule, "b", "b")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        for (sql in sqls("select i.displayName, j.displayName from a.Inventory i join b.Inventory j on i.id=j.id", host)) {
            assertTrue(sql, sql.contains("from a_inventory i"))
            assertTrue(sql, sql.contains("join b_inventory j"))
            assertTrue(sql, sql.contains("i.a_label"))
            assertTrue(sql, sql.contains("j.b_label"))
        }
    }

    fun testCancelledAndFatalResolutionAreNotSwallowed() {
        val table = cn.song.mybatisplus.visualizer.model.TableInfo("Inventory", "inventory", emptyList())
        val cancelled = com.intellij.openapi.progress.ProcessCanceledException()
        try {
            HqlConverter.convertWithEntityResolver("from Inventory i join Detail d on i.id=d.id", table) { throw cancelled }
            fail("Cancellation must escape conversion")
        } catch (actual: com.intellij.openapi.progress.ProcessCanceledException) {
            assertSame(cancelled, actual)
        }
        val fatal = LinkageError("fatal probe")
        try {
            HqlConverter.convertWithEntityResolver("from Inventory i join Detail d on i.id=d.id", table) { throw fatal }
            fail("Fatal errors must escape conversion")
        } catch (actual: LinkageError) {
            assertSame(fatal, actual)
        }
    }

    fun testAmbiguousSecondaryAliasesDoNotBorrowRootColumns() {
        val left = createModule("left")
        val right = createModule("right")
        val host = source(myModule, "host.Query")
        source(myModule, "model.Root", "@javax.persistence.Column(name=\"root_label\") String displayName;",
            "@javax.persistence.Entity @javax.persistence.Table(name=\"root_table\")")
        source(left, "left.Detail", "@javax.persistence.Column(name=\"left_label\") String displayName;",
            "@javax.persistence.Entity @javax.persistence.Table(name=\"left_detail\")")
        source(right, "right.Detail", "@javax.persistence.Column(name=\"right_label\") String displayName;",
            "@javax.persistence.Entity @javax.persistence.Table(name=\"right_detail\")")
        ModuleRootModificationUtil.addDependency(myModule, left)
        ModuleRootModificationUtil.addDependency(myModule, right)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val resolution = EntityClassResolver.resolveByName(project, "Detail", host)
        assertTrue("Fixture must produce genuine visible ambiguity", resolution.ambiguous)
        assertNull(resolution.psiClass())
        for (sql in sqls("select r.displayName, d.displayName from Root r join Detail d on d.displayName=r.displayName " +
            "where exists (select s.displayName from Detail s where s.displayName=r.displayName)", host)) {
            assertTrue(sql, sql.contains("r.root_label"))
            assertTrue(sql, sql.contains("d.displayName"))
            assertTrue(sql, sql.contains("s.displayName"))
            assertFalse(sql, sql.contains("d.root_label"))
            assertFalse(sql, sql.contains("s.root_label"))
            assertFalse(sql, sql.contains("left_label") || sql.contains("right_label"))
        }
        // FQN removes the ambiguity: each side must use its own real column, not the root's.
        for (sql in sqls("select r.displayName, d.displayName, e.displayName from Root r " +
            "join left.Detail d on d.displayName=r.displayName join right.Detail e on e.displayName=r.displayName", host)) {
            assertTrue(sql, sql.contains("r.root_label"))
            assertTrue(sql, sql.contains("d.left_label"))
            assertTrue(sql, sql.contains("e.right_label"))
        }
    }

    fun testMissingDeclaredSecondaryAndUnknownQualifierHaveDifferentContracts() {
        val host = source(myModule, "host.Query")
        source(myModule, "model.Root", "@javax.persistence.Column(name=\"root_label\") String displayName;",
            "@javax.persistence.Entity @javax.persistence.Table(name=\"root_table\")")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        for (sql in sqls("select r.displayName, d . displayName, unknown.displayName from Root r " +
            "join MissingDetail d on d.displayName=r.displayName", host)) {
            assertTrue(sql, sql.contains("r.root_label"))
            assertTrue(sql, sql.contains("d . displayName"))
            assertTrue(sql, sql.contains("d.displayName"))
            assertFalse(sql, sql.contains("d.root_label"))
            assertTrue("Undeclared qualifier retains legacy fallback: $sql", sql.contains("unknown.root_label"))
        }
    }

    fun testAmbiguousRootDoesNotBorrowResolvedSecondaryColumns() {
        val host = source(myModule, "host.Query")
        entities(myModule, "a", "a")
        entities(myModule, "b", "b")
        source(myModule, "model.UniqueDetail", "@javax.persistence.Column(name=\"secondary_label\") String displayName;", "@javax.persistence.Entity @javax.persistence.Table(name=\"unique_detail\")")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        for (sql in sqls("select i.displayName, d.displayName from Inventory i join UniqueDetail d on i.displayName=d.displayName", host)) {
            assertFalse(sql, sql.contains("a_inventory"))
            assertFalse(sql, sql.contains("b_inventory"))
            assertFalse(sql, sql.contains("secondary_label"))
            assertTrue(sql, sql.contains("i.displayName"))
        }
    }
}
