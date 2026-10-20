package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.Disposer
import java.lang.reflect.InvocationTargetException
import java.util.IdentityHashMap

/**
 * attachConsole 的 client 挂项目，但 native view 挂应用级 DatabaseSessionViewService。
 * 只记录本插件实际取得的 view 身份，在项目关闭前释放；面板关闭/clearPrepared 不触碰它。
 *
 * 243 destroyView(client) 按(project,file)查 view 后就是 Disposer.dispose(view)。这里保存具体
 * view 而不是关闭时重新按 file 查，防止同文件后来被用户重建的控制台遭到旧client误删。
 * 不直接 releaseEditor，不重挂原生 Disposer 树；原生 view 自带缓存移除和编辑器/布局释放。
 */
@Service(Service.Level.PROJECT)
internal class NativeConsoleViewLifetime(private val project: Project) : Disposable {
    private val views = IdentityHashMap<Disposable, Boolean>()
    private val sessions = IdentityHashMap<Disposable, Boolean>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                override fun projectClosing(closing: Project) {
                    if (closing === project) {
                        releaseSessionOutputs()
                        releaseViews()
                    }
                }
            })
    }

    /** 不创建视图；attach后的原生视图已存在时才登记，重复登记按对象身份去重。 */
    fun remember(console: Any) {
        val loader = console.javaClass.classLoader
        val serviceType = Class.forName(SERVICE_CLASS, false, loader)
        val clientType = Class.forName(CLIENT_CLASS, false, loader)
        val service = serviceType.getMethod("getInstance").invoke(null)
        val view = serviceType.getMethod("getClientViewIfAny", clientType).invoke(service, console) as? Disposable
        synchronized(views) {
            views.keys.removeIf { Disposer.isDisposed(it) }
            if (view != null) views[view] = true
        }
        // Session output与client view不是同一棵树；它可能稍后才建，关闭时只查既有view。
        val session = clientType.getMethod("getSession").invoke(console) as? Disposable ?: return
        synchronized(sessions) {
            sessions.keys.removeIf { Disposer.isDisposed(it) }
            sessions[session] = true
        }
    }

    private fun releaseSessionOutputs() {
        val owned = synchronized(sessions) { sessions.keys.toList().also { sessions.clear() } }
        for (session in owned) {
            bestEffortRegistration({
                if (!Disposer.isDisposed(session)) {
                    val loader = session.javaClass.classLoader
                    val serviceType = Class.forName(SERVICE_CLASS, false, loader)
                    val sessionType = Class.forName(SESSION_CLASS, false, loader)
                    val service = serviceType.getMethod("getInstance").invoke(null)
                    val output = serviceType.getMethod("getViewIfAny", sessionType).invoke(service, session) as? Disposable
                    if (output != null && !Disposer.isDisposed(output)) {
                        val baseViewType = Class.forName("com.intellij.database.run.session.BaseLogView", false, loader)
                        val languageConsole = baseViewType.getMethod("getConsoleView").invoke(output) as? Disposable
                        if (languageConsole != null && !Disposer.isDisposed(languageConsole)) Disposer.dispose(languageConsole)
                    }
                }
            }) { LOG.warn("Native session output disposal failed", it) }
        }
        // sessionViews的缓存移除回调挂session，真实关闭后续由平台销毁session并清缓存。
        // 只提前释放语言控制台子树。dispose整个BaseLogView会经Content disposer销毁target
        // session，故保留外层view/布局至平台原树收尾；不触及连接/事务。仅projectClosing执行，
        // 插件卸载或预览关闭不能破坏仍可用的共享session output。
    }

    private fun releaseViews() {
        val owned = synchronized(views) { views.keys.toList().also { views.clear() } }
        for (view in owned) {
            try {
                if (!Disposer.isDisposed(view)) Disposer.dispose(view)
            } catch (failure: Throwable) {
                val cause = (failure as? InvocationTargetException)?.targetException ?: failure
                if (FatalErrors.isFatal(cause)) throw cause
                if (cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
                // 某一原生view释放失败不能使其它自有view也留到应用退出；保留完整错误现场。
                LOG.warn("[UI016] native console view disposal failed", cause)
            }
        }
    }

    override fun dispose() {
        // 插件卸载也需要释放；正常项目关闭时projectClosing已清空，不重复释放。
        releaseViews()
        synchronized(sessions) { sessions.clear() }
    }

    companion object {
        private const val SERVICE_CLASS = "com.intellij.database.console.session.DatabaseSessionViewService"
        private const val CLIENT_CLASS = "com.intellij.database.console.client.DatabaseSessionClientWithFile"
        private const val SESSION_CLASS = "com.intellij.database.console.session.DatabaseSession"
        private val LOG = Logger.getInstance(NativeConsoleViewLifetime::class.java)

        fun remember(project: Project, console: Any) {
            bestEffortRegistration({
                project.getService(NativeConsoleViewLifetime::class.java).remember(console)
            }) { LOG.warn("Native console lifetime registration failed; keep existing prepare behavior", it) }
        }

        internal fun bestEffortRegistration(register: () -> Unit, report: (Throwable) -> Unit) {
            try {
                register()
            } catch (failure: Throwable) {
                if (FatalErrors.isFatal(failure)) throw failure
                val cause = unwrapAndRethrowCritical(failure)
                report(cause)
            }
        }

        internal fun unwrapAndRethrowCritical(failure: Throwable): Throwable {
            var cause = failure
            while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
            if (FatalErrors.isFatal(cause) || cause is com.intellij.openapi.progress.ProcessCanceledException) throw cause
            return cause
        }
    }
}
