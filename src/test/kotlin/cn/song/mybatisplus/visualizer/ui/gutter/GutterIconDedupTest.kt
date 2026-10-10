package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * T-GutterDedup：SQL 预览图标重复/缺失修复——按 demo 场景断言"每处调用恰一个预览图标"。
 *
 * 覆盖用户标注的三类问题：
 *  - `commonDao.execute(hql,...)` 原双图标（Jdbc + CustomDaoHql）→ 应恰 1
 *  - `getHibernateTemplate().execute(callback)` 原三图标（Jdbc + CustomDaoHql + 内层 Hql）→ execute 处 0、createQuery 处 1
 *  - `getHibernateTemplate().find / findByValueBean` 原无图标 → 应恰 1
 *  另含 `session.createQuery` / `jdbcTemplate.queryForList` 的归属回归（各 1，不跨家族）。
 *
 * T-GutterDedup 补盲区（20260907-1630）：方法定义锚点 [StringSqlMethodGutterLineMarkerProvider]
 * 纳入计数——其方法体扫描带家族守卫后，CUSTOM_DAO 家族调用（commonDao.execute）方法定义行不再亮图标。
 *
 * 计数口径：对同一方法名 leaf，统计方法调用类 provider（Hql / CustomDaoHql / Jdbc / StringSqlMethod）
 * 中产生 LineMarkerInfo 的数量；方法定义行单独用 [methodIconCount] 断言。
 */
class GutterIconDedupTest : BasePlatformTestCase() {

    private val providers = listOf(
        HqlGutterLineMarkerProvider(),
        CustomDaoHqlGutterLineMarkerProvider(),
        JdbcTemplateSqlGutterLineMarkerProvider(),
        StringSqlMethodGutterLineMarkerProvider(),
    )

    /** 方法调用名 leaf（父级为 PsiReferenceExpression，排除方法声明名）上方法调用类 provider 产生的图标总数。 */
    private fun iconCountAt(methodName: String): Int = iconCountAt(methodName, providers)

    /**
     * 同上，但按调用方给定的 provider 集合计数。
     *
     * T-GutterDupFreeze：链根锚点由 [SqlGutterLineMarkerProvider] 产生，而本类默认 provider 集不含它
     * （默认集是「方法名锚点类」），故新增按集合计数的重载，不改默认集以免影响既有 14 条断言。
     */
    private fun iconCountAt(methodName: String, providerList: List<LineMarkerProvider>): Int {
        val leaf = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .first { it.text == methodName && it.parent is com.intellij.psi.PsiReferenceExpression }
        return providerList.count { it.getLineMarkerInfo(leaf) != null }
    }

    /** 方法定义名 leaf（父级为 PsiMethod）上 [StringSqlMethodGutterLineMarkerProvider] 产生的图标数。 */
    private fun methodIconCount(methodName: String): Int {
        val leaf = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .first { it.text == methodName && it.parent is com.intellij.psi.PsiMethod }
        return StringSqlMethodGutterLineMarkerProvider().getLineMarkerInfo(leaf)?.let { 1 } ?: 0
    }

    fun testCommonDaoExecute_singleIcon() {
        myFixture.configureByText("Service.java", """
            class CommonDao { int execute(String hql, Object... p); }
            class MyService {
                CommonDao commonDao;
                int updateStatusById(Long id, String s) {
                    return commonDao.execute("update WmsDockBooking b set b.status = :newStatus where b.id = :id", "newStatus", s, "id", id);
                }
            }
        """.trimIndent())
        assertEquals("commonDao.execute 调用行应恰 1 图标（CustomDaoHql；Jdbc 接收者非 JDBC 家族不认领）", 1, iconCountAt("execute"))
        // T-GutterDedup 补盲区：方法定义行不得因 StringSqlMethod 家族守卫缺失再亮一个（用户真机双图标实证）
        assertEquals("commonDao.execute 场景方法定义行应 0 图标（CUSTOM_DAO 家族守卫）", 0, methodIconCount("updateStatusById"))
    }

    fun testCommonDaoExecuteDelete_methodRowNoIcon() {
        // demo 形态 7：DELETE HQL 同样走 commonDao.execute，方法定义行同样不亮
        myFixture.configureByText("Service.java", """
            class CommonDao { int execute(String hql, Object... p); }
            class MyService {
                CommonDao commonDao;
                int deleteAsnById(Long id) {
                    return commonDao.execute("delete from WmsAsn asn where asn.id = :id", "id", id);
                }
            }
        """.trimIndent())
        assertEquals("commonDao.execute(DELETE) 调用行应恰 1 图标", 1, iconCountAt("execute"))
        assertEquals("commonDao.execute(DELETE) 方法定义行应 0 图标", 0, methodIconCount("deleteAsnById"))
    }

