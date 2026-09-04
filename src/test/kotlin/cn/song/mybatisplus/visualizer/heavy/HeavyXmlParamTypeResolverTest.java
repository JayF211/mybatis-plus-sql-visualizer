package cn.song.mybatisplus.visualizer.heavy;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.ui.XmlParamTypeResolver;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.ui.components.JBTextField;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HeavyXmlParamTypeResolverTest extends HeavyPlatformTestCase {

    private static final String DTO_MAPPER = """
        package com.example;

        import java.time.LocalDate;

        public class SettlementQueryDTO {
            private LocalDate settlementStartDate;
            private Integer dicCreditStatus;
            private String merchantName;
        }

        public interface SettlementMapper {
            java.util.Map<String, Object> statistics(
                @org.apache.ibatis.annotations.Param("p") SettlementQueryDTO p);
        }
        """;

    private PsiFile addSourceFile(String name, String content) {
        HeavyXmlParamTypeResolverTest self = this;
        Computable<PsiFile> computable = () -> {
            try {
                VirtualFile root = self.createTestProjectStructure();
                VirtualFile src = root.findChild("src");
                if (src == null) {
                    src = root.createChildDirectory(self, "src");
                }
                VirtualFile vf = src.createChildData(self, name);
                VfsUtil.saveText(vf, content.stripIndent());
                return PsiManager.getInstance(self.getProject()).findFile(vf);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        };
        return WriteCommandAction.runWriteCommandAction(getProject(), computable);
    }

    @Test
    public void testDtoParamFieldTypesResolved() throws Exception {
        addSourceFile("SettlementMapper.java", DTO_MAPPER);
        Map<String, Boolean> quotes =
                XmlParamTypeResolver.INSTANCE.resolveQuotes(getProject(), "com.example.SettlementMapper", "statistics");
        assertEquals(Boolean.TRUE, quotes.get("p.settlementStartDate"));
        assertEquals(Boolean.FALSE, quotes.get("p.dicCreditStatus"));
        assertEquals(Boolean.TRUE, quotes.get("p.merchantName"));
    }

    @Test
    public void testMapParamPrefixFallback() throws Exception {
        addSourceFile(
                "MapMapper.java",
                """
                package com.example;

                public interface MapMapper {
                    java.util.Map<String, Object> statistics(
                        @org.apache.ibatis.annotations.Param("p") java.util.Map<String, Object> params);
                }
                """);
        Map<String, Boolean> quotes =
                XmlParamTypeResolver.INSTANCE.resolveQuotes(getProject(), "com.example.MapMapper", "statistics");
        assertEquals(Boolean.TRUE, quotes.get("p"));
    }

    @Test
    public void testNestedDtoAndNoParamAndPrimitive() throws Exception {
        addSourceFile(
                "NestedMapper.java",
                """
                package com.example;

                public class SettlementQueryDTO {
                    private java.time.LocalDate settlementStartDate;
                    private Integer dicCreditStatus;
                    private String merchantName;
                }

                public class InnerDTO {
                    private java.time.LocalDate date;
                }

                public class OuterDTO {
                    private InnerDTO dto;
                }

                public interface NestedMapper {
                    void q(@org.apache.ibatis.annotations.Param("p") OuterDTO p);
                    void r(SettlementQueryDTO dto);
                    void page(long pageNum, long pageSize);
                }
                """);
        Map<String, Boolean> nested =
                XmlParamTypeResolver.INSTANCE.resolveQuotes(getProject(), "com.example.NestedMapper", "q");
        assertEquals(Boolean.TRUE, nested.get("p.dto.date"));
        Map<String, Boolean> noParam =
                XmlParamTypeResolver.INSTANCE.resolveQuotes(getProject(), "com.example.NestedMapper", "r");
        assertEquals(Boolean.FALSE, noParam.get("dto.dicCreditStatus"));
        Map<String, Boolean> primitive =
                XmlParamTypeResolver.INSTANCE.resolveQuotes(getProject(), "com.example.NestedMapper", "page");
        assertEquals(Boolean.FALSE, primitive.get("pageNum"));
        assertEquals(Boolean.FALSE, primitive.get("pageSize"));
    }

    @Test
    public void testUnknownNamespaceOrStatementReturnsEmpty() throws Exception {
        addSourceFile("SettlementMapper.java", DTO_MAPPER);
        assertTrue(XmlParamTypeResolver.INSTANCE
                .resolveQuotes(getProject(), "com.example.Unknown", "statistics").isEmpty());
        assertTrue(XmlParamTypeResolver.INSTANCE
                .resolveQuotes(getProject(), "com.example.SettlementMapper", "unknown").isEmpty());
        assertTrue(XmlParamTypeResolver.INSTANCE
                .resolveQuotes(null, "com.example.SettlementMapper", "statistics").isEmpty());
    }

    @Test
    public void testEndToEndPanelBackfillByParamType() throws Exception {
        addSourceFile("SettlementMapper.java", DTO_MAPPER);
        String xml = """
            <mapper namespace="com.example.SettlementMapper">
              <select id="statistics" resultType="java.util.Map">
                SELECT * FROM sys_merchant_wallet_settlement t
                <where>
                    t.deleted = 0
                    <if test="p.settlementStartDate != null">
                        AND t.settlement_period &gt;= #{p.settlementStartDate}
                    </if>
                    <if test="p.dicCreditStatus != null">
                        AND t.dic_credit_status = #{p.dicCreditStatus}
                    </if>
                </where>
              </select>
            </mapper>
            """.stripTrailing();
        PreviewResult result = XmlPreviewService.INSTANCE.preview(xml, "statistics", getProject());
        assertNotNull("应产出预览结果", result);
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result);
        List<JBTextField> fields = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            JBTextField f = invokeParamField(panel, i);
            if (f != null) {
                fields.add(f);
            }
        }
        JBTextField dateField = null;
        JBTextField statusField = null;
        for (JBTextField f : fields) {
            if ("#p.settlementStartDate".equals(f.getToolTipText())) {
                dateField = f;
            }
            if ("#p.dicCreditStatus".equals(f.getToolTipText())) {
                statusField = f;
            }
        }
        assertNotNull("应有日期参数输入行", dateField);
        assertNotNull("应有状态参数输入行", statusField);
        dateField.setText("2025");
        statusField.setText("2");
        Editor editor = invokeEditor(panel);
        String sql = editor.getDocument().getText().replaceAll("\\s+", " ").trim();
        assertTrue("LocalDate 字段应引号：" + sql, sql.contains("t.settlement_period >= '2025'"));
        assertTrue("Integer 字段应裸渲染：" + sql, sql.contains("t.dic_credit_status = 2"));
        assertFalse("Integer 字段不应带引号：" + sql, sql.contains("t.dic_credit_status = '2'"));
        panel.clear();
    }

    private static JBTextField invokeParamField(SqlPreviewPanel panel, int i) {
        try {
            Method m = SqlPreviewPanel.class.getDeclaredMethod("paramFieldForTest", int.class);
            m.setAccessible(true);
            return (JBTextField) m.invoke(panel, i);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Editor invokeEditor(SqlPreviewPanel panel) {
        try {
            Method m = SqlPreviewPanel.class.getDeclaredMethod("editorForTest");
            m.setAccessible(true);
            return (Editor) m.invoke(panel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
