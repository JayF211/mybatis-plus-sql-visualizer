package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.TableInfo
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wrapper 搜索服务单元测试（O-1 Phase A）：测试表名匹配纯逻辑。
 *
 * ReferencesSearch 部分需真实项目环境（集成测试覆盖），本测试聚焦纯函数 [WrapperSearchService.matchesTable]。
 */
class WrapperSearchServiceTest {

    @Test
    fun `精确表名匹配返回 true`() {
        val table = TableInfo(entityClassName = "User", tableName = "user", columns = emptyList())
        assertTrue(WrapperSearchService.matchesTable(table, "user"))
    }

    @Test
    fun `大小写不敏感匹配`() {
        val table = TableInfo(entityClassName = "User", tableName = "user_info", columns = emptyList())
        assertTrue(WrapperSearchService.matchesTable(table, "USER_INFO"))
    }

    @Test
    fun `表名不匹配返回 false`() {
        val table = TableInfo(entityClassName = "User", tableName = "user", columns = emptyList())
        assertFalse(WrapperSearchService.matchesTable(table, "order_info"))
    }

    @Test
    fun `null 表信息返回 false`() {
        assertFalse(WrapperSearchService.matchesTable(null, "user"))
    }

    @Test
    fun `空表名返回 false`() {
        val table = TableInfo(entityClassName = "User", tableName = "user", columns = emptyList())
        assertFalse(WrapperSearchService.matchesTable(table, ""))
    }

    @Test
    fun `空目标表名返回 false`() {
        val table = TableInfo(entityClassName = "User", tableName = "user", columns = emptyList())
        assertFalse(WrapperSearchService.matchesTable(table, ""))
    }
}

/**
 * WrapperSearchService 搜索通道集成测试（T-ServiceChainSearch，O-6）。
 *
 * 磁盘源码级注入 com.baomidou stub（IService / 链 Wrapper / 工具类 / 实体 / 用户 service 接口），
 * 在 Heavy 真实索引项目内走 ReferencesSearch + locateWithTable 全链路。
 * 用 Heavy 而非 Light 的原因（计划原定 BasePlatformTestCase + myFixture.addClass）：
 *  - ReferencesSearch 依赖方法名索引 + 跨文件（stub ↔ 调用点）泛型接口继承解析，本项目 M27 实证
 *    Light 环境跨文件 resolve 不可用，[InvocationMpjHeavyTest] 已确立 Heavy + 磁盘源码注入先例；
 *  - 本环境实测 myFixture.addClass 不可解析（compileTestKotlin Unresolved reference）。
 *
 * 覆盖：
 *  - 服务链通道（O-6 新增）：`userService.lambdaQuery()...list()` 等 IService 无参链起点命中；
 *  - 静态工厂通道（O-6 FQN 修复）：`Wrappers.<User>lambdaQuery()` 修复前简名 findClass 恒空 →
 *    本用例同时实证开发计划 §1 附加发现；
 *  - 消费重载（`service.update(w)`）与表名不匹配不产生候选。
 */
class WrapperSearchServiceSearchChannelTest : HeavyPlatformTestCase() {

    override fun setUp() {
        super.setUp()
        addMpStubs()
        addDomainClasses()
    }

    /** 在项目 src 下按包路径创建 Java 源文件（磁盘 + 索引；路径与包声明匹配），返回 PsiClass。 */
    private fun addClassFile(pkg: String, name: String, content: String): PsiClass {
        return WriteCommandAction.runWriteCommandAction(project, Computable {
            val root = createTestProjectStructure()
            var dir = root.findChild("src") ?: root.createChildDirectory(this, "src")
            for (seg in pkg.split('.')) {
                if (seg.isEmpty()) continue
                dir = dir.findChild(seg) ?: dir.createChildDirectory(this, seg)
            }
            val vf = dir.createChildData(this, name)
            VfsUtil.saveText(vf, content.trimIndent())
            PsiManager.getInstance(project).findFile(vf)!!
        }).let { file ->
            PsiTreeUtil.findChildOfType(file, PsiClass::class.java)!!
        }
    }

