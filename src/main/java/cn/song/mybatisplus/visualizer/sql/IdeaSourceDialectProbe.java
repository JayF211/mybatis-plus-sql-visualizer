package cn.song.mybatisplus.visualizer.sql;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/**
 * master 的 {@code internal object IdeaSourceDialectProbe} 的 Java 承载（全 static 形态）。
 */
final class IdeaSourceDialectProbe {

    private IdeaSourceDialectProbe() {
    }

    public interface LanguageIdLookup {
        @Nullable String lookup(Project project, VirtualFile source);
    }

    static volatile LanguageIdLookup languageIdForTest = null;

    static String dialectName(Project project, VirtualFile source) {
        String id;
        LanguageIdLookup testLookup = languageIdForTest;
        if (testLookup != null) {
            id = testLookup.lookup(project, source);
        } else {
            id = IdeaSqlContextBridge.resolveDialect(project, source).effectiveDialectId();
        }
        if ("TSQL".equalsIgnoreCase(id)) {
            return "SQL Server";
        }
        if (id == null) {
            return null;
        }
        for (Dialect dialect : DialectRegistry.INSTANCE.getDialects()) {
            if (dialect.getName().equalsIgnoreCase(id)) {
                return dialect.getName();
            }
        }
        return null;
    }
}
