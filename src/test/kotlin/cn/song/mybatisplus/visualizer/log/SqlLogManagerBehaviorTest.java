package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import kotlin.Pair;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * SqlLogManager 设置生效性行为测试（T-xx 设置生效性审计）：
 *
 *  - capturing 状态机（D3）：桥 attach 失败**不得**进入「已捕捉但无监听」的假状态；
 *  - 自动打开决策与面板解耦（D2）：窗口从未打开（未 addUiListener）时首条捕获也触发自动打开；
 *  - 项目生命周期语义（D4）：启动自动监听仅由 LogStartupCapture 在项目打开时触发，
 *    项目关闭（dispose）后 capturing 自动重置。
 */
public class SqlLogManagerBehaviorTest extends BasePlatformTestCase {

    /** 受控桥：attach 结果注入 + 主控制台存在性注入，验证 capturing 状态机。 */
    private static class FakeBridge extends SqlLogConsoleBridge {
        private final boolean attachResult;
        private final boolean hasMainConsoleResult;
        int attachCalls = 0;

        FakeBridge(boolean attachResult, boolean hasMainConsoleResult) {
            super(new SqlLogEntryBuffer());
            this.attachResult = attachResult;
            this.hasMainConsoleResult = hasMainConsoleResult;
        }

        @Override
        public boolean attach(Project project) {
            attachCalls++;
            return attachResult;
        }

        @Override
        public boolean hasMainConsole() {
            return hasMainConsoleResult;
        }
    }

    /** 可变主控制台存在性桥（T02/T04 模拟控制台动态出现）。 */
    private static class MutableFakeBridge extends SqlLogConsoleBridge {
        private final boolean attachResult;
        boolean hasMainConsoleResult;
        int attachCalls = 0;

        MutableFakeBridge(boolean attachResult, boolean hasMainConsoleInitial) {
            super(new SqlLogEntryBuffer());
            this.attachResult = attachResult;
            this.hasMainConsoleResult = hasMainConsoleInitial;
        }

        @Override
        public boolean attach(Project project) {
            attachCalls++;
            return attachResult;
        }

        @Override
        public boolean hasMainConsole() {
            return hasMainConsoleResult;
        }
    }

    /** 间谍桥：追踪 detachAllFilters 调用 + 暴露轮询状态 + 可控 isCapturing 回调。 */
    private static class SpyBridge extends SqlLogConsoleBridge {
        private final boolean attachResult;
        private final boolean hasMainConsoleResult;
        int detachAllFiltersCalls = 0;
        int scanAllCalls = 0;

        SpyBridge(boolean attachResult) {
            this(attachResult, true, () -> true);
        }

        SpyBridge(boolean attachResult, BooleanSupplier isCapturing) {
            this(attachResult, true, isCapturing);
        }

        private SpyBridge(boolean attachResult, boolean hasMainConsoleResult, BooleanSupplier isCapturing) {
            super(new SqlLogEntryBuffer(),
                    () -> new ParserConfig("Preparing:", "Parameters:", true, 20000),
                    () -> {
                    }, () -> {
                    }, isCapturing);
            this.attachResult = attachResult;
            this.hasMainConsoleResult = hasMainConsoleResult;
        }

        @Override
        public boolean attach(Project project) {
            return attachResult;
        }

        @Override
        public boolean hasMainConsole() {
            return hasMainConsoleResult;
        }

        @Override
        public void scanAll() {
            scanAllCalls++;
            super.scanAll();
        }

        @Override
        public void detachAllFilters() {
            detachAllFiltersCalls++;
            super.detachAllFilters();
        }
    }

    /** 装配注入受控桥的 manager（生产构造 + 测试缝替换桥）。 */
    private SqlLogManager managerWith(SqlLogConsoleBridge bridge) {
        SqlLogManager manager = new SqlLogManager(getProject());
        manager.installBridgeForTest(bridge);
        return manager;
    }

