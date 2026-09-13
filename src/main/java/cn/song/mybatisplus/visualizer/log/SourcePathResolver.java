package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * sourceFile 相对路径 → VirtualFile（M37.1 多源根容错）。
 *
 * <p>先按项目根直拼（F-44 既有语义）；失败再按各模块 content root 直拼——只查模块声明的根
 * （O(模块数) 次路径查找），不遍历文件树，无慢操作风险。调用方保证持有读权限
 * （EDT 或 ReadAction；模块模型读取在两者下均合法）。
 */
public final class SourcePathResolver {

    public static final SourcePathResolver INSTANCE = new SourcePathResolver();

    private SourcePathResolver() {
    }

    @Nullable
    public static VirtualFile resolveVirtualFile(@NotNull Project project, @NotNull String relativePath) {
        String rel = EntrySourceMatcher.normalize(relativePath);
        if (rel.isEmpty()) return null;
        LocalFileSystem fs = LocalFileSystem.getInstance();
        String basePath = project.getBasePath();
        if (basePath != null) {
            VirtualFile direct = fs.findFileByPath(EntrySourceMatcher.normalize(basePath) + "/" + rel);
            if (direct != null) return direct;
        }
        try {
            for (Module module : ModuleManager.getInstance(project).getModules()) {
                for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
                    VirtualFile hit = fs.findFileByPath(root.getPath() + "/" + rel);
                    if (hit != null) return hit;
                }
            }
        } catch (Throwable ignored) {
            // runCatching 等价：模块模型读取失败时调用方优雅降级
        }
        return null;
    }
}
