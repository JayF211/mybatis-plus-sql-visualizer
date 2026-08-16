package cn.song.mybatisplus.visualizer.heavy

import cn.song.mybatisplus.visualizer.psi.EntityClassFinder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 实体类查找 Heavy 测试（T-M36.2：SQL 反向转换 Lambda 模式的实体上下文解析）。
 *
 * 按表名在**索引**中检索实体类（PsiShortNamesCache）——跨文件检索依赖真实索引，
 * Light 环境不可用（M27 实证：Light 跨文件 resolve/FileBasedIndex 不可用），故用
 * [HeavyPlatformTestCase]（磁盘项目 + 索引；文件经 createTestProjectStructure 落磁盘）。
 */
class EntityClassFinderHeavyTest : HeavyPlatformTestCase() {

    /** 在项目 src 根下创建 Java 源文件（磁盘 + 索引），返回 PsiClass。 */
    private fun addClassFile(name: String, content: String): PsiClass {
        return WriteCommandAction.runWriteCommandAction(project, Computable {
            val root = createTestProjectStructure()
            val src = root.findChild("src") ?: root.createChildDirectory(this, "src")
            val vf = src.createChildData(this, name)
            VfsUtil.saveText(vf, content.trimIndent())
            PsiManager.getInstance(project).findFile(vf)!!
        }).let { file ->
            PsiTreeUtil.findChildOfType(file, PsiClass::class.java)!!
        }
    }

    fun testTableNameAnnotationMatch() {
        addClassFile(
            "CarDeviceInfoEntity.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("car_device_info")
            public class CarDeviceInfoEntity {
                private Long id;
                private String name;
            }
            """,
        )
        val found = EntityClassFinder(project).find("car_device_info")
        assertEquals(1, found.size)
        assertEquals("CarDeviceInfoEntity", found[0].name)
    }

    fun testClassNameInferenceMatchWithoutAnnotation() {
        // 无 @TableName：按命名策略推断（User → user）
        addClassFile(
            "User.java",
            """
            public class User {
                private Long id;
                private String name;
            }
            """,
        )
        val found = EntityClassFinder(project).find("user")
        assertEquals(1, found.size)
        assertEquals("User", found[0].name)
    }

    fun testEntitySuffixCandidate() {
        // 候选类名回退：CarDeviceInfo 不存在、CarDeviceInfoEntity 存在（@TableName 匹配）
        addClassFile(
            "CarDeviceInfoEntity.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("car_device_info")
            public class CarDeviceInfoEntity {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("car_device_info")
        assertEquals(1, found.size)
        assertEquals("CarDeviceInfoEntity", found[0].name)
    }

    fun testTableNameMismatchExcluded() {
        // 同名类但 @TableName 指向别的表 → 排除（防误配）
        addClassFile(
            "CarDeviceInfo.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("car_device_log")
            public class CarDeviceInfo {
                private Long id;
            }
            """,
        )
        assertTrue(EntityClassFinder(project).find("car_device_info").isEmpty())
    }

    fun testVoDtoClassesExcluded() {
        // T-M36.3：VO/DTO 类即使表名匹配也被排除（命名惯例显式防御）
        addClassFile(
            "User.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("user")
            public class User {
                private Long id;
                private String name;
            }
            """,
        )
        addClassFile(
            "UserVO.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("user")
            public class UserVO {
                private String name;
            }
            """,
        )
        addClassFile(
            "UserDto.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("user")
            public class UserDto {
                private String name;
            }
            """,
        )
        val found = EntityClassFinder(project).find("user")
        assertEquals(1, found.size)
        assertEquals("User", found[0].name)
    }

    fun testNoMatchReturnsEmpty() {
        assertTrue(EntityClassFinder(project).find("not_exist_table").isEmpty())
    }

    // ------------------------------------------------------------------
    // @TableName 反查慢路径（类名与表名驼峰不一致时的兜底）
    // ------------------------------------------------------------------

    fun testTableNameAnnotationReverseLookup() {
        // 实体命名与表名驼峰不一致（AccountRoleRelEntity ↔ sys_account_role_rel）：
        // 类名推断候选 [SysAccountRoleRel, SysAccountRoleRelEntity] 无法命中 → @TableName 反查兜底
        addClassFile(
            "AccountRoleRelEntity.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class AccountRoleRelEntity {
                private Long roleId;
                private Long accountId;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(1, found.size)
        assertEquals("AccountRoleRelEntity", found[0].name)
    }

    fun testTableNameAnnotationReverseLookupIgnoreCase() {
        // 注解值大小写不同（SYS_ACCOUNT_ROLE_REL）仍命中（表名比较大小写不敏感）
        addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("SYS_ACCOUNT_ROLE_REL")
            public class RoleRel {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(1, found.size)
        assertEquals("RoleRel", found[0].name)
    }

    fun testReverseLookupMultipleCandidates() {
        // 反查命中多个候选（不同类名同一 @TableName）→ 全部返回（调用方弹选择框）
        addClassFile(
            "AccountRoleRelEntity.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class AccountRoleRelEntity {
                private Long id;
            }
            """,
        )
        addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRel {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(2, found.size)
        assertEquals(listOf("AccountRoleRelEntity", "RoleRel"), found.map { it.name })
    }

    fun testReverseLookupVoDtoExcluded() {
        // 反查路径同样排除 VO/DTO 命名
        addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRel {
                private Long id;
            }
            """,
        )
        addClassFile(
            "RoleRelVO.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelVO {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(1, found.size)
        assertEquals("RoleRel", found[0].name)
    }

    fun testReverseLookupAnnotationMismatchExcluded() {
        // 反查命中判定依赖表名匹配——注解指向别的表的类不命中
        addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("other_table")
            public class RoleRel {
                private Long id;
            }
            """,
        )
        assertTrue(EntityClassFinder(project).find("sys_account_role_rel").isEmpty())
    }
}
