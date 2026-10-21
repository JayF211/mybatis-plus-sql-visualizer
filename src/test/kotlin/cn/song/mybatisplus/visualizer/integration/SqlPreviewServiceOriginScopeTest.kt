package cn.song.mybatisplus.visualizer.integration

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PresetMethodScope：服务层必须给预览结果写上「声明作用域」。
 *
 * 语料取报障原形态——demo 仓 `WmsInventoryJarQueryService` 的两个方法：一个把 HQL 放在
 * `String hql = "…"` 里直接传给 `createQuery`，另一个用 `StringBuffer` 逐句 append 后
 * `sb.toString()`。两段 HQL 语义文本一致，渲染出的 SQL 也一致，旧键（表名 + SQL 指纹）
 * 区分不开，于是预设与参数历史全部共享。
 *
 * 反向验证：摘掉 `buildJpaPreviewResult` 里的 `originScopeKey = PreviewOriginScope.of(...)`
 * → 两个作用域都为空 → 本类红。
 */
class SqlPreviewServiceOriginScopeTest : BasePlatformTestCase() {

    private lateinit var service: SqlPreviewService

    override fun setUp() {
        super.setUp()
        service = SqlPreviewService(myFixture.project)
    }

    private val source = """
        public class WmsInventoryJarQueryService {
            public Object findAvailableJarInventory(EntityManager em, String warehouseId) {
                String hql = "from WmsInventoryEntity inv where inv.warehouseId = :warehouseId order by inv.id";
                return em.createQuery(hql).setParameter("warehouseId", warehouseId);
            }

            public Object findAvailableJarInventoryByBuffer(EntityManager em, String warehouseId) {
                StringBuffer sb = new StringBuffer();
                sb.append("from WmsInventoryEntity inv where inv.warehouseId = :warehouseId order by inv.id");
                return em.createQuery(sb.toString()).setParameter("warehouseId", warehouseId);
            }

            interface EntityManager { Query createQuery(String ql); }
            interface Query { Query setParameter(String name, Object value); }

            @Entity
            @Table(name = "wms_inventory_jar_demo")
            class WmsInventoryEntity {
                @Id Long id;
                Long warehouseId;
            }
        }
    """.trimIndent()

    fun testSameHqlTwoMethodsSameSqlDifferentScope() {
        myFixture.configureByText("WmsInventoryJarQueryService.java", source)
        val calls = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == "createQuery" }
            .toList()
        assertEquals("语料应有两个 createQuery 调用", 2, calls.size)

        val first = service.previewHql(calls[0])
        val second = service.previewHql(calls[1])
        assertNotNull("字面量形态的 HQL 应可预览", first)
        assertNotNull("StringBuffer 形态的 HQL 应可预览", second)

        assertEquals(
            "两方法渲染出的 SQL 应逐字相同——这正是旧键撞车的前提",
            first!!.sql,
            second!!.sql,
        )
        assertTrue("作用域必须非空（HQL 链路的注入点在 buildJpaPreviewResult）", first.originScopeKey.isNotEmpty())
        assertTrue(
            "同一段 HQL 写在两个方法里，声明作用域必须不同（实际：${first.originScopeKey} vs ${second.originScopeKey}）",
            first.originScopeKey != second.originScopeKey,
        )
    }
}
