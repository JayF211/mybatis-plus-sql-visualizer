package cn.song.mybatisplus.visualizer.log;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * WrapLayout：FlowLayout 的换行变体——空间不足时组件自动折到下一行，且 preferred/minimum
 * 尺寸按多行计算（原生 FlowLayout.preferredLayoutSize 只按单行算，折行后第二行会被裁掉，
 * 表现为「控件消失」——2026-08-27 用户实测 SQL类型按钮选中两个类型后触发的缺陷）。
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout() {
        super(LEFT, 4, 2);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return computeSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return computeSize(target, false);
    }

    private Dimension computeSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int availWidth = (target.getParent() != null ? target.getParent().getWidth() : Integer.MAX_VALUE)
                    - insets.left - insets.right;
            int x = 0;
            int y = insets.top + getVgap();
            int rowHeight = 0;
            int maxRowWidth = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (x + d.width > availWidth && x > 0) {
                    maxRowWidth = Math.max(maxRowWidth, x - getHgap());
                    x = 0;
                    y += rowHeight + getVgap();
                    rowHeight = 0;
                }
                x += d.width + getHgap();
                rowHeight = Math.max(rowHeight, d.height);
            }
            maxRowWidth = Math.max(maxRowWidth, x - getHgap());
            return new Dimension(maxRowWidth + insets.left + insets.right, y + rowHeight + insets.bottom);
        }
    }

    @Override
    public void layoutContainer(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int availWidth = target.getWidth() - insets.left - insets.right;
            int x = insets.left;
            int y = insets.top;
            int rowHeight = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) {
                    continue;
                }
                Dimension d = m.getPreferredSize();
                int rightEdge = x + d.width;
                int maxRight = insets.left + availWidth;
                if (rightEdge > maxRight && x > insets.left) {
                    x = insets.left;
                    y += rowHeight + getVgap();
                    rowHeight = 0;
                }
                m.setBounds(x, y, d.width, d.height);
                x += d.width + getHgap();
                rowHeight = Math.max(rowHeight, d.height);
            }
        }
    }
}
