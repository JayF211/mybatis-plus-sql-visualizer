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
 * <p>三级策略按精确度递减：① 项目根直拼（历史面板 {@code src/main/java/...} 形态，F-44 既有语义）；
 * ② 各模块 content root 直拼；③ 各模块 <b>source root</b> 直拼（T-JumpPath-02 收编旧
 * {@code SqlLogPanel.findSourceFile} 语义——拦截器标记路径是包式相对<b>源码根</b>
 * {@code cn/song/a/S.java}，Maven/Gradle 标准布局下前两级均缺 {@code src/main/java} 段，
 * 缺本策略实时拦截器条目多模块工程全形态解析失败）。只查模块声明的根
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
            Module[] modules = ModuleManager.getInstance(project).getModules();
            // ② 各模块 content root 直拼
            VirtualFile contentHit = null;
            for (Module module : modules) {
                for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
                    VirtualFile hit = fs.findFileByPath(root.getPath() + "/" + rel);
                    if (hit != null) {
                        contentHit = hit;
                        break;
                    }
                }
                if (contentHit != null) break;
            }
            if (contentHit != null) return contentHit;
            // ③ 各模块 source root 直拼（T-JumpPath-02：包式相对源码根路径）
            for (Module module : modules) {
                for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
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
