package cn.song.mybatisplus.visualizer.log;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.border.AbstractBorder;

/**
 * 选中描边 Border（T-CrudColor §1.2.1）：{@code W} 像素四边描边 + 内层 Border 委托。
 *
 * 用于 {@code background}（整行淡背景）样式的<b>选中行</b>——该样式不铺平台选中填充，
 * 而由「类型色调加深 + 本描边」共同表达选中，保证浏览全程不丢色。
 *
 * 三个设计约束（改动前请先读）：
 * 1. <b>insets 逐边相等</b>：{@code getBorderInsets} 在内层 insets 上四边各 +width，因此渲染器把内层
 *    {@code EmptyBorder} 四边各减 width 即可让选中/未选中两态 insets 完全一致——这是
 *    「选中瞬间文本不位移」的守卫（位移在键盘连续浏览时表现为抖动，描边越粗越明显）。
 * 2. <b>零分配</b>：实例由渲染器在 {@code init} 预建、渲染时<b>引用切换</b>；描边色随主题/焦点变化只改
 *    color 字段（{@link #setColor}），不重建 Border——既避免热路径分配，也避免
 *    「主题切换后描边色冻结在构造时」。
 * 3. <b>四条边带绘制</b>：用 {@code fillRect} 画上/下/左/右四条带，而不是嵌套 {@code drawRect}——
 *    {@code drawRect} 只能画 1px，W&gt;1 时嵌套多次会角部重叠且线宽受绘制精度影响。
 *
 * @param width 描边厚度（逻辑像素），超界按 {@link #MIN_WIDTH}..{@link #MAX_WIDTH} 夹紧
 * @param color 初始描边色（可变，见 {@link #setColor}）
 * @param inner 内层 Border（尺寸委托对象；其 insets 会被本描边四边 +width）。
 *   声明为 {@link AbstractBorder}（而非 {@link javax.swing.border.Border} 接口）是<b>必需的</b>：双参
 *   {@code getBorderInsets(Component, Insets)} 定义在 {@code AbstractBorder} 上、不在 {@code Border} 接口上，
 *   只有它才能走「复用入参 Insets」的零分配路径。
 */
public class SqlListSelectionOutline extends AbstractBorder {

    /** 描边最小厚度。 */
    public static final int MIN_WIDTH = 1;

    /**
     * 描边最大厚度 = 渲染器上内边距 5 − 1（留 1px 余量）。
     * 再粗会让内层 {@code EmptyBorder(5-W, …)} 算出负 inset 而抛 IllegalArgumentException；
     * 想突破须同步加大 {@code EmptyBorder} 上下内边距——那会改变行高，触及
     * T-LogListFreeze 的固定行高快路径，故不建议。
     */
    public static final int MAX_WIDTH = 4;

    /** 实际描边厚度（夹紧到安全区间后）。 */
    public final int width;

    /** 当前描边色（可变；测试与渲染器可读）。 */
    private Color color;

    private final AbstractBorder inner;

    public SqlListSelectionOutline(int width, Color color, AbstractBorder inner) {
        this.width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
        this.color = color;
        this.inner = inner;
    }

    /** 当前描边色（测试与渲染器可读）。 */
    public Color getOutlineColor() {
        return color;
    }

    /** 更新描边色（焦点/失焦两档、主题切换；不重建实例）。 */
    public void setColor(Color c) {
        color = c;
    }

    @Override
    public Insets getBorderInsets(Component c) {
        Insets i = inner.getBorderInsets(c);
        return new Insets(i.top + width, i.left + width, i.bottom + width, i.right + width);
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        Insets i = inner.getBorderInsets(c, insets);
        insets.top += width;
        insets.left += width;
        insets.bottom += width;
        insets.right += width;
        return i;
    }

    @Override
    public boolean isBorderOpaque() {
        return true;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
        // 不自行绘制 inner：本类作为 CompoundBorder 的 outer，inner 由 CompoundBorder 负责
        if (w <= 0 || h <= 0) {
            return;
        }
        Color old = g.getColor();
        g.setColor(color);
        g.fillRect(x, y, w, width);                                  // 上（整宽）
        g.fillRect(x, y + h - width, w, width);                      // 下（整宽）
        int middleHeight = h - 2 * width;                            // 退化尺寸（h < 2W）时为负 → fillRect 自忽略
        if (middleHeight > 0) {
            g.fillRect(x, y + width, width, middleHeight);           // 左
            g.fillRect(x + w - width, y + width, width, middleHeight); // 右
        }
        g.setColor(old);
    }
}
