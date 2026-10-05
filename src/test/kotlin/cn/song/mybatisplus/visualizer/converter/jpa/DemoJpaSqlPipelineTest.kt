package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * demo 语料端到端守卫（T-HqlUpdateEntityMap + T-JpaDeleteWhereGuard）。
 *
 * 来龙去脉：用户 2026-10-05 报障 demo 工程 `jpa-hibernate-demo` 形态 17
 * `updateEmbeddedCreatedTime`——session.createQuery 通道的 HQL UPDATE **无 FROM 子句**，
 * 此前实体名提取三处正则全是 FROM-only，提取不到 `WmsDockBooking`，导致
 * resolveHqlTable 三路径全跳过 → TableInfo 落空 → 表名/嵌入列映射整链跳过，
 * 预览产物原样透传 JPQL 属性路径（`WmsDockBooking` / `booking.updateInfo.createdTime`）。
 * 修复提交 8e9ca7e：新增语句头优先的 HqlEntityNameExtractor 四处接入（S1）+
 * JpaUpdateParser 单表 UPDATE 剥别名（S2）。
 *
 * 为什么放在这里：仓内 converter 级测试（HqlQueryLocatorTest / JpaUpdateConverterTest）
 * 已镜像报障形态并钉死管道各环节，但都是**直传 TableInfo 或自造实体**——J2 测试全绿而生产
 * 漏的教训说明，同类问题必须在**真实 demo 语料**的端到端链路上再拦一道：
 * 装载真实宿主类 + 两个真实实体文件，HqlQueryLocator.locate → JpaEntityResolver.resolve →
 * HqlConverter.convert → SqlGenerator.generate 全链走通，三条守卫分别覆盖报障形态 17
 * （UPDATE 无 FROM）、形态 18（DELETE 条件树，BlockAttack 误报守卫）、形态 19（SELECT
 * 回归保护：嵌入段 + 普通属性映射不回归）。
 *
 * 为什么是 Heavy 而不是 Light（BasePlatformTestCase）：实体解析要按短名跨文件检索
 * （PsiShortNamesCache / JavaPsiFacade.findClass 命中另两个文件里的 WmsDockBooking，
 * 嵌入字段还要 resolve 到 UpdateInfo）——M27 实证 Light 环境跨文件 resolve/FileBasedIndex
 * 不可用（见 EntityClassFinderHeavyTest 头注），故照其先例用 [HeavyPlatformTestCase]
 * （磁盘项目 + 真实索引，文件经 createTestProjectStructure 落盘）。
 */
class DemoJpaSqlPipelineTest : HeavyPlatformTestCase() {

    private val demoRoot =
        File("..", "mybatis-plus-sql-visualizer-demo/jpa-hibernate-demo/src/main/java/cn/song/jpademo")

    private lateinit var srcRoot: VirtualFile

