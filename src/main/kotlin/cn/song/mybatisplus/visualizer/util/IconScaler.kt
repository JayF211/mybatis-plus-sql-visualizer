package cn.song.mybatisplus.visualizer.util

import java.awt.Image
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * 图标缩放工具（纯 Swing 实现——IDEA 的 `com.intellij.util.ui.IconUtil` 在 IPGP 发行版
 * 被裁剪（编译实证），缩放小图标用基础 AWT/Swing API 替代）。
 */
object IconScaler {

    /** 按比例缩放（如 12f/16f → 0.75）；结果按图标原始尺寸计算，最小 1px。 */
    fun scale(icon: Icon, scale: Float): Icon {
        val w = (icon.iconWidth * scale).toInt().coerceAtLeast(1)
        val h = (icon.iconHeight * scale).toInt().coerceAtLeast(1)
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        icon.paintIcon(null, image.createGraphics(), 0, 0)
        return ImageIcon(image.getScaledInstance(w, h, Image.SCALE_SMOOTH))
    }
}
