// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/sql/PlaceholderPositions.kt 移植
package cn.song.mybatisplus.visualizer.sql;

/**
 * T-PSG-04：{@code ?} 位点数与参数条数对账（<b>不阻断</b>——与我方既有 {@code ${}} 闸门的
 * 「确认/标注」档一致，不引入拒绝档）：不等时给出一行如实标注，相等时返回 null。
 *
 * <p>标注形态照抄 {@code CopyExecutableSqlAction.DOLLAR_RAW_MARKER} 的既有头部标记（{@code --} SQL
 * 注释行，不改产物可执行性），不新造档位。</p>
 *
 * <p>两条拼接链的输入形态不同，故提供两个判定口（<b>有意的不对称</b>，见各自 Javadoc）：
 * 面板回填链拼的是渲染原文本（{@code ?} 齐全）走严格等值；复制链拼的是面板已回填文本
 * （已填参数的位置不再是 {@code ?}）只认「占位符多于参数」这一必然残留裸 {@code ?} 的方向。</p>
 *
 * <p>移植注记：master 侧为同文件顶层 {@code internal object PlaceholderCardinality}；Java 一个文件
 * 只能有一个 public 顶层类型，故按 k2j 既定惯例独立成件，成员落为静态方法。</p>
 */
public final class PlaceholderCardinality {

    private PlaceholderCardinality() {
    }

    /** 严格不等时的标注行；相等返回 null（面板回填链：输入 {@code ?} 齐全，反向不等同样是异常）。 */
    public static String mismatchMarker(int placeholderCount, int paramCount) {
        return placeholderCount == paramCount ? null : marker(placeholderCount, paramCount);
    }

    /**
     * 只在「占位符多于参数」时的标注行（复制链）。
     *
     * <p>反向（位点少于参数条数）在复制链是<b>常态</b>而非缺陷：复制链拼的是面板编辑器里的
     * 已回填文本——已填参数的位置早已不是 {@code ?}，而 sources 仍是完整参数表，按严格等值断言
     * 会「填一个参数就报一次不等」的天天误报。真正值得如实标注的是产物里必然残留的、
     * 无参数可填的裸 {@code ?}（可执行 SQL 不完整）。</p>
     */
    public static String excessPlaceholderMarker(int placeholderCount, int paramCount) {
        return placeholderCount > paramCount ? marker(placeholderCount, paramCount) : null;
    }

    private static String marker(int placeholderCount, int paramCount) {
        return "-- placeholder/param count mismatch (" + placeholderCount + " placeholders vs " + paramCount + " params)";
    }
}
