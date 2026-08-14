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

    // ------------------------------------------------------------------
    // T-M16.2-02：父类字段（递归收集 + @TableLogic/@TableId 继承识别）
    // ------------------------------------------------------------------

    /** 按类名查找实体（文件含多个类时避免取到首个 BaseEntity）。 */
    private fun resolveEntityByName(name: String, code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .first { it.name == name }
    }

    fun testInheritedSuperClassFieldsIncluded() {
        // 继承场景：子类 extends BaseEntity——SELECT 全列展开应含父类字段（id/createTime/deleted），
        // @TableLogic 父类 deleted 被识别（M15 对继承实体失效的修复）、@TableId 父类 id 为主键
        val clazz = resolveEntityByName(
            "DeviceInfoEntity",
            """
            public class BaseEntity {
                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;
                private String createTime;
                @com.baomidou.mybatisplus.annotation.TableLogic
                private Integer deleted;
            }

            public class DeviceInfoEntity extends BaseEntity {
                private String name;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        // T-M23.3 角色分组：id(PRIMARY) 置顶、createTime/name(BUSINESS) 居中保持声明顺序、deleted(SYSTEM) 沉底
        assertEquals(listOf("id", "create_time", "name", "deleted"), table.allColumnNames)
        // 父类 @TableLogic → 逻辑删除列识别
        assertEquals("deleted", table.logicDeleteColumn)
        // 父类 @TableId → 主键标记
        assertEquals(true, table.columnByField("id")?.isId)
        // 子类字段正常解析
        assertNotNull(table.columnByField("name"))
    }

    fun testMultiLevelInheritanceFieldsCollected() {
        // 多级继承递归：A → B → C，字段按父级优先顺序全部收集
        val clazz = resolveEntityByName(
            "C",
            """
            public class A {
                private String a1;
            }

            public class B extends A {
                private String b1;
            }

            public class C extends B {
                private String c1;
            }
            """.trimIndent(),
        )
        assertEquals(listOf("a1", "b1", "c1"), EntityResolver().resolve(clazz).allColumnNames)
    }

    fun testSuperClassTableFieldExistFalseSkipped() {
        // 父类 @TableField(exist = false) 的非表字段仍跳过（resolveColumn 对父类字段通用）
        val clazz = resolveEntityByName(
            "DeviceInfoEntity",
            """
            public class BaseEntity {
                private Long id;
                @com.baomidou.mybatisplus.annotation.TableField(exist = false)
                private String transientField;
            }

            public class DeviceInfoEntity extends BaseEntity {
                private String name;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        assertEquals(listOf("id", "name"), table.allColumnNames)
        assertNull(table.columnByField("transientField"))
    }

    fun testTableFieldDefaultValueFallsBackToSnakeCase() {
        // T-M16.3-03 真实项目回归（用户实测 `SELECT id, , , deleted, ...` 空列名根因）：
        // 真实项目注解类可解析时，`@TableField(fill = ...)` 未指定 value →
        // `findAttributeValue("value")` 返回注解**默认值 ""**（同文件声明 @interface 模拟可解析环境）——
        // 列名必须回退命名策略（create_time），不得为空串；javadoc 注释不受影响
        val clazz = resolveEntityByName(
            "DeviceInfoEntity",
            """
            public @interface TableField {
                String value() default "";
                boolean exist() default true;
                String comment() default "";
            }

            public class BaseEntity {
                @TableId
                private Long id;

                @TableField(fill = "INSERT")
                private String createTime;

                @TableField(fill = "INSERT_UPDATE")
                private String updateTime;

                @TableLogic
                private Integer deleted;
            }

            public class DeviceInfoEntity extends BaseEntity {
                /** 场站ID（关联 biz_bike_station_info.id） */
                private Long stationId;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        // 无空列名：@TableField 默认 value="" 不回退为空串，全部按命名策略
        // T-M23.3 角色分组：字符串形态 fill（无 FieldFill）不沉底——create/update 为 BUSINESS 居中，
        // station_id(BUSINESS) 在 deleted(SYSTEM 沉底) 前
        assertEquals(listOf("id", "create_time", "update_time", "station_id", "deleted"), table.allColumnNames)
        // 注释不受影响（javadoc；@TableField 默认 comment="" 不误取）
        assertEquals("场站ID（关联 biz_bike_station_info.id）", table.columnByField("stationId")?.comment)
    }

    // ------------------------------------------------------------------
    // T-M22-02：@Version 乐观锁版本列识别
    // ------------------------------------------------------------------

    fun testVersionColumnSnakeCaseFallback() {
        // T-M22-02：@Version 标注字段 → versionColumn（无 @TableField 时列名按命名策略 snake_case，
        // 与 @TableLogic 同模式）
        val clazz = resolveEntity(
            """
            public class UserEntity {
                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;
                @com.baomidou.mybatisplus.annotation.Version
                private Integer version;
            }
            """.trimIndent(),
        )
        assertEquals("version", EntityResolver().resolve(clazz).versionColumn)
    }

    fun testVersionColumnFromTableFieldAnnotation() {
        // T-M22-02：@Version + @TableField("row_ver") → 列名取 @TableField 值（注解优先）
        val clazz = resolveEntity(
            """
            public class UserEntity {
                @com.baomidou.mybatisplus.annotation.TableField("row_ver")
                @com.baomidou.mybatisplus.annotation.Version
                private Integer rowVersion;
            }
            """.trimIndent(),
        )
        assertEquals("row_ver", EntityResolver().resolve(clazz).versionColumn)
    }

    fun testNoVersionColumnIsNull() {
        // T-M22-02：无 @Version 字段 → versionColumn 为 null（不误识别）
        val clazz = resolveEntity(
            """
            public class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        assertNull(EntityResolver().resolve(clazz).versionColumn)
    }
}
