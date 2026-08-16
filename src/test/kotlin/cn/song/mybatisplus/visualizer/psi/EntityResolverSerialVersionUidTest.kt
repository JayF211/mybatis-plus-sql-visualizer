package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * EntityResolver serialVersionUID 默认忽略测试（T-M42，层 1 源头）：
 *  - 实体含 serialVersionUID 字段 → columns 不含该列（columnToField 无映射，对齐 MP 官方跳过
 *    static 字段语义）；正向 SELECT 全列展开/批量面板/补全统一受益
 *  - 父类继承场景同样生效（BaseEntity 常驻 serialVersionUID）
 *  - 普通业务字段（含 serial 前缀）不受影响（回归）
 */
class EntityResolverSerialVersionUidTest : BasePlatformTestCase() {

    private fun resolveEntity(code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    /** 按类名查找实体（文件含多个类时避免取到首个 BaseEntity）。 */
    private fun resolveEntityByName(name: String, code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .first { it.name == name }
    }

    fun testSerialVersionUidFieldNotMappedAsColumn() {
        val clazz = resolveEntity(
            """
            public class User {
                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;

                private static final long serialVersionUID = 1L;

                private String username;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        // columns 不含 serial_version_uid（columnToField 构建点全部源于 columns → 无映射）
        assertEquals(listOf("id", "username"), table.allColumnNames)
        assertTrue(table.columns.none { it.fieldName == "serialVersionUID" })
    }

    fun testSerialVersionUidInSuperClassNotMapped() {
        // 父类 BaseEntity 含 serialVersionUID（常见形态）→ 继承字段收集后同样跳过
        val clazz = resolveEntityByName(
            "DeviceInfoEntity",
            """
            public class BaseEntity {
                private static final long serialVersionUID = 1L;
                private String createBy;
            }

            public class DeviceInfoEntity extends BaseEntity {
                private String deviceName;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        assertEquals(listOf("create_by", "device_name"), table.allColumnNames)
    }

    fun testNormalFieldsUnaffected() {
        // 回归：普通字段照常映射；serial 前缀的业务字段不受误伤
        val clazz = resolveEntity(
            """
            public class User {
                private String serialNo;
                private String name;
            }
            """.trimIndent(),
        )
        assertEquals(listOf("serial_no", "name"), EntityResolver().resolve(clazz).allColumnNames)
    }
}
