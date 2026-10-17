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
 * UI016：attachConsole 的 client 挂项目，但 native view 挂应用级 DatabaseSessionViewService。
 * 只记录本插件实际取得的 view 身份，在项目关闭前释放；面板关闭/clearPrepared 不触碰它。
 *
 * 243 destroyView(client) 按(project,file)查 view 后就是 Disposer.dispose(view)。这里保存具体
 * view 而不是关闭时重新按 file 查，防止同文件后来被用户重建的控制台遭到旧client误删。
 * 不直接 releaseEditor，不重挂原生 Disposer 树；原生 view 自带缓存移除和编辑器/布局释放。
 */
@Service(Service.Level.PROJECT)
internal class NativeConsoleViewLifetime(private val project: Project) : Disposable {
    private val views = IdentityHashMap<Disposable, Boolean>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                override fun projectClosing(closing: Project) {
                    if (closing === project) releaseViews()
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
            ?: return
        synchronized(views) {
            views.keys.removeIf { Disposer.isDisposed(it) }
            views[view] = true
        }
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
    }

    companion object {
        private const val SERVICE_CLASS = "com.intellij.database.console.session.DatabaseSessionViewService"
        private const val CLIENT_CLASS = "com.intellij.database.console.client.DatabaseSessionClientWithFile"
        private val LOG = Logger.getInstance(NativeConsoleViewLifetime::class.java)

        fun remember(project: Project, console: Any) {
            project.getService(NativeConsoleViewLifetime::class.java).remember(console)
        }
    }
}
