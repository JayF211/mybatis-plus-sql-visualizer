package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.converter.EasyQueryConverter
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * Easy-Query proxy 风格端到端预览测试（EQ，M44 其他 ORM 第一批，矩阵 §4）。
 *
 * 覆盖：
 *  - EQ1/EQ4：queryable + where 语句块（多条 o.col().op(args); 分号分隔）→ 条件树 → SELECT；
 *  - EQ5：orderBy lambda 块 / limit(offset, count) / 布尔前缀 where(boolean, lambda)；
 *  - EQ6：leftJoin(Class, (a,b) -> a.uid().eq(b.id())) 双参 on lambda → JOIN + ColumnComparison；
 *  - EQ7：rangeClosed → BETWEEN；rangeOpen → 开区间 AND 组；
 *  - EQ8/EQ9：com.easy.query.core.annotation.Table/@Column(primaryKey, generatedKey)/@ColumnIgnore 轨；
 *  - EQ10：写路径 insertable(entity) → INSERT、updatable+setColumns → UPDATE、deletable → DELETE；
 *  - 锚点场景 F：queryable entry + receiver 启发亮图标；负例（普通类同名方法）不识别。
 */
class EasyQueryConverterTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().state.injectTenantLine = false
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.dialect = PluginSettings.DEFAULT_DIALECT
        super.tearDown()
    }

    /** 按方法名取文件内第一个方法调用表达式。 */
    private fun call(name: String): PsiMethodCallExpression =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == name }

    /** 实体（@Table value + @Column value/primaryKey + @ColumnIgnore）。 */
    private val topicEntity = """
        @com.easy.query.core.annotation.Table("t_topic")
        class Topic {
            @com.easy.query.core.annotation.Column(value = "t_id", primaryKey = true)
            private String id;

            @com.easy.query.core.annotation.Column(value = "create_time")
            private java.time.LocalDateTime createTime;

            @com.easy.query.core.annotation.Column(value = "topic_name")
            private String name;

            @com.easy.query.core.annotation.ColumnIgnore
            private String runtimeField;
        }
    """.trimIndent()

    fun testEq4WhereBlockMultipleStatementsRendersSelect() {
        // EQ4：where lambda 是 void 注册式语句块（分号分隔多条 o.col().op(args)）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public java.util.List<Topic> demo() {
                    return easyEntityQuery.queryable(Topic.class).where(o -> {
                        o.id().eq("123");
                        o.name().like("x");
                    }).toList();
                }
            }

            class EasyEntityQuery {}

            $topicEntity
            """.trimIndent(),
        )
        val result = newService().preview(call("queryable"))
        assertNotNull("EQ4：queryable 链应产出预览", result)
        assertEquals(WrapperType_TAG, result!!.wrapperType?.name)
        assertEquals("t_topic", result.tableName)
        // @ColumnIgnore 字段不落列；@Column value 列名生效
        assertEquals(
            """
            SELECT t_id, create_time, topic_name
            FROM t_topic
            WHERE t_id = '123'
                AND topic_name LIKE '%x%'
            """.trimIndent(),
            result.sql,
        )
    }

    fun testEq5OrderByLimitAndBooleanWhere() {
        // EQ5：orderBy lambda 块 → ORDER BY；limit(offset, count) → LIMIT 1, 20；
        // where(boolean, lambda) 布尔前缀 → 条件挂动态开关（运行时布尔默认勾选渲染）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public java.util.List<Topic> demo(boolean enabled) {
                    return easyEntityQuery.queryable(Topic.class)
                        .where(enabled, o -> o.id().eq("123"))
                        .orderBy(o -> o.createTime().desc())
                        .limit(1, 20)
                        .toList();
                }
            }

            class EasyEntityQuery {}

            $topicEntity
            """.trimIndent(),
        )
        val result = newService().preview(call("queryable"))
        assertNotNull(result)
        assertEquals(
            """
            SELECT t_id, create_time, topic_name
            FROM t_topic
            WHERE t_id = '123'
            ORDER BY create_time DESC
            LIMIT 1, 20
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testEq7RangeFamilyMappedToBetweenAndOpenInterval() {
        // EQ7：rangeClosed → BETWEEN；rangeOpen → 开区间 AND 组（col > ? AND col < ?）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public java.util.List<Topic> demo() {
                    return easyEntityQuery.queryable(Topic.class).where(o -> {
                        o.createTime().rangeClosed("2026-01-01", "2026-12-31");
                        o.id().rangeOpen(1, 100);
                    }).toList();
                }
            }

            class EasyEntityQuery {}

            $topicEntity
            """.trimIndent(),
        )
        val result = newService().preview(call("queryable"))
        assertNotNull(result)
        assertEquals(
            """
            SELECT t_id, create_time, topic_name
            FROM t_topic
            WHERE create_time BETWEEN '2026-01-01' AND '2026-12-31'
                AND (t_id > 1 AND t_id < 100)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testEq6LeftJoinOnLambdaRendersColumnComparison() {
        // EQ6：leftJoin(Class, (a,b) -> a.uid().eq(b.id()))——首参副表 proxy、次参主表 proxy
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public java.util.List<Topic> demo() {
                    return easyEntityQuery.queryable(Topic.class)
                        .leftJoin(User.class, (t, t1) -> t.uid().eq(t1.id()))
                        .where(o -> o.id().eq("1"))
                        .toList();
                }
            }

            class EasyEntityQuery {}

            class User {}

            $topicEntity
            """.trimIndent(),
        )
        val result = newService().preview(call("queryable"))
        assertNotNull(result)
        // User 无注解 → 表名推断 user；副表别名 t1、主表别名 t（JOIN 存在时主表列带别名）
        assertEquals(
            """
            SELECT t.t_id, t.create_time, t.topic_name
            FROM t_topic t
            LEFT JOIN `user` t1 ON t1.uid = t.t_id
            WHERE t.t_id = '1'
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // EQ10：写路径
    // ------------------------------------------------------------------

    fun testEq10InsertableRendersInsertTemplate() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public void demo(Topic entity) {
                    easyEntityQuery.insertable(entity).execute();
                }
            }

            $topicEntity
            """.trimIndent(),
        )
        val result = newService().preview(call("insertable"))
        assertNotNull("EQ10：insertable 应产出预览", result)
        assertEquals(
            "INSERT INTO t_topic (t_id, create_time, topic_name)\nVALUES (?, ?, ?)",
            result!!.sql,
        )
        assertNotNull("INSERT 应携带列勾选模型", result.batchModel)
    }

    fun testEq10UpdatableSetColumnsWhereRendersUpdate() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public void demo(Topic entity) {
                    easyEntityQuery.updatable(entity)
                        .setColumns(s -> s.name().set("新标题"))
                        .where(o -> o.id().eq("123"))
                        .execute();
                }
            }

            $topicEntity
            """.trimIndent(),
        )
        val result = newService().preview(call("updatable"))
        assertNotNull("EQ10：updatable 链应产出预览", result)
        assertEquals(
            "UPDATE t_topic\nSET topic_name = '新标题'\nWHERE t_id = '123'",
            result!!.sql,
        )
    }

    fun testEq10UpdatableSetColumnsWithoutWhereWarns() {
        // 普查批：updatable + setColumns 无 where 段 → 全表更新警示
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public void demo(Topic entity) {
                    easyEntityQuery.updatable(entity)
                        .setColumns(s -> s.name().set("新标题"))
                        .execute();
                }
            }

            class EasyEntityQuery {}

            $topicEntity
            """.trimIndent(),
        )
        val result = newService().preview(call("updatable"))
        assertNotNull(result)
        assertEquals("⚠ 无 WHERE 条件，将更新全表数据！", result!!.warning)
        assertEquals("UPDATE t_topic\nSET topic_name = '新标题'", result.sql)
    }

    fun testEq10UpdatableSetColumnsWithWhereNoWarning() {
        // 对照：updatable + setColumns 带 where 段 → 不出警示
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public void demo(Topic entity) {
                    easyEntityQuery.updatable(entity)
                        .setColumns(s -> s.name().set("标题b"))
                        .where(o -> o.id().eq("456"))
                        .execute();
                }
            }

            class EasyEntityQuery {}

            $topicEntity
            """.trimIndent(),
        )
        val result = newService().preview(call("updatable"))
        assertNotNull(result)
        assertNull("带 WHERE 的 update 不应出全表警示", result!!.warning)
        assertEquals(
            "UPDATE t_topic\nSET topic_name = '标题b'\nWHERE t_id = '456'",
            result.sql,
        )
    }

    fun testEq10UpdatableEntityWithoutPrimaryKeyWarns() {
        // 普查批：updatable(entity) 无 setColumns + 实体无 primaryKey 列 → 全表更新警示 + notes 按实际语义
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public void demo(Plain entity) {
                    easyEntityQuery.updatable(entity).execute();
                }
            }

            class EasyEntityQuery {}

            @com.easy.query.core.annotation.Table("t_plain")
            class Plain {
                @com.easy.query.core.annotation.Column(value = "p_name")
                private String name;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("updatable"))
        assertNotNull(result)
        assertEquals("⚠ 无 WHERE 条件，将更新全表数据！", result!!.warning)
        assertEquals(
            "UPDATE t_plain\nSET p_name = ?" +
                "\n-- updatable(entity)：未调用 setColumns，按实体全列 SET 近似（无 WHERE 条件，将更新全表）",
            result.sql,
        )
    }

    fun testEq10DeletableWithWhereRendersDelete() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public void demo() {
                    easyEntityQuery.deletable(Topic.class).where(o -> o.id().eq("123")).execute();
                }
            }

            $topicEntity
            """.trimIndent(),
        )
        val result = newService().preview(call("deletable"))
        assertNotNull("EQ10：deletable 链应产出预览", result)
        assertEquals(
            "DELETE FROM t_topic\nWHERE t_id = '123'",
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // EQ8：@Column(generatedKey) 主键不进 INSERT 列
    // ------------------------------------------------------------------

    fun testEq8GeneratedKeyPrimaryKeyExcludedFromInsert() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;

                public void demo(Topic entity) {
                    easyEntityQuery.insertable(entity).execute();
                }
            }

            class EasyEntityQuery {}

            @com.easy.query.core.annotation.Table("t_topic")
            class Topic {
                @com.easy.query.core.annotation.Column(value = "t_id", primaryKey = true, generatedKey = true)
                private String id;

                private String name;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("insertable"))
        assertNotNull(result)
        assertEquals(
            "INSERT INTO t_topic (name)\nVALUES (?)\n-- 主键 t_id：@Column(generatedKey = true) 应用端/数据库生成，不进 INSERT 列清单",
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // 锚点场景 F + 负例守卫
    // ------------------------------------------------------------------

    fun testEqAnchorHighlightedAndNegativeCaseRejected() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private EasyEntityQuery easyEntityQuery;
                private NotEasy notEasy;

                public Object demo() {
                    easyEntityQuery.queryable(Topic.class).toList();
                    return notEasy.queryable(Topic.class);
                }
            }

            class EasyEntityQuery {}
            class NotEasy {}

            class Topic {
                private String id;
            }
            """.trimIndent(),
        )
        val queryableIdentifiers = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.text == "queryable" }
        assertEquals(2, queryableIdentifiers.size)
        // 正例：easyEntityQuery.queryable —— receiver 变量名启发
        assertNotNull(
            "EQ：easyEntityQuery.queryable 应亮锚点",
            WrapperAnchorDetector.anchorElement(queryableIdentifiers[0]),
        )
        // 负例：普通类 NotEasy.queryable —— 宁可无图标不可误亮
        assertNull(
            "负例守卫：普通类同名方法不应亮锚点",
            WrapperAnchorDetector.anchorElement(queryableIdentifiers[1]),
        )
        // preview 通道负例：普通 receiver 不产出 SQL
        val notEasyCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == "queryable" }[1]
        assertNull(EasyQueryConverter.convert(notEasyCall))
    }

    private companion object {
        const val WrapperType_TAG = "EQ_PROXY"
    }
}
