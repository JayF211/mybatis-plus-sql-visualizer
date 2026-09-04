package cn.song.mybatisplus.visualizer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import cn.song.mybatisplus.visualizer.model.PreviewResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 注解 SQL 预览服务测试（T-M25-02/03）：
 *  - @Select 单值/数组拼接、script 动态标签、@Param 参数名 mock（含 param1..N）
 *  - Provider L1 常量折叠与降级提示
 *  - @Lang 自定义驱动提示
 */
public class AnnotationPreviewServiceTest extends BasePlatformTestCase {

    private static final String MAPPER = """
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
""".stripTrailing();

    private PsiAnnotation annotationOf(String methodName) {
        for (PsiAnnotation a : PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiAnnotation.class)) {
            PsiMethod m = PsiTreeUtil.getParentOfType(a, PsiMethod.class);
            if (m != null && m.getName().equals(methodName)
                    && AnnotationSqlPreviewService.INSTANCE.isSqlAnnotation(a)) {
                return a;
            }
        }
        throw new AssertionError("annotation not found: " + methodName);
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String methodNameOf(PsiAnnotation annotation) {
        return PsiTreeUtil.getParentOfType(annotation, PsiMethod.class).getName();
    }

    @Test
    public void testSelectSingleValue() throws Exception {
        myFixture.configureByText("UserMapper.java", MAPPER);
        PreviewResult result = AnnotationSqlPreviewService.INSTANCE.preview(annotationOf("selectById"), getProject());
        assertNotNull(result);
        assertEquals("SELECT * FROM t_user WHERE id = ?", compact(result.getSql()));
        assertEquals(List.of("id"), result.getUnresolvedParams());
        assertNull(result.getWarning());
    }

    @Test
    public void testSelectArrayJoinedWithSpace() throws Exception {
        myFixture.configureByText("UserMapper.java", MAPPER);
        PreviewResult result = AnnotationSqlPreviewService.INSTANCE.preview(annotationOf("selectByName"), getProject());
        // 数组元素以单个空格连接（对齐 String.join(" ", value).trim()）
        assertEquals("SELECT * FROM t_user WHERE name = ?", compact(result.getSql()));
        assertEquals(List.of("name"), result.getUnresolvedParams());
    }

    @Test
    public void testUpdateScriptDynamicTags() throws Exception {
        myFixture.configureByText("UserMapper.java", MAPPER);
        PreviewResult result = AnnotationSqlPreviewService.INSTANCE.preview(annotationOf("updateById"), getProject());
        assertEquals("UPDATE t_user SET name = ? WHERE id = ?", compact(result.getSql()));
        // @Param 名 + param1..N 注入 mock
        List<String> filtered = result.getXmlModel().getParamProps().stream()
                .filter(p -> !p.startsWith("param"))
                .collect(Collectors.toList());
        assertEquals(List.of("id", "name"), filtered);
        assertTrue(result.getXmlModel().getParamProps().contains("param1"));
        assertTrue(result.getXmlModel().getParamProps().contains("param2"));
        assertEquals(1, result.getXmlModel().getConditionViews().size());
    }

    @Test
    public void testProviderConstantFolding() throws Exception {
        myFixture.configureByText("UserMapper.java", MAPPER);
        PreviewResult result = AnnotationSqlPreviewService.INSTANCE.preview(annotationOf("selectByStatus"), getProject());
        assertNotNull(result);
        assertEquals("SELECT * FROM t_user WHERE status = ?", compact(result.getSql()));
        assertNull(result.getWarning());
    }

    @Test
    public void testProviderDynamicSqlDegrades() throws Exception {
        myFixture.configureByText("UserMapper.java", MAPPER);
        PreviewResult result = AnnotationSqlPreviewService.INSTANCE.preview(annotationOf("selectDynamic"), getProject());
        // 含参数依赖的 return（"..." + id）→ 常量折叠失败 → 降级提示
        assertNotNull(result);
        assertTrue(result.getWarning() != null && result.getWarning().contains("无法静态预览"));
    }

