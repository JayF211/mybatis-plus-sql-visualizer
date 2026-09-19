// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/FocusTab.kt 移植
package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.List;

/**
 * T-M55-03/T-M55-04 共用：动态聚焦过滤页签模型（页签是过滤器不是分组——不碰 VisibleDiff
 * 差分与缓冲分组模型，呈现保持扁平时间序）。
 *
 * <ul>
 * <li>{@link Method}：调用方方法行号区间近似聚焦（{@link CallerMethodRangeResolver} 静态解析
 * sourceFile+sourceLine 所在 PsiMethod 的行号区间；命名 {@code FooService#bar}）；</li>
 * <li>{@link Request}：请求标签聚焦（{@code LogSqlEntry.requestTag}，同一次请求的多条 SQL 共享同值）。</li>
 * </ul>
 *
 * <p>多开语义 = 并集过滤（条目命中任一开启页签即可见）；无对应维度的条目（无 sourceFile /
 * 无 requestTag）天然不命中任何页签，在聚焦视图下隐藏（面板有计数提示）。
 */
public sealed interface FocusTab {

    /** 页签展示名。 */
    String label();

    /** Kotlin {@code abstract val label} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    default String getLabel() {
        return label();
    }

    /** 调用方方法聚焦（行号区间含两端；sourceFile 已规约化——正斜杠、段对齐比较）。 */
    record Method(
        /** 规约化后的调用方源文件相对路径。 */
        String sourceFile,
        /** 方法体起始行（1-based 含两端）。 */
        int startLine,
        /** 方法体结束行（1-based 含两端）。 */
        int endLine,
        /** 具名外层类名（匿名类归属上一层具名类）。 */
        String className,
        /** 方法名。 */
        String methodName
    ) implements FocusTab {
        @Override
        public String label() {
            return className + "#" + methodName;
        }
    }

    /** 请求标签聚焦（精确匹配）。 */
    record Request(
        /** 请求级聚合标签（同一次请求的多条 SQL 共享同值）。 */
        String requestTag
    ) implements FocusTab {
        @Override
        public String label() {
            return requestTag;
        }
    }

    /** 请求聚焦页签上限（K 有界——长压测会话 requestTag 高基数，防页签栏爆列表）。 */
    int FOCUS_REQUEST_TAB_MAX = 8;

    /** 单条目是否命中单个聚焦页签。 */
    static boolean entryMatchesFocusTab(LogSqlEntry entry, FocusTab tab) {
        if (tab instanceof Method method) {
            return entry.sourceFile() != null && entry.sourceLine() > 0
                && EntrySourceMatcher.sameFile(EntrySourceMatcher.normalize(entry.sourceFile()), method.sourceFile())
                && entry.sourceLine() >= method.startLine() && entry.sourceLine() <= method.endLine();
        }
        if (tab instanceof Request request) {
            return entry.requestTag() != null && entry.requestTag().equals(request.requestTag());
        }
        return false;
    }

    /**
     * 聚焦页签过滤（纯逻辑，可独立单测；插在既有 filterByFocus 与 filterBySearch 之间的再一层）：
     * 无页签 = 透传；有页签 = 命中任一页签的条目保留（并集），其余隐藏。
     */
    static List<LogSqlEntry> filterByFocusTabs(List<LogSqlEntry> entries, List<FocusTab> tabs) {
        if (tabs.isEmpty()) return entries;
        List<LogSqlEntry> kept = new ArrayList<>();
        for (LogSqlEntry entry : entries) {
            boolean hit = false;
            for (FocusTab tab : tabs) {
                if (entryMatchesFocusTab(entry, tab)) {
                    hit = true;
                    break;
                }
            }
            if (hit) kept.add(entry);
        }
        return kept;
    }

    /**
     * 新增请求页签的准入（纯函数）：去重、K 有界——超限挤掉最早的请求页签（method 页签不受影响，
     * 用户显式聚焦的操作方法不被自动页签顶掉），返回收缩后的<b>新</b>列表（调用方 clear+addAll 回写，
     * 不得原样返回入参可变列表——别名回写会自清空）。
     */
    static List<FocusTab> addRequestTab(List<FocusTab> tabs, String requestTag) {
        String tag = requestTag.trim();
        if (tag.isEmpty()) return new ArrayList<>(tabs);
        for (FocusTab tab : tabs) {
            if (tab instanceof Request request && request.requestTag().equals(tag)) return new ArrayList<>(tabs);
        }
        List<FocusTab> requestTabs = new ArrayList<>();
        List<FocusTab> others = new ArrayList<>();
        for (FocusTab tab : tabs) {
            if (tab instanceof Request) {
                requestTabs.add(tab);
            } else {
                others.add(tab);
            }
        }
        List<FocusTab> next = new ArrayList<>(requestTabs);
        next.add(new Request(tag));
        List<FocusTab> trimmed = next.size() > FOCUS_REQUEST_TAB_MAX
            ? new ArrayList<>(next.subList(next.size() - FOCUS_REQUEST_TAB_MAX, next.size()))
            : next;
        List<FocusTab> result = new ArrayList<>(others);
        result.addAll(trimmed);
        return result;
    }
}
