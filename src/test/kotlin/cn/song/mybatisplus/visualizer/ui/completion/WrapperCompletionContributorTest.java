package cn.song.mybatisplus.visualizer.ui.completion;

import cn.song.mybatisplus.visualizer.model.WrapperType;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WrapperCompletionContributorTest extends BasePlatformTestCase {

    private static ChainStateDetector.ChainState state(
            WrapperType wrapperType,
            List<String> calledMethods,
            String lastMethod,
            boolean isInsideMethodArgs,
            String currentMethodName) {
        return new ChainStateDetector.ChainState(
                wrapperType, calledMethods, lastMethod, isInsideMethodArgs, currentMethodName);
    }

    @Test
    public void testContributorRegisteredForJava() throws Exception {
        List<CompletionContributor> contributors =
                CompletionContributor.forLanguage(JavaLanguage.INSTANCE);
        boolean found = false;
        for (CompletionContributor c : contributors) {
            if (c instanceof WrapperCompletionContributor) {
                found = true;
                break;
            }
        }
        assertTrue("应注册 WrapperCompletionContributor 到 JAVA 语言", found);
    }

    @Test
    public void testMethodLookupFactory_generatesConditionMethodsAtHead() throws Exception {
        ChainStateDetector.ChainState state = state(WrapperType.LAMBDA_QUERY, List.of(), null, false, null);
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        Set<String> names = lookupNames(lookups);
        assertTrue("链首应含 eq", names.contains("eq"));
        assertTrue("链首应含 like", names.contains("like"));
        assertTrue("链首应含 in", names.contains("in"));
        assertTrue("链首应含 orderBy", names.contains("orderBy"));
        assertTrue("链首应含 groupBy", names.contains("groupBy"));
        assertTrue("链首应含 select", names.contains("select"));
    }

    @Test
    public void testMethodLookupFactory_afterConditionMethod() throws Exception {
        ChainStateDetector.ChainState state = state(WrapperType.LAMBDA_QUERY, List.of("eq"), "eq", false, null);
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        Set<String> names = lookupNames(lookups);
        assertTrue("eq 后应含 and", names.contains("and"));
        assertTrue("eq 后应含 or", names.contains("or"));
        assertTrue("eq 后应含 orderBy", names.contains("orderBy"));
        assertTrue("eq 后应含 ne", names.contains("ne"));
    }

    @Test
    public void testMethodLookupFactory_afterSelect() throws Exception {
        ChainStateDetector.ChainState state = state(WrapperType.LAMBDA_QUERY, List.of("select"), "select", false, null);
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        Set<String> names = lookupNames(lookups);
        assertTrue("select 后应含 selectAs", names.contains("selectAs"));
        assertTrue("select 后应含 selectIgnore", names.contains("selectIgnore"));
        assertTrue("select 后应含 groupBy", names.contains("groupBy"));
        assertTrue("select 后应含 having", names.contains("having"));
        assertTrue("select 后应含 orderBy", names.contains("orderBy"));
    }

    @Test
    public void testMethodLookupFactory_afterOrderBy() throws Exception {
        ChainStateDetector.ChainState state = state(WrapperType.LAMBDA_QUERY, List.of("eq", "orderBy"), "orderBy", false, null);
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        Set<String> names = lookupNames(lookups);
        assertTrue("orderBy 后应含 orderByAsc", names.contains("orderByAsc"));
        assertTrue("orderBy 后应含 orderByDesc", names.contains("orderByDesc"));
        assertTrue("orderBy 后应含 limit", names.contains("limit"));
        assertTrue("orderBy 后应含 page", names.contains("page"));
        assertTrue("orderBy 后应含 list", names.contains("list"));
        assertTrue("orderBy 后应含 one", names.contains("one"));
    }

    @Test
    public void testMethodLookupFactory_mpjHeadIncludesJoinMethods() throws Exception {
        ChainStateDetector.ChainState state = state(WrapperType.MPJ_LAMBDA, List.of(), null, false, null);
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        Set<String> names = lookupNames(lookups);
        assertTrue("MPJ 链首应含 leftJoin", names.contains("leftJoin"));
        assertTrue("MPJ 链首应含 rightJoin", names.contains("rightJoin"));
        assertTrue("MPJ 链首应含 selectAll", names.contains("selectAll"));
        assertTrue("MPJ 链首应含 innerJoin", names.contains("innerJoin"));
    }

    @Test
    public void testMethodLookupFactory_terminalMethodReturnsEmpty() throws Exception {
        ChainStateDetector.ChainState state = state(WrapperType.LAMBDA_QUERY, List.of("eq", "list"), "list", false, null);
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        assertEquals("终结点后应无方法名补全", 0, lookups.size());
    }

    @Test
    public void testMethodLookupFactory_insideArgsReturnsEmpty() throws Exception {
        ChainStateDetector.ChainState state = state(WrapperType.LAMBDA_QUERY, List.of(), null, true, "eq");
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        assertEquals("实参内应无方法名补全", 0, lookups.size());
    }

    @Test
    public void testMethodLookupFactory_updateAfterSet() throws Exception {
        ChainStateDetector.ChainState state = state(WrapperType.LAMBDA_UPDATE, List.of("set"), "set", false, null);
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        Set<String> names = lookupNames(lookups);
        assertTrue("set 后应含 set", names.contains("set"));
        assertTrue("set 后应含 setSql", names.contains("setSql"));
        assertTrue("set 后应含 setIncrBy", names.contains("setIncrBy"));
        assertTrue("set 后应含 setDecrBy", names.contains("setDecrBy"));
        assertTrue("set 后应含 eq", names.contains("eq"));
        assertTrue("set 后应含 where", names.contains("where"));
    }

    @Test
    public void testMethodLookupElements_haveTypeText() throws Exception {
        ChainStateDetector.ChainState state = state(WrapperType.LAMBDA_QUERY, List.of(), null, false, null);
        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        assertTrue("应生成补全项", !lookups.isEmpty());
        LookupElement eqLookup = null;
        for (LookupElement l : lookups) {
            if ("eq".equals(l.getLookupString())) {
                eqLookup = l;
                break;
            }
        }
        LookupElementPresentation presentation = new LookupElementPresentation();
        eqLookup.renderElement(presentation);
        assertNotNull("eq 应有 typeText（语义描述）", presentation.getTypeText());
        assertEquals("=", presentation.getTypeText());
    }

    @Test
    public void testChainStateDetector_integrationWithContributor() throws Exception {
        myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() throws Exception {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1L)
                            .like(DeviceInfoEntity::getName, "test");
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                        private String name;
                    }
                }
                """.stripTrailing());
        String text = myFixture.getFile().getText();
        int likeNameOffset = text.indexOf(".like(") + 2;
        PsiElement element = findAtMyFile(likeNameOffset);
        ChainStateDetector.ChainState state = ChainStateDetector.detect(element);
        assertNotNull("应检测到链状态", state);
        assertEquals(WrapperType.LAMBDA_QUERY, state.getWrapperType());
        assertTrue("应含 eq", state.getCalledMethods().contains("eq"));
        assertEquals("eq", state.getLastMethod());
        assertFalse("在方法名位置不应判定为实参内", state.isInsideMethodArgs());

        List<LookupElement> lookups = MethodLookupFactory.createLookups(state);
        assertTrue("应生成非空补全项", !lookups.isEmpty());
    }

    private static Set<String> lookupNames(List<LookupElement> lookups) {
        Set<String> names = new HashSet<>();
        for (LookupElement l : lookups) {
            names.add(l.getLookupString());
        }
        return names;
    }

    private PsiElement findAtMyFile(int offset) {
        PsiElement current = myFixture.getFile();
        while (true) {
            PsiElement child = null;
            for (PsiElement c : current.getChildren()) {
                if (c.getTextRange().contains(offset)) {
                    child = c;
                    break;
                }
            }
            if (child == null) {
                return current;
            }
            current = child;
        }
    }
}