    fun testBeanCruderSelectList_methodRowStillHasIcon() {
        // UNKNOWN 家族（自研框架）方法定义行图标不回归：家族守卫只挡专属家族
        myFixture.configureByText("Service.java", """
            class ScheduleVO {}
            class BeanCruder { java.util.List<?> selectList(Class c, String sql, Object... p); }
            class MyService {
                BeanCruder beanCruder;
                java.util.List<?> getScheduleList(Long userId) {
                    String sql = "SELECT wp.PROC_NAME FROM WKFL_TASK WT WHERE WT.OWNER = :CUR_USER ";
                    return beanCruder.selectList(ScheduleVO.class, sql, "CUR_USER", userId);
                }
            }
        """.trimIndent())
        assertEquals("beanCruder.selectList UNKNOWN 家族方法定义行应仍亮 1 图标", 1, methodIconCount("getScheduleList"))
    }

    fun testGetHibernateTemplateExecuteCallback_icons() {
        myFixture.configureByText("Service.java", """
            class HibernateTemplate { Object execute(java.util.concurrent.Callable c); }
            interface HibernateCallback<T> { T doInHibernate(Session s); }
            interface Session { Query createQuery(String hql); }
            class Query { java.util.List<?> list(); }
            class MyService {
                HibernateTemplate getHibernateTemplate() { return new HibernateTemplate(); }
                java.util.List<?> findBookingByPage(String warehouseId) {
                    return getHibernateTemplate().execute(new HibernateCallback<java.util.List<?>>() {
                        public java.util.List<?> doInHibernate(Session session) {
                            Query query = session.createQuery("from WmsDockBooking b where b.warehouseId = :warehouseId");
                            return query.list();
                        }
                    });
                }
            }
        """.trimIndent())
        // execute 回调：非字符串实参 → Jdbc/CustomDaoHql 均不认领（0）
        assertEquals("getHibernateTemplate().execute(callback) 处应 0 图标（非字符串实参）", 0, iconCountAt("execute"))
        // 内层 session.createQuery → Hql 恰 1（CustomDaoHql 因 JPA_SESSION 不认领）
        assertEquals("内层 session.createQuery 应恰 1 图标（Hql）", 1, iconCountAt("createQuery"))
    }

    fun testGetHibernateTemplateFind_singleIcon() {
        myFixture.configureByText("Service.java", """
            class HibernateTemplate { java.util.List<?> find(String hql, Object... p); }
            class MyService {
                HibernateTemplate getHibernateTemplate() { return new HibernateTemplate(); }
                java.util.List<?> findBookingByTypePositional(String type) {
                    return getHibernateTemplate().find("from WmsDockBooking b where b.type = ?", type);
                }
            }
        """.trimIndent())
        assertEquals("getHibernateTemplate().find 应恰 1 图标（CustomDaoHql 认领 HIBERNATE find）", 1, iconCountAt("find"))
    }

    fun testGetHibernateTemplateFindByValueBean_singleIcon() {
        myFixture.configureByText("Service.java", """
            class WmsDockBooking { Long id; }
            class HibernateTemplate { java.util.List<?> findByValueBean(String hql, Object bean); }
            class MyService {
                HibernateTemplate getHibernateTemplate() { return new HibernateTemplate(); }
                java.util.List<?> findBookingByValueBean(WmsDockBooking sample) {
                    return getHibernateTemplate().findByValueBean("from WmsDockBooking b where b.warehouseId = :warehouseId", sample);
                }
            }
        """.trimIndent())
        assertEquals("getHibernateTemplate().findByValueBean 应恰 1 图标（HIBERNATE findByValueBean）", 1, iconCountAt("findByValueBean"))
    }

    fun testSessionCreateQuery_singleIcon() {
        myFixture.configureByText("Service.java", """
            interface Session { Query createQuery(String hql); }
            class Query {}
            class MyService {
                Query run(Session session) {
                    Query q = session.createQuery("from WmsDockBooking b where b.id = :id");
                    return q;
                }
            }
        """.trimIndent())
        assertEquals("session.createQuery 应恰 1 图标（Hql；Jdbc/CustomDaoHql 不认领）", 1, iconCountAt("createQuery"))
    }

