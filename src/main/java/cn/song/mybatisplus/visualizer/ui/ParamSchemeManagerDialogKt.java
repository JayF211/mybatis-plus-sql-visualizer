// T-SyncK2JMaster482 自 master ui/ParamSchemeManagerDialog.kt 顶层函数移植（R22 T-04）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;

/** Kotlin 顶层函数承载（{@code ParamSchemeManagerDialog.kt} → Java facade）。 */
public final class ParamSchemeManagerDialogKt {

    private ParamSchemeManagerDialogKt() {
    }

    /** 方案显示名：默认方案（空串内部名）经 bundle 本地化，命名方案原名直显。 */
    @NotNull
    public static String displaySchemeName(@NotNull String name) {
        return ParamHistoryCache.DEFAULT_SCHEME.equals(name)
            ? MpSqlVisualizerBundle.message("panel.scheme.default")
            : name;
    }
}