    /** 宿主类文件（三个文件都落在 src 根下，方法检索须钉住宿主文件）。 */
    private lateinit var hostFile: PsiFile

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        val root = createTestProjectStructure()
        val src = WriteCommandAction.runWriteCommandAction<VirtualFile>(project, Computable {
            root.findChild("src") ?: root.createChildDirectory(this, "src")
        })
        // createTestProjectStructure 的 source root 是临时根目录本身（src 只是普通子目录）——
        // 包索引按 source root 下目录层级推导包名，须把包目录直接建在 source root 下，
        // 否则文件包声明与目录推导包名不一致，同包类型 resolve（@Embedded 展平）落空
        srcRoot = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).sourceRoots.firstOrNull() ?: src
        // 照 demo 工程目录布局落盘（<sourceRoot>/cn/song/jpademo/...）
        hostFile = addDemoFile("service/impl/HibernateTemplateDemoServiceImpl.java")
        addDemoFile("entity/WmsDockBooking.java")
        addDemoFile("entity/UpdateInfo.java")
    }

    /** 把真实 demo 文件落进 source root 下 cn/song/jpademo/<relative>（保留包目录层级）。 */
    private fun addDemoFile(relative: String): PsiFile {
        val f = File(demoRoot, relative)
        assertTrue("demo 文件应存在: $f", f.exists())
        return WriteCommandAction.runWriteCommandAction<PsiFile>(project, Computable {
            val pkgDir = VfsUtil.createDirectories(srcRoot.path + "/cn/song/jpademo/" + relative.substringBeforeLast('/'))
            val vf = pkgDir.createChildData(this, f.name)
            VfsUtil.saveText(vf, f.readText())
            PsiManager.getInstance(project).findFile(vf)!!
        })
    }

    /** 从宿主类文件里找方法（PsiTreeUtil 收集 PsiMethod，照 HqlQueryLocatorTest 口径）。 */
    private fun findMethod(methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(hostFile, PsiMethod::class.java)
        return methods.firstOrNull { it.name == methodName }
            ?: error("宿主类缺少方法: $methodName")
    }

    /** 实体解析：短名 findClass → JpaEntityResolver.resolve。 */
    private fun resolveBookingTable(): TableInfo {
        val facade = JavaPsiFacade.getInstance(project)
        // 实测（2026-10-05 本仓 Heavy 环境）：findClass 短名轨不命中（短名缓存 allClassNames
        // 明明含 WmsDockBooking），须 FQN 轨 / PsiShortNamesCache.getClassesByName 兜底
        val entityClass: PsiClass = facade.findClass("WmsDockBooking", GlobalSearchScope.allScope(project))
            ?: facade.findClass("cn.song.jpademo.entity.WmsDockBooking", GlobalSearchScope.allScope(project))
            ?: com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                .getClassesByName("WmsDockBooking", GlobalSearchScope.allScope(project)).firstOrNull()
            ?: error("项目中应能解析到实体 WmsDockBooking（检查三个 demo 文件是否都落在 source root 的包目录下）")
        return JpaEntityResolver().resolve(entityClass)
    }

    /**
     * 形态 17 端到端守卫（原始报障形态）：无 FROM 的 HQL UPDATE，
     * locate 实体名 → 实体注解解析（表名 + @Embedded 展平列）→ convert → 渲染产物剥别名。
     */
    fun testForm17_updateWithoutFrom_endToEnd() {
        val info = HqlQueryLocator.locate(findMethod("updateEmbeddedCreatedTime"))
        assertNotNull("无 FROM 的 update HQL 应被识别", info)
        assertEquals(
            "实体类名应按语句头提取（修复前 FROM-only 提取为 null）",
            "WmsDockBooking",
            info!!.entityClassName,
        )

        val table = resolveBookingTable()
        assertEquals("wms_dock_booking", table.tableName)
        val createdTime = table.columnByField("createdTime")
        assertNotNull("@Embedded 展平列 createdTime 应存在", createdTime)
        assertEquals("created_time", createdTime!!.columnName)
        assertEquals("updateInfo", createdTime.embeddedParent)

        val result = HqlConverter.convert(info.hql, table, project)
        // 管道文本：表名映射 + 嵌入段剥离（别名限定符按管道口径保留，contains 断言）
        assertTrue("管道文本应含表名: ${result.sql}", result.sql.contains("wms_dock_booking"))
        assertTrue("管道文本应含展平列: ${result.sql}", result.sql.contains("created_time"))

        // 渲染产物：S2 剥别名语义，别名/嵌入段不得残留（面板所见与 Hibernate 真实产物对齐）
        val rendered = SqlGenerator.generate(result.statement).replace(Regex("\\s+"), " ").trim()
        assertEquals("UPDATE wms_dock_booking SET created_time = ? WHERE id = ?", rendered)
    }

    /**
     * 形态 18 守卫（与 T-JpaDeleteWhereGuard 联动）：带 FROM 的 DELETE + 嵌入段路径，
     * WHERE 须解析进 DeleteStatement.conditions（修复前恒空 → BlockAttack 误报全表删除）。
     */
    fun testForm18_deleteWithFrom_conditionsParsed() {
        val info = HqlQueryLocator.locate(findMethod("deleteByEmbeddedCreatedTimeBefore"))
        assertNotNull("带 FROM 的 delete HQL 应被识别", info)
        assertEquals("WmsDockBooking", info!!.entityClassName)

        val table = resolveBookingTable()
        val result = HqlConverter.convert(info.hql, table, project)
        assertTrue(
            "DELETE 应返回 DeleteStatement，实际 ${result.statement::class.simpleName}",
            result.statement is DeleteStatement,
        )
        val conditions = (result.statement as DeleteStatement).conditions
        assertTrue("带 WHERE 的 DELETE 条件树不应为空（BlockAttack 误报守卫）: $conditions", conditions.isNotEmpty())
        val first = conditions[0]
        if (first is Condition.Simple) {
            assertEquals("嵌入段路径条件应映射为展平列", "created_time", first.column.columnName)
        }

        assertTrue("管道文本应含表名: ${result.sql}", result.sql.contains("wms_dock_booking"))
        assertTrue("管道文本应含展平列: ${result.sql}", result.sql.contains("created_time"))
    }

    /**
     * 形态 19 守卫（SELECT 回归保护）：嵌入段映射（booking.updateInfo.createdTime →
     * booking.created_time）与普通属性映射（booking.warehouseId → booking.warehouse_id）不回归。
     */
    fun testForm19_select_embeddedAndPlainMapping() {
        val info = HqlQueryLocator.locate(findMethod("findByEmbeddedCreatedTimeAndWarehouse"))
        assertNotNull("select HQL 应被识别", info)
        assertEquals("WmsDockBooking", info!!.entityClassName)

        val table = resolveBookingTable()
        val result = HqlConverter.convert(info.hql, table, project)
        assertTrue(
            "嵌入段属性应映射为展平列（别名限定保留）: ${result.sql}",
            result.sql.contains("booking.created_time"),
        )
        assertTrue(
            "普通属性应映射为蛇形列: ${result.sql}",
            result.sql.contains("booking.warehouse_id"),
        )
    }
}
