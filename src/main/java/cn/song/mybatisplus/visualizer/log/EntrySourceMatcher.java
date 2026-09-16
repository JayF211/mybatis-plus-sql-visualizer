package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * M37.1 日志↔源码双向深链的纯逻辑部分（P-立项-06 N-2）。
 *
 * <p>正向（日志→源码）F-44 已实现行级精确跳转；本类补**反向**（源码→日志）——
 * 按锚点文件+行号窗口反查日志条目。配套 {@link SourcePathResolver}（路径→VirtualFile）
 * 与 {@link LogEntryPreviewOpener}（反查条目打开预览弹窗）。
 *
 * <p>反查数据源由调用方提供（实时场景传内存 buffer 快照；历史 .sql 文件读取属 IO，
 * 须调用方在后台线程完成——本类零 IO、零 PSI，纯函数可单测）。
 */
public final class EntrySourceMatcher {

    public static final EntrySourceMatcher INSTANCE = new EntrySourceMatcher();

    private EntrySourceMatcher() {
    }

    /** 行号窗口：拦截器记录的**调用处**行与链锚点行的最大允许偏差（方法体典型长度）。 */
    public static final int DEFAULT_WINDOW = 30;

    /** 返回条目上限（弹窗展示防刷屏）。 */
    public static final int MAX_RESULTS = 20;

    /**
     * 反查日志条目：同文件条目按时间倒序，行号窗口内（|Δ| ≤ window）优先，其余同文件条目随后。
     *
     * <p>窗口而非等值匹配——SqlSourceInterceptor 记录的是 SQL 执行的调用处行，
     * 可能与 Wrapper 链锚点行不同（链起点之下的语句行）。
     * sourceLine=0（旧条目未知行）与锚点行差值天然超窗，自动排除。
     *
     * @param sourceFilePath 锚点文件的相对项目根路径（null/空 = 无法反查，返回空）
     * @param anchorLine     锚点行号（1-based；≤0 返回空）
     */
    public static List<LogSqlEntry> match(List<LogSqlEntry> entries, String sourceFilePath,
                                          int anchorLine, int window) {
        if (sourceFilePath == null || sourceFilePath.isBlank() || anchorLine <= 0) return List.of();
        String key = normalize(sourceFilePath);
        List<LogSqlEntry> sameFile = new ArrayList<>();
        for (LogSqlEntry entry : entries) {
            if (entry.sourceFile() != null && sameFile(normalize(entry.sourceFile()), key)) {
                sameFile.add(entry);
            }
        }
        if (sameFile.isEmpty()) return List.of();
        sameFile.sort(Comparator.comparingLong(LogSqlEntry::timestamp).reversed());
        List<LogSqlEntry> inWindow = new ArrayList<>();
        List<LogSqlEntry> outWindow = new ArrayList<>();
        for (LogSqlEntry entry : sameFile) {
            if (Math.abs(entry.sourceLine() - anchorLine) <= window) {
                inWindow.add(entry);
            } else {
                outWindow.add(entry);
            }
        }
        List<LogSqlEntry> result = new ArrayList<>(inWindow);
        result.addAll(outWindow);
        return result.size() > MAX_RESULTS ? new ArrayList<>(result.subList(0, MAX_RESULTS)) : result;
    }

    public static List<LogSqlEntry> match(List<LogSqlEntry> entries, String sourceFilePath, int anchorLine) {
        return match(entries, sourceFilePath, anchorLine, DEFAULT_WINDOW);
    }

    /**
     * 同一文件判定（T-JumpPath-03）：两种路径形态段对齐后缀互含。
     *
     * <p>锚点=项目根相对（多模块含模块前缀，如 {@code mod/src/main/java/cn/song/a/S.java}），
     * 条目 sourceFile=拦截器包式路径（相对源码根，如 {@code cn/song/a/S.java}）——
     * 等值比较恒不命中，「最近执行记录」恒空（真机实锤）。段对齐（{@code /} 为界）
     * 杜绝 {@code xmod/...} 之类后缀假命中。
     */
    public static boolean sameFile(String a, String b) {
        return a.equals(b) || a.endsWith("/" + b) || b.endsWith("/" + a);
    }

    /** 路径规约：统一正斜杠、去首尾空白与尾斜杠（Windows 反斜杠来源兼容）。 */
    public static String normalize(String path) {
        String result = path.trim().replace('\\', '/');
        int end = result.length();
        while (end > 0 && result.charAt(end - 1) == '/') end--;
        return result.substring(0, end);
    }
}
