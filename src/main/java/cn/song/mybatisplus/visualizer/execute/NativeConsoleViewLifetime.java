package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.util.Disposer;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * attachConsole 的 client 挂项目，但 native view 挂应用级 DatabaseSessionViewService。
 * 只记录本插件实际取得的 view 身份，在项目关闭前释放；面板关闭/clearPrepared 不触碰它。
 *
 * <p>243 destroyView(client) 按(project,file)查 view 后就是 Disposer.dispose(view)。这里保存具体
 * view 而不是关闭时重新按 file 查，防止同文件后来被用户重建的控制台遭到旧 client 误删。
 * 不直接 releaseEditor，不重挂原生 Disposer 树；原生 view 自带缓存移除和编辑器/布局释放。</p>
 */
@Service(Service.Level.PROJECT)
public final class NativeConsoleViewLifetime implements Disposable {

    private static final String SERVICE_CLASS = "com.intellij.database.console.session.DatabaseSessionViewService";
    private static final String CLIENT_CLASS = "com.intellij.database.console.client.DatabaseSessionClientWithFile";
    private static final String SESSION_CLASS = "com.intellij.database.console.session.DatabaseSession";
    private static final Logger LOG = Logger.getInstance(NativeConsoleViewLifetime.class);

    private final Project project;
    private final Map<Disposable, Boolean> views = new IdentityHashMap<>();
    private final Map<Disposable, Boolean> sessions = new IdentityHashMap<>();

    public NativeConsoleViewLifetime(Project project) {
        this.project = project;
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
                    @Override
                    public void projectClosing(Project closing) {
                        if (closing == project) {
                            releaseSessionOutputs();
                            releaseViews();
                        }
                    }
                });
    }

    /** 不创建视图；attach 后的原生视图已存在时才登记，重复登记按对象身份去重。 */
    public void remember(Object console) throws Exception {
        ClassLoader loader = console.getClass().getClassLoader();
        Class<?> serviceType = Class.forName(SERVICE_CLASS, false, loader);
        Class<?> clientType = Class.forName(CLIENT_CLASS, false, loader);
        Object service = serviceType.getMethod("getInstance").invoke(null);
        Object viewRaw = serviceType.getMethod("getClientViewIfAny", clientType).invoke(service, console);
        Disposable view = viewRaw instanceof Disposable disposable ? disposable : null;
        synchronized (views) {
            views.keySet().removeIf(Disposer::isDisposed);
            if (view != null) {
                views.put(view, Boolean.TRUE);
            }
        }
        // Session output 与 client view 不是同一棵树；它可能稍后才建，关闭时只查既有 view。
        Object sessionRaw = clientType.getMethod("getSession").invoke(console);
        Disposable session = sessionRaw instanceof Disposable disposable ? disposable : null;
        if (session == null) {
            return;
        }
        synchronized (sessions) {
            sessions.keySet().removeIf(Disposer::isDisposed);
            sessions.put(session, Boolean.TRUE);
        }
    }

    private void releaseSessionOutputs() {
        List<Disposable> owned;
        synchronized (sessions) {
            owned = new ArrayList<>(sessions.keySet());
            sessions.clear();
        }
        for (Disposable session : owned) {
            bestEffortRegistration(() -> releaseSessionOutput(session),
                    failure -> LOG.warn("Native session output disposal failed", failure));
        }
        // sessionViews 的缓存移除回调挂 session，真实关闭后续由平台销毁 session 并清缓存。
        // 只提前释放语言控制台子树。dispose 整个 BaseLogView 会经 Content disposer 销毁 target
        // session，故保留外层 view/布局至平台原树收尾；不触及连接/事务。仅 projectClosing 执行，
        // 插件卸载或预览关闭不能破坏仍可用的共享 session output。
    }

    private void releaseSessionOutput(Disposable session) throws Exception {
        if (Disposer.isDisposed(session)) {
            return;
        }
        ClassLoader loader = session.getClass().getClassLoader();
        Class<?> serviceType = Class.forName(SERVICE_CLASS, false, loader);
        Class<?> sessionType = Class.forName(SESSION_CLASS, false, loader);
        Object service = serviceType.getMethod("getInstance").invoke(null);
        Object outputRaw = serviceType.getMethod("getViewIfAny", sessionType).invoke(service, session);
        if (!(outputRaw instanceof Disposable output) || Disposer.isDisposed(output)) {
            return;
        }
        Class<?> baseViewType = Class.forName("com.intellij.database.run.session.BaseLogView", false, loader);
        Object languageRaw = baseViewType.getMethod("getConsoleView").invoke(output);
        if (languageRaw instanceof Disposable languageConsole && !Disposer.isDisposed(languageConsole)) {
            Disposer.dispose(languageConsole);
        }
    }

    private void releaseViews() {
        List<Disposable> owned;
        synchronized (views) {
            owned = new ArrayList<>(views.keySet());
            views.clear();
        }
        for (Disposable view : owned) {
            try {
                if (!Disposer.isDisposed(view)) {
                    Disposer.dispose(view);
                }
            } catch (Throwable failure) {
                Throwable cause = failure instanceof InvocationTargetException ite && ite.getTargetException() != null
                        ? ite.getTargetException()
                        : failure;
                if (FatalErrors.isFatal(cause)) {
                    throw (Error) cause;
                }
                if (cause instanceof ProcessCanceledException pce) {
                    throw pce;
                }
                // 某一原生 view 释放失败不能使其它自有 view 也留到应用退出；保留完整错误现场。
                LOG.warn("[UI016] native console view disposal failed", cause);
            }
        }
    }

    @Override
    public void dispose() {
        // 插件卸载也需要释放；正常项目关闭时 projectClosing 已清空，不重复释放。
        releaseViews();
        synchronized (sessions) {
            sessions.clear();
        }
    }

    public static void remember(Project project, Object console) {
        bestEffortRegistration(
                () -> project.getService(NativeConsoleViewLifetime.class).remember(console),
                failure -> LOG.warn("Native console lifetime registration failed; keep existing prepare behavior",
                        failure));
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void bestEffortRegistration(ThrowingRunnable register, Consumer<Throwable> report) {
        try {
            register.run();
        } catch (Throwable failure) {
            if (FatalErrors.isFatal(failure)) {
                throw (Error) failure;
            }
            Throwable cause = unwrapAndRethrowCritical(failure);
            report.accept(cause);
        }
    }

    static Throwable unwrapAndRethrowCritical(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException ite && ite.getTargetException() != null) {
            cause = ite.getTargetException();
        }
        if (FatalErrors.isFatal(cause)) {
            throw (Error) cause;
        }
        if (cause instanceof ProcessCanceledException pce) {
            throw pce;
        }
        return cause;
    }
}