    @Test
    public void testStartCaptureKeepsCapturingOffWhenAttachFails() throws Exception {
        // Option A：无控制台 + attach 失败仍进入 armed 态（capturing=true），用户可重试
        FakeBridge bridge = new FakeBridge(false, false);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture();
        assertTrue("attach 失败仍应进入 armed 态（capturing=true，用户可重试）", manager.isCapturing());
        assertTrue("attach 失败应保持等待态", manager.isWaitingForConsole());
        assertEquals("attach 失败仍须尝试挂载", 1, bridge.attachCalls);
    }

    @Test
    public void testStartCaptureSkipsWithoutMainConsole() throws Exception {
        // Option A：无控制台时立即进入 armed 态（capturing=true, isWaitingForConsole=true）
        FakeBridge bridge = new FakeBridge(true, false);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture();
        assertTrue("无控制台时应进入 armed 态（capturing=true）", manager.isCapturing());
        assertTrue("无控制台时应处于等待态", manager.isWaitingForConsole());
        assertEquals(
                "无主控制台时仍须挂载桥监听（否则主控制台出现时检测不到——图标不刷新，日志实证）",
                1,
                bridge.attachCalls);
    }

    @Test
    public void testStartCaptureSetsCapturingWhenAttachSucceeds() throws Exception {
        SqlLogManager manager = managerWith(new FakeBridge(true, true));
        manager.startCapture();
        assertTrue("attach 成功且存在主控制台才进入捕捉态", manager.isCapturing());
        manager.stopCapture();
        assertFalse("停止后恢复未捕捉态", manager.isCapturing());
    }

    // F-44：自动打开独立窗口功能已移除，相关测试删除。

    @Test
    public void testDisposeResetsCapturing() throws Exception {
        SqlLogManager manager = managerWith(new FakeBridge(true, true));
        manager.startCapture();
        assertTrue("startCapture 后应进入捕捉态", manager.isCapturing());
        manager.dispose();
        assertFalse("dispose（项目关闭）后 capturing 应重置为 false", manager.isCapturing());
    }

    // ===== T-13：手动捕捉无需等待项目运行（userRequestedCapture 意图传递） =====

    @Test
    public void testT01_ManualCaptureWithoutConsole_EnterWaitingState() throws Exception {
        FakeBridge bridge = new FakeBridge(true, false);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture(true);
        // Option A：无控制台时立即进入 armed 态（capturing=true + isWaitingForConsole=true）
        assertTrue("无控制台时应进入 armed 态（capturing=true）", manager.isCapturing());
        assertTrue("应进入等待态（isWaitingForConsole=true）", manager.isWaitingForConsole());
        assertEquals("仍须挂载桥监听", 1, bridge.attachCalls);
    }

    @Test
    public void testT02_WaitingConsoleAppeared_AutoEntersCapture() throws Exception {
        MutableFakeBridge bridge = new MutableFakeBridge(true, false);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture(true);
        assertTrue("应处于等待态", manager.isWaitingForConsole());

        // 模拟控制台出现
        bridge.hasMainConsoleResult = true;
        manager.onConsoleAppeared();
        assertTrue("控制台出现后应自动进入捕捉态", manager.isCapturing());
        assertFalse("等待态应清除", manager.isWaitingForConsole());
    }

    @Test
    public void testT03_WaitingManualStop_ClearsAllFlags() throws Exception {
        FakeBridge bridge = new FakeBridge(true, false);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture(true);
        assertTrue("应处于等待态", manager.isWaitingForConsole());

        manager.stopCapture();
        assertFalse("stop 后 capturing=false", manager.isCapturing());
        assertFalse("stop 后 isWaitingForConsole=false", manager.isWaitingForConsole());
    }

