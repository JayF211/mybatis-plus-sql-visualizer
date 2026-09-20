package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.psi.EntityClassFinder
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * PluginCacheManager 统计覆盖测试（设置页缓存信息恒 0 B 修复）：
 *
 * 根因（2026-09-04 用户反馈「缓存信息一直是 0B」）：`sizeBytes` 只统计 EntityClassFinder
 * **反查兜底缓存**——该缓存仅在「类名推断快路径未命中」时才填充；真实项目实体命名惯例
 * （`XxxEntity` ↔ `xxx`）下快路径恒命中，反查缓存恒空 → 展示恒 0 B。
 * 而真正持续积累的两类缓存完全未计入：EntityResolver 解析缓存（T-EntityCache，全局）、
 * SqlPreviewService 的 Wrapper 解析缓存（项目服务）。
 *
 *  - 快路径命中：反查缓存恒空 + 解析缓存积累（复现恒 0 B 的场景）
 *  - totalSizeBytes / entryCount：修复后的统计口径（反查 + 解析 + Wrapper 预览）
 *  - clearAll：清除后全部归零
 *
 * 依赖真实索引（PsiShortNamesCache），使用 [HeavyPlatformTestCase]。
 */
class PluginCacheManagerCoverageTest : HeavyPlatformTestCase() {

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
     * 复现根因：主流命名（DeviceInfoEntity ↔ device_info）走快路径，反查缓存恒空；
     * 但 EntityResolver 解析缓存已积累（find 内 resolve 校验触发）——修复前 totalSizeBytes
     * 只看反查缓存 → 展示恒 0 B。
     */
    fun testFastPathHitLeavesReverseLookupEmptyWhileResolverAccumulates() {
        // T-ST-01：前置由本用例自建（清空项目级反查缓存），不依赖「本用例开始时项目还没有反查
        // 缓存条目」这一环境假设——否则同项目更早的用例/类若走过慢路径，断言会为无关原因变红。
        EntityClassFinder.clearReverseLookupCache(project)
        addClassFile(
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
        assertEquals(1, EntityClassFinder(project).find("device_info").size)
        assertEquals("快路径命中时反查缓存应为空（恒 0 B 的直接原因）", 0L, PluginCacheManager.sizeBytes(project))
        assertTrue("解析缓存应已积累（修复前未计入统计）", EntityResolver.cacheSize() > 0)
    }

    /** 修复目标：统计口径覆盖解析缓存——主链路使用后 totalSizeBytes/entryCount 必须非零。 */
    fun testTotalSizeCoversResolverCacheAfterFastPathFind() {
        addClassFile(
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
        assertEquals(1, EntityClassFinder(project).find("device_info").size)
        assertTrue(
            "totalSizeBytes 应计入解析缓存（修复前主链路恒 0 B）",
            PluginCacheManager.totalSizeBytes(listOf(project)) > 0,
        )
        assertTrue("entryCount 应大于 0", PluginCacheManager.entryCount(listOf(project)) > 0)
    }

    /** 对照：慢路径兜底触发时反查缓存有值（机制本身通，修复前后均应非零）。 */
    fun testSlowPathPopulatesReverseLookupSize() {
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
        assertEquals(1, EntityClassFinder(project).find("sys_account_role_rel").size)
        assertTrue("慢路径触发后 sizeBytes 应非零", PluginCacheManager.sizeBytes(project) > 0)
        assertTrue(
            "慢路径触发后 totalSizeBytes 应非零",
            PluginCacheManager.totalSizeBytes(listOf(project)) > 0,
        )
    }

    /** clearAll 语义：清除后 totalSizeBytes / entryCount 全部归零。 */
    fun testClearAllZerosTotalSizeAndEntryCount() {
        addClassFile(
            "DeviceInfoEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("device_info")
            public class DeviceInfoEntity {
                private Long id;
            }
            """,
        )
        assertEquals(1, EntityClassFinder(project).find("device_info").size)
        assertTrue(PluginCacheManager.totalSizeBytes(listOf(project)) > 0)

        PluginCacheManager.clearAll(project)
        assertEquals("清除后 totalSizeBytes 应归零", 0L, PluginCacheManager.totalSizeBytes(listOf(project)))
        assertEquals("清除后 entryCount 应归零", 0, PluginCacheManager.entryCount(listOf(project)))
    }

    /**
     * 清除动作不得实例化项目服务（Wrapper 缓存经 getServiceIfCreated 访问，未创建跳过）。
     *
     * T-ST-01：断言形态为「调用前实例 == 调用后实例」而非「调用后应为 null」——后者断言的是
     * **环境**（测试项目此刻还没创建过该服务）而非被测行为，任何先创建过该服务的代码路径
     * （同用例前置、同项目更早的用例/类）都会让它红，而 clearAll 什么都没做错。前者语义不变
     * （仍能抓「clearAll 创建了服务」：未创建时是 null == null，已创建时断言未被换成新实例），
     * 但去掉了对环境状态的依赖。
     */
    fun testClearAllDoesNotInstantiatePreviewService() {
        val before = project.getServiceIfCreated(SqlPreviewService::class.java)
        PluginCacheManager.clearAll(project)
        assertSame(
            "clearAll 不得触发 SqlPreviewService 实例化",
            before,
            project.getServiceIfCreated(SqlPreviewService::class.java),
        )
    }
}
