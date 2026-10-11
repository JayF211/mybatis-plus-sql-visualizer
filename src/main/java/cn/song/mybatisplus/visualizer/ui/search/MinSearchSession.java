package cn.song.mybatisplus.visualizer.ui.search;

import com.intellij.find.FindModel;
import com.intellij.find.SearchSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-SearchToggleNativeLook：给平台原生 ToggleRegex/ToggleMatchCase 用的极小 SearchSession。
 *
 * Java 实现（Kotlin 对平台类型的 null 语义过严，Java 侧返回 null 不受 @NotNull 编译约束）：
 * 开关动作只落 FindModel 两旗；getComponent 返回 null——本组件不参与原生
 * replace/livePreview 生态（调用方不触达该成员）。
 */
public final class MinSearchSession implements SearchSession {

    private final FindModel model;

    public MinSearchSession(@NotNull FindModel model) {
        this.model = model;
    }

    @Override
    @NotNull
    public FindModel getFindModel() {
        return model;
    }

    @Override
    @Nullable
    public com.intellij.find.SearchReplaceComponent getComponent() {
        return null;
    }

    @Override
    public boolean hasMatches() {
        return false;
    }

    @Override
    public void searchForward() {
    }

    @Override
    public void searchBackward() {
    }

    @Override
    public void close() {
    }

}
