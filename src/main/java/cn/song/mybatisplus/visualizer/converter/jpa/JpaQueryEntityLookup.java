package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import java.util.HashMap;
import java.util.Map;

/** 单次转换共享解析结果；context 始终是查询宿主，不得换成已找到的实体文件。 */
public final class JpaQueryEntityLookup {
    private final Project project;
    private final PsiElement context;
    private final Map<String, PsiClass> classes = new HashMap<>();
    private final Map<String, TableInfo> tables = new HashMap<>();

    public JpaQueryEntityLookup(Project project, PsiElement context) {
        this.project = project;
        this.context = context;
    }

    public PsiClass resolveClass(String name) {
        if (!classes.containsKey(name)) {
            classes.put(name, JpaAliasTableResolver.recoverJpaPsi(() -> project == null ? null
                    : EntityAliasSweep.resolve(project, name, context).psiClass()));
        }
        return classes.get(name);
    }

    public TableInfo resolveTable(String name) {
        if (!tables.containsKey(name)) {
            tables.put(name, JpaAliasTableResolver.recoverJpaPsi(() -> {
                PsiClass clazz = resolveClass(name);
                return clazz == null ? null : new JpaEntityResolver().resolve(clazz);
            }));
        }
        return tables.get(name);
    }

    /** 委托共享提取器：避免预览弱提取的非空 TableInfo 压过转换链强提取。 */
    public static String rootEntity(String sql) { return HqlEntityNameExtractor.extract(sql); }
}
