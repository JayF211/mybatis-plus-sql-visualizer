// T-SyncK2JMaster309 自 execute/DataSourceSelector.kt（ExecDocAutosaveHost 顶级类）移植
package cn.song.mybatisplus.visualizer.execute;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.util.Disposer;

/**
 * T-PS4-11：F5 防抖自动落盘监听的挂靠宿主（应用级轻服务，Disposable parent）。
 *
 * <p>挂靠定案：监听以两参重载挂到本服务实例持有的子 Disposable 上，而不是「自建应用级
 * Disposable + 订阅 DynamicPluginListener 在卸载回调里手动 dispose」——本插件的动态卸载走
 * {@code cn.song.mybatisplus.visualizer.update.DynamicInstallSupport} → 平台 {@code DynamicPlugins
 * .unloadPlugin}，平台卸载会 dispose 插件应用级容器并级联释放其中的服务（轻服务实例注册于
 * 容器 Disposer 树），以其为根的监听随之自动移除，插件卸载时监听随类加载器一并释放；而
 * DynamicInstallSupport KDoc 明示卸载/装载回调内只宜触达平台 API 与纯函数（调用栈运行在
 * 将被卸载的旧 classloader 上），回调里反手 dispose 自身类有 classloader 时序风险。容器
 * 级联释放即平台对「插件级应用监听」的惯例挂法。</p>
 *
 * <p>服务惰性实例化（首次 {@link #getInstance} 才创建）与监听「惰性装一次」语义一致；正常应用退出时
 * 应用容器 dispose 同样级联移除监听。子 Disposable 缓解重挂：宿主被容器 dispose 后实例不可
 * 再作 parent（Disposer 拒绝已 dispose 节点），但仅卸载监听（测试卸载模拟/极端重装）时 dispose
 * 子节点即可重挂——容器级卸载（服务 dispose）则 {@link #dispose} 复位后同实例不再可用，新容器装载
 * 新类自然拿到新宿主。</p>
 */
@Service(Service.Level.APP)
public final class ExecDocAutosaveHost implements com.intellij.openapi.Disposable {

    /** 当前挂载的子 Disposable（null = 未挂载）。经 eventMulticaster 两参重载挂监听。 */
    private com.intellij.openapi.Disposable attached = null;

    private final Object lock = new Object();

    public static ExecDocAutosaveHost getInstance() {
        return com.intellij.openapi.application.ApplicationManager.getApplication().getService(ExecDocAutosaveHost.class);
    }

    /** F5 监听挂载（幂等；仅未挂载时装，重复调用 no-op）。 */
    public void ensureListenerInstalled(com.intellij.openapi.editor.event.DocumentListener listener) {
        synchronized (lock) {
            if (attached != null) return;
            com.intellij.openapi.Disposable parent = Disposer.newDisposable("ExecDocAutosaveHost.listener");
            EditorFactory.getInstance().getEventMulticaster()
                    .addDocumentListener(listener, parent);
            Disposer.register(this, parent);
            attached = parent;
        }
    }

    /** T-PS4-11 测试缝：仅卸载监听（dispose 子节点），宿主可重挂——模拟「监听随 Disposable 卸载」。 */
    public void detachListenerForTest() {
        synchronized (lock) {
            if (attached != null) {
                Disposer.dispose(attached);
            }
            attached = null;
        }
    }

    /** T-PS4-11 测试缝：监听是否在挂（attach 幂等判重 + 卸载断言用）。 */
    public boolean isListenerAttached() {
        synchronized (lock) {
            return attached != null;
        }
    }

    @Override
    public void dispose() {
        // 子节点经 Disposer 树级联 dispose（监听随之移除）；复位挂载态。
        // 宿主已随插件容器 dispose 后实例不再作 parent（重挂会抛 already disposed）——
        // 生产语义即「插件已卸载，旧类不应再装监听」，仅测试卸载模拟走 detachListenerForTest。
        synchronized (lock) {
            attached = null;
        }
    }
}
