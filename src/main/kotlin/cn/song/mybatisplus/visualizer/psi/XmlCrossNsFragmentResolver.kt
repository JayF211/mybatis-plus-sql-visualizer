package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.xml.CrossNamespaceFragments
import cn.song.mybatisplus.visualizer.xml.XmlIncludeScan
import cn.song.mybatisplus.visualizer.xml.XmlMapperFragments
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile

/**
 * 跨命名空间 include 片段解析（T-IC-02，O-30 / zMyBatis Z-8）。
 *
 * 同文件 include 展开已由 [cn.song.mybatisplus.visualizer.xml.XmlIncludeExpander] 完整实现；
 * 本解析器补 `refid="otherNamespace.sqlId"` 的全限定形态：本文件查表失败且 refid 含 `.` 时，
 * 按**最后一个 `.`** 切 targetNamespace / fragmentId（对齐 MyBatis `applyCurrentNamespace(refid, isReference=true)`
 * 「refid 已含点即按全限定使用」语义），项目内扫 mapper XML 按其根 `namespace` 匹配建跨文件片段表，
 * 交展开器合并展开（[CrossNamespaceFragments]）。
 *
 * 流程：
 *  1. [XmlMapperParser.scanIncludes] 取当前文本根 namespace / 本文件片段 id / 含 `.` 的 refid（property 已静态替换）；
 *  2. 无候选（或不含 `.` 的 refid）→ 空表：**惰性触发**，无跨命名空间引用的文件零索引遍历；
 *  3. [FileTypeIndex] 扫项目内 XML（[MapperXmlLocator]/[cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlEntityResolver] 同款先例），
 *     按 PSI 根 `<mapper>` + `namespace` 预筛，再 [XmlMapperParser.collectFragments] 收集片段；
 *  4. 结果按 refid 归并：唯一命中 → 片段文本；未找到 / 同名片段多文件重复声明（歧义）→ 失败原因
 *     （展开器据此在预览警示中给出具体原因，不新增 UI 通道）。
 *
 * 边界（v1，如实记录）：
 *  - 只做 `namespace.id` 全限定 refid，不做裸 id 全局搜索（与 MyBatis 运行时语义一致：裸 id 属当前 namespace）；
 *    跨文件片段内的**裸** refid 由引用方本文件片段表解析（MyBatis 同语义）——该片段只在同 namespace 的
 *    其它文件里时降级，v1 不覆盖（补 `当前namespace.裸id` 候选需展开期持有当前 namespace，改动面更大）；
 *  - 无 project（纯文本链，含 MCP 片段直渲）→ 空表，沿用改动前的降级文案与行为；
 *  - 扫描范围为 projectScope（本入口只拿到文件文本、拿不到当前文件路径，无法按同目录收窄）；
 *    遍历仅在「本文件存在未解析的全限定 refid」时发生，且以 PSI 预筛避免对非 mapper XML 做 DOM 解析；
 *  - 同 namespace 声明在多个文件（非法但存在）→ 歧义降级；MyBatis 运行时为后注册覆盖，差异如实记录；
 *  - 目标文件未落盘（新建未保存）时不在索引内 → 该 refid 降级（预览不中断）。
 *
 * 线程：内部索引查询与 PSI 只读均包在 [ReadAction] 内（EDT 与后台线程均可直调）；任何异常按空表降级，绝不抛出。
 */
object XmlCrossNsFragmentResolver {

    /**
     * 解析当前 mapper 文本中的跨命名空间 include 片段。
     *
     * @param fileText 当前 mapper 文件文本（PSI 链取 `file.text`；纯文本链无 project 时直接返回空表）
     * @param project  当前项目；null 表示无 IDE 上下文（纯文本链）→ 空表
     */
    fun resolve(fileText: String, project: Project?): CrossNamespaceFragments {
        if (project == null) return CrossNamespaceFragments.EMPTY
        val scan = runCatching { XmlMapperParser().scanIncludes(fileText) }.getOrNull()
            ?: return CrossNamespaceFragments.EMPTY
        if (scan.dottedRefIds.isEmpty()) return CrossNamespaceFragments.EMPTY
        val targets = scan.dottedRefIds.mapTo(mutableSetOf()) { it.substringBeforeLast('.') }
        val files = runCatching {
            ReadAction.compute<List<XmlMapperFragments>, RuntimeException> { mapperFragments(project, targets) }
        }.getOrDefault(emptyList())
        return collect(scan, files)
    }

    /**
     * 纯逻辑归并（可测）：候选 mapper 片段表 → 全限定 refid 片段表 + 失败原因表。
     *
     * 判定顺序：目标 namespace 无文件 → 有文件但无该片段 → 同名片段多文件重复（歧义）→ 唯一命中。
     */
    internal fun collect(scan: XmlIncludeScan, files: List<XmlMapperFragments>): CrossNamespaceFragments {
        if (scan.dottedRefIds.isEmpty()) return CrossNamespaceFragments.EMPTY
        val byNamespace = files.filter { !it.namespace.isNullOrEmpty() }.groupBy { it.namespace.orEmpty() }
        val fragments = mutableMapOf<String, String>()
        val failures = mutableMapOf<String, String>()
        for (refId in scan.dottedRefIds.sorted()) {
            val targetNamespace = refId.substringBeforeLast('.')
            val fragmentId = refId.substringAfterLast('.')
            if (targetNamespace.isEmpty() || fragmentId.isEmpty()) {
                failures[refId] = "refid 形态非法"
                continue
            }
            val candidates = byNamespace[targetNamespace].orEmpty()
            val owners = candidates.filter { it.fragments.containsKey(fragmentId) }
            when {
                candidates.isEmpty() ->
                    failures[refId] = "未找到 namespace 为 $targetNamespace 的 mapper 文件"
                owners.isEmpty() ->
                    failures[refId] = "namespace $targetNamespace 下无片段 $fragmentId"
                owners.size > 1 ->
                    failures[refId] = "片段 $targetNamespace.$fragmentId 在 ${owners.size} 个 mapper 文件中重复声明（歧义）"
                else -> fragments[refId] = owners.single().fragments.getValue(fragmentId)
            }
        }
        return CrossNamespaceFragments(fragments, failures)
    }

    /** 项目内 mapper XML 片段表（仅 namespace 命中 [targets] 者；PSI 预筛后按文本做 DOM 片段收集）。 */
    private fun mapperFragments(project: Project, targets: Set<String>): List<XmlMapperFragments> {
        val xmlFileType = FileTypeManager.getInstance().getStdFileType("XML")
        val parser = XmlMapperParser()
        return FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
            .asSequence()
            .filter { it.name.endsWith(".xml", ignoreCase = true) }
            .mapNotNull { virtualFile -> PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile }
            .filter { it.rootTag?.name == "mapper" }
            .filter { file ->
                val namespace = file.rootTag?.getAttributeValue("namespace")
                namespace != null && namespace in targets
            }
            .mapNotNull { file -> parser.collectMapperFragments(file.text) }
            .filter { file ->
                val namespace = file.namespace
                namespace != null && namespace in targets
            }
            .toList()
    }
}
