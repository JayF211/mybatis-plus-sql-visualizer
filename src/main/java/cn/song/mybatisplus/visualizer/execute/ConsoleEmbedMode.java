package cn.song.mybatisplus.visualizer.execute;

/**
 * T-FirstExecResultInvisible F3：控制台内嵌模式的折叠判定（纯函数，可单测）。
 *
 * <p>旧形态（previewMode 布尔）一个参数兼「预览」与「全链执行」两语义：全链执行换入也传
 * previewMode=true 导致执行组件被折叠——执行链三处折叠点（DataSourceSelector
 * prepareAfterEditorResolved/reuseSharedConsole + 面板 embedConsole）归口本枚举后，
 * <b>EXECUTE 恒展开</b>，PREVIEW 仅在结果区无内容时折叠（有内容不吞用户可见的结果——
 * 覆盖「执行中快速切换预览条目」的竞态窗口）。</p>
 */
public enum ConsoleEmbedMode {
    /** 预览准备链（prepareDefaultConsole/recoverConsole）：无内容折叠隐藏空占位，有内容保持。 */
    PREVIEW,

    /** 执行链（executeSql 全链/executePrepared/EXPLAIN 直送）：恒展开（divider=0.6）。 */
    EXECUTE;

    /** 折叠判定：EXECUTE 恒 false；PREVIEW 仅结果区无内容时 true。 */
    public boolean shouldCollapseDivider(boolean resultAreaHasContent) {
        return this == PREVIEW && !resultAreaHasContent;
    }
}
