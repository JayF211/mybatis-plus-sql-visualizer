package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * CustomDaoHqlLocator 单元测试。
 *
 * 验证自定义 DAO 方法中的 HQL 字符串识别能力（三重启发检测）。
 */
class CustomDaoHqlLocatorTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 正向用例：应识别为自定义 DAO HQL
    // ------------------------------------------------------------------

    fun testCustomDao_nameValuePairs() {
        myFixture.configureByText(
            "WmsZoneService.java",
            """
            public class WmsZoneService {
                public Long countAutoZone(CommonDao commonDao, java.util.List<Long> locIdList) {
                    return (Long) commonDao.findByQueryUniqueResult(
                        "select count(1) from WmsZone z " +
                        "left join WmsLocation loc on loc.zoneId = z.id " +
                        "where loc.id in (:locIdList) and z.beAutoZone = true",
                        "locIdList", locIdList);
                }
            }
            interface CommonDao {
                Object findByQueryUniqueResult(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("countAutoZone")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("commonDao.findByQueryUniqueResult 应被识别", info)
        assertEquals("WmsZone", info!!.entityClassName)
        assertTrue("HQL 应含 from WmsZone", info.hql.contains("from WmsZone"))
        assertTrue("参数列表不应为空", info.parameters.isNotEmpty())
        assertEquals("locIdList", info.parameters[0].paramName)
        assertEquals(ParamSource.NAME_VALUE_PAIRS, info.paramSource)
    }

    fun testCustomDao_mapParam() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List<UserEntity> findUsers(UserDao userDao, java.util.Map<String,Object> params) {
                    return userDao.findByQuery("from UserEntity u where u.status = :status", params);
                }
            }
            interface UserDao {
                java.util.List findByQuery(String hql, java.util.Map params);
            }
            """.trimIndent(),
        )
        val method = findMethod("findUsers")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("userDao.findByQuery (Map 参数) 应被识别", info)
        assertEquals("UserEntity", info!!.entityClassName)
        assertEquals(ParamSource.MAP, info.paramSource)
    }

    fun testCustomDao_noParam() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List<UserEntity> findAll(BaseDao baseDao) {
                    return baseDao.findByQuery("from UserEntity");
                }
            }
            interface BaseDao {
                java.util.List findByQuery(String hql);
            }
            """.trimIndent(),
        )
        val method = findMethod("findAll")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("baseDao.findByQuery (无参数) 应被识别", info)
        assertEquals("UserEntity", info!!.entityClassName)
        assertEquals(ParamSource.NONE, info.paramSource)
    }

    fun testCustomDao_updateHql() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public int updateStatus(CommonDao commonDao, int oldStatus, int newStatus) {
                    return commonDao.execute("update UserEntity set status = :newStatus where status = :oldStatus", "newStatus", newStatus, "oldStatus", oldStatus);
                }
            }
            interface CommonDao {
                int execute(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("updateStatus")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("commonDao.execute (UPDATE) 应被识别", info)
        assertTrue("HQL 应含 update: [" + info!!.hql + "]", info.hql.contains("update", ignoreCase = true))
        assertEquals("UserEntity", info.entityClassName)
    }

    fun testCustomDao_deleteHql() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public int deleteByStatus(CommonDao commonDao, int status) {
                    return commonDao.execute("delete from UserEntity where status = :status", "status", status);
                }
            }
            interface CommonDao {
                int execute(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("deleteByStatus")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("commonDao.execute (DELETE) 应被识别", info)
        assertTrue("HQL 应含 delete", info!!.hql.contains("delete", ignoreCase = true))
    }

    fun testCustomDao_joinQuery() {
        myFixture.configureByText(
            "WmsZoneService.java",
            """
            public class WmsZoneService {
                public Long countAutoZone(CommonDao commonDao, java.util.List<Long> locIdList) {
                    return (Long) commonDao.findByQueryUniqueResult(
                        "select count(1) from WmsZone z " +
                        "left join WmsLocation loc on loc.zoneId = z.id " +
                        "where loc.id in (:locIdList) and z.beAutoZone = true",
                        "locIdList", locIdList);
                }
            }
            interface CommonDao {
                Object findByQueryUniqueResult(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("countAutoZone")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("JOIN HQL 应被识别", info)
        assertEquals("WmsZone", info!!.entityClassName)
        assertTrue("HQL 应含 left join", info.hql.contains("left join", ignoreCase = true))
    }

    fun testCustomDao_mapperSuffix() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List<UserEntity> findUsers(UserMapper userMapper) {
                    return userMapper.findByQuery("from UserEntity u where u.name = :name", "name", "test");
                }
            }
            interface UserMapper {
                java.util.List findByQuery(String hql, Object... params);
            }
            """.trimIndent(),
        )
        val method = findMethod("findUsers")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("userMapper.findByQuery 应被识别（qualifier 匹配 *Mapper）", info)
        assertEquals("UserEntity", info!!.entityClassName)
    }

    fun testCustomDao_repositorySuffix() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List<UserEntity> findUsers(UserRepository userRepository) {
                    return userRepository.findByQuery("from UserEntity");
                }
            }
            interface UserRepository {
                java.util.List findByQuery(String hql);
            }
            """.trimIndent(),
        )
        val method = findMethod("findUsers")
        val info = CustomDaoHqlLocator.locate(method)
        assertNotNull("userRepository.findByQuery 应被识别（qualifier 匹配 *Repository）", info)
    }

    // ------------------------------------------------------------------
    // 负向用例：不应识别为非 HQL
    // ------------------------------------------------------------------

    fun testNotHql_plainString() {
        myFixture.configureByText(
            "LogService.java",
            """
            public class LogService {
                public void logMessage(Logger logger) {
                    logger.info("select something from somewhere");
                }
            }
            interface Logger {
                void info(String msg);
            }
            """.trimIndent(),
        )
        val method = findMethod("logMessage")
        val info = CustomDaoHqlLocator.locate(method)
        assertNull("logger.info 不应被识别（qualifier 不匹配 DAO 模式）", info)
    }

    fun testNotHql_noEntityName() {
        myFixture.configureByText(
            "CommonService.java",
            """
            public class CommonService {
                public void doSomething(CommonDao commonDao) {
                    commonDao.execute("print 'hello'");
                }
            }
            interface CommonDao {
                void execute(String cmd);
            }
            """.trimIndent(),
        )
        val method = findMethod("doSomething")
        val info = CustomDaoHqlLocator.locate(method)
        assertNull("commonDao.execute(\"print 'hello'\") 不应被识别（内容不匹配 HQL 模式）", info)
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private fun findMethod(methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
        // 优先返回有方法体的方法（类方法），避免接口方法声明（无 body 导致 locate 返回 null）
        return methods.firstOrNull { it.name == methodName && it.body != null }
            ?: methods.firstOrNull { it.name == methodName }
            ?: error("测试文件缺少方法: $methodName")
    }
}
