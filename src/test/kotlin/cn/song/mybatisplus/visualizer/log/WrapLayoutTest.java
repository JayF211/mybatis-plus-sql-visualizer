package cn.song.mybatisplus.visualizer.log;

import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * WrapLayout 换行高度测试（2026-08-27 用户实测缺陷「SQL类型筛选项消失」的回归锁）：
 *
 * 原生 FlowLayout.preferredLayoutSize 只按单行算高度，组件折到第二行后被裁掉——表现为工具栏
 * 控件消失。WrapLayout 重写后应按多行计算高度。本测试在窄宽度下验证 preferredLayoutSize 高度
 * 明显高于单行高度，证明换行后第二行不会被裁掉。
 */
public class WrapLayoutTest {

    /** 构建含 3 个 60×20 组件的 target，置于指定宽度的父容器中，返回 WrapLayout 的 preferredLayoutSize。 */
    private static Dimension preferredSizeAt(int parentWidth) {
        WrapLayout layout = new WrapLayout();
        JPanel target = new JPanel(layout);
        for (int i = 0; i < 3; i++) {
            JLabel label = new JLabel("x");
            label.setPreferredSize(new Dimension(60, 20));
            target.add(label);
        }
        JPanel parent = new JPanel(new BorderLayout());
        parent.add(target, BorderLayout.CENTER);
        parent.setSize(parentWidth, 400);
        parent.doLayout();
        return layout.preferredLayoutSize(target);
    }

    @Test
    public void 窄宽度换行时高度大于单行高度_控件不被裁掉() {
        Dimension singleRow = preferredSizeAt(400); // 足够宽 → 单行
        Dimension multiRow = preferredSizeAt(130);   // 窄 → 3 个 60 宽组件折成 2 行

        assertTrue("单行高度应 > 0", singleRow.height > 0);
        // 关键断言：窄宽度换行后高度必须明显大于单行高度（原生 FlowLayout 会返回单行高度 → 控件消失）
        assertTrue(
            "换行后高度(" + multiRow.height + ")应明显大于单行高度(" + singleRow.height + ")",
            multiRow.height > singleRow.height
        );
    }

    @Test
    public void 组件全部可见且计入多行宽度() {
        Dimension dim = preferredSizeAt(130);
        // 两行各最多 2 个 60 宽组件 + hgap(4)：单行宽度约 60+4+60=124
        assertTrue("多行布局宽度应能容纳至少两个组件(>120)", dim.width > 120);
        assertTrue("高度应能容纳两行(>单行20)", dim.height > 20);
    }
}
