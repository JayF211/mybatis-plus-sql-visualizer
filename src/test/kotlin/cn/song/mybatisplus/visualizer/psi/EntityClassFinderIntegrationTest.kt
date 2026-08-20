package cn.song.mybatisplus.visualizer.psi

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * EntityClassFinder 反查命中后正确传递到下游的接缝测试。
 *
 * 验证 EntityClassFinder 与下游 SqlToJavaConverter / JavaCodeGenerator 的接缝：
 *  - @TableName 实体 find 返回正确 qualifiedName
 *  - 表名推断命中（sys_ 前缀去首段）
 *  - 反查缓存命中后第二次 find 零扫描
 *  - 缓存清除后重新扫描
 *
 * 依赖真实索引（PsiShortNamesCache），使用 [HeavyPlatformTestCase]。
 */
class EntityClassFinderIntegrationTest : HeavyPlatformTestCase() {

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

    /**
     * 接缝：EntityClassFinder（@TableName 命中）→ 下游 SqlToJavaConverter（qualifiedName 传递）。
     * 场景：有 @TableName 实体时，find 返回正确 qualifiedName。
     */
    fun testAnnotatedEntityReturnsCorrectQualifiedName() {
        val clazz = addClassFile(
            "DeviceInfoEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }
            """,
        )
        // 验证 @TableName 实体创建成功
        assertNotNull("实体类应创建成功", clazz)
        assertEquals("DeviceInfoEntity", clazz.name)

        // find 返回命中
        val found = EntityClassFinder(project).find("device_info")
        assertEquals("应找到 1 个实体", 1, found.size)

        // 验证 qualifiedName 正确传递到下游
        assertEquals("com.example.DeviceInfoEntity", found[0].qualifiedName)
        assertEquals("device_info", EntityResolver().resolve(found[0]).tableName)
    }

    /**
     * 接缝：EntityClassFinder（表名推断命中）→ 下游（qualifiedName 传递）。
     * 场景：sys_ 前缀去首段推断命中（sys_account_role_rel → AccountRoleRel）。
     */
    fun testTableNameInferenceHitsByRemovingFirstSegment() {
        addClassFile(
            "AccountRoleRelEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class AccountRoleRelEntity {
                private Long roleId;
                private Long accountId;
            }
            """,
        )
        // 表名推断：sys_account_role_rel → 去首段 AccountRoleRel → 加 Entity 后缀命中
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals("去首段推断应命中 1 个实体", 1, found.size)
        assertEquals("AccountRoleRelEntity", found[0].name)
        assertEquals("com.example.AccountRoleRelEntity", found[0].qualifiedName)
    }

    /**
     * 接缝：EntityClassFinder（缓存命中）→ 下游（零扫描）。
     * 场景：反查缓存命中后第二次 find 零扫描（性能接缝）。
     */
    fun testCacheHitResultsInZeroScansOnSecondFind() {
        addClassFile(
            "RoleRelEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelEntity {
                private Long id;
            }
            """,
        )
        val finder = EntityClassFinder(project)

        // 首次 find：全量扫描 1 次
        val first = finder.find("sys_account_role_rel")
        assertEquals("首次 find 应命中", 1, first.size)
        val scansAfterFirst = EntityClassFinder.scanCountForTest(project)
        assertEquals("首次 find 应触发 1 次全量扫描", 1, scansAfterFirst)

        // 第二次 find（同表名）：缓存命中，零扫描
        val second = finder.find("sys_account_role_rel")
        assertEquals("二次 find 应同样命中", 1, second.size)
        val scansAfterSecond = EntityClassFinder.scanCountForTest(project)
        assertEquals("缓存命中后二次 find 应零扫描", scansAfterFirst, scansAfterSecond)

        // 验证下游收到的 qualifiedName 一致（缓存传递一致性）
        assertEquals("缓存命中后 qualifiedName 应一致", first[0].qualifiedName, second[0].qualifiedName)
    }

    /**
     * 接缝：EntityClassFinder（缓存清除）→ 下游（重新扫描）。
     * 场景：缓存清除后重新扫描，find 仍能命中。
     */
    fun testCacheClearTriggersRescan() {
        // 使用与表名驼峰无关的类名（RoleRel ↔ sys_account_role_rel）：快路径候选
        // [SysAccountRoleRel, SysAccountRoleRelEntity, AccountRoleRel, AccountRoleRelEntity] 不含 RoleRel → 走反查慢路径
        addClassFile(
            "RoleRel.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRel {
                private Long id;
            }
            """,
        )
        val finder = EntityClassFinder(project)

        // 首次 find → 反查慢路径 → 缓存
        assertEquals(1, finder.find("sys_account_role_rel").size)
        assertEquals("首次扫描应为 1 次", 1, EntityClassFinder.scanCountForTest(project))

