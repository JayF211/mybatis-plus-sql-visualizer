package cn.song.mybatisplus.visualizer.util;

import org.jetbrains.annotations.NotNull;

import java.awt.Image;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.ImageIcon;

/**
 * 图标缩放工具（纯 Swing 实现——IDEA 的 `com.intellij.util.ui.IconUtil` 在 IPGP 发行版
 * 被裁剪（编译实证），缩放小图标用基础 AWT/Swing API 替代）。
 */
public final class IconScaler {

    private IconScaler() {
    }

    /** 按比例缩放（如 12f/16f → 0.75）；结果按图标原始尺寸计算，最小 1px。 */
    @NotNull
    public static Icon scale(@NotNull Icon icon, float scale) {
        int w = Math.max(1, (int) (icon.getIconWidth() * scale));
        int h = Math.max(1, (int) (icon.getIconHeight() * scale));
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        icon.paintIcon(null, image.createGraphics(), 0, 0);
        return new ImageIcon(image.getScaledInstance(w, h, Image.SCALE_SMOOTH));
    }
}
