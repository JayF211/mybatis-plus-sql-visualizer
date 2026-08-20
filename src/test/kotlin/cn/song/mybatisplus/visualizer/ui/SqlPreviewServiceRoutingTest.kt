package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * WrapperLocator → ConverterRegistry 分派接缝测试。
 *
 * 验证 WrapperLocator 定位结果正确分派到对应 converter：
 *  - new 表达式定位 → LambdaQueryConverter（LAMBDA_QUERY）
 *  - Wrappers 工厂链定位 → QueryWrapperConverter（QUERY）
 *  - update 消费定位 → UPDATE 语句（含 SET 占位）
 *  - delete 消费定位 → DELETE 语句（含全表删除警告）
 *  - count 消费定位 → SELECT COUNT(*)
 *  - exists 消费定位 → SELECT EXISTS（子查询包裹）
 *
 * 每个场景验证：定位成功 + 分派正确 + SQL 类型正确 + SQL 内容正确。
 */
class SqlPreviewServiceRoutingTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    /**
     * 定位锚点元素：文件内第一个方法调用；无调用时回退到 Wrapper 变量声明 / 字面量 / 任意元素。
     */
    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    /**
     * 接缝：WrapperLocator（new 表达式定位）→ ConverterRegistry（LambdaQueryConverter）→ SELECT。
     * 场景：`new LambdaQueryWrapper<Entity>().eq(...)` 内联构造，分派到 LambdaQueryConverter。
     */
    fun testNewExpressionRoutesToLambdaQueryConverter() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    list(new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1001L));
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // 定位验证：WrapperLocator 应识别为 LAMBDA_QUERY 类型
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("new 表达式应被 WrapperLocator 定位", located)
        assertEquals("new 表达式应识别为 LAMBDA_QUERY", WrapperType.LAMBDA_QUERY, located!!.chain.wrapperType)

        // 分派验证：preview 应产生 SELECT 语句（LambdaQueryConverter 分派）
        val result = newService().preview(anchor())
        assertNotNull("preview 应返回非 null 结果", result)
        assertTrue(
            "LambdaQueryConverter 分派应生成 SELECT: ${result!!.sql}",
            result.sql.startsWith("SELECT"),
        )
        assertTrue("SELECT 应含 WHERE 条件: ${result.sql}", result.sql.contains("WHERE"))
        assertEquals("device_info_entity", result.tableName)
        assertEquals(WrapperType.LAMBDA_QUERY, result.wrapperType)
    }

    /**
     * 接缝：WrapperLocator（Wrappers 工厂链定位）→ ConverterRegistry（QueryWrapperConverter）→ SELECT。
     * 场景：`Wrappers.<Entity>query().eq(...)` 静态工厂链，分派到 QueryWrapperConverter。
     */
    fun testFactoryChainRoutesToQueryWrapperConverter() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    QueryWrapper<DeviceInfoEntity> w = Wrappers.<DeviceInfoEntity>query()
                        .eq("id", 1001L);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // 定位验证：WrapperLocator 应识别为 QUERY 类型
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("工厂链应被 WrapperLocator 定位", located)
        assertEquals("Wrappers.query() 应识别为 QUERY", WrapperType.QUERY, located!!.chain.wrapperType)

        // 分派验证：preview 应产生 SELECT 语句（QueryWrapperConverter 分派）
        val result = newService().preview(anchor())
        assertNotNull("preview 应返回非 null 结果", result)
        assertTrue(
            "QueryWrapperConverter 分派应生成 SELECT: ${result!!.sql}",
            result.sql.startsWith("SELECT"),
        )
        assertEquals("device_info_entity", result.tableName)
        assertEquals(WrapperType.QUERY, result.wrapperType)
    }

    /**
     * 接缝：WrapperLocator（update 消费定位）→ SqlPreviewService.updateEntityConsumed 分支 → UPDATE。
     * 场景：`service.update(entity, wrapper)` 双参实体更新消费。
     */
    fun testUpdateConsumerRoutesToUpdateStatement() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void update() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.update(entity, w);
                }

                static class DeviceInfoEntity { private Long id; private String name; }
            }
            """.trimIndent(),
        )
        // 定位验证：updateEntityConsumed 应为 true
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("wrapper 变量应被定位", located)
        assertTrue(
            "service.update(entity, wrapper) 应标记 updateEntityConsumed",
            located!!.chain.updateEntityConsumed,
        )

        // 分派验证：preview 应产生 UPDATE 语句
        val result = newService().preview(anchor())
        assertNotNull("preview 应返回非 null 结果", result)
        assertTrue(
            "update 消费应生成 UPDATE: ${result!!.sql}",
            result.sql.startsWith("UPDATE"),
        )
        assertTrue("UPDATE 应含 SET 子句: ${result.sql}", result.sql.contains("SET"))
        assertTrue("UPDATE 应含 WHERE 条件: ${result.sql}", result.sql.contains("WHERE"))
        assertEquals("device_info_entity", result.tableName)
    }

    /**
     * 接缝：WrapperLocator（delete 消费定位）→ SqlPreviewService.deleteConsumed 分支 → DELETE。
     * 场景：`service.remove(wrapper)` 删除消费 + 无 WHERE 条件时全表删除警告。
     */
    fun testDeleteConsumerRoutesToDeleteStatementWithWarning() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void remove() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>();
                    service.remove(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // 定位验证：deleteConsumed 应为 true
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("wrapper 变量应被定位", located)
        assertTrue(
            "service.remove(wrapper) 应标记 deleteConsumed",
            located!!.chain.deleteConsumed,
        )

        // 分派验证：preview 应产生 DELETE 语句 + 全表删除警告
        val result = newService().preview(anchor())
        assertNotNull("preview 应返回非 null 结果", result)
        assertTrue(
            "delete 消费应生成 DELETE: ${result!!.sql}",
            result.sql.startsWith("DELETE FROM"),
        )
        assertTrue(
            "无 WHERE 条件的 DELETE 应含全表删除警告: ${result.warning}",
            result.warning?.contains("删除全表") == true,
        )
        assertEquals("device_info_entity", result.tableName)
    }

    /**
     * 接缝：WrapperLocator（count 消费定位）→ SqlPreviewService.countConsumed 分支 → SELECT COUNT(*)。
     * 场景：`service.count(wrapper)` 计数消费。
     */
    fun testCountConsumerRoutesToSelectCount() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void count() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.count(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // 定位验证：countConsumed 应为 true
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("wrapper 变量应被定位", located)
        assertTrue(
            "service.count(wrapper) 应标记 countConsumed",
            located!!.chain.countConsumed,
        )

        // 分派验证：preview 应产生 SELECT COUNT(*) 语句
        val result = newService().preview(anchor())
        assertNotNull("preview 应返回非 null 结果", result)
        assertTrue(
            "count 消费应生成 SELECT COUNT(*): ${result!!.sql}",
            result.sql.startsWith("SELECT") && result.sql.contains("COUNT"),
        )
        assertTrue("COUNT(*) 应保留 WHERE 条件: ${result.sql}", result.sql.contains("WHERE"))
        assertEquals("device_info_entity", result.tableName)
    }

    /**
     * 接缝：WrapperLocator（exists 消费定位）→ SqlPreviewService.existsConsumed 分支 → SELECT EXISTS（子查询）。
     * 场景：`service.exists(wrapper)` exists 消费。
     */
    fun testExistsConsumerRoutesToSelectExists() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void exists() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.exists(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // 定位验证：existsConsumed 应为 true
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("wrapper 变量应被定位", located)
        assertTrue(
            "service.exists(wrapper) 应标记 existsConsumed",
            located!!.chain.existsConsumed,
        )

        // 分派验证：preview 应产生 SELECT EXISTS (...) 语句
        val result = newService().preview(anchor())
        assertNotNull("preview 应返回非 null 结果", result)
        assertTrue(
            "exists 消费应生成 SELECT EXISTS: ${result!!.sql}",
            result.sql.startsWith("SELECT EXISTS"),
        )
        assertTrue("EXISTS 应含子查询括号: ${result.sql}", result.sql.contains("(") && result.sql.contains(")"))
        assertTrue("EXISTS 子查询应保留 WHERE 条件: ${result.sql}", result.sql.contains("WHERE"))
        assertEquals("device_info_entity", result.tableName)
    }
}
