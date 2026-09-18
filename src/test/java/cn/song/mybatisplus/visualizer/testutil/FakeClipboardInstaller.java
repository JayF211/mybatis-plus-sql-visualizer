package cn.song.mybatisplus.visualizer.testutil;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.testFramework.ServiceContainerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * 测试用剪贴板服务替换的 Java 薄封装（T-ClipboardIso）。
 *
 * 平台入口 {@code com.intellij.testFramework.ServiceContainerUtil} 是 Kotlin 文件里声明的
 * internal 对象——字节码 public（Java 可调），但 Kotlin 侧跨模块不可见（编译报 Unresolved
 * reference）。故本封装用 Java 写，供 {@code FakeClipboard.kt} 调用，避免反射（反射入口需登记
 * PlatformSignatureCrossVersionTest，属不必要的负担）。
 */
public final class FakeClipboardInstaller {

    private FakeClipboardInstaller() {
    }

    /**
     * 以内存假实现替换平台剪贴板应用服务。
     *
     * @param fake             假实现（{@link cn.song.mybatisplus.visualizer.testutil.FakeClipboardManager}）
     * @param parentDisposable 释放时自动还原平台实现（传 testRootDisposable）
     */
    public static void replaceClipboard(@NotNull CopyPasteManager fake, @NotNull Disposable parentDisposable) {
        ServiceContainerUtil.replaceService(
                ApplicationManager.getApplication(),
                CopyPasteManager.class,
                fake,
                parentDisposable
        );
    }
}
