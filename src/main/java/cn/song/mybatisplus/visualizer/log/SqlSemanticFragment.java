package cn.song.mybatisplus.visualizer.log;

import com.intellij.psi.tree.IElementType;

/** 不携带主题属性的词法片段，适合缓存；颜色在每次绘制时投影。 */
public record SqlSemanticFragment(String text, IElementType tokenType) {
}
