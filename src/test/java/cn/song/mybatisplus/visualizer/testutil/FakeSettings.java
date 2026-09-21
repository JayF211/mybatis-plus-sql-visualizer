package cn.song.mybatisplus.visualizer.testutil;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableGroup;
import com.intellij.openapi.options.ex.Settings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.Promise;

import java.util.List;

/**
 * 测试用 {@link Settings} 薄壳（N5 设置落地页导航 EDT 慢操作）。
 *
 * <p>平台的 {@code Settings} 是抽象类、且只能由设置对话框内部创建；而「对话框内导航」正是本次要验
 * 的行为（{@code settings.find(id)} → {@code ConfigurableVisitor.findById}）。故测试里给一个只承载
 * groups 的壳：查找走平台实现，选中/建 UI 一律不落到 UI（被调到即说明用例用错了入口）。
 *
 * <p>用 Java 写是因为 Kotlin 侧覆写 {@code Promise<? super Object>} 这类带通配的返回类型容易踩
 * 型变坑（与本仓 {@code FakeClipboardInstaller} 同样的取舍）。
 */
public final class FakeSettings extends Settings {

    public FakeSettings(@NotNull List<? extends ConfigurableGroup> groups) {
        super(groups);
    }

    @Override
    protected @NotNull Promise<? super Object> selectImpl(@NotNull Configurable configurable) {
        throw new UnsupportedOperationException("FakeSettings 只验查找，不驱动设置对话框选中");
    }

    @Override
    protected Configurable getConfigurableWithInitializedUiComponentImpl(@NotNull Configurable configurable,
                                                                        boolean initializeUiComponentIfNotYet) {
        throw new UnsupportedOperationException("FakeSettings 不创建 UI");
    }

    @Override
    protected void checkModifiedImpl(@NotNull Configurable configurable) {
    }

    @Override
    protected void setSearchText(String option) {
    }
}
