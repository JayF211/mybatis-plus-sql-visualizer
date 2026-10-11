// T-SyncK2JMaster482 新建：master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlSelectionDialog.kt 顶层函数 entityCandidateSubtitle 的 Java 门面
// （master 的 SqlSelectionDialog.kt 既有 class SqlSelectionDialog 又有文件级顶层函数；k2j 侧类已落
//  ui/SqlSelectionDialog.java，顶层函数按本仓惯例落同名 Kt 门面承载，internal 升 public。）
package cn.song.mybatisplus.visualizer.ui;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;

import org.jetbrains.annotations.NotNull;

/**
 * 实体候选副标题：完整限定类名——副标题只放文件名时，不同包/模块的同名实体
 * （如多模块 demo 里两处 {@code User.java}）完全无法区分（20261011 录制实测）。
 * qualifiedName 不可得时回退 {@code 包路径/文件名}，再不行退文件名。
 */
public final class SqlSelectionDialogKt {

    private SqlSelectionDialogKt() {}

    public static String entityCandidateSubtitle(@NotNull PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName != null && !qualifiedName.isBlank()) {
            return qualifiedName;
        }
        if (psiClass.getContainingFile() == null) {
            return "";
        }
        String pkg = "";
        if (psiClass.getContainingFile() instanceof PsiJavaFile) {
            String packageName = ((PsiJavaFile) psiClass.getContainingFile()).getPackageName();
            if (packageName != null && !packageName.isBlank()) {
                pkg = packageName + ".";
            }
        }
        return pkg + psiClass.getContainingFile().getName();
    }
}