    /** MP stub（与真实 jar javap 签名一致：IService 四链方法 + 消费重载 update(Wrapper)）。 */
    private fun addMpStubs() {
        addClassFile(
            "com.baomidou.mybatisplus.core.conditions", "Wrapper.java",
            """
            package com.baomidou.mybatisplus.core.conditions;
            public abstract class Wrapper<T> {}
            """,
        )
        addClassFile(
            "com.baomidou.mybatisplus.core.conditions.query", "LambdaQueryWrapper.java",
            """
            package com.baomidou.mybatisplus.core.conditions.query;
            import com.baomidou.mybatisplus.core.conditions.Wrapper;
            public class LambdaQueryWrapper<T> extends Wrapper<T> {
                public LambdaQueryWrapper<T> eq(Object column, Object val) { return this; }
                public java.util.List<T> list() { return java.util.Collections.emptyList(); }
            }
            """,
        )
        addClassFile(
            "com.baomidou.mybatisplus.core.toolkit", "Wrappers.java",
            """
            package com.baomidou.mybatisplus.core.toolkit;
            public class Wrappers {
                public static <T> com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<T> lambdaQuery() { return null; }
            }
            """,
        )
        addClassFile(
            "com.baomidou.mybatisplus.extension.toolkit", "ChainWrappers.java",
            """
            package com.baomidou.mybatisplus.extension.toolkit;
            public class ChainWrappers {
                public static <T> com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<T> lambdaQueryChain(Class<T> entityClass) { return null; }
            }
            """,
        )
        addClassFile(
            "com.baomidou.mybatisplus.extension.conditions.query", "LambdaQueryChainWrapper.java",
            """
            package com.baomidou.mybatisplus.extension.conditions.query;
            public class LambdaQueryChainWrapper<T> {
                public LambdaQueryChainWrapper<T> eq(Object column, Object val) { return this; }
                public java.util.List<T> list() { return java.util.Collections.emptyList(); }
            }
            """,
        )
        addClassFile(
            "com.baomidou.mybatisplus.extension.conditions.query", "QueryChainWrapper.java",
            """
            package com.baomidou.mybatisplus.extension.conditions.query;
            public class QueryChainWrapper<T> {
                public QueryChainWrapper<T> eq(Object column, Object val) { return this; }
                public java.util.List<T> list() { return java.util.Collections.emptyList(); }
            }
            """,
        )
        addClassFile(
            "com.baomidou.mybatisplus.extension.conditions.update", "LambdaUpdateChainWrapper.java",
            """
            package com.baomidou.mybatisplus.extension.conditions.update;
            public class LambdaUpdateChainWrapper<T> {
                public LambdaUpdateChainWrapper<T> set(Object column, Object val) { return this; }
                public boolean update() { return true; }
            }
            """,
        )
        addClassFile(
            "com.baomidou.mybatisplus.extension.conditions.update", "UpdateChainWrapper.java",
            """
            package com.baomidou.mybatisplus.extension.conditions.update;
            public class UpdateChainWrapper<T> {
                public boolean update() { return true; }
            }
            """,
        )
        addClassFile(
            "com.baomidou.mybatisplus.extension.service", "IService.java",
            """
            package com.baomidou.mybatisplus.extension.service;
            public interface IService<T> {
                com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<T> lambdaQuery();
                com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<T> lambdaUpdate();
                com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper<T> query();
                com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper<T> update();
                boolean update(com.baomidou.mybatisplus.core.conditions.Wrapper<T> wrapper);
            }
            """,
        )
    }

    /** 业务 stub：实体 + 用户 service 接口（命名策略推断 User → user）。 */
    private fun addDomainClasses() {
        addClassFile(
            "com.biz.entity", "User.java",
            """
            package com.biz.entity;
            public class User {
                private Long id;
                private String name;
                public Long getId() { return id; }
                public String getName() { return name; }
            }
            """,
        )
        addClassFile(
            "com.biz.service", "IUserService.java",
            """
            package com.biz.service;
            import com.baomidou.mybatisplus.extension.service.IService;
            import com.biz.entity.User;
            public interface IUserService extends IService<User> {}
            """,
        )
    }

    /** 搜索（heavy 测试 EDT 隐式读权限，与 EntityClassFinderIntegrationTest 同模式）；打印候选数/耗时量级基线。 */
    private fun searchAndLog(tableName: String): WrapperSearchResult {
        val start = System.currentTimeMillis()
        val result = WrapperSearchService.search(project, tableName)
        val candidates = (result as? WrapperSearchResult.Multiple)?.candidates?.size
            ?: if (result is WrapperSearchResult.Single) 1 else 0
        // T-ServiceChainSearch：候选数/耗时量级基线日志（计划 §3 步骤 3）
        println("WrapperSearchServiceSearchChannel: table=$tableName candidates=$candidates elapsed=${System.currentTimeMillis() - start}ms")
        return result
    }

