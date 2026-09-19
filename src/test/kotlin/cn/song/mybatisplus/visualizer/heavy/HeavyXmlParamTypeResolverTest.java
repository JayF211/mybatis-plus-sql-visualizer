package cn.song.mybatisplus.visualizer.heavy;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.ui.XmlParamTypeResolver;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo;
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
import static org.junit.Assert.assertNull;
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
        // Map 入参：键 = @Param 值，任意属性前缀命中（面板兜底引号）
        // T-GoldenDefense：@Param("map") 命名形态与同 FQN 双实体场景已锁定为金样 X8（下方两条）
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

    /** X8-1（T-GoldenDefense）：`@Param("map")` Map 参数 → 键 = 参数名（@Param 值）且值为引号。 */
    @Test
    public void testMapParamKeyEqualsParamNameWithQuote() throws Exception {
        addSourceFile(
                "MapParamMapper.java",
                """
                package com.example;

                public interface MapParamMapper {
                    java.util.Map<String, Object> queryByMap(
                        @org.apache.ibatis.annotations.Param("map") java.util.Map<String, Object> map);
                }
                """);
        Map<String, Boolean> quotes =
                XmlParamTypeResolver.INSTANCE.resolveQuotes(getProject(), "com.example.MapParamMapper", "queryByMap");
        assertEquals("X8-1: Map 参数键 = 参数名且值为引号", Map.of("map", true), quotes);
    }

    /** X8-2（T-GoldenDefense）：同 FQN 双实体共存（多模块同名实体竞品翻车形态）——
     *  resolveQuotes 不抛错，`map` 键存在且无实体字段混入（Map 参数不展开实体，命中顺序不锁定）。 */
    @Test
    public void testDuplicateFqnEntitiesLeaveMapQuotesIntact() throws Exception {
        addSourceFile("User.java", "package com.example;\n\npublic class User {\n    private Long id;\n}");
        // 同 FQN 复制一份（不同文件同包同类名，字段不同）
        addSourceFile("UserDup.java", "package com.example;\n\npublic class User {\n    private String name;\n}");
        addSourceFile(
                "DupFqnMapMapper.java",
                """
                package com.example;

                public interface DupFqnMapMapper {
                    java.util.Map<String, Object> queryByMap(
                        @org.apache.ibatis.annotations.Param("map") java.util.Map<String, Object> map);
                }
                """);
        Map<String, Boolean> quotes =
                XmlParamTypeResolver.INSTANCE.resolveQuotes(getProject(), "com.example.DupFqnMapMapper", "queryByMap");
        assertEquals("X8-2: map 键存在且值为引号", Boolean.TRUE, quotes.get("map"));
        assertEquals("X8-2: 同名实体不影响 Map 键（无字段混入）", Map.of("map", true), quotes);
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

    // ------------------------------------------------------------------
    // T-PT-01：类型类别映射（Heavy 真实 PSI resolve）
    // ------------------------------------------------------------------

    /** 覆盖六类别 + 边界形态：数值族 / 时间 / 文本 / 集合 / Map / 枚举 / byte[] / 嵌套 DTO。 */
    private static final String KIND_MAPPER = """
            package com.example;

            import java.math.BigDecimal;
            import java.time.LocalDate;
            import java.util.Date;
            import java.util.List;
            import java.util.Map;

            public enum Status { A, B }

            public class InnerKindDTO {
                private LocalDate innerDate;
            }

            public class KindDTO {
                private Integer id;
                private int pageNum;
                private String name;
                private LocalDate startDate;
                private Date createdAt;
                private BigDecimal amount;
                private List<String> tags;
                private Status status;
                private byte[] blob;
                private InnerKindDTO inner;
            }

            public interface KindMapper {
                void byKinds(@org.apache.ibatis.annotations.Param("p") KindDTO p,
                             @org.apache.ibatis.annotations.Param("ids") List<Long> ids,
                             @org.apache.ibatis.annotations.Param("params") Map<String, Object> params,
                             @org.apache.ibatis.annotations.Param("keyword") String keyword,
                             @org.apache.ibatis.annotations.Param("bytes") byte[] bytes,
                             @org.apache.ibatis.annotations.Param("flag") boolean flag);
            }
            """;

    /** Kotlin `types["k"]?.kind` 等价物：键缺席返回 null（失败形态仍是断言不符，不是 NPE）。 */
    private static ParamKind kindOf(Map<String, ParamTypeInfo> types, String key) {
        ParamTypeInfo info = types.get(key);
        return info == null ? null : info.kind();
    }

    /** Kotlin `types["k"]?.nullable` 等价物。 */
    private static Boolean nullableOf(Map<String, ParamTypeInfo> types, String key) {
        ParamTypeInfo info = types.get(key);
        return info == null ? null : info.nullable();
    }

    @Test
    public void testParamKindsMappedBySignature() throws Exception {
        addSourceFile("KindMapper.java", KIND_MAPPER);
        Map<String, ParamTypeInfo> types =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.KindMapper", "byKinds");
        // DTO 参数键 = 字段路径（不产出顶层键——与升级前引号映射键形态一致）
        assertNull("DTO 参数不产出顶层键", types.get("p"));
        assertEquals(ParamKind.NUMBER, kindOf(types, "p.id"));
        assertEquals(ParamKind.NUMBER, kindOf(types, "p.pageNum"));
        assertEquals(ParamKind.TEXT, kindOf(types, "p.name"));
        assertEquals(ParamKind.TEMPORAL, kindOf(types, "p.startDate"));
        assertEquals(ParamKind.TEMPORAL, kindOf(types, "p.createdAt"));
        assertEquals(ParamKind.NUMBER, kindOf(types, "p.amount"));
        assertEquals(ParamKind.COLLECTION, kindOf(types, "p.tags"));
        assertEquals("枚举映射不到 → UNKNOWN（宁缺勿错）", ParamKind.UNKNOWN, kindOf(types, "p.status"));
        assertEquals("byte[] 按 BLOB 语义落 UNKNOWN", ParamKind.UNKNOWN, kindOf(types, "p.blob"));
        assertEquals("嵌套 DTO 继续递归（键为字段路径）", ParamKind.TEMPORAL, kindOf(types, "p.inner.innerDate"));
        // 顶层参数
        assertEquals(ParamKind.COLLECTION, kindOf(types, "ids"));
        assertEquals(ParamKind.OBJECT, kindOf(types, "params"));
        assertEquals(ParamKind.TEXT, kindOf(types, "keyword"));
        assertEquals(ParamKind.UNKNOWN, kindOf(types, "bytes"));
        assertEquals(ParamKind.UNKNOWN, kindOf(types, "flag"));
        // nullable 标记：原语类型非空
        assertEquals(Boolean.FALSE, nullableOf(types, "p.pageNum"));
        assertEquals(Boolean.TRUE, nullableOf(types, "p.id"));
        assertEquals(Boolean.FALSE, nullableOf(types, "flag"));
    }

    @Test
    public void testResolveQuotesDerivesFromKindsAndKeepsLegacyValues() throws Exception {
        addSourceFile("KindMapper.java", KIND_MAPPER);
        Map<String, ParamTypeInfo> types =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.KindMapper", "byKinds");
        Map<String, Boolean> quotes =
                XmlParamTypeResolver.INSTANCE.resolveQuotes(getProject(), "com.example.KindMapper", "byKinds");
        // 派生视图：NUMBER→false、其余 true（逐键一致，防回归断言）
        Map<String, Boolean> derived = types.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().kind().isQuoteRequired()));
        assertEquals(derived, quotes);
        assertEquals(Boolean.FALSE, quotes.get("p.id"));
        assertEquals(Boolean.FALSE, quotes.get("p.amount"));
        assertEquals(Boolean.TRUE, quotes.get("p.name"));
        assertEquals(Boolean.TRUE, quotes.get("p.startDate"));
        assertEquals(Boolean.TRUE, quotes.get("p.tags"));
        assertEquals(Boolean.TRUE, quotes.get("p.status"));
        assertEquals(Boolean.TRUE, quotes.get("params"));
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
