package cn.song.mybatisplus.visualizer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.xml.EwPlaceholder;
import cn.song.mybatisplus.visualizer.xml.MockObject;
import cn.song.mybatisplus.visualizer.xml.XmlEwExpander;
import cn.song.mybatisplus.visualizer.xml.XmlMapperModel;
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import cn.song.mybatisplus.visualizer.xml.XmlStatement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MP 占位展开端到端测试（T-M26-B，P-立项-03）：mapper XML `${ew.customSqlSegment}` →
 * namespace 关联 Mapper 接口 → ReferencesSearch 找调用 → wrapper 实参展开 → WHERE 片段注入。
 */
public class XmlEwResolverTest extends BasePlatformTestCase {

    /** 注入轻量调用查找的预览入口（生产路径在 XmlPreviewService 内部走 ReferencesSearch）。 */
    private PreviewResult previewWithFinder(String xml, String id, Project project, PsiFile serviceFile) {
        XmlMapperModel parsed = new XmlMapperParser().parse(xml);
        XmlStatement statement = null;
        for (XmlStatement s : parsed.statements()) {
            if (s.id().equals(id)) {
                statement = s;
                break;
            }
        }
        List<String> warnings = new ArrayList<>();
        List<EwPlaceholder> placeholders = XmlEwExpander.placeholders(xml);
        XmlEwResolver.CallFinder finder = method -> {
            List<PsiMethodCallExpression> calls =
                    new java.util.ArrayList<>(PsiTreeUtil.collectElementsOfType(serviceFile, PsiMethodCallExpression.class));
            return calls.stream()
                    .filter(e -> method.getName().equals(e.getMethodExpression().getReferenceName()))
                    .collect(Collectors.<PsiElement>toList());
        };
        Map<String, Map<String, String>> resolved =
                XmlEwResolver.INSTANCE.resolve(parsed.namespace(), id, placeholders, project, warnings, finder);
        Map<String, Object> ewBindings = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : resolved.entrySet()) {
            ewBindings.put(e.getKey(), new MockObject(new LinkedHashMap<>(e.getValue())));
        }
        // Kotlin 具名实参 from(statement, errors, extraBindings = ewBindings) 的 Java 等价：
        // extraParamProps=空、staticFieldValues=空、paramQuotes=空、paramTypes=空（余下按生产默认）
        XmlPreviewModel model = XmlPreviewModel.from(
                statement, parsed.errors(), Set.of(), Map.of(), ewBindings, Map.of());
        return XmlPreviewService.INSTANCE.toResult(model, warnings);
    }

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static final String MAPPER_XML = """
    <mapper namespace="com.example.UserMapper">
      <select id="selectList" resultType="map">
        SELECT * FROM t_user ${ew.customSqlSegment}
      </select>
    </mapper>
    """.stripTrailing();

    @Test
    public void testEwCustomSqlSegmentExpanded() throws Exception {
        myFixture.addFileToProject(
                "UserMapper.java",
                """
                package com.example;
                import com.baomidou.mybatisplus.core.conditions.Wrapper;

                public class User {
                    private Long id;
                    private String name;
                }

                public interface UserMapper {
                    java.util.List<Object> selectList(Wrapper ew);
                }
                """.stripTrailing());
        myFixture.configureByText(
                "Service.java",
                """
                package com.example;
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

                public class Service {
                    public void run(UserMapper mapper) {
                        mapper.selectList(new QueryWrapper<com.example.User>().eq("id", 1L).eq("name", "x"));
                    }
                }
                """.stripTrailing());
        // Light 测试注入轻量调用查找（生产走 ReferencesSearch，此处直接扫描调用表达式）
        PsiFile serviceFile = myFixture.getFile();
        PreviewResult result = previewWithFinder(MAPPER_XML, "selectList", getProject(), serviceFile);
        assertTrue("应展开 ew.customSqlSegment：" + result.getSql(), result.getSql().contains("WHERE (id = 1"));
        assertTrue("不应有占位求值为空提示：" + result.getWarning(),
                result.getWarning() == null || !result.getWarning().contains("求值为空"));
        assertEquals("SELECT * FROM t_user WHERE (id = 1 AND name = 'x')", compact(result.getSql()));
    }

    @Test
    public void testEwNoCallDegradesWithWarning() throws Exception {
        // 接口存在但无调用处 → 降级提示（占位保持空）
        myFixture.addFileToProject(
                "UserMapper.java",
                """
                package com.example;
                import com.baomidou.mybatisplus.core.conditions.Wrapper;

                public interface UserMapper {
                    java.util.List<Object> selectList(Wrapper ew);
                }
                """.stripTrailing());
        myFixture.configureByText("Unrelated.java", "public class Unrelated { int x = 1; }");
        PreviewResult result = XmlPreviewService.INSTANCE.preview(MAPPER_XML, "selectList", getProject());
        assertTrue("应提示未找到调用：" + result.getWarning(),
                result.getWarning() != null && result.getWarning().contains("未找到"));
        assertEquals("SELECT * FROM t_user", compact(result.getSql()));
    }

    @Test
    public void testEwNoNamespaceDegrades() throws Exception {
        String noNsXml = """
        <mapper>
          <select id="selectList">SELECT * FROM t WHERE ${ew.customSqlSegment}</select>
        </mapper>
        """.stripTrailing();
        PreviewResult result = XmlPreviewService.INSTANCE.preview(noNsXml, "selectList", getProject());
        assertTrue("应提示缺 namespace：" + result.getWarning(),
                result.getWarning() != null && result.getWarning().contains("namespace"));
    }

    @Test
    public void testEwWithoutProjectKeepsOldBehavior() throws Exception {
        // 无 project（纯文本）→ 不展开（占位求值为空提示）
        PreviewResult result = XmlPreviewService.INSTANCE.preview(MAPPER_XML, "selectList", null);
        assertTrue("无 project 时应保持占位求值为空提示",
                result.getWarning() != null && result.getWarning().contains("求值为空"));
    }
}
