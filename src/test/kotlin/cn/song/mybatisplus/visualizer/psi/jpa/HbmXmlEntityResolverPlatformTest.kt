package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * HbmXmlEntityResolver 平台集成测试（T-J11）。
 *
 * 在 LightPlatform 虚拟项目中创建真实 .hbm.xml 文件（走 VirtualFile + PsiManager 全链路），
 * 验证：
 *  - 单文件多 `<class>` 全部解析并按实体名索引（T-J11：原先 findFirstSubTag 只取第一个）；
 *  - 文件发现走 FileTypeIndex + 后缀过滤（T-J11：原先 FilenameIndex 通配符不生效）；
 *  - 按简单类名 / 全限定名双入口查找（对外 API 兼容）。
 *
 * 注意：resolve 带项目级缓存，测试前后 clearCache 防串扰。
 */
class HbmXmlEntityResolverPlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        HbmXmlEntityResolver.clearCache()
    }

    override fun tearDown() {
        HbmXmlEntityResolver.clearCache()
        super.tearDown()
    }

    fun testTJ11_multiClassFile_allEntitiesResolvable() {
        myFixture.addFileToProject(
            "hibernate/Order.hbm.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <hibernate-mapping>
                <class name="com.demo.Order" table="T_ORDER">
                    <id name="id" column="ORDER_ID"/>
                    <property name="orderNo" column="ORDER_NO"/>
                    <property name="amount" column="AMOUNT"/>
                </class>
                <class name="com.demo.OrderItem" table="T_ORDER_ITEM">
                    <id name="id" column="ITEM_ID"/>
                    <property name="productName" column="PRODUCT_NAME"/>
                    <many-to-one name="order" class="com.demo.Order" column="ORDER_ID"/>
                </class>
            </hibernate-mapping>
            """.trimIndent(),
        )

        // 第一个 <class>：向后兼容（旧实现唯一能查到的）
        val order = HbmXmlEntityResolver.resolve("Order", myFixture.project)
        assertNotNull("第一个 <class> 应可解析", order)
        assertEquals("T_ORDER", order!!.tableName)
        assertEquals("com.demo.Order", order.fullClassName)
        assertEquals(3, order.properties.size)

        // 第二个 <class>：T-J11 修复点（旧实现查不到）
        val item = HbmXmlEntityResolver.resolve("OrderItem", myFixture.project)
        assertNotNull("同文件第二个 <class> 也应可解析（T-J11）", item)
        assertEquals("T_ORDER_ITEM", item!!.tableName)
        assertEquals(1, item.associations.size)
        assertEquals("ORDER_ID", item.associations[0].joinColumn)
        assertEquals("com.demo.Order", item.associations[0].targetClassName)

        // 全限定名入口保持不变
        val byFqn = HbmXmlEntityResolver.resolve("com.demo.OrderItem", myFixture.project)
        assertNotNull("全限定名查找应命中", byFqn)
        assertEquals("T_ORDER_ITEM", byFqn!!.tableName)
    }

    fun testTJ11_resolveAll_indexesEveryClass() {
        myFixture.addFileToProject(
            "hibernate/Order.hbm.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <hibernate-mapping>
                <class name="com.demo.Order" table="T_ORDER">
                    <id name="id" column="ORDER_ID"/>
                </class>
                <class name="com.demo.OrderItem" table="T_ORDER_ITEM">
                    <id name="id" column="ITEM_ID"/>
                </class>
            </hibernate-mapping>
            """.trimIndent(),
        )

        val index = HbmXmlEntityResolver.resolveAll(myFixture.project)
        // 每个实体两个键：简单名 + 全限定名
        assertEquals("索引应含 2 实体 × 2 键", 4, index.size)
        assertNotNull(index["Order"])
        assertNotNull(index["OrderItem"])
        assertNotNull(index["com.demo.Order"])
        assertNotNull(index["com.demo.OrderItem"])
        assertEquals("T_ORDER", index["Order"]!!.tableName)
    }

    fun testTJ11_nonHbmXmlFileFilteredOut() {
        // 非 .hbm.xml 后缀的 XML 不参与索引（文件名后缀过滤守卫）
        myFixture.addFileToProject(
            "meta/notes.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <hibernate-mapping>
                <class name="com.demo.PlainEntity" table="T_PLAIN">
                    <id name="id" column="ID"/>
                </class>
            </hibernate-mapping>
            """.trimIndent(),
        )

        val index = HbmXmlEntityResolver.resolveAll(myFixture.project)
        assertTrue("非 .hbm.xml 文件不应入索引", index.isEmpty())
        assertNull("resolve 也不应命中非 .hbm.xml 文件中的实体", HbmXmlEntityResolver.resolve("PlainEntity", myFixture.project))
    }

    fun testTJ11_unknownEntityReturnsNull() {
        myFixture.addFileToProject(
            "hibernate/User.hbm.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <hibernate-mapping>
                <class name="com.demo.User" table="T_USER">
                    <id name="id" column="USER_ID"/>
                </class>
            </hibernate-mapping>
            """.trimIndent(),
        )
        assertNull("不存在的实体应返回 null", HbmXmlEntityResolver.resolve("Nonexistent", myFixture.project))
    }

    fun testTJ11_malformedClassDoesNotBlockSiblings() {
        // 单个 <class> 属性缺失只跳过自身，不影响同文件其余映射（优雅降级）
        myFixture.addFileToProject(
            "hibernate/Mixed.hbm.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <hibernate-mapping>
                <class table="T_NO_NAME">
                    <id name="id" column="ID"/>
                </class>
                <class name="com.demo.Healthy" table="T_HEALTHY">
                    <id name="id" column="HEALTHY_ID"/>
                </class>
            </hibernate-mapping>
            """.trimIndent(),
        )
        val healthy = HbmXmlEntityResolver.resolve("Healthy", myFixture.project)
        assertNotNull("畸形 <class> 不应阻断后续映射解析", healthy)
        assertEquals("T_HEALTHY", healthy!!.tableName)
    }
}
