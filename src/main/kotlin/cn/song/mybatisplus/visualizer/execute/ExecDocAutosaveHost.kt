package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import javax.swing.Icon

// DataSourceSelector 里的执行文档自动保存宿主
// （T-M53-II 2026-10-10：由 DataSourceSelector.kt 原样搬出，同包同可见性，零行为改动。）
/**
 * T-PS4-11：F5 防抖自动落盘监听的挂靠宿主（应用级轻服务，Disposable parent）。
 *
 * 挂靠定案：监听以两参重载挂到本服务实例持有的子 Disposable 上，而不是「自建应用级
 * Disposable + 订阅 DynamicPluginListener 在卸载回调里手动 dispose」——本插件的动态卸载走
 * [cn.song.mybatisplus.visualizer.update.DynamicInstallSupport] → 平台 `DynamicPlugins
 * .unloadPlugin`，平台卸载会 dispose 插件应用级容器并级联释放其中的服务（轻服务实例注册于
 * 容器 Disposer 树），以其为根的监听随之自动移除，插件卸载时监听随类加载器一并释放；而
 * DynamicInstallSupport KDoc 明示卸载/装载回调内只宜触达平台 API 与纯函数（调用栈运行在
 * 将被卸载的旧 classloader 上），回调里反手 dispose 自身类有 classloader 时序风险。容器
 * 级联释放即平台对「插件级应用监听」的惯例挂法。
 *
 * 服务惰性实例化（首次 [getInstance] 才创建）与监听「惰性装一次」语义一致；正常应用退出时
 * 应用容器 dispose 同样级联移除监听。子 Disposable 缓解重挂：宿主被容器 dispose 后实例不可
 * 再作 parent（Disposer 拒绝已 dispose 节点），但仅卸载监听（测试卸载模拟/极端重装）时 dispose
 * 子节点即可重挂——容器级卸载（服务 dispose）则 [dispose] 复位后同实例不再可用，新容器装载
 * 新类自然拿到新宿主。
 */
@Service(Service.Level.APP)
internal class ExecDocAutosaveHost : com.intellij.openapi.Disposable {

    companion object {
        fun getInstance(): ExecDocAutosaveHost =
            ApplicationManager.getApplication().getService(ExecDocAutosaveHost::class.java)
    }

    /** 当前挂载的子 Disposable（null = 未挂载）。经 eventMulticaster 两参重载挂监听。 */
    private var attached: com.intellij.openapi.Disposable? = null

    /** F5 监听挂载（幂等；仅未挂载时装，重复调用 no-op）。 */
    fun ensureListenerInstalled(listener: com.intellij.openapi.editor.event.DocumentListener) {
        synchronized(this) {
            if (attached != null) return
            val parent = Disposer.newDisposable("ExecDocAutosaveHost.listener")
            com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster
                .addDocumentListener(listener, parent)
            Disposer.register(this, parent)
            attached = parent
        }
    }

    /** T-PS4-11 测试缝：仅卸载监听（dispose 子节点），宿主可重挂——模拟「监听随 Disposable 卸载」。 */
    internal fun detachListenerForTest() {
        synchronized(this) {
            attached?.let { Disposer.dispose(it) }
            attached = null
        }
    }

    /** T-PS4-11 测试缝：监听是否在挂（attach 幂等判重 + 卸载断言用）。 */
    internal val isListenerAttached: Boolean
        get() = synchronized(this) { attached != null }

    override fun dispose() {
        // 子节点经 Disposer 树级联 dispose（监听随之移除）；复位挂载态。
        // 宿主已随插件容器 dispose 后实例不再作 parent（重挂会抛 already disposed）——
        // 生产语义即「插件已卸载，旧类不应再装监听」，仅测试卸载模拟走 detachListenerForTest。
        synchronized(this) { attached = null }
    }
}