    fun testJdbcTemplateQueryForList_singleIcon_notDoubleClaimed() {
        myFixture.configureByText("Service.java", """
            class JdbcTemplate { java.util.List<?> queryForList(String sql, Object... p); }
            class MyService {
                JdbcTemplate jdbcTemplate;
                java.util.List<?> users() {
                    return jdbcTemplate.queryForList("select * from t_user");
                }
            }
        """.trimIndent())
        assertEquals("jdbcTemplate.queryForList 应恰 1 图标（Jdbc 认领；CustomDaoHql 因 JDBC 家族不认领）", 1, iconCountAt("queryForList"))
    }

    // ------------------------------------------------------------------
    // demo 其余形态：自定义 DAO / HibernateTemplate 命名参数 / EntityManager / Criteria
    // ------------------------------------------------------------------

    fun testCommonDaoFindByQueryUniqueResult_singleIcon() {
        myFixture.configureByText("Service.java", """
            class CommonDao { Object findByQueryUniqueResult(String hql, String[] names, Object[] values); }
            class MyService {
                CommonDao commonDao;
                Object[] stats(String warehouseId) {
                    return (Object[]) commonDao.findByQueryUniqueResult("from WmsDockBooking b where b.warehouseId = :warehouseId", new String[]{"warehouseId"}, new Object[]{warehouseId});
                }
            }
        """.trimIndent())
        assertEquals("commonDao.findByQueryUniqueResult 应恰 1 图标（CustomDaoHql）", 1, iconCountAt("findByQueryUniqueResult"))
    }

    fun testCommonDaoFindByQuery_singleIcon() {
        myFixture.configureByText("Service.java", """
            class CommonDao { java.util.List<?> findByQuery(String hql, Object... p); }
            class MyService {
                CommonDao commonDao;
                java.util.List<?> findBookings(String warehouseId) {
                    return commonDao.findByQuery("from WmsDockBooking b where b.warehouseId = :warehouseId", "warehouseId", warehouseId);
                }
            }
        """.trimIndent())
        assertEquals("commonDao.findByQuery 应恰 1 图标（CustomDaoHql）", 1, iconCountAt("findByQuery"))
    }

    fun testGetHibernateTemplateFindByNamedParam_singleIcon() {
        myFixture.configureByText("Service.java", """
            class HibernateTemplate { java.util.List<?> findByNamedParam(String hql, String[] names, Object[] values); }
            class MyService {
                HibernateTemplate getHibernateTemplate() { return new HibernateTemplate(); }
                java.util.List<?> findNamed(String warehouseId) {
                    return getHibernateTemplate().findByNamedParam("from WmsDockBooking b where b.warehouseId = :warehouseId", new String[]{"warehouseId"}, new Object[]{warehouseId});
                }
            }
        """.trimIndent())
        assertEquals("getHibernateTemplate().findByNamedParam 应恰 1 图标（HIBERNATE findByNamedParam）", 1, iconCountAt("findByNamedParam"))
    }

    fun testEntityManagerCreateQueryClass_singleHql() {
        myFixture.configureByText("Service.java", """
            interface EntityManager { Query createQuery(String q, Class<?> type); }
            class Query {}
            class UserEntity {}
            class MyService {
                Query named(EntityManager em, String username) {
                    return em.createQuery("select u from UserEntity u where u.username = :username", UserEntity.class);
                }
            }
        """.trimIndent())
        assertEquals("em.createQuery(JPQL, Class) 应恰 1 图标（Hql；Jdbc/CustomDaoHql 不认领）", 1, iconCountAt("createQuery"))
    }

