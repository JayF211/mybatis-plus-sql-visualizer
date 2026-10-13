// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperSyntaxHighlighterFactory.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/**
 * T-XmlSynHi D1：注入语言的高亮器工厂（plugin.xml {@code lang.syntaxHighlighterFactory} 注册）。
 *
 * <p><b>EP 名必须写限定形态</b>（{@code lang.syntaxHighlighterFactory}，限定名
 * {@code com.intellij.lang.syntaxHighlighterFactory}）：裸 {@code <syntaxHighlighterFactory>} 会被平台按
 * 未知 EP 静默忽略，本工厂永不实例化 → 注入片段整段无色（20261012 定位的「SQL 文本整段白」
 * 真根因；守卫见 {@code config/PluginXmlExtensionPointResolutionTest}）。</p>
 */
public class SqlMapperSyntaxHighlighterFactory extends SyntaxHighlighterFactory {

    @Override
    public SyntaxHighlighter getSyntaxHighlighter(@Nullable Project project, @Nullable VirtualFile virtualFile) {
        return new SqlMapperSyntaxHighlighter();
    }
}
