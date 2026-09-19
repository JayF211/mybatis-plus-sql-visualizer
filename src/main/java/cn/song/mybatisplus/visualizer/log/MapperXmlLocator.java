// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/MapperXmlLocator.kt 移植
package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * T-M55-01 跳转双目标：mapper 语句 id → 项目内 mapper XML 语句节点的定位器。
 *
 * <p>条目 {@code LogSqlEntry.mapperStatement}（拦截器 {@code ms.getId()}，形如 {@code com.example.mapper.UserMapper.selectById}）
 * 拆 namespace + id 后，经 {@link FileTypeIndex} 按文件类型扫描项目内 XML（仿 psi/jpa/HbmXmlEntityResolver.kt
 * 的 FileTypeIndex 先例），PSI 校验 {@code <mapper namespace>} 匹配后定位同 id 的语句标签
 * （select/insert/update/delete，直接子级——沿 ui/gutter/XmlSqlGutterLineMarkerProvider.kt 范式），
 * 换算语句起始行号供 {@code com.intellij.openapi.fileEditor.OpenFileDescriptor} 直跳。
 *
 * <p>已知边界（首批记录，不弹选择器）：多模块同名 mapper（同 namespace 同 id）取索引序第一个并 LOG.info 留痕。
 * MP 内置注入语句（Wrapper CRUD）XML 通常不存在 → locate 返回 null，调用方回退 Java 跳转，无劣化。
 *
 * <p>线程约定：{@link #locate} 自带 {@link ReadAction} 包裹，EDT（右键菜单）与后台线程均可直调；
 * 内部为索引查询 + PSI 只读，无写操作。
 */
public final class MapperXmlLocator {

    public static final MapperXmlLocator INSTANCE = new MapperXmlLocator();

    private MapperXmlLocator() {
    }

    private static final Logger LOG = Logger.getInstance(MapperXmlLocator.class);

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    /** 定位结果：mapper XML 文件 + 语句标签起始行（0-based，OpenFileDescriptor 直用）。 */
    public record Location(
        /** mapper XML 虚拟文件。 */
        @NotNull VirtualFile virtualFile,
        /** 语句标签起始行（0-based）。 */
        int line
    ) {
    }

    /**
     * 定位语句在 mapper XML 中的位置；任一环失败返回 null（调用方回退 Java 跳转）。
     * @param statementId 完整语句 id（{@code namespace.id}，如 {@code com.example.mapper.UserMapper.selectById}）
     */
    @Nullable
    public static Location locate(@NotNull Project project, @NotNull String statementId) {
        kotlin.Pair<String, String> parts = splitStatementId(statementId);
        if (parts == null) return null;
        String namespace = parts.getFirst();
        String id = parts.getSecond();
        return ReadAction.compute(() -> locateUnderRead(project, namespace, id));
    }

    /** 拆 {@code namespace.id}（按最后一个 {@code .} 分段——namespace 本身含点，语句 id 惯例不含）；形态不符返回 null。 */
    @Nullable
    public static kotlin.Pair<String, String> splitStatementId(@NotNull String statementId) {
        String s = statementId.trim();
        if (s.isEmpty()) return null;
        int idx = s.lastIndexOf('.');
        if (idx <= 0 || idx == s.length() - 1) return null;
        return new kotlin.Pair<>(s.substring(0, idx), s.substring(idx + 1));
    }

    /** 读上下文内的定位主体（FileTypeIndex → namespace/id 校验 → 行号换算）。 */
    @Nullable
    private static Location locateUnderRead(@NotNull Project project, @NotNull String namespace, @NotNull String id) {
        XmlFile firstFile = null;
        XmlTag firstTag = null;
        int extraHits = 0;
        for (XmlFile file : findMapperXmlFiles(project)) {
            XmlTag tag = findStatementTag(file, namespace, id);
            if (tag == null) continue;
            if (firstFile == null) {
                firstFile = file;
                firstTag = tag;
            } else {
                extraHits++;
            }
        }
        if (extraHits > 0) {
            String firstPath = (firstFile != null && firstFile.getVirtualFile() != null)
                ? firstFile.getVirtualFile().getPath() : null;
            LOG.info("MapperXmlLocator: 语句 " + namespace + "." + id + " 命中 " + (extraHits + 1)
                + " 个 mapper XML（多模块同名），取第一个：" + firstPath);
        }
        if (firstFile == null || firstTag == null) return null;
        VirtualFile vf = firstFile.getVirtualFile();
        if (vf == null) return null;
        com.intellij.openapi.editor.Document document =
            PsiDocumentManager.getInstance(project).getDocument(firstFile);
        if (document == null) return null;
        int line = document.getLineNumber(firstTag.getTextRange().getStartOffset());
        return new Location(vf, line);
    }

    /**
     * 项目内全部 mapper XML（{@code .xml} 后缀 + 根元素 {@code <mapper>}）。
     * XmlFileType 在 262 平台不可直接引用（IPGP 裁剪），按 HbmXmlEntityResolver 同款用
     * FileTypeManager 取标准 XML 类型；索引异常按空列表处理（优雅降级，绝不抛出）。
     */
    @NotNull
    private static List<XmlFile> findMapperXmlFiles(@NotNull Project project) {
        try {
            com.intellij.openapi.fileTypes.FileType xmlFileType =
                FileTypeManager.getInstance().getStdFileType("XML");
            List<XmlFile> result = new ArrayList<>();
            for (VirtualFile vf : FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (!(psiFile instanceof XmlFile xmlFile)) continue;
                if (xmlFile.getRootTag() == null || !"mapper".equals(xmlFile.getRootTag().getName())) continue;
                result.add(xmlFile);
            }
            return result;
        } catch (Throwable ignored) {
            // runCatching 等价：索引异常按空列表处理（优雅降级，绝不抛出）
            return List.of();
        }
    }

    /**
     * 单文件内定位语句标签：namespace 属性精确匹配（{@link #splitStatementId} 的 namespace 段），
     * 语句标签（{@code XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS} 同款集合）为 {@code <mapper>} 直接子级且 id 相等。
     */
    @Nullable
    private static XmlTag findStatementTag(@NotNull XmlFile file, @NotNull String namespace, @NotNull String id) {
        XmlTag root = file.getRootTag();
        if (root == null) return null;
        if (!namespace.equals(root.getAttributeValue("namespace"))) return null;
        for (XmlTag subTag : root.getSubTags()) {
            if (STATEMENT_TAGS.contains(subTag.getName()) && id.equals(subTag.getAttributeValue("id"))) {
                return subTag;
            }
        }
        return null;
    }
}
