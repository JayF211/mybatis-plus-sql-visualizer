package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wrapper 搜索服务（O-1 Phase A）：在项目内搜索引用指定表的 Wrapper 链，供日志 ↔ 源码关联跳转。
 *
 * 性能约束：
 * - 使用 {@link ReferencesSearch} 限定搜索范围（scope 受限，仅项目文件）
 * - 搜索操作含 PSI 读，调用方必须在 ReadAction 中调度
 * - 表名匹配走纯函数 {@link #matchesTable(TableInfo, String)}（< 1ms）
 */
public final class WrapperSearchService {

    public static final WrapperSearchService INSTANCE = new WrapperSearchService();

    private static final Logger LOG = Logger.getInstance(WrapperSearchService.class);

    private WrapperSearchService() {
    }

    /**
     * 判断表信息是否匹配目标表名（纯函数，可独立单测）。
     *
     * @param table     解析到的表信息（可能为 null）
     * @param tableName 目标表名（来自 SQL 提取）
     * @return true = 匹配
     */
    public static boolean matchesTable(TableInfo table, String tableName) {
        if (table == null || tableName.isBlank()) {
            return false;
        }
        return table.getTableName().equalsIgnoreCase(tableName);
    }

    /**
     * 在项目内搜索引用指定表的 Wrapper 链。
     *
     * 注意：本方法含 PSI 读操作，调用方必须在 ReadAction 中调用。
     *
     * @param project   当前项目
     * @param tableName 目标表名
     * @return 搜索结果（唯一/多候选/无匹配）
     */
    public static WrapperSearchResult search(Project project, String tableName) {
        if (tableName.isBlank()) {
            return WrapperSearchResult.None.INSTANCE;
        }

        List<WrapperCandidate> matchedCandidates = new ArrayList<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);

        try {
            for (String className : WrapperLocator.INSTANCE.getWrapperFactoryClassNames()) {
                PsiClass psiClass = facade.findClass(className, GlobalSearchScope.allScope(project));
                if (psiClass == null) {
                    continue;
                }
                searchInClass(psiClass, tableName, scope, matchedCandidates);
            }
        } catch (Exception e) {
            LOG.warn("WrapperSearchService.search 异常（table=" + tableName + "）：" + e.getMessage(), e);
        }

        return switch (matchedCandidates.size()) {
            case 0 -> WrapperSearchResult.None.INSTANCE;
            case 1 -> new WrapperSearchResult.Single(matchedCandidates.get(0));
            default -> {
                // 按 anchorMethod 起始偏移去重（保持原有顺序）
                List<WrapperCandidate> distinct = new ArrayList<>();
                Set<Integer> seen = new HashSet<>();
                for (WrapperCandidate c : matchedCandidates) {
                    int key = c.anchorMethod().getTextRange().getStartOffset();
                    if (seen.add(key)) {
                        distinct.add(c);
                    }
                }
                yield new WrapperSearchResult.Multiple(distinct);
            }
        };
    }

    /** 在单个工厂类内搜索匹配的 Wrapper 引用。 */
    private static void searchInClass(
            PsiClass psiClass,
            String tableName,
            GlobalSearchScope scope,
            List<WrapperCandidate> out
    ) {
        for (PsiMethod method : psiClass.getMethods()) {
            if (!WrapperLocator.INSTANCE.getWrapperFactoryMethodNames$mybatis_plus_sql_visualizer().contains(method.getName())) {
                continue;
            }
            Collection<com.intellij.psi.PsiReference> refs;
            try {
                refs = ReferencesSearch.search(method, scope, false).findAll();
            } catch (Exception ignored) {
                continue;
            }
            for (com.intellij.psi.PsiReference ref : refs) {
                com.intellij.psi.PsiElement element = ref.getElement();
                cn.song.mybatisplus.visualizer.psi.LocatedWrapper located = WrapperLocator.INSTANCE.locateWithTable(element, true);
                if (located == null) {
                    continue;
                }
                TableInfo locatedTable = located.getTable();
                if (locatedTable == null) {
                    continue;
                }
                if (!matchesTable(locatedTable, tableName)) {
                    continue;
                }
                PsiMethod anchorMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                if (anchorMethod == null) {
                    continue;
                }
                PsiFile file = element.getContainingFile();
                if (file == null) {
                    continue;
                }
                out.add(new WrapperCandidate(anchorMethod, file, locatedTable));
            }
        }
    }

    /**
     * 跳转到候选元素（在 EDT 中执行编辑器定位）。
     */
    public static void navigateTo(Project project, WrapperCandidate candidate) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            com.intellij.openapi.vfs.VirtualFile virtualFile = candidate.file().getVirtualFile();
            if (virtualFile == null) {
                return;
            }
            OpenFileDescriptor descriptor = new OpenFileDescriptor(
                    project, virtualFile, candidate.anchorMethod().getTextRange().getStartOffset());
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
        });
    }
}
