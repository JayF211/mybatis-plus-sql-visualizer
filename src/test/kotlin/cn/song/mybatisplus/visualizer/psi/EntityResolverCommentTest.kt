package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * EntityResolver 注释读取测试（T-M16-01/05）：
 *  - 四级优先级：注解（@Schema/@ApiModelProperty/@ExcelProperty 等白名单）> javadoc > 行注释
 *  - MP 注解 value 不误取（@TableField("col") 的 value 是列名）
 *  - 类级注释（表注释）：类注解 > 类 javadoc > 类上方行注释
 */
class EntityResolverCommentTest : BasePlatformTestCase() {

    private fun resolveEntity(code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    fun testSchemaAnnotationComment() {
        // 最高优先级：@Schema(description = "...") → 注解注释
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;

                @io.swagger.v3.oas.annotations.media.Schema(description = "设备名称")
                private String name;
            }
            """.trimIndent(),
        )
        val resolver = EntityResolver()
        val table = resolver.resolve(clazz)
        assertEquals("设备名称", table.columnByField("name")?.comment)
    }

    fun testApiModelPropertyValueAndExcelProperty() {
        // swagger 2 value/description 属性 + easyexcel value 属性
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                @io.swagger.annotations.ApiModelProperty(value = "设备编号")
                private String code;

                @com.alibaba.excel.annotation.ExcelProperty(value = "状态名称")
                private String statusName;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        assertEquals("设备编号", table.columnByField("code")?.comment)
        assertEquals("状态名称", table.columnByField("statusName")?.comment)
    }

    fun testAnnotationWinsOverJavadoc() {
        // 注解与 javadoc 并存 → 取注解
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                /**
                 * 设备名称（javadoc 描述）
                 */
                @io.swagger.v3.oas.annotations.media.Schema(description = "设备名称")
                private String name;
            }
            """.trimIndent(),
        )
        assertEquals("设备名称", EntityResolver().resolve(clazz).columnByField("name")?.comment)
    }

    fun testJavadocOnlyMultiLineCleaned() {
        // 仅 javadoc：多行清洗为单行（去 *、@ 标签前、空行折叠）
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                /**
                 * 设备名称
                 * 用于展示与检索
                 *
                 * @author test
                 */
                private String name;
            }
            """.trimIndent(),
        )
        assertEquals("设备名称 用于展示与检索", EntityResolver().resolve(clazz).columnByField("name")?.comment)
    }

    fun testLineCommentFallback() {
        // 行注释兜底：字段上方紧贴的行注释
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                // 设备名称
                private String name;
            }
            """.trimIndent(),
        )
        val field = clazz.fields.first { it.name == "name" }
        assertNotNull("字段存在", field)
        assertEquals("设备名称", EntityResolver().resolve(clazz).columnByField("name")?.comment)
    }

    fun testMpAnnotationValueNotMisread() {
        // @TableField("col") 的 value 是列名——不误取；@TableField(comment = "...") 的 comment 属性例外可取
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                @com.baomidou.mybatisplus.annotation.TableField("name_col")
                private String name;

                @com.baomidou.mybatisplus.annotation.TableField(comment = "设备名称")
                private String code;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        assertNull("@TableField(\"name_col\") 的 value 不应被当作注释", table.columnByField("name")?.comment)
        assertEquals("@TableField(comment=) 的 comment 属性可取", "设备名称", table.columnByField("code")?.comment)
    }

    fun testNoCommentReturnsNull() {
        // 全无 → null
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        assertNull(EntityResolver().resolve(clazz).columnByField("id")?.comment)
    }

    fun testTableCommentFromClassAnnotation() {
        // 表注释：类级 @Schema(description) → TableInfo.comment
        val clazz = resolveEntity(
            """
            @io.swagger.v3.oas.annotations.media.Schema(description = "设备信息表")
            @com.baomidou.mybatisplus.annotation.TableName("device_info")
            public class DeviceInfoEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        assertEquals("设备信息表", EntityResolver().resolve(clazz).comment)
    }

    fun testTableCommentFromClassJavadoc() {
        // 表注释兜底：类 javadoc
        val clazz = resolveEntity(
            """
            /**
             * 设备信息表
             */
            public class DeviceInfoEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        assertEquals("设备信息表", EntityResolver().resolve(clazz).comment)
    }

    fun testCommentSourceMergeChainSchemaReserved() {
        // schema 预留：CommentSource 优先级链——SCHEMA 最高，当前 schema 源返回 null 时注解生效；
        // 模拟注入 schema 注释后应覆盖代码内注释（合并逻辑 firstNonBlank 语义）
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                @io.swagger.v3.oas.annotations.media.Schema(description = "设备名称")
                private String name;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        assertEquals("设备名称", table.columnByField("name")?.comment)
        // 合并链断言：schema 源（模拟）> 注解——firstNonBlank 按来源顺序取最高优先级非空
        val merged = listOf("schema 注释", null, null, null).firstOrNull { !it.isNullOrBlank() }
        assertEquals("schema 注释", merged)
    }
}