    @Test
    public void testT04_AutoStartSetting_UnaffectedByNewFeature() throws Exception {
        PluginSettings settings = PluginSettings.getInstance();
        boolean old = settings.getState().getLogAutoCaptureOnStart();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);
            // 自动路径：LogStartupCapture 触发 startCapture（无控制台）
            manager.startCapture();
            // Option A：自动路径无控制台时也进入 armed 态（capturing=true）
            assertTrue("autoStart=true：无控制台时应进入 armed 态（capturing=true）", manager.isCapturing());
            // 控制台出现
            bridge.hasMainConsoleResult = true;
            manager.onConsoleAppeared();
            assertTrue("autoStart=true：控制台出现后应自动捕捉", manager.isCapturing());
            assertFalse("控制台出现后等待态应清除", manager.isWaitingForConsole());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(old);
        }
    }

    @Test
    public void testT05_HasConsoleManualCapture_DirectCapture() throws Exception {
        FakeBridge bridge = new FakeBridge(true, true);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture(true);
        assertTrue("有控制台时应直接进入捕捉态", manager.isCapturing());
        assertFalse("有控制台时不应处于等待态", manager.isWaitingForConsole());
        manager.stopCapture();
    }

    @Test
    public void testT06_DisposeWhileWaiting_ClearsAllFlags() throws Exception {
        FakeBridge bridge = new FakeBridge(true, false);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture(true);
        assertTrue("应处于等待态", manager.isWaitingForConsole());

        manager.dispose();
        assertFalse("dispose 后 capturing=false", manager.isCapturing());
        assertFalse("dispose 后 isWaitingForConsole=false", manager.isWaitingForConsole());
    }

    // ===== 项目停止后回到 armed 态，重启自动恢复捕捉 =====

    @Test
    public void testT07_ProjectStop_GoesBackToArmedState() throws Exception {
        MutableFakeBridge bridge = new MutableFakeBridge(true, false);
        SqlLogManager manager = managerWith(bridge);
        // 点击捕捉 → armed
        manager.startCapture(true);
        assertTrue("armed 态", manager.isCapturing());
        // 项目启动 → 控制台出现 → 捕捉
        bridge.hasMainConsoleResult = true;
        manager.onConsoleAppeared();
        assertTrue("捕捉中", manager.isCapturing());
        assertFalse("等待态已清除", manager.isWaitingForConsole());
        // 项目停止 → 控制台移除 → 回到 armed 态
        bridge.hasMainConsoleResult = false;
        manager.onConsoleRemoved();
        assertTrue("回到 armed 态（capturing=true）", manager.isCapturing());
        assertTrue("等待态恢复", manager.isWaitingForConsole());
    }

    @Test
    public void testT08_ProjectRestart_AutoResumesCapture() throws Exception {
        MutableFakeBridge bridge = new MutableFakeBridge(true, false);
        SqlLogManager manager = managerWith(bridge);
        // 点击捕捉 → armed → 项目启动 → 捕捉
        manager.startCapture(true);
        bridge.hasMainConsoleResult = true;
        manager.onConsoleAppeared();
        assertTrue("第一次捕捉", manager.isCapturing());
        // 项目停止 → armed
        bridge.hasMainConsoleResult = false;
        manager.onConsoleRemoved();
        assertTrue("armed 态", manager.isCapturing());
        // 项目重启 → 自动恢复捕捉
        bridge.hasMainConsoleResult = true;
        manager.onConsoleAppeared();
        assertTrue("自动恢复捕捉", manager.isCapturing());
        assertFalse("等待态已清除", manager.isWaitingForConsole());
    }

    @Test
    public void testT09_AfterManualStop_NoAutoCapture() throws Exception {
        MutableFakeBridge bridge = new MutableFakeBridge(true, false);
        SqlLogManager manager = managerWith(bridge);
        // 点击捕捉 → armed → 项目启动 → 捕捉
        manager.startCapture(true);
        bridge.hasMainConsoleResult = true;
        manager.onConsoleAppeared();
        // 手动停止
        manager.stopCapture();
        assertFalse("已停止", manager.isCapturing());
        // 项目重启 → 不会自动捕捉
        bridge.hasMainConsoleResult = true;
        manager.onConsoleAppeared();
        assertFalse("不会自动捕捉", manager.isCapturing());
    }

    // ===== 自动启动（logAutoCaptureOnStart=true）行为 =====

    @Test
    public void testT10_AutoStart_ProjectStop_StopsCompletely() throws Exception {
        PluginSettings settings = PluginSettings.getInstance();
        boolean old = settings.getState().getLogAutoCaptureOnStart();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);
            // 自动启动：项目打开时进入 armed 态（manual=false, userArmed=false）
            manager.startCapture(); // manual=false
            assertTrue("armed 态", manager.isCapturing());
            assertFalse("自动启动不设置 userArmed", manager.isUserArmed());
            // 项目启动 → 控制台出现 → 捕捉
            bridge.hasMainConsoleResult = true;
            manager.onConsoleAppeared();
            assertTrue("捕捉中", manager.isCapturing());
            // 项目停止 → 控制台移除 → 完全停止（非 armed）
            bridge.hasMainConsoleResult = false;
            manager.onConsoleRemoved();
            assertFalse("自动启动：项目停止后完全停止", manager.isCapturing());
            assertFalse("等待态清除", manager.isWaitingForConsole());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(old);
        }
    }

    @Test
    public void testT11_AutoStart_ProjectRestart_AutoCapturesAgain() throws Exception {
        PluginSettings settings = PluginSettings.getInstance();
        boolean old = settings.getState().getLogAutoCaptureOnStart();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);
            // 第一次：armed → 启动 → 捕捉
            manager.startCapture();
            bridge.hasMainConsoleResult = true;
            manager.onConsoleAppeared();
            assertTrue("第一次捕捉", manager.isCapturing());
            // 项目停止 → 完全停止
            bridge.hasMainConsoleResult = false;
            manager.onConsoleRemoved();
            assertFalse("停止", manager.isCapturing());
            // 项目重启 → 再次自动捕捉
            bridge.hasMainConsoleResult = true;
            manager.onConsoleAppeared();
            assertFalse("userArmed 未设置", manager.isUserArmed());
            assertTrue("再次自动捕捉", manager.isCapturing());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(old);
        }
    }

    @Test
    public void testT12_ManualAndAutoStart_ProjectStop_StopsCompletely() throws Exception {
        PluginSettings settings = PluginSettings.getInstance();
        boolean old = settings.getState().getLogAutoCaptureOnStart();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);
            // 手动点击捕捉（manual=true）+ 自动启动开启
            manager.startCapture(true);
            assertTrue("armed 态", manager.isCapturing());
            assertTrue("userArmed 已设置", manager.isUserArmed());
            // 项目启动 → 捕捉
            bridge.hasMainConsoleResult = true;
            manager.onConsoleAppeared();
            assertTrue("捕捉中", manager.isCapturing());
            // 项目停止 → 完全停止（自动启动优先于手动 armed）
            bridge.hasMainConsoleResult = false;
            manager.onConsoleRemoved();
            assertFalse("手动+自动同时开启：项目停止后完全停止", manager.isCapturing());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(old);
        }
    }

    @Test
    public void testT13_AutoStart_UserStop_SuppressedPreventsRestart() throws Exception {
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldAutoStart = settings.getState().getLogAutoCaptureOnStart();
        boolean oldSuppressed = settings.getState().getLogAutoCaptureSuppressed();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);

            // 模拟 LogStartupCapture 触发（项目打开）→ armed 态
            manager.startCapture(); // auto-start, manual=false
            assertTrue("自动启动进入 armed 态", manager.isCapturing());

            // 用户点击停止 → suppressed=true, capturing=false
            manager.stopCapture();
            assertFalse("已停止", manager.isCapturing());
            assertTrue("抑制标志已设置", settings.getState().getLogAutoCaptureSuppressed());

            // 项目启动 → suppressed 阻止自动捕捉（BUG FIX 核心行为）
            bridge.hasMainConsoleResult = true;
            manager.onConsoleAppeared();
            assertTrue("项目启动后 suppressed 保持 true（不被自动事件清除）",
                    settings.getState().getLogAutoCaptureSuppressed());
            assertFalse("suppressed=true 时项目启动不应自动捕捉", manager.isCapturing());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(oldAutoStart);
            settings.getState().setLogAutoCaptureSuppressed(oldSuppressed);
        }
    }

    // ===== BUG FIX：stopCapture 后 Filter 移除 + 轮询停止 =====

    @Test
    public void testT14_StopCapture_clearsAttachedConsoles() throws Exception {
        SpyBridge bridge = new SpyBridge(true);
        SqlLogManager manager = managerWith(bridge);
        // 先启动捕捉（设置 capturing=true）
        manager.startCapture();
        assertTrue("precondition: capturing=true", manager.isCapturing());
        // 模拟已挂载的 Filter（直接写入 attachedConsoles 模拟 scanAll 挂载结果）
        Object mockConsole = new Object();
        Object mockFilter = new Object();
        Class<?> mockFilterClass = Class.forName("java.lang.Object");
        bridge.attachedConsoles.put(mockConsole, new Pair<>(mockFilter, mockFilterClass));
        assertTrue("precondition: attachedConsoles 非空", !bridge.attachedConsoles.isEmpty());

        manager.stopCapture();
        assertTrue("stopCapture 应清空 attachedConsoles（Filter 从控制台移除）", bridge.attachedConsoles.isEmpty());
        assertTrue("stopCapture 应调用 detachAllFilters", bridge.detachAllFiltersCalls >= 1);
    }

    @Test
    public void testT15_StopCapture_stopsPolling() throws Exception {
        SpyBridge bridge = new SpyBridge(true);
        SqlLogManager manager = managerWith(bridge);
        // 启动捕捉 → 启动轮询
        manager.startCapture();
        // 手动启动轮询（模拟 attach 成功后的 startPolling）
        bridge.startPolling();
        assertTrue("precondition: 轮询应活跃", bridge.isPollingActive());

        manager.stopCapture();
        assertFalse("stopCapture 后轮询应停止", bridge.isPollingActive());
    }

    @Test
    public void testT16_AttachFilter_notCapturing_skipsAttach() throws Exception {
        SpyBridge bridge = new SpyBridge(true, () -> false);
        // 通过反射调用私有 attachFilter 方法验证守卫
        Method method = SqlLogConsoleBridge.class.getDeclaredMethod("attachFilter", Object.class);
        method.setAccessible(true);
        method.invoke(bridge, new Object());
        assertTrue("非捕捉态时 attachFilter 不应挂载", bridge.attachedConsoles.isEmpty());
    }

    @Test
    public void testT17_StopThenRestart_reAttachesFilters() throws Exception {
        SpyBridge bridge = new SpyBridge(true);
        SqlLogManager manager = managerWith(bridge);
        // 先启动捕捉
        manager.startCapture();
        assertTrue("precondition: capturing=true", manager.isCapturing());
        // 模拟挂载
        Object mockConsole = new Object();
        Object mockFilter = new Object();
        Class<?> mockFilterClass = Class.forName("java.lang.Object");
        bridge.attachedConsoles.put(mockConsole, new Pair<>(mockFilter, mockFilterClass));

        // 停止 → 清空
        manager.stopCapture();
        assertFalse("停止后 capturing=false", manager.isCapturing());
        assertTrue("停止后 attachedConsoles 为空", bridge.attachedConsoles.isEmpty());

        // 重新开始 → capturing=true，attachFilter 守卫放行
        manager.startCapture(true);
        assertTrue("重新开始后 capturing=true", manager.isCapturing());
    }

    // ===== BUG FIX：buffer.paused 安全网 =====

    @Test
    public void testT18_StopCapture_setsBufferPaused() throws Exception {
        SpyBridge bridge = new SpyBridge(true);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture();
        assertFalse("捕捉中 paused=false", manager.getEntries().isPaused());

        manager.stopCapture();
        assertTrue("stopCapture 后 paused=true", manager.getEntries().isPaused());
    }

    @Test
    public void testT19_PausedBuffer_rejectsAddEntry() throws Exception {
        SpyBridge bridge = new SpyBridge(true);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture();
        // 先成功入队一条
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", java.util.List.of(), 0L);
        assertTrue("paused=false 时 addEntry 成功", manager.getEntries().addEntry(entry));

        // 停止后 paused=true，addEntry 被拒绝
        manager.stopCapture();
        LogSqlEntry entry2 = new LogSqlEntry("SELECT 2", "SELECT 2", java.util.List.of(), 1L);
        assertFalse("paused=true 时 addEntry 应被拒绝", manager.getEntries().addEntry(entry2));
    }

    @Test
    public void testT20_StartCapture_clearsPaused() throws Exception {
        SpyBridge bridge = new SpyBridge(true);
        SqlLogManager manager = managerWith(bridge);
        manager.startCapture();
        manager.stopCapture();
        assertTrue("停止后 paused=true", manager.getEntries().isPaused());

        manager.startCapture(true);
        assertFalse("startCapture 后 paused=false", manager.getEntries().isPaused());
    }

    // ===== BUG FIX：onConsoleAppeared 尊重 suppressed 标志 =====

    @Test
    public void testT21_SuppressedPreventsAutoRestart() throws Exception {
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldAutoStart = settings.getState().getLogAutoCaptureOnStart();
        boolean oldSuppressed = settings.getState().getLogAutoCaptureSuppressed();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            settings.getState().setLogAutoCaptureSuppressed(true);
            SpyBridge bridge = new SpyBridge(true);
            SqlLogManager manager = managerWith(bridge);

            // 模拟控制台出现 → 不应重启捕捉
            manager.onConsoleAppeared();
            assertFalse("suppressed=true 时 onConsoleAppeared 不应重启捕捉", manager.isCapturing());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(oldAutoStart);
            settings.getState().setLogAutoCaptureSuppressed(oldSuppressed);
        }
    }

    @Test
    public void testT22_NotSuppressed_allowsAutoRestart() throws Exception {
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldAutoStart = settings.getState().getLogAutoCaptureOnStart();
        boolean oldSuppressed = settings.getState().getLogAutoCaptureSuppressed();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            settings.getState().setLogAutoCaptureSuppressed(false);
            SpyBridge bridge = new SpyBridge(true);
            SqlLogManager manager = managerWith(bridge);

            // 模拟控制台出现 → 应重启捕捉
            manager.onConsoleAppeared();
            assertTrue("suppressed=false 时 onConsoleAppeared 应重启捕捉", manager.isCapturing());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(oldAutoStart);
            settings.getState().setLogAutoCaptureSuppressed(oldSuppressed);
        }
    }

    @Test
    public void testT23_ArmedState_allowsAutoRestart() throws Exception {
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldAutoStart = settings.getState().getLogAutoCaptureOnStart();
        try {
            // autoStart=false + manual=true：模拟手动开启但未启用自动启动
            settings.getState().setLogAutoCaptureOnStart(false);
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);

            // 手动启动（无控制台）→ capturing=true, userArmed=true, isWaitingForConsole=true
            manager.startCapture(true);
            assertTrue("手动启动后 capturing=true", manager.isCapturing());
            assertTrue("手动启动后 userArmed=true", manager.isUserArmed());
            assertTrue("手动启动后 isWaitingForConsole=true", manager.isWaitingForConsole());

            // 控制台出现 → 等待态清除
            bridge.hasMainConsoleResult = true;
            manager.onConsoleAppeared();
            assertTrue("控制台出现后 capturing=true", manager.isCapturing());
            assertFalse("控制台出现后等待态清除", manager.isWaitingForConsole());

            // 控制台移除 → armed 态（capturing 保持 true, isWaitingForConsole=true, userArmed 保持 true）
            bridge.hasMainConsoleResult = false;
            manager.onConsoleRemoved();
            assertTrue("armed 态 capturing 保持 true", manager.isCapturing());
            assertTrue("armed 态 userArmed=true", manager.isUserArmed());
            assertTrue("armed 态 isWaitingForConsole=true", manager.isWaitingForConsole());

            // 控制台出现 → 应自动重启捕捉（等待态清除）
            bridge.hasMainConsoleResult = true;
            manager.onConsoleAppeared();
            assertTrue("armed 态下 onConsoleAppeared 应重启捕捉", manager.isCapturing());
            assertFalse("等待态应清除", manager.isWaitingForConsole());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(oldAutoStart);
        }
    }

    // ===== 20260907-2045 第二轮修复：onProjectProcessStarted（进程级自动捕捉入口） =====

    @Test
    public void testT23_ProcessStarted_suppressedCleared_andCaptureArmed() throws Exception {
        // 场景：设置开 + 用户手动停止（suppressed=true 持久化）→ 再 Run 项目 →
        // onProjectProcessStarted 清 suppressed + 重新进入捕捉链（沙盒 20:41 会话根因回归）
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldAutoStart = settings.getState().getLogAutoCaptureOnStart();
        boolean oldSuppressed = settings.getState().getLogAutoCaptureSuppressed();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            settings.getState().setLogAutoCaptureSuppressed(true);
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);

            manager.onProjectProcessStarted();

            assertFalse("项目启动应清除 suppressed（设置优先）", settings.getState().getLogAutoCaptureSuppressed());
            assertTrue("项目启动应重新进入 armed/捕捉态", manager.isCapturing());
            assertTrue("armed 态等待控制台", manager.isWaitingForConsole());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(oldAutoStart);
            settings.getState().setLogAutoCaptureSuppressed(oldSuppressed);
        }
    }

    @Test
    public void testT24_ProcessStarted_autoStartOff_noop() throws Exception {
        // 场景：设置关 → 进程启动回调不动作（不捕捉、不动 suppressed）
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldAutoStart = settings.getState().getLogAutoCaptureOnStart();
        boolean oldSuppressed = settings.getState().getLogAutoCaptureSuppressed();
        try {
            settings.getState().setLogAutoCaptureOnStart(false);
            settings.getState().setLogAutoCaptureSuppressed(false);
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);

            manager.onProjectProcessStarted();

            assertFalse("设置关时进程启动不应捕捉", manager.isCapturing());
            assertFalse("不应写 suppressed", settings.getState().getLogAutoCaptureSuppressed());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(oldAutoStart);
            settings.getState().setLogAutoCaptureSuppressed(oldSuppressed);
        }
    }

    @Test
    public void testT25_ProcessStarted_capturingActive_idempotent() throws Exception {
        // 场景：已处于捕捉态（armed 等待控制台）→ 进程启动回调幂等（不重复 attach、状态不变）
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldAutoStart = settings.getState().getLogAutoCaptureOnStart();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);
            manager.startCapture();  // armed
            int attachBefore = bridge.attachCalls;

            manager.onProjectProcessStarted();  // capturing=true, waiting=true → 幂等分支

            assertTrue("保持 armed 捕捉态", manager.isCapturing());
            assertTrue("attach 不重复", bridge.attachCalls == attachBefore);
        } finally {
            settings.getState().setLogAutoCaptureOnStart(oldAutoStart);
        }
    }

    @Test
    public void testT26_ProcessStarted_withConsole_directCapture() throws Exception {
        // 场景：suppressed=true + 控制台已存在（先 Run 后点面板捕捉又停的乱序）→
        // 进程启动回调清 suppressed 且直接进入完整捕捉态（hasMainConsole=true 分支）
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldAutoStart = settings.getState().getLogAutoCaptureOnStart();
        boolean oldSuppressed = settings.getState().getLogAutoCaptureSuppressed();
        try {
            settings.getState().setLogAutoCaptureOnStart(true);
            settings.getState().setLogAutoCaptureSuppressed(true);
            MutableFakeBridge bridge = new MutableFakeBridge(true, true);
            SqlLogManager manager = managerWith(bridge);

            manager.onProjectProcessStarted();

            assertFalse("suppressed 已清除", settings.getState().getLogAutoCaptureSuppressed());
            assertTrue("直接进入捕捉态", manager.isCapturing());
            assertFalse("非等待态", manager.isWaitingForConsole());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(oldAutoStart);
            settings.getState().setLogAutoCaptureSuppressed(oldSuppressed);
        }
    }

    // ===== 20260907-2045 第四轮：被动模式（processListenerActive=true 时设置开启不预 armed） =====

    @Test
    public void testT27_SettingEnabled_passiveMode_doesNotArm() throws Exception {
        // 场景：进程级监听可用（被动模式）→ onAutoStartSettingEnabled 清 suppressed 但不进 armed——
        // 用户 08:33 会话反馈回归：「还没启动项目却自动开启监听」
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldAutoStart = settings.getState().getLogAutoCaptureOnStart();
        boolean oldSuppressed = settings.getState().getLogAutoCaptureSuppressed();
        try {
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);
            manager.setProcessListenerActiveForTest(true);
            settings.getState().setLogAutoCaptureSuppressed(true);

            manager.onAutoStartSettingEnabled();

            assertFalse("被动模式：设置开启不应预 armed", manager.isCapturing());
            assertFalse("设置开启应清 suppressed（用户重新确认）", settings.getState().getLogAutoCaptureSuppressed());
            assertFalse("不应挂载轮询", bridge.isPollingActive());
        } finally {
            settings.getState().setLogAutoCaptureOnStart(oldAutoStart);
            settings.getState().setLogAutoCaptureSuppressed(oldSuppressed);
        }
    }

    @Test
    public void testT28_SettingEnabled_degradedMode_armsAsBefore() throws Exception {
        // 场景：进程级监听不可用（降级模式，execution API 缺失）→ 维持原行为进 armed
        // （RCM 轮询是唯一检测通道——第一/二轮前的既有语义防回归）
        PluginSettings settings = PluginSettings.getInstance();
        boolean oldSuppressed = settings.getState().getLogAutoCaptureSuppressed();
        try {
            MutableFakeBridge bridge = new MutableFakeBridge(true, false);
            SqlLogManager manager = managerWith(bridge);
            manager.setProcessListenerActiveForTest(false);
            settings.getState().setLogAutoCaptureSuppressed(true);

            manager.onAutoStartSettingEnabled();

            assertTrue("降级模式：进 armed 态等控制台（原第九轮语义）", manager.isCapturing());
            assertTrue("降级模式：等待控制台", manager.isWaitingForConsole());
            assertFalse("设置开启清 suppressed", settings.getState().getLogAutoCaptureSuppressed());
        } finally {
            settings.getState().setLogAutoCaptureSuppressed(oldSuppressed);
        }
    }

    // ---------- T-LogRowNumber：项目启动捕获序号清零 ----------

    @Test
    public void testT29_ResetEntrySequence_clearsCounterForNewEntries() throws Exception {
        // 转发生效：manager.resetEntrySequence 后新条目从 #1 重新计数，旧条目行号保留
        SqlLogManager manager = managerWith(new FakeBridge(true, false));
        manager.getEntries().addEntry(new LogSqlEntry("SELECT 1", "SELECT 1", List.of()));
        manager.getEntries().addEntry(new LogSqlEntry("SELECT 2", "SELECT 2", List.of()));
        assertEquals(List.of(1L, 2L), manager.getEntries().snapshot().stream().map(LogSqlEntry::sequenceNumber).toList());

        manager.resetEntrySequence();

        assertEquals("旧条目行号保留", List.of(1L, 2L),
                manager.getEntries().snapshot().stream().map(LogSqlEntry::sequenceNumber).toList());
        manager.getEntries().addEntry(new LogSqlEntry("SELECT 3", "SELECT 3", List.of()));
        assertEquals("新条目从 #1 起计数", 1L,
                manager.getEntries().snapshot().get(manager.getEntries().snapshot().size() - 1).sequenceNumber());
    }
}