    @Test
    public void testLangCustomDriverWarning() throws Exception {
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
                """.stripTrailing());
        // 默认 XML 语言驱动 → 无警告
        PreviewResult result = AnnotationSqlPreviewService.INSTANCE.preview(annotationOf("one"), getProject());
        assertEquals("SELECT 1", compact(result.getSql()));
    }

    @Test
    public void testXmlPreviewResolvesStaticConstantFromProject() throws Exception {
        // T-M25.2（用户实测）：bind 表达式 @类全名@静态字段——PSI 解析用户项目常量类字段初始值
        myFixture.configureByText(
                "DicConstants.java",
                """
                package com.cyzc.platform.common.constant;
                public class DicConstants {
                    public static final String DIC_VALUE_BIKE = "1";
                    public static final String DIC_VALUE_CAR = "2";
                }
                """.stripTrailing());
        String mapper = """
        <mapper namespace="n">
          <select id="s1">
            <bind name="status" value="@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE"/>
            SELECT * FROM t WHERE status = #{status} AND type = #{type}
          </select>
        </mapper>
        """.stripTrailing();
        PreviewResult result = XmlPreviewService.INSTANCE.preview(mapper, "s1", getProject());
        assertNull("静态常量解析成功不应有警告", result.getWarning());
        assertEquals("SELECT * FROM t WHERE status = ? AND type = ?", compact(result.getSql()));
        assertEquals(List.of("status", "type"), result.getUnresolvedParams());
        // bind 的 mock 值：常量 "1"（面板 bind 行显示表达式，SQL 中 #{} 为参数占位）
        assertEquals("status", result.getXmlModel().getBindViews().get(0).name());
    }

    @Test
    public void testXmlPreviewStaticConstantUnresolvedWarning() throws Exception {
        myFixture.configureByText(
                "DicConstants.java",
                """
                package com.cyzc.platform.common.constant;
                public class DicConstants {
                    public static final String DIC_VALUE_BIKE = "1";
                }
                """.stripTrailing());
        String mapper = """
        <mapper namespace="n">
          <select id="s1">
            <bind name="status" value="@com.cyzc.platform.common.constant.DicConstants@MISSING_FIELD"/>
            SELECT * FROM t WHERE status = #{status}
          </select>
        </mapper>
        """.stripTrailing();
        PreviewResult result = XmlPreviewService.INSTANCE.preview(mapper, "s1", getProject());
        assertTrue("字段不存在应提示", result.getWarning() != null && result.getWarning().contains("无法解析"));
    }

    @Test
    public void testAnnotationCandidatesFromFile() throws Exception {
        // M26-A：文件级多注解候选——收集全部 SQL 注解（含 Provider 族），排除非 SQL 注解
        myFixture.configureByText(
                "MultiMapper.java",
                """
                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.Update;
                import org.apache.ibatis.annotations.Param;

                public interface MultiMapper {
                    @Select("SELECT * FROM t_user WHERE id = #{id}")
                    User selectById(@Param("id") Long id);

                    @Select({"SELECT * FROM t_user", "WHERE name = #{name}"})
                    User selectByName(String name);

                    @Update("UPDATE t_user SET name = #{name} WHERE id = #{id}")
                    int updateName(@Param("id") Long id, @Param("name") String name);

                    @Deprecated
                    int legacy();
                }
                """.stripTrailing());
        List<PsiAnnotation> candidates = AnnotationSqlPreviewService.INSTANCE.annotationCandidates(myFixture.getFile());
        assertEquals(3, candidates.size());
        // 摘要：value 首个元素（数组取首元素）
        Map<String, PsiAnnotation> byMethod = new LinkedHashMap<>();
        for (PsiAnnotation a : candidates) {
            PsiMethod m = PsiTreeUtil.getParentOfType(a, PsiMethod.class);
            byMethod.put(m.getName(), a);
        }
        assertEquals("SELECT * FROM t_user WHERE id = #{id}",
                AnnotationSqlPreviewService.INSTANCE.sqlSnippet(byMethod.get("selectById"), Integer.MAX_VALUE));
        assertEquals("SELECT * FROM t_user",
                AnnotationSqlPreviewService.INSTANCE.sqlSnippet(byMethod.get("selectByName"), Integer.MAX_VALUE));
        assertEquals("UPDATE t_user SET name = #{name} WHERE id = #{id}",
                AnnotationSqlPreviewService.INSTANCE.sqlSnippet(byMethod.get("updateName"), Integer.MAX_VALUE));
    }

    // ------------------------------------------------------------------
    // T-M29.6：选择区内注解候选（annotationCandidatesInRange）
    // ------------------------------------------------------------------

    private static final String SELECTION_MAPPER = """
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface MultiMapper {
    @Select("SELECT * FROM t_user WHERE id = #{id}")
    User selectById(Long id);

    @Select("SELECT * FROM t_user WHERE name = #{name}")
    User selectByName(String name);

    @Update("UPDATE t_user SET name = #{name} WHERE id = #{id}")
    int updateName(Long id, String name);

    @Deprecated
    int legacy();
}
""".stripTrailing();

    @Test
    public void testAnnotationCandidatesInRangeCollectsSelectionAnnotations() throws Exception {
        myFixture.configureByText("MultiMapper.java", SELECTION_MAPPER);
        PsiAnnotation first = annotationOf("selectById");
        PsiAnnotation last = annotationOf("updateName");
        List<PsiAnnotation> result = AnnotationSqlPreviewService.INSTANCE.annotationCandidatesInRange(
                myFixture.getFile(),
                first.getTextRange().getStartOffset(),
                last.getTextRange().getEndOffset());
        assertEquals(
                "选择区（selectById 注解开头 → updateName 注解结束）应含 3 个 SQL 注解且按 offset 排序，非 SQL 注解排除",
                List.of("selectById", "selectByName", "updateName"),
                result.stream().map(AnnotationPreviewServiceTest::methodNameOf).collect(Collectors.toList()));
    }

    @Test
    public void testAnnotationCandidatesInRangeEmptyWhenSelectionContainsNoAnnotationStart() throws Exception {
        myFixture.configureByText("MultiMapper.java", SELECTION_MAPPER);
        // 选择区从 selectById 注解内部开始（不含注解起始点）→ 无注解起始点（startOffset 判定与 Java 候选一致）
        PsiAnnotation first = annotationOf("selectById");
        List<PsiAnnotation> result = AnnotationSqlPreviewService.INSTANCE.annotationCandidatesInRange(
                myFixture.getFile(),
                first.getTextRange().getStartOffset() + 1,
                first.getTextRange().getEndOffset());
        assertTrue("选择区内无注解起始点应返回空", result.isEmpty());
    }

    @Test
    public void testAnnotationCandidatesInRangeExcludesAnnotationAtSelectionEnd() throws Exception {
        myFixture.configureByText("MultiMapper.java", SELECTION_MAPPER);
        // until 语义：注解起始恰好等于选择区结束 → 不计入
        PsiAnnotation first = annotationOf("selectById");
        PsiAnnotation second = annotationOf("selectByName");
        List<PsiAnnotation> result = AnnotationSqlPreviewService.INSTANCE.annotationCandidatesInRange(
                myFixture.getFile(),
                first.getTextRange().getStartOffset(),
                second.getTextRange().getStartOffset());
        assertEquals(
                "选择区结束恰在 selectByName 注解起始 → 只含 selectById",
                List.of("selectById"),
                result.stream().map(AnnotationPreviewServiceTest::methodNameOf).collect(Collectors.toList()));
    }
}
