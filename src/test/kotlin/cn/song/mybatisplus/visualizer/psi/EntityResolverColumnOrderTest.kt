package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * EntityResolver 字段顺序角色分组测试（T-M23.3）：
 *  - 排序键 PRIMARY(0) < BUSINESS(1) < AUDIT(2) < SYSTEM(3)，组内**稳定排序**保持声明顺序
 *  - @TableId 置顶；fill 自动填充字段（AUDIT）与 @TableLogic/@Version（SYSTEM）沉底；业务字段居中
 *  - 纯业务实体（无审计字段）顺序 = 声明顺序（零变化回归）
 */
class EntityResolverColumnOrderTest : BasePlatformTestCase() {

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

    fun testBaseEntityInheritanceAuditFieldsSink() {
        // 用户场景：BaseEntity（@TableId + fill INSERT/INSERT_UPDATE + @TableLogic）继承——
        // id 置顶、业务字段居中、fill 审计字段与逻辑删除沉底
        val clazz = resolveEntityByName(
            "DeviceInfoEntity",
            """
            public class BaseEntity {
                @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
                private Long id;

                @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
                private java.time.LocalDateTime createTime;

                @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
                private java.time.LocalDateTime updateTime;

                @com.baomidou.mybatisplus.annotation.TableLogic
                private Integer deleted;
            }

            public class DeviceInfoEntity extends BaseEntity {
                private String deviceName;
                private Integer status;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        // id(PRIMARY) 置顶 → 业务字段(BUSINESS) 居中 → create/update(fill=AUDIT) → deleted(SYSTEM) 沉底
        assertEquals(
            listOf("id", "device_name", "status", "create_time", "update_time", "deleted"),
            table.allColumnNames,
        )
    }

    fun testPureBusinessEntityOrderUnchanged() {
        // 无审计字段实体：角色分组不改变声明顺序（零变化回归）
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;

                private String code;

                private String name;
            }
            """.trimIndent(),
        )
        assertEquals(listOf("id", "code", "name"), EntityResolver().resolve(clazz).allColumnNames)
    }

    fun testVersionAndLogicDeleteBothSink() {
        // @Version 与 @TableLogic 同为 SYSTEM 沉底组，组内保持声明顺序（deleted 在前则 version 在后）
        val clazz = resolveEntityByName(
            "DeviceInfoEntity",
            """
            public class BaseEntity {
                private String createBy;

                @com.baomidou.mybatisplus.annotation.TableLogic
                private Integer deleted;

                @com.baomidou.mybatisplus.annotation.Version
                private Integer version;
            }

            public class DeviceInfoEntity extends BaseEntity {
                private String name;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        // createBy(BUSINESS) 居中；deleted/version(SYSTEM) 沉底且保持声明顺序
        assertEquals(listOf("create_by", "name", "deleted", "version"), table.allColumnNames)
    }

    fun testFillFieldsKeepDeclarationOrderInSinkGroup() {
        // 多个 fill 字段（AUDIT 组）沉底后组内保持声明顺序
        val clazz = resolveEntityByName(
            "DeviceInfoEntity",
            """
            public class BaseEntity {
                @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
                private java.time.LocalDateTime createTime;

                @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
                private java.time.LocalDateTime updateTime;

                @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
                private String updateBy;
            }

            public class DeviceInfoEntity extends BaseEntity {
                private String deviceName;
            }
            """.trimIndent(),
        )
        val table = EntityResolver().resolve(clazz)
        // 业务字段居中；三个 fill 字段按声明顺序沉底（create_time < update_time < update_by）
        assertEquals(listOf("device_name", "create_time", "update_time", "update_by"), table.allColumnNames)
    }

    fun testPrimaryKeyMovesToFront() {
        // 主键置顶独立于声明位置：@TableId 声明在业务字段之后也移到最前
        val clazz = resolveEntity(
            """
            public class DeviceInfoEntity {
                private String code;

                private String name;

                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;
            }
            """.trimIndent(),
        )
        assertEquals(listOf("id", "code", "name"), EntityResolver().resolve(clazz).allColumnNames)
    }
}
