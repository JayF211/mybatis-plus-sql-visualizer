package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * T-M22-04：Db kit / SimpleQuery / ActiveRecord 识别覆盖率测试。
 *
 * fixture 风格与 ConsumerMethodsCoverageTest 一致（BasePlatformTestCase + anchor 锚点 + preview 断言）。
 * 覆盖：
 *  - **Db kit**（com.baomidou.mybatisplus.extension.toolkit.Db 静态工具）：方法名与 IService 相同，
 *    多数经既有 wrapper/批量路径已可预览——本组确认 `Db.getById(Class, id)`（实体经类字面量实参解析）、
 *    `Db.lambdaQuery(Class)` 链（wrapperFactoryClassNames 收录 "Db"，factoryWrapperType 恒映射
 *    LAMBDA_QUERY，实体经链内方法引用推断）、`Db.list(Class, wrapper)`（wrapper 实参默认 SELECT）；
 *  - **SimpleQuery**（extension.toolkit.SimpleQuery）：`list(wrapper)` / `keyMap(wrapper, col)`
 *    ——wrapper 消费形态，方法名不在删除/计数/exists/实体更新集合 → 默认 SELECT；
 *  - **ActiveRecord**（AbstractModel）：`insert()` / `updateById()` / `deleteById()` / `selectAll()`
 *    ——resolveEntityTable 步骤 4（qualifier 实体类型）解析实体 + BATCH_OPERATIONS 注册
 *    （insert 既有 / deleteById 主键等值 / selectAll 全列无 WHERE），与 `selectOne(wrapper)` 消费形态。
 */
class DbActiveRecordCoverageTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    /** 文件内第一个方法调用（既有测试同款 helper）。 */
    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    // ------------------------------------------------------------------
    // Db kit（T-M22-04）
    // ------------------------------------------------------------------

    fun testDbGetByIdResolvesEntityFromClassArg() {
        // Db.getById(UserEntity.class, id) 两参——实体从类字面量实参解析（resolveEntityTable 步骤 1/2：
        // Class<UserEntity> 泛型参数 / 实体类型均可命中），主键等值 SELECT 模板
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    Db.getById(UserEntity.class, 1001L);
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("Db.getById 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM user_entity
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testDbLambdaQueryChainRendersSelect() {
        // Db.lambdaQuery(UserEntity.class).eq(...).list() 链——"Db" 收录 wrapperFactoryClassNames，
        // factoryWrapperType 恒映射 LAMBDA_QUERY；实体经链内方法引用文本兜底推断（无显式泛型）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    Db.lambdaQuery(UserEntity.class)
                        .eq(UserEntity::getStatus, 1)
                        .list();
                }

                static class UserEntity {
                    private Long id;
                    private Integer status;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("Db.lambdaQuery 链应产出预览结果", result)
        assertEquals(
            """
            SELECT id, status
            FROM user_entity
            WHERE status = 1
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testDbListWithClassAndWrapperRendersSelect() {
        // Db.list(UserEntity.class, wrapper)——wrapper 实参消费形态（方法名 list 非删除/计数/exists/更新
        // 消费集合 → 默认 SELECT；实体从 wrapper 泛型解析，全列展开）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<UserEntity> w = new LambdaQueryWrapper<UserEntity>()
                        .eq(UserEntity::getId, 1001L);
                    Db.list(UserEntity.class, w);
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("Db.list 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM user_entity
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // SimpleQuery（T-M22-04）
    // ------------------------------------------------------------------

    fun testSimpleQueryListRendersSelect() {
        // SimpleQuery.list(wrapper)——wrapper 消费形态默认 SELECT
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<UserEntity> w = new LambdaQueryWrapper<UserEntity>()
                        .eq(UserEntity::getId, 1001L);
                    SimpleQuery.list(w);
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("SimpleQuery.list 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM user_entity
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testSimpleQueryKeyMapRendersSelect() {
        // SimpleQuery.keyMap(wrapper, col)——第二参方法引用不影响 wrapper 消费（默认 SELECT）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<UserEntity> w = new LambdaQueryWrapper<UserEntity>()
                        .eq(UserEntity::getId, 1001L);
                    SimpleQuery.keyMap(w, UserEntity::getName);
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("SimpleQuery.keyMap 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM user_entity
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // ActiveRecord（T-M22-04）：resolveEntityTable 步骤 4 qualifier 实体解析
    // ------------------------------------------------------------------

    fun testActiveRecordInsertRendersInsert() {
        // entity.insert()——无实参，实体从 qualifier 变量类型解析（步骤 4），走既有 insert INSERT 模板
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void insert() {
                    UserEntity entity = new UserEntity();
                    entity.insert();
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("ActiveRecord insert 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO user_entity (id, name)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testActiveRecordUpdateByIdRendersUpdate() {
        // entity.updateById()——无实参，步骤 4 解析实体 → UPDATE 非主键列 SET + 主键 WHERE
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    UserEntity entity = new UserEntity();
                    entity.updateById();
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("ActiveRecord updateById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity
            SET name = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testActiveRecordDeleteByIdRendersDelete() {
        // entity.deleteById()——步骤 4 解析实体，deleteById 与 Mapper.deleteById(id) 同主键等值模板
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void delete() {
                    UserEntity entity = new UserEntity();
                    entity.deleteById();
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("ActiveRecord deleteById 应产出预览结果", result)
        assertEquals(
            """
            DELETE FROM user_entity
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testActiveRecordSelectAllRendersSelectNoWhere() {
        // entity.selectAll()——步骤 4 解析实体，selectAll 全列查询（无 WHERE，方法名分支）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    UserEntity entity = new UserEntity();
                    entity.selectAll();
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("ActiveRecord selectAll 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM user_entity
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testActiveRecordSelectOneWithWrapperRendersSelect() {
        // entity.selectOne(wrapper)——wrapper 实参消费形态（默认 SELECT；实体从 wrapper 泛型解析）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    LambdaQueryWrapper<UserEntity> w = new LambdaQueryWrapper<UserEntity>()
                        .eq(UserEntity::getId, 1001L);
                    entity.selectOne(w);
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("ActiveRecord selectOne 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, name
            FROM user_entity
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testSelectAllOnNonEntityQualifierReturnsNull() {
        // 防误伤（T-M22-04）：selectAll 是常见方法名——`List<String>.selectAll()` 的 qualifier
        // 非实体（isLikelyEntity 限定步骤 3/4 均跳过）→ 不产出预览（返回 null）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    java.util.List<String> names = new java.util.ArrayList<String>();
                    names.selectAll();
                }
            }
            """.trimIndent(),
        )
        assertNull("非实体 qualifier 的 selectAll 不应产出预览", newService().preview(anchor()))
    }
}
