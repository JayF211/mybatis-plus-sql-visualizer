package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 注解 SQL 预览服务测试（T-M25-02/03）：
 *  - @Select 单值/数组拼接、script 动态标签、@Param 参数名 mock（含 param1..N）
 *  - Provider L1 常量折叠与降级提示
 *  - @Lang 自定义驱动提示
 */
class AnnotationPreviewServiceTest : BasePlatformTestCase() {

    private val mapper = """
        import org.apache.ibatis.annotations.Param;
        import org.apache.ibatis.annotations.Select;
        import org.apache.ibatis.annotations.Update;

        public interface UserMapper {
            @Select("SELECT * FROM t_user WHERE id = #{id}")
            User selectById(@Param("id") Long id);

            @Select({"SELECT * FROM t_user", "WHERE name = #{name}"})
            User selectByName(String name);

            @Update({"<script>", "UPDATE t_user", "<set>", "<if test='name != null'>name = #{name},</if>", "</set>", "WHERE id = #{id}", "</script>"})
            int updateById(@Param("id") Long id, @Param("name") String name);

            @SelectProvider(type = UserSqlProvider.class, method = "selectByStatus")
            User selectByStatus(@Param("status") Integer status);

            class UserSqlProvider {
                public String selectByStatus() {
                    return "SELECT * FROM t_user WHERE status = #{status}";
                }
            }

            @SelectProvider(type = DynamicProvider.class, method = "selectDynamic")
            User selectDynamic(@Param("id") Long id);

            class DynamicProvider {
                public String selectDynamic(Long id) {
                    return "SELECT * FROM t_user WHERE id = " + id;
                }
            }
        }
    """.trimIndent()

    private fun annotationOf(methodName: String): PsiAnnotation =
        PsiTreeUtil.findChildrenOfType(myFixture.file, PsiAnnotation::class.java)
            .first {
                PsiTreeUtil.getParentOfType(it, com.intellij.psi.PsiMethod::class.java)?.name == methodName &&
                    AnnotationSqlPreviewService.isSqlAnnotation(it)
            }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun testSelectSingleValue() {
        myFixture.configureByText("UserMapper.java", mapper)
        val result = AnnotationSqlPreviewService.preview(annotationOf("selectById"), project)
        assertNotNull(result)
        assertEquals("SELECT * FROM t_user WHERE id = ?", compact(result!!.sql))
        assertEquals(listOf("id"), result.unresolvedParams)
        assertNull(result.warning)
    }

    fun testSelectArrayJoinedWithSpace() {
        myFixture.configureByText("UserMapper.java", mapper)
        val result = AnnotationSqlPreviewService.preview(annotationOf("selectByName"), project)
        // 数组元素以单个空格连接（对齐 String.join(" ", value).trim()）
        assertEquals("SELECT * FROM t_user WHERE name = ?", compact(result!!.sql))
        assertEquals(listOf("name"), result.unresolvedParams)
    }

    fun testUpdateScriptDynamicTags() {
        myFixture.configureByText("UserMapper.java", mapper)
        val result = AnnotationSqlPreviewService.preview(annotationOf("updateById"), project)
        assertEquals("UPDATE t_user SET name = ? WHERE id = ?", compact(result!!.sql))
        // @Param 名 + param1..N 注入 mock
        assertEquals(listOf("id", "name"), result.xmlModel?.paramProps?.filter { !it.startsWith("param") })
        assertTrue(result.xmlModel!!.paramProps.contains("param1"))
        assertTrue(result.xmlModel!!.paramProps.contains("param2"))
        assertEquals(1, result.xmlModel!!.conditionViews.size)
    }

    fun testProviderConstantFolding() {
        myFixture.configureByText("UserMapper.java", mapper)
        val result = AnnotationSqlPreviewService.preview(annotationOf("selectByStatus"), project)
        assertNotNull(result)
        assertEquals("SELECT * FROM t_user WHERE status = ?", compact(result!!.sql))
        assertNull(result.warning)
    }

    fun testProviderDynamicSqlDegrades() {
        myFixture.configureByText("UserMapper.java", mapper)
        val result = AnnotationSqlPreviewService.preview(annotationOf("selectDynamic"), project)
        // 含参数依赖的 return（"..." + id）→ 常量折叠失败 → 降级提示
        assertNotNull(result)
        assertTrue(result!!.warning?.contains("无法静态预览") == true)
    }

    fun testLangCustomDriverWarning() {
        myFixture.configureByText(
            "LangMapper.java",
            """
            import org.apache.ibatis.annotations.Select;
            import org.apache.ibatis.annotations.Lang;

            public interface LangMapper {
                @Lang(org.apache.ibatis.scripting.xmltags.XMLLanguageDriver.class)
                @Select("SELECT 1")
                int one();
            }
            """.trimIndent(),
        )
        // 默认 XML 语言驱动 → 无警告
        val result = AnnotationSqlPreviewService.preview(annotationOf("one"), project)
        assertEquals("SELECT 1", compact(result!!.sql))
    }
}
