package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结果 Tab 选择判定测试（M36.5.22b）。
 *
 * DBN 产生新结果时复用同一个 Content 实例并改名（Result 1 → Result 2），
 * 判定逻辑已抽为纯函数 DataSourceSelector.pickResultTab，不依赖 Database 插件运行时。
 */
public class ResultTabSelectionTest extends BasePlatformTestCase {

    /**
     * 可改名的 Content 代理——模拟 DBN 复用实例改名的真实行为。
     */
    private static class FakeContent {
        String displayName;
        final Content proxy;

        FakeContent(String initialName) {
            this.displayName = initialName;
            this.proxy = (Content) Proxy.newProxyInstance(
                    FakeContent.class.getClassLoader(),
                    new Class[]{Content.class},
                    (InvocationHandler) (proxyObj, method, args) -> {
                        switch (method.getName()) {
                            case "getDisplayName":
                                return displayName;
                            case "setDisplayName":
                                displayName = (String) args[0];
                                return null;
                            case "equals":
                                return proxyObj == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxyObj);
                            case "toString":
                                return "FakeContent(" + displayName + ")";
                            default:
                                return null;
                        }
                    });
        }
    }

    /** 反射 getContents() 的假 RunnerLayoutUi。 */
    private static class FakeUi {
        private final Content[] tabs;

        FakeUi(Content[] tabs) {
            this.tabs = tabs;
        }

        public Content[] getContents() {
            return tabs;
        }
    }

    // ==================== 场景一：实例复用改名（M36.5.22b 回归核心） ====================

    @Test
    public void testRenamedContentInstancePickedImmediately() throws Exception {
        FakeContent reused = new FakeContent("Result 1");
        Set<String> preExisting = Set.of("Result 1");
        reused.displayName = "Result 2";

        Content picked = DataSourceSelector.pickResultTab(new Content[]{reused.proxy}, preExisting, 1);
        assertEquals("改名后的实例应第 1 拍即选中（滞后一轮回归点）", "Result 2", picked.getDisplayName());
    }

    @Test
    public void testSnapshotThenRenameFullChain() throws Exception {
        FakeContent reused = new FakeContent("Result 1");
        FakeUi ui = new FakeUi(new Content[]{reused.proxy});
        Set<String> preExistingNames = DataSourceSelector.resultContents(ui).stream()
                .map(Content::getDisplayName).collect(Collectors.toSet());
        assertEquals(Set.of("Result 1"), preExistingNames);

        reused.displayName = "Result 2";
        Content picked = DataSourceSelector.pickResultTab(
                DataSourceSelector.resultContents(ui).toArray(new Content[0]),
                preExistingNames,
                1);
        assertEquals("Result 2", picked.getDisplayName());
    }

    // ==================== 场景二：新 Tab 新名称 ====================

    @Test
    public void testNewTabNameWinsImmediately() throws Exception {
        Content old = new FakeContent("Result 1").proxy;
        Content newTab = new FakeContent("Result 2").proxy;
        Content picked = DataSourceSelector.pickResultTab(new Content[]{old, newTab}, Set.of("Result 1"), 1);
        assertEquals("Result 2", picked.getDisplayName());
    }

    @Test
    public void testMultipleNewTabsPicksFirst() throws Exception {
        Content a = new FakeContent("Result 2").proxy;
        Content b = new FakeContent("Result 3").proxy;
        Content picked = DataSourceSelector.pickResultTab(new Content[]{a, b}, Set.of("Result 1"), 1);
        assertEquals("Result 2", picked.getDisplayName());
    }

    // ==================== 场景三：旧 Tab 复用阈值 ====================

    @Test
    public void testStaleTabNotPickedBeforeThreshold() throws Exception {
        Content stale = new FakeContent("Result 1").proxy;
        Content picked = DataSourceSelector.pickResultTab(
                new Content[]{stale}, Set.of("Result 1"), DataSourceSelector.REUSE_ACCEPT_TICKS - 1);
        assertNull("阈值前不应选中旧 Tab（防旧结果抢跑）", picked);
    }

    @Test
    public void testStaleTabAcceptedAtThreshold() throws Exception {
        Content stale = new FakeContent("Result 1").proxy;
        Content picked = DataSourceSelector.pickResultTab(
                new Content[]{stale}, Set.of("Result 1"), DataSourceSelector.REUSE_ACCEPT_TICKS);
        assertEquals("Result 1", picked.getDisplayName());
    }

    // ==================== 场景四：Output 过滤 ====================

    @Test
    public void testOutputTabExcluded() throws Exception {
        Content output = new FakeContent("Output").proxy;
        Content result = new FakeContent("Result 1").proxy;
        Content picked = DataSourceSelector.pickResultTab(new Content[]{output, result}, Set.of(), 1);
        assertEquals("Result 1", picked.getDisplayName());
    }

    @Test
    public void testChineseOutputTabExcluded() throws Exception {
        Content output = new FakeContent("输出").proxy;
        Content result = new FakeContent("Result 1").proxy;
        Content picked = DataSourceSelector.pickResultTab(new Content[]{output, result}, Set.of(), 1);
        assertEquals("Result 1", picked.getDisplayName());
    }

    @Test
    public void testOnlyOutputYieldsNull() throws Exception {
        Content output = new FakeContent("Output").proxy;
        assertNull(DataSourceSelector.pickResultTab(new Content[]{output}, Set.of(), 40));
    }

    @Test
    public void testEmptyContentsYieldsNull() throws Exception {
        assertNull(DataSourceSelector.pickResultTab(new Content[0], Set.of(), 40));
    }

    // ==================== 场景五：空名占位（T-ExplainFirstNative 首点病灶） ====================

    /**
     * DBN 首次执行先 add 空名 Content 再异步改名/建网格（262 真机 21:06:45 实证
     * `contentAdded: `）。空名 ∉ 空快照集合恒真，此前第 1 拍即被当新结果选中并强制换入
     * （网格尚未构建），事后无人补换入 → 结果滞留原生控制台。
     */
    @Test
    public void testBlankNamePlaceholderNotPickedAsNew() {
        Content blank = new FakeContent("").proxy;
        assertNull("空名占位不得当新结果选中",
                DataSourceSelector.pickResultTab(new Content[]{blank}, Set.of(), 1));
        assertNull("阈值后空名占位也不得复用（不是结果）",
                DataSourceSelector.pickResultTab(new Content[]{blank}, Set.of(), DataSourceSelector.REUSE_ACCEPT_TICKS));
    }

    /** 空名占位与旧名 Tab 并存：不选空名，走旧 Tab 阈值语义。 */
    @Test
    public void testBlankNameWithStaleTabWaitsForThreshold() {
        Content blank = new FakeContent("").proxy;
        Content stale = new FakeContent("Result 1").proxy;
        assertNull(DataSourceSelector.pickResultTab(new Content[]{blank, stale}, Set.of("Result 1"), 1));
        Content picked = DataSourceSelector.pickResultTab(
                new Content[]{blank, stale}, Set.of("Result 1"), DataSourceSelector.REUSE_ACCEPT_TICKS);
        assertEquals("阈值后选中的是具名旧 Tab 而非占位", "Result 1", picked.getDisplayName());
    }

    /** 占位改名后（"" → "Result 1"）第 1 拍即选中——真机首点病灶的完整链路复现。 */
    @Test
    public void testPlaceholderRenamedPickedImmediately() {
        FakeContent placeholder = new FakeContent("");
        Set<String> preExisting = DataSourceSelector.resultContents(new FakeUi(new Content[]{placeholder.proxy}))
                .stream().map(Content::getDisplayName).collect(Collectors.toSet());
        placeholder.displayName = "Result 1"; // DBN 异步改名+建网格
        Content picked = DataSourceSelector.pickResultTab(new Content[]{placeholder.proxy}, preExisting, 1);
        assertEquals("改名后应立即选中并换入", "Result 1", picked.getDisplayName());
    }

    // ==================== resultContents 反射读取 ====================

    @Test
    public void testResultContentsFiltersOutputTabs() throws Exception {
        Content output = new FakeContent("Output").proxy;
        Content outputCn = new FakeContent("输出").proxy;
        Content result = new FakeContent("Result 1").proxy;
        List<Content> list = DataSourceSelector.resultContents(new FakeUi(new Content[]{output, outputCn, result}));
        assertEquals(List.of("Result 1"), list.stream().map(Content::getDisplayName).collect(Collectors.toList()));
    }

    @Test
    public void testResultContentsNullUiYieldsEmpty() throws Exception {
        assertTrue(DataSourceSelector.resultContents(null).isEmpty());
    }

    @Test
    public void testResultContentsNonReflectiveUiYieldsEmpty() throws Exception {
        assertTrue(DataSourceSelector.resultContents(new Object()).isEmpty());
    }
}