        // 清除缓存
        EntityClassFinder.clearReverseLookupCache(project)
        assertEquals("清除后缓存信息应归零", 0 to 0, EntityClassFinder.reverseLookupCacheInfo(project))

        // 再次 find → 重新扫描
        val afterClear = finder.find("sys_account_role_rel")
        assertEquals("清除缓存后 find 应仍能命中", 1, afterClear.size)
        assertEquals("RoleRel", afterClear[0].name)
        // 清除缓存后重新扫描：新缓存实例扫描计数重置，扫描 1 次
        assertEquals("清除缓存后 find 应重新扫描 1 次", 1, EntityClassFinder.scanCountForTest(project))
    }

    /**
     * 接缝：EntityClassFinder（find 结果）→ 下游 SqlToJavaConverter（多候选顺序传递）。
     * 场景：多候选实体时，调用方按 find 返回的顺序处理。
     */
    fun testMultipleCandidatesPassedInOrder() {
        addClassFile(
            "RoleRelA.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelA {
                private Long id;
            }
            """,
        )
        addClassFile(
            "RoleRelB.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelB {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals("应返回 2 个候选", 2, found.size)
        // 验证顺序：按类名排序（A 先于 B）
        assertEquals(listOf("RoleRelA", "RoleRelB"), found.map { it.name })
        // 验证下游能正确获取每个候选的 qualifiedName
        assertEquals("com.example.RoleRelA", found[0].qualifiedName)
        assertEquals("com.example.RoleRelB", found[1].qualifiedName)
    }

    /**
     * 接缝：EntityClassFinder（PSI 修改 → 缓存失效）→ 下游（新表名可被找到）。
     * 场景：文档修改后缓存失效，重新扫描找到修改后的 @TableName。
     */
    fun testPsiModificationInvalidatesCache() {
        // 使用与表名驼峰无关的类名（RoleRel ↔ sys_account_role_rel）：快路径不命中 → 走反查慢路径
        val psiFile = addClassFile(
            "RoleRel.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("old_table")
            public class RoleRel {
                private Long id;
            }
            """,
        ).containingFile

        // 首次 find 旧表名（走反查慢路径，因为 RoleRel 不在 old_table 的快路径候选中）
        assertEquals(1, EntityClassFinder(project).find("old_table").size)
        assertEquals("首次扫描应为 1 次", 1, EntityClassFinder.scanCountForTest(project))

        // 新表名此时找不到（走反查慢路径 → 负缓存）
        assertTrue("新表名修改前应找不到", EntityClassFinder(project).find("new_table").isEmpty())

        // 修改文档 @TableName → PSI modificationCount 递增 → 缓存失效
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
        WriteCommandAction.runWriteCommandAction(project, Computable {
            document.setText(
                """
                package com.example;
                import com.baomidou.mybatisplus.annotation.TableName;

                @TableName("new_table")
                public class RoleRel {
                    private Long id;
                }
                """.trimIndent(),
            )
            null
        })
        PsiDocumentManager.getInstance(project).commitDocument(document)

        // 缓存失效后重新扫描 → 新表名可被找到
        val found = EntityClassFinder(project).find("new_table")
        assertEquals("PSI 修改后应找到新表名实体", 1, found.size)
        assertEquals("RoleRel", found[0].name)
    }

    /**
     * 接缝：EntityClassFinder（无匹配）→ 下游（空列表传递，调用方回落字符串列名形态）。
     * 场景：无实体匹配时返回空列表，下游调用方应正确处理空列表。
     */
    fun testNoMatchReturnsEmptyListForDownstreamFallback() {
        val found = EntityClassFinder(project).find("non_existent_table_xyz")
        assertTrue("无匹配时应返回空列表", found.isEmpty())
        // 验证负缓存生效（再次 find 不再扫描）
        val firstScanCount = EntityClassFinder.scanCountForTest(project)
        EntityClassFinder(project).find("non_existent_table_xyz")
        assertEquals("负缓存命中后不应再扫描", firstScanCount, EntityClassFinder.scanCountForTest(project))
    }

    /**
     * 接缝：EntityClassFinder（VO/DTO 排除）→ 下游（不含 VO/DTO 的候选）。
     * 场景：VO/DTO 类即使表名匹配也被排除，下游只收到真正的实体类。
     */
    fun testVoDtoExcludedFromDownstreamCandidates() {
        addClassFile(
            "UserEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("t_user")
            public class UserEntity {
                private Long id;
                private String name;
            }
            """,
        )
        addClassFile(
            "UserVO.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("t_user")
            public class UserVO {
                private String name;
            }
            """,
        )
        val found = EntityClassFinder(project).find("t_user")
        assertEquals("VO 排除后应只返回实体类", 1, found.size)
        assertEquals("UserEntity", found[0].name)
        assertEquals("com.example.UserEntity", found[0].qualifiedName)
    }
}
