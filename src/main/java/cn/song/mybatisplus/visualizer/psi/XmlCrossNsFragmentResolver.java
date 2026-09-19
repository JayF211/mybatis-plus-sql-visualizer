// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/psi/XmlCrossNsFragmentResolver.kt 移植
package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.xml.CrossNamespaceFragments;
import cn.song.mybatisplus.visualizer.xml.XmlIncludeScan;
import cn.song.mybatisplus.visualizer.xml.XmlMapperFragments;
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨命名空间 include 片段解析（T-IC-02，O-30 / zMyBatis Z-8）。
 *
 * <p>同文件 include 展开已由 {@link cn.song.mybatisplus.visualizer.xml.XmlIncludeExpander} 完整实现；
 * 本解析器补 {@code refid="otherNamespace.sqlId"} 的全限定形态：本文件查表失败且 refid 含 {@code .} 时，
 * 按<b>最后一个 {@code .}</b> 切 targetNamespace / fragmentId（对齐 MyBatis {@code applyCurrentNamespace(refid, isReference=true)}
 * 「refid 已含点即按全限定使用」语义），项目内扫 mapper XML 按其根 {@code namespace} 匹配建跨文件片段表，
 * 交展开器合并展开（{@link CrossNamespaceFragments}）。</p>
 *
 * <p>流程：</p>
 * <ol>
 *  <li>{@link XmlMapperParser#scanIncludes} 取当前文本根 namespace / 本文件片段 id / 含 {@code .} 的 refid（property 已静态替换）；</li>
 *  <li>无候选（或不含 {@code .} 的 refid）→ 空表：<b>惰性触发</b>，无跨命名空间引用的文件零索引遍历；</li>
 *  <li>{@link FileTypeIndex} 扫项目内 XML（{@code MapperXmlLocator}/{@code cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlEntityResolver} 同款先例），
 *      按 PSI 根 {@code <mapper>} + {@code namespace} 预筛，再 {@link XmlMapperParser#collectFragments} 收集片段；</li>
 *  <li>结果按 refid 归并：唯一命中 → 片段文本；未找到 / 同名片段多文件重复声明（歧义）→ 失败原因
 *      （展开器据此在预览警示中给出具体原因，不新增 UI 通道）。</li>
 * </ol>
 *
 * <p>边界（v1，如实记录）：</p>
 * <ul>
 *  <li>只做 {@code namespace.id} 全限定 refid，不做裸 id 全局搜索（与 MyBatis 运行时语义一致：裸 id 属当前 namespace）；
 *      跨文件片段内的<b>裸</b> refid 由引用方本文件片段表解析（MyBatis 同语义）——该片段只在同 namespace 的
 *      其它文件里时降级，v1 不覆盖（补 {@code 当前namespace.裸id} 候选需展开期持有当前 namespace，改动面更大）；</li>
 *  <li>无 project（纯文本链，含 MCP 片段直渲）→ 空表，沿用改动前的降级文案与行为；</li>
 *  <li>扫描范围为 projectScope（本入口只拿到文件文本、拿不到当前文件路径，无法按同目录收窄）；
 *      遍历仅在「本文件存在未解析的全限定 refid」时发生，且以 PSI 预筛避免对非 mapper XML 做 DOM 解析；</li>
 *  <li>同 namespace 声明在多个文件（非法但存在）→ 歧义降级；MyBatis 运行时为后注册覆盖，差异如实记录；</li>
 *  <li>目标文件未落盘（新建未保存）时不在索引内 → 该 refid 降级（预览不中断）。</li>
 * </ul>
 *
 * <p>线程：内部索引查询与 PSI 只读均包在 {@link ReadAction} 内（EDT 与后台线程均可直调）；任何异常按空表降级，绝不抛出。</p>
 */
public final class XmlCrossNsFragmentResolver {

    public static final XmlCrossNsFragmentResolver INSTANCE = new XmlCrossNsFragmentResolver();

    private XmlCrossNsFragmentResolver() {
    }

    /**
     * 解析当前 mapper 文本中的跨命名空间 include 片段。
     *
     * @param fileText 当前 mapper 文件文本（PSI 链取 {@code file.text}；纯文本链无 project 时直接返回空表）
     * @param project  当前项目；null 表示无 IDE 上下文（纯文本链）→ 空表
     */
    public CrossNamespaceFragments resolve(String fileText, Project project) {
        if (project == null) return CrossNamespaceFragments.EMPTY;
        XmlIncludeScan scan;
        try {
            scan = new XmlMapperParser().scanIncludes(fileText);
        } catch (Throwable e) { // Kotlin runCatching 语义：任何异常按空表降级
            return CrossNamespaceFragments.EMPTY;
        }
        if (scan == null || scan.dottedRefIds().isEmpty()) return CrossNamespaceFragments.EMPTY;
        Set<String> targets = new LinkedHashSet<>();
        for (String refId : scan.dottedRefIds()) {
            targets.add(substringBeforeLast(refId, '.'));
        }
        List<XmlMapperFragments> files;
        try {
            files = ReadAction.compute(() -> mapperFragments(project, targets));
        } catch (Throwable e) { // Kotlin runCatching.getOrDefault(emptyList()) 语义
            files = new ArrayList<>();
        }
        return collect(scan, files);
    }

    /**
     * 纯逻辑归并（可测）：候选 mapper 片段表 → 全限定 refid 片段表 + 失败原因表。
     *
     * <p>判定顺序：目标 namespace 无文件 → 有文件但无该片段 → 同名片段多文件重复（歧义）→ 唯一命中。</p>
     */
    public CrossNamespaceFragments collect(XmlIncludeScan scan, List<XmlMapperFragments> files) {
        if (scan.dottedRefIds().isEmpty()) return CrossNamespaceFragments.EMPTY;
        // files.filter { !it.namespace.isNullOrEmpty() }.groupBy { it.namespace.orEmpty() }
        Map<String, List<XmlMapperFragments>> byNamespace = new HashMap<>();
        for (XmlMapperFragments file : files) {
            if (file.namespace() == null || file.namespace().isEmpty()) continue;
            byNamespace.computeIfAbsent(file.namespace(), k -> new ArrayList<>()).add(file);
        }
        Map<String, String> fragments = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> sortedRefIds = new ArrayList<>(scan.dottedRefIds());
        sortedRefIds.sort(null);
        for (String refId : sortedRefIds) {
            String targetNamespace = substringBeforeLast(refId, '.');
            String fragmentId = substringAfterLast(refId, '.');
            if (targetNamespace.isEmpty() || fragmentId.isEmpty()) {
                failures.put(refId, "refid 形态非法");
                continue;
            }
            List<XmlMapperFragments> candidates =
                    byNamespace.getOrDefault(targetNamespace, new ArrayList<>());
            List<XmlMapperFragments> owners = new ArrayList<>();
            for (XmlMapperFragments candidate : candidates) {
                if (candidate.fragments().containsKey(fragmentId)) owners.add(candidate);
            }
            if (candidates.isEmpty()) {
                failures.put(refId, "未找到 namespace 为 " + targetNamespace + " 的 mapper 文件");
            } else if (owners.isEmpty()) {
                failures.put(refId, "namespace " + targetNamespace + " 下无片段 " + fragmentId);
            } else if (owners.size() > 1) {
                failures.put(refId, "片段 " + targetNamespace + "." + fragmentId
                        + " 在 " + owners.size() + " 个 mapper 文件中重复声明（歧义）");
            } else {
                fragments.put(refId, owners.get(0).fragments().get(fragmentId));
            }
        }
        return new CrossNamespaceFragments(fragments, failures);
    }

    /** 项目内 mapper XML 片段表（仅 namespace 命中 {@code targets} 者；PSI 预筛后按文本做 DOM 片段收集）。 */
    private List<XmlMapperFragments> mapperFragments(Project project, Set<String> targets) {
        var xmlFileType = FileTypeManager.getInstance().getStdFileType("XML");
        var parser = new XmlMapperParser();
        List<XmlMapperFragments> result = new ArrayList<>();
        for (var virtualFile : FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))) {
            if (!virtualFile.getName().toLowerCase().endsWith(".xml")) continue;
            var file = PsiManager.getInstance(project).findFile(virtualFile);
            if (!(file instanceof XmlFile xmlFile)) continue;
            var rootTag = xmlFile.getRootTag();
            if (rootTag == null || !"mapper".equals(rootTag.getName())) continue;
            String namespace = rootTag.getAttributeValue("namespace");
            if (namespace == null || !targets.contains(namespace)) continue;
            XmlMapperFragments mapped = parser.collectMapperFragments(xmlFile.getText());
            if (mapped == null) continue;
            if (mapped.namespace() == null || !targets.contains(mapped.namespace())) continue;
            result.add(mapped);
        }
        return result;
    }

    /** Kotlin {@code substringBeforeLast}：无分隔符时返回原串。 */
    private static String substringBeforeLast(String s, char delim) {
        int idx = s.lastIndexOf(delim);
        return idx < 0 ? s : s.substring(0, idx);
    }

    /** Kotlin {@code substringAfterLast}：无分隔符时返回原串。 */
    private static String substringAfterLast(String s, char delim) {
        int idx = s.lastIndexOf(delim);
        return idx < 0 ? s : s.substring(idx + 1);
    }
}
