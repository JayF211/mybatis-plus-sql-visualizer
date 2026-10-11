package cn.song.mybatisplus.visualizer.heavy;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.ui.XmlParamTypeResolver;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo;
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
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
import java.util.Set;

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

    /** X8-1（T-GoldenDefense）：`@Param("map")` Map 参数 → 键 = 参数名（@Param 值）且值为引号。
     *  T-PC2-03 起单参数全显式亦补 `param1`（MyBatis `ParamMap` 位置别名）——金样随之收进 `param1`。 */
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
        assertEquals("X8-1: Map 参数键 = 参数名且值为引号", Map.of("map", true, "param1", true), quotes);
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
        // T-PC2-03 起单参数全显式亦补 param1；断言锁「无实体字段混入」（Map 不展开实体）
        assertEquals("X8-2: 同名实体不影响 Map 键（无字段混入）", Map.of("map", true, "param1", true), quotes);
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

    // ------------------------------------------------------------------
    // T-PC2-01 / T-PC2-02：BigInteger 补入数值族、UUID 归文本（真实 PSI 解析链）
    // ------------------------------------------------------------------

    /** 补缺夹具：`java.math.BigInteger`（漏收）与 `java.util.UUID`（全仓无映射）两种来源（顶层参数 + DTO 字段）。 */
    private static final String MATH_MAPPER = """
        package com.example;

        import java.math.BigInteger;
        import java.util.UUID;

        public class MathDTO {
            private BigInteger serial;
            private UUID traceId;
        }

        public interface MathMapper {
            void byMath(@org.apache.ibatis.annotations.Param("p") MathDTO p,
                        @org.apache.ibatis.annotations.Param("big") BigInteger big,
                        @org.apache.ibatis.annotations.Param("uuid") UUID uuid);
        }
        """;

    @Test
    public void testBigIntegerAndUuidMappedBySignature() throws Exception {
        addSourceFile("MathMapper.java", MATH_MAPPER);
        Map<String, ParamTypeInfo> types =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.MathMapper", "byMath");
        assertEquals("BigInteger 顶层参数应落 NUMBER（漏收修复）", ParamKind.NUMBER, kindOf(types, "big"));
        assertEquals("UUID 顶层参数应落 TEXT", ParamKind.TEXT, kindOf(types, "uuid"));
        assertEquals("DTO 的 BigInteger 字段应落 NUMBER", ParamKind.NUMBER, kindOf(types, "p.serial"));
        assertEquals("DTO 的 UUID 字段应落 TEXT", ParamKind.TEXT, kindOf(types, "p.traceId"));
        // 引号语义：BigInteger 裸渲染（原 UNKNOWN 带引号 → 修正）、UUID 带引号
        Map<String, Boolean> quotes =
                XmlParamTypeResolver.INSTANCE.resolveQuotes(getProject(), "com.example.MathMapper", "byMath");
        assertEquals("BigInteger 应裸渲染", Boolean.FALSE, quotes.get("big"));
        assertEquals("UUID 应带引号", Boolean.TRUE, quotes.get("uuid"));
        assertEquals(Boolean.FALSE, quotes.get("p.serial"));
        assertEquals(Boolean.TRUE, quotes.get("p.traceId"));
    }

    // ------------------------------------------------------------------
    // T-PC2-03：XML 路径 paramN 位置别名（Z-17 全或无门槛）
    // ------------------------------------------------------------------

    /** 门槛夹具：全显式 / 含未命名 / 全未命名 / 单参数全显式 四态。 */
    private static final String ALIAS_MAPPER = """
        package com.example;

        public interface AliasMapper {
            void allNamed(@org.apache.ibatis.annotations.Param("a") long a,
                          @org.apache.ibatis.annotations.Param("b") String b);
            void mixed(@org.apache.ibatis.annotations.Param("a") long a, String b);
            void unnamed(long a, String b);
            void singleNamed(@org.apache.ibatis.annotations.Param("only") String only);
        }
        """;

    @Test
    public void testParamNAliasOnlyWhenAllParamsAnnotated() throws Exception {
        addSourceFile("AliasMapper.java", ALIAS_MAPPER);
        // 全显式 → 补 param1..N（类型跟随位置参数）
        Map<String, ParamTypeInfo> all =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.AliasMapper", "allNamed");
        assertEquals(ParamKind.NUMBER, kindOf(all, "param1"));
        assertEquals(ParamKind.TEXT, kindOf(all, "param2"));
        assertEquals("原语参数经别名继承非空标记", Boolean.FALSE, nullableOf(all, "param1"));
        assertEquals(Boolean.TRUE, nullableOf(all, "param2"));
        // 单参数全显式：MyBatis ParamMap 同样补 param1
        Map<String, ParamTypeInfo> single =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.AliasMapper", "singleNamed");
        assertEquals(ParamKind.TEXT, kindOf(single, "param1"));
        // 含未命名参数 → 整体不推导（宁缺勿错），命名键照常保留
        Map<String, ParamTypeInfo> mixed =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.AliasMapper", "mixed");
        assertNull("含未命名参数不推导 paramN", mixed.get("param1"));
        assertNull(mixed.get("param2"));
        assertTrue("命名键仍在", mixed.containsKey("a") && mixed.containsKey("b"));
        // 全未命名 → 不推导
        Map<String, ParamTypeInfo> unnamed =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.AliasMapper", "unnamed");
        assertNull(unnamed.get("param1"));
        assertNull(unnamed.get("param2"));
        assertTrue(unnamed.containsKey("a") && unnamed.containsKey("b"));
    }

    /** T-PC2-03 别名与类型类别联动：`#{param1.字段}` 经前缀兜底落 OBJECT（DTO 参数的位置别名）。 */
    @Test
    public void testParamNAliasForDtoParamResolvesObject() throws Exception {
        addSourceFile("AliasDtoMapper.java", """
            package com.example;

            public class AliasDto {
                private Integer id;
            }

            public interface AliasDtoMapper {
                void byDto(@org.apache.ibatis.annotations.Param("p") AliasDto p,
                           @org.apache.ibatis.annotations.Param("k") String k);
            }
            """);
        String xml = """
            <mapper namespace="com.example.AliasDtoMapper">
              <select id="byDto">SELECT * FROM t WHERE id = #{param1.id} AND k = #{param2}</select>
            </mapper>
            """;
        XmlPreviewModel model = XmlPreviewModel.from(
                new XmlMapperParser().parse(xml).statements().get(0),
                List.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.AliasDtoMapper", "byDto"));
        assertEquals(ParamKind.OBJECT, model.kindFor("param1"));
        assertEquals("param1.字段 前缀兜底命中别名条目", ParamKind.OBJECT, model.kindFor("param1.id"));
        assertEquals(ParamKind.TEXT, model.kindFor("param2"));
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
        cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelResultViewKt.showResult(panel, result);
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
        cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelSqlViewKt.clear(panel);
    }

    // ------------------------------------------------------------------
    // T-FCA-01（N-32）：foreach 集合快捷别名（ParamNameResolver.wrapToMapIfCollection）
    //
    // 验收 1（「真引擎对照下三种快捷名渲染逐字节相等」）的**承重判据在本节**：三条用例钉我方注入
    // （`XmlParamTypeResolver.collectionAliasTypes` 经 `resolveTypes` → paramTypes），摘掉注入即红
    // （反向验证两次读数见实施记录）。`xml/fidelity/RealEngineFidelityTest` 的 P31~P33 只钉
    // **真引擎键集 + 自研 foreach 语义**（自研侧手工喂别名键、绕开面板 mock 层），对我方注入不构成判据
    // ——那边的注记同源。
    // ------------------------------------------------------------------

    /**
     * 别名门槛夹具（覆盖真引擎的逐形态分支 + 三类负向）：单 List / 单数组 / 单 Set /
     * 单 Iterable（非 Collection）/ 单参数带 @Param / 多参数 / 非集合标量 /
     * 泛型数组两种（`List<Long>[]`、`Map<String,Object>[]`，见 {@link #testCollectionAliasForGenericArrayKeysArrayOnly}）。
     */
    private static final String COLLECTION_ALIAS_MAPPER = """
        package com.example;

        public interface CollectionAliasMapper {
            void byList(java.util.List<Long> ids);
            void byArray(Long[] ids);
            void bySet(java.util.Set<Long> ids);
            void byIterable(Iterable<Long> ids);
            void byNamed(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids);
            void byMulti(java.util.List<Long> ids, String keyword);
            void byScalar(Long id);
            void byGenericListArray(java.util.List<Long>[] ids);
            void byGenericSetArray(java.util.Set<Long>[] ids);
            void byGenericMapArray(java.util.Map<String, Object>[] ids);
        }
        """;

    /**
     * 正向：单集合参数且无 `@Param` → 别名键存在，键集按真引擎实测形态
     * （`List` → `collection` + `list`；`T[]` → 仅 `array`；`Set` → 仅 `collection`）。
     * 参数名键照常保留（别名是**追加**不是替换）。
     *
     * **承重**：本用例（与 {@link #testCollectionAliasAbsentWhenGateFails}）是「我方别名注入」的直接判据——
     * 摘掉 `resolveTypes` 里的注入即红；`RealEngineFidelityTest` 的 P31~P33 对本注入不可见（见节头注记）。
     */
    @Test
    public void testCollectionAliasKeysByParamShape() throws Exception {
        addSourceFile("CollectionAliasMapper.java", COLLECTION_ALIAS_MAPPER);
        Map<String, ParamTypeInfo> list =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.CollectionAliasMapper", "byList");
        assertEquals("List 参数应双写 collection + list", ParamKind.COLLECTION, kindOf(list, "collection"));
        assertEquals(ParamKind.COLLECTION, kindOf(list, "list"));
        assertNull("List 参数不产 array 键", list.get("array"));
        assertEquals("参数名键照常保留", ParamKind.COLLECTION, kindOf(list, "ids"));

        Map<String, ParamTypeInfo> array =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.CollectionAliasMapper", "byArray");
        assertEquals("数组参数只产 array 键", ParamKind.COLLECTION, kindOf(array, "array"));
        assertNull("数组参数不产 collection 键", array.get("collection"));
        assertNull("数组参数不产 list 键", array.get("list"));
        assertEquals(ParamKind.COLLECTION, kindOf(array, "ids"));

        Map<String, ParamTypeInfo> set =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.CollectionAliasMapper", "bySet");
        assertEquals("Set（Collection 非 List）只产 collection", ParamKind.COLLECTION, kindOf(set, "collection"));
        assertNull("Set 参数不产 list 键（真引擎 instanceof List 不成立）", set.get("list"));
        assertNull(set.get("array"));
    }

    /**
     * 负向（全或无门槛的反面）：多参数、单参数带显式 `@Param`、非集合标量、`Iterable`
     * （集合语义但非 `java.util.Collection`，真引擎不包装）→ **均不产别名键**。
     */
    @Test
    public void testCollectionAliasAbsentWhenGateFails() throws Exception {
        addSourceFile("CollectionAliasMapper.java", COLLECTION_ALIAS_MAPPER);
        Map<String, ParamTypeInfo> named =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.CollectionAliasMapper", "byNamed");
        assertNull("带 @Param 不产 list 别名", named.get("list"));
        assertNull(named.get("collection"));
        assertNull(named.get("array"));
        assertEquals("命名键仍在", ParamKind.COLLECTION, kindOf(named, "ids"));

        Map<String, ParamTypeInfo> multi =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.CollectionAliasMapper", "byMulti");
        assertNull("多参数不产 list 别名", multi.get("list"));
        assertNull(multi.get("collection"));
        assertNull(multi.get("array"));
        assertTrue("命名键仍在", multi.containsKey("ids") && multi.containsKey("keyword"));

        Map<String, ParamTypeInfo> scalar =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.CollectionAliasMapper", "byScalar");
        assertNull("非集合标量不产别名", scalar.get("collection"));
        assertNull(scalar.get("list"));
        assertNull(scalar.get("array"));

        Map<String, ParamTypeInfo> iterable =
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.CollectionAliasMapper", "byIterable");
        assertNull("Iterable 非 java.util.Collection：真引擎不包装 → 不产别名", iterable.get("collection"));
        assertNull(iterable.get("list"));
    }

    /**
     * 泛型数组键集（T-FCA-01 修正）：数组形态判定**必须先于泛型剥壳**。
     *
     * 实测缺陷（独立复核员读数 `expected:<[array, ids]> but was:<[collection, ids, list]>`）：
     * 旧实现先 `substringBefore('<')` 再判 `endsWith("[]")`，`java.util.List<java.lang.Long>[]`
     * 被截成 `List` → 误判为 List 族并产出 `list` 键；`Set<Long>[]` 同源（得 `collection`）。
     * 而数组在真引擎里**只有 `array` 键**（`RealEngineFidelityTest` 的「别名键集钉」用例 `T[]` 支：
     * `instanceof Collection` 对数组不成立，走 `getClass().isArray()` 支）。
     *
     * 断言用**整键集**（而非单键存在性）：误产出的 `list`/`collection` 键只有整集比对才看得见。
     * `Map<String,Object>[]` 是同一根因在类别层的显形（旧顺序剥壳后按 `Map` 落 OBJECT，
     * 连别名门槛都进不去）——数组类别与元素类型无关。
     */
    @Test
    public void testCollectionAliasForGenericArrayKeysArrayOnly() throws Exception {
        addSourceFile("CollectionAliasMapper.java", COLLECTION_ALIAS_MAPPER);
        // 对照：普通数组本就正确（修正泛型数组后不得回退）
        assertEquals("普通数组 T[] → 仅 array + 参数名键", Set.of("array", "ids"), resolveAliasKeys("byArray"));
        assertEquals(
                "泛型数组 List<Long>[] → 仅 array（不得出现 list/collection）",
                Set.of("array", "ids"),
                resolveAliasKeys("byGenericListArray"));
        assertEquals(
                "泛型数组 Set<Long>[] → 仅 array",
                Set.of("array", "ids"),
                resolveAliasKeys("byGenericSetArray"));
        assertEquals(
                "泛型数组 Map<String,Object>[] → 仅 array（数组类别不随元素类型变）",
                Set.of("array", "ids"),
                resolveAliasKeys("byGenericMapArray"));
        assertEquals(
                "数组类别恒为 COLLECTION（元素是 Map 也不例外）",
                ParamKind.COLLECTION,
                kindOf(
                        XmlParamTypeResolver.INSTANCE.resolveTypes(
                                getProject(), "com.example.CollectionAliasMapper", "byGenericMapArray"),
                        "ids"));
    }

    /**
     * 别名与预览绑定构造联动：`collection="list"` 的 foreach 在预览里按别名键展开（引号档随类别）。
     *
     * **承重**：注入的**唯一可观测面**（面板类型视图 `kindFor`/`quoteFor`）在此钉住——渲染成功本身
     * 不是判据（`mockBindings` 直接按 `collectionExpr` 建键，无注入也渲染得出来，实测）。
     */
    @Test
    public void testCollectionAliasFeedsPreviewBindings() throws Exception {
        addSourceFile("CollectionAliasMapper.java", COLLECTION_ALIAS_MAPPER);
        String xml = """
                <mapper namespace="com.example.CollectionAliasMapper">
                  <select id="byList">SELECT * FROM t WHERE id IN<foreach collection="list" item="id" open="(" separator="," close=")">#{id}</foreach></select>
                </mapper>
                """;
        XmlPreviewModel model = XmlPreviewModel.from(
                new XmlMapperParser().parse(xml).statements().get(0),
                List.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                XmlParamTypeResolver.INSTANCE.resolveTypes(getProject(), "com.example.CollectionAliasMapper", "byList"));
        assertEquals("别名键进 paramTypes（预览类型视图可命中）", ParamKind.COLLECTION, model.kindFor("list"));
        assertEquals("别名键引号档随类别（COLLECTION → 引号）", Boolean.TRUE, model.quoteFor("list"));
        assertEquals("foreach 视图照常收集", "list", model.getForEachViews().get(0).collectionExpr());
    }

    /** {@link #testCollectionAliasForGenericArrayKeysArrayOnly} 用：方法 → `resolveTypes` 整键集
     *  （Kotlin 侧为测试内局部函数 `keysOf`；误产出的 `list`/`collection` 键只有整集比对才看得见）。 */
    private Set<String> resolveAliasKeys(String statementId) {
        return XmlParamTypeResolver.INSTANCE
                .resolveTypes(getProject(), "com.example.CollectionAliasMapper", statementId)
                .keySet();
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
