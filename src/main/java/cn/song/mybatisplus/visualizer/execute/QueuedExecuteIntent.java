package cn.song.mybatisplus.visualizer.execute;

import com.intellij.openapi.editor.Editor;

import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

import javax.swing.JComponent;

/**
 * 排队执行意图（F1 拦截点的回调快照）。拦截时执行链的回调不可丢弃——面板在 executeSql 里
 * 已 showLoading 并拿到 callback(true)，消费点必须用意图自身的 onError/onErrorRich 解除
 * 加载态、用 onConsole（面板 EXECUTE 内嵌）换入结果，否则排队执行失败即静默卡死。
 */
public record QueuedExecuteIntent(
    /** 控制台内嵌回调（面板 EXECUTE 结果换入）；null = 不内嵌。 */
    @Nullable Consumer<JComponent> onConsole,
    /** 控制台编辑器就绪回调；null = 不需要。 */
    @Nullable Consumer<Editor> onEditor,
    /** 旧通道错误回调（解除加载态）；null = 不通知。 */
    @Nullable Consumer<String> onError,
    /** 富通道错误回调；null = 不通知。 */
    @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
    /** 是否整脚本执行。 */
    boolean wholeScriptExec
) {
}