    private fun addUserServiceDemo(methodSource: String): PsiClass =
        addClassFile(
            "com.biz.web", "UserServiceDemo.java",
            """
            package com.biz.web;
            import com.biz.entity.User;
            import com.biz.service.IUserService;
            public class UserServiceDemo {
                private IUserService userService;
                $methodSource
            }
            """,
        )

    fun testServiceChainLambdaQueryHit() {
        addUserServiceDemo(
            """
            public java.util.List<User> listUsers() {
                return userService.lambdaQuery().eq(User::getName, "a").list();
            }
            """.trimIndent(),
        )
        val result = searchAndLog("user")
        assertTrue("服务链 lambdaQuery 应命中（实际 $result）", result is WrapperSearchResult.Single)
        val candidate = (result as WrapperSearchResult.Single).candidate
        assertEquals("锚点应落在调用方法", "listUsers", candidate.anchorMethod.name)
        assertEquals("user", candidate.table.tableName)
    }

    fun testServiceChainQueryAndLambdaUpdateHit() {
        addUserServiceDemo(
            """
            public java.util.List<User> queryUsers() {
                return userService.query().eq(User::getName, "a").list();
            }
            public void touchName() {
                userService.lambdaUpdate().set(User::getName, "b").update();
            }
            """.trimIndent(),
        )
        val result = searchAndLog("user")
        assertTrue("query + lambdaUpdate 两条服务链应产生多候选（实际 $result）", result is WrapperSearchResult.Multiple)
        val anchors = (result as WrapperSearchResult.Multiple).candidates.map { it.anchorMethod.name }.toSet()
        assertEquals(setOf("queryUsers", "touchName"), anchors)
    }

    fun testServiceChainUpdateConsumerOverloadNotMatched() {
        addUserServiceDemo(
            """
            public void consume() {
                com.baomidou.mybatisplus.core.conditions.Wrapper<User> w = null;
                userService.update(w);
            }
            """.trimIndent(),
        )
        val result = searchAndLog("user")
        assertTrue("消费重载 update(Wrapper) 不应产生候选（实际 $result）", result is WrapperSearchResult.None)
    }

    fun testServiceChainTableMismatchReturnsNone() {
        addUserServiceDemo(
            """
            public java.util.List<User> listUsers() {
                return userService.lambdaQuery().eq(User::getName, "a").list();
            }
            """.trimIndent(),
        )
        val result = searchAndLog("order_info")
        assertTrue("表名不匹配应返回 None（实际 $result）", result is WrapperSearchResult.None)
    }

    /** 静态工厂通道 FQN 修复回归：修复前简名 findClass 恒空 → None（实证计划 §1 附加发现）。 */
    fun testStaticFactoryWrappersLambdaQueryHit() {
        addClassFile(
            "com.biz.web", "FactoryDemo.java",
            """
            package com.biz.web;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;
            import com.biz.entity.User;
            public class FactoryDemo {
                public java.util.List<User> factoryQuery() {
                    return Wrappers.<User>lambdaQuery().eq(User::getName, "a").list();
                }
            }
            """.trimIndent(),
        )
        val result = searchAndLog("user")
        assertTrue("静态工厂 Wrappers.lambdaQuery 应命中（实际 $result）", result is WrapperSearchResult.Single)
        assertEquals("factoryQuery", (result as WrapperSearchResult.Single).candidate.anchorMethod.name)
    }

    /** 静态工厂通道 extension 包类（ChainWrappers）FQN 同样恢复命中。 */
    fun testStaticFactoryChainWrappersLambdaQueryChainHit() {
        addClassFile(
            "com.biz.web", "ChainFactoryDemo.java",
            """
            package com.biz.web;
            import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
            import com.biz.entity.User;
            public class ChainFactoryDemo {
                public java.util.List<User> chainQuery() {
                    return ChainWrappers.lambdaQueryChain(User.class).eq(User::getName, "a").list();
                }
            }
            """.trimIndent(),
        )
        val result = searchAndLog("user")
        assertTrue("静态工厂 ChainWrappers.lambdaQueryChain 应命中（实际 $result）", result is WrapperSearchResult.Single)
        assertEquals("chainQuery", (result as WrapperSearchResult.Single).candidate.anchorMethod.name)
    }
}
