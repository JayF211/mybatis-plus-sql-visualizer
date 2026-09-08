package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.editor.Editor;
import javax.swing.JComponent;

/** 预览内容持有者：面板组件 + 编辑器实例（测试断言与释放用）。 */
public record SqlClickPreviewContent(JComponent component, Editor editor) {
}
