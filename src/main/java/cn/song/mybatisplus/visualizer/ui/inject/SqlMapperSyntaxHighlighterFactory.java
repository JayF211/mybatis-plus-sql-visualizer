// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperSyntaxHighlighterFactory.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/** T-XmlSynHi D1：注入语言的高亮器工厂（plugin.xml syntaxHighlighterFactory 注册）。 */
public class SqlMapperSyntaxHighlighterFactory extends SyntaxHighlighterFactory {

    @Override
    public SyntaxHighlighter getSyntaxHighlighter(@Nullable Project project, @Nullable VirtualFile virtualFile) {
        return new SqlMapperSyntaxHighlighter();
    }
}