    fun testCriteriaBuilder_noFalseHqlClaim_criteriaOwnsMethod() {
        myFixture.configureByText("Service.java", """
            interface EntityManager { CriteriaBuilder getCriteriaBuilder(); Query createQuery(CriteriaQuery<?> q); }
            interface CriteriaBuilder { CriteriaQuery<?> createQuery(Class<?> type); }
            interface CriteriaQuery<T> { Root<?> from(Class<?> e); }
            interface Root<T> {}
            interface Query {}
            class UserEntity {}
            class MyService {
                java.util.List<?> findUsers(EntityManager em) {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    cq.from(UserEntity.class);
                    return em.createQuery(cq).getResultList();
                }
            }
        """.trimIndent())
        // cb.createQuery(Class) 与 em.createQuery(cq) 处，Hql/CustomDaoHql/Jdbc 均不认领（0 图标）
        assertEquals("cb.createQuery(Class) 处 Hql/CustomDaoHql/Jdbc 应 0（Class 首参非 HQL 字符串）", 0, iconCountAt("createQuery"))
        // CriteriaQuery 图标锚在宿主方法名上（findUsers），恰 1
        val methodLeaf = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiIdentifier::class.java)
            .first { it.text == "findUsers" && it.parent !is com.intellij.psi.PsiReferenceExpression }
        val criteriaProvider = CriteriaQueryGutterLineMarkerProvider()
        assertNotNull("CriteriaQuery 应锚在方法名 findUsers 上产生 1 图标", criteriaProvider.getLineMarkerInfo(methodLeaf))
    }

    fun testBeanCruderSelectList_singleIcon() {
        // T-DaoSql-03：自研框架 `beanCruder.selectList(Class, sql, ...)`（UNKNOWN 家族 + 字符串形态实参）
        // 由 Jdbc provider 恰 1 认领；批量锚点（场景 D）让位不参与调用行计数
        myFixture.configureByText("Service.java", """
            class ScheduleVO {}
            class BeanCruder { java.util.List<?> selectList(Class c, String sql, Object... p); }
            class MyService {
                BeanCruder beanCruder;
                java.util.List<?> getScheduleList(Long userId) {
                    String sql = "SELECT wp.PROC_NAME FROM WKFL_TASK WT WHERE WT.OWNER = :CUR_USER ";
                    return beanCruder.selectList(ScheduleVO.class, sql, "CUR_USER", userId);
                }
            }
        """.trimIndent())
        assertEquals("beanCruder.selectList(Class, sql, ...) 调用行应恰 1 图标（Jdbc provider）",
            1, iconCountAt("selectList"))
    }

    /** T-GutterDupFreeze：链根锚点（[SqlGutterLineMarkerProvider] 场景 B/C）+ 假 HQL 锚点（[CustomDaoHqlGutterLineMarkerProvider]）。 */
    private val chainProviders = listOf(
        SqlGutterLineMarkerProvider(),
        CustomDaoHqlGutterLineMarkerProvider(),
    )

    fun testMapperSelectListWithWrapperChain_noIconOnCall() {
        // T-GutterDupFreeze（S1+S2，20261008 用户真机）：MP 工厂链直作 mapper 方法实参。
        // 修复前：链内嵌 exists 子查询串 → 内容启发把链源码当 HQL（CustomDaoHql 假图标）
        //       + selectList 在批量注册表 → 场景 D 再挂一枚 = 同一行 2 枚；现应为 0/1。
        myFixture.configureByText("Service.java", """
            class User {}
            class W {
                W exists(String sql);
                W eq(Object column, Object value);
            }
            class Wrappers { static W lambdaQuery(Class<?> entityClass); }
            interface UserMapper { Object selectList(Object wrapper); }
            class MyService {
                UserMapper userMapper;
                Object run(String username) {
                    return userMapper.selectList(Wrappers.lambdaQuery(User.class)
                            .exists("select 1 from t_order o where o.user_id = user.id")
                            .eq(null, username));
                }
            }
        """.trimIndent())
        assertEquals(
            "selectList(<wrapper 链>) 调用行应 0 图标（批量锚点让位 + 内容启发不得认链源码）",
            0, iconCountAt("selectList", chainProviders),
        )
        assertEquals(
            "链根 Wrappers.lambdaQuery 应恰 1 图标（场景 C）",
            1, iconCountAt("lambdaQuery", chainProviders),
        )
    }

    fun testMapperSelectListWithWrapperVariable_noIconOnCall() {
        // T-GutterDupFreeze（S2 变量实参分支）：`selectList(variableWrapper)` 的语句承载在实参链上，
        // 锚点由变量声明行（场景 B）承载，批量锚点让位。
        myFixture.configureByText("Service.java", """
            class User {}
            class LambdaQueryWrapper<T> { LambdaQueryWrapper<T> eq(Object column, Object value); }
            class Wrappers { static <T> LambdaQueryWrapper<T> lambdaQuery(Class<T> entityClass); }
            interface UserMapper { Object selectList(Object wrapper); }
            class MyService {
                UserMapper userMapper;
                Object run(String username) {
                    LambdaQueryWrapper<User> variableWrapper =
                            Wrappers.lambdaQuery(User.class).eq(null, username);
                    return userMapper.selectList(variableWrapper);
                }
            }
        """.trimIndent())
        assertEquals(
            "selectList(wrapper 变量) 调用行应 0 图标（批量锚点让位）",
            0, iconCountAt("selectList", chainProviders),
        )
        val declared = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .first { it.text == "variableWrapper" && it.parent is com.intellij.psi.PsiVariable }
        assertEquals(
            "变量声明行应恰 1 图标（场景 B）",
            1, chainProviders.count { it.getLineMarkerInfo(declared) != null },
        )
    }
}
