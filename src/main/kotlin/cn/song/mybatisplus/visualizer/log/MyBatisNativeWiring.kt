package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * R24：MyBatis 原生（非 Spring Boot）项目的 SqlSourceInterceptor 装配内核。
 *
 * Spring Boot 路径的装配点 = Spring 容器（@Configuration + SmartInitializingSingleton 把拦截器
 * 追加进 MybatisPlusInterceptor bean）；原生路径的装配点 = **mybatis-config.xml 的 `<plugins>`**
 * ——MyBatis 读 `<plugin interceptor="FQCN">` 时按无参构造实例化并挂进拦截器链。拦截器本体
 * （`SqlSourceInterceptor implements InnerInterceptor`）不是原生 MyBatis 的 `Interceptor`，
 * 不能直接写进 `<plugin>`，故由一个薄包装（[NATIVE_PLUGIN_FILE_NAME]，实现
 * org.apache.ibatis.plugin.Interceptor）持有并分发——两文件见 [SourceInterceptorTemplates]。
 *
 * fail-closed 边界（任务口径）：检测不到 config、config 多处、根标签不是 mybatis 的
 * `<configuration>`、插件已注册——一律不写 XML；前三者回 [Decision] 交调用方走既有
 * UnsupportedProject 兜底（引导文案不变），不猜不抢。
 *
 * 纯决策与 XML 编辑在此分层：[decide]/[pluginFqcn] 是平台无关纯函数（裸 JUnit 直测），
 * [findConfigCandidates]/[registerPluginInConfig] 是 VFS/XML PSI 薄接线（fixture 直测）。
 */
object MyBatisNativeWiring {

    /** 原生包装插件类文件名（落在 `<basePackage>.config` 包，与拦截器本体同包）。 */
    const val NATIVE_PLUGIN_FILE_NAME = "SqlVisualizerNativePlugin.java"

    /** mybatis-config.xml 的候选文件名（MyBatis / MyBatis-Plus 官方文档惯用名，精确匹配）。 */
    val CONFIG_FILE_CANDIDATE_NAMES: Set<String> = sortedSetOf("mybatis-config.xml", "mybatis.xml")

    /** 原生包装插件的全限定名（`<plugin interceptor="…">` 的注册值）。 */
    fun pluginFqcn(basePackage: String): String =
        "$basePackage.config.${NATIVE_PLUGIN_FILE_NAME.removeSuffix(".java")}"

    /** 装配决策（fail-closed 四路 + 一路放行）。 */
    enum class Decision {
        /** 检测不到 mybatis-config.xml——不注入（引导文案照旧）。 */
        NO_CONFIG,

        /** config 多处（多个候选文件）——无法判定运行时生效的是哪份，不注入。 */
        AMBIGUOUS_CONFIG,

        /** 唯一候选存在但根标签不是 `<configuration>`（不是 MyBatis 配置或解析不出根）。 */
        NOT_MYBATIS_CONFIG,

        /** `<plugins>` 里已注册本插件——幂等跳过（XML 不动，文件补齐仍由调用方处理）。 */
        ALREADY_REGISTERED,

        /** 放行：唯一 config、根 `<configuration>`、未注册——执行注入。 */
        INJECT,
    }

    /**
     * 纯决策：候选数 / 唯一候选的根标签名 / 已注册与否 → [Decision]。
     * 顺序即优先级：先判 config 存在性与唯一性（结构性 fail-closed 最优先），
     * 再判根标签，最后幂等。
     */
    fun decide(configCount: Int, rootTagName: String?, alreadyRegistered: Boolean): Decision = when {
        configCount <= 0 -> Decision.NO_CONFIG
        configCount > 1 -> Decision.AMBIGUOUS_CONFIG
        rootTagName != "configuration" -> Decision.NOT_MYBATIS_CONFIG
        alreadyRegistered -> Decision.ALREADY_REGISTERED
        else -> Decision.INJECT
    }

    /**
     * 在 [sourceRoot]（模块主源根，`src/main/java`）的**同级 resources 目录**下递归找
     * [CONFIG_FILE_CANDIDATE_NAMES] 精确同名文件（运行时 classpath 根的惯用位置）。
     * 找不到 resources 目录 → 空清单（fail-closed）；同名多份（含不同目录）→ 全部返回，
     * 由 [decide] 按 AMBIGUOUS_CONFIG 拒绝。
     */
    fun findConfigCandidates(sourceRoot: VirtualFile): List<VirtualFile> {
        val resources = sourceRoot.parent?.findChild("resources")?.takeIf { it.isDirectory }
            ?: return emptyList()
        val out = mutableListOf<VirtualFile>()
        walk(resources, out)
        return out
    }

    private fun walk(dir: VirtualFile, out: MutableList<VirtualFile>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                walk(child, out)
            } else if (child.name in CONFIG_FILE_CANDIDATE_NAMES) {
                out.add(child)
            }
        }
    }

    /** 候选文件解析为 XmlFile 并取根标签名（非 XML / 无根 → null，交 [decide] 判 NOT_MYBATIS_CONFIG）。 */
    fun rootTagName(psiManager: PsiManager, file: VirtualFile): String? {
        val xmlFile = PsiManager.getInstance(psiManager.project).findFile(file) as? XmlFile ?: return null
        return xmlFile.rootTag?.name
    }

    /** `<plugins>` 里是否已注册 [fqcn]（幂等判据；无 `<plugins>` 即未注册）。 */
    fun isPluginRegistered(rootTag: XmlTag, fqcn: String): Boolean =
        rootTag.findFirstSubTag("plugins")
            ?.findSubTags("plugin")
            ?.any { it.getAttributeValue("interceptor") == fqcn } == true

    /**
     * 把 `<plugin interceptor="[fqcn]"/>` 注册进 [xmlFile] 的 `<configuration>/<plugins>`。
     *
     * `<plugins>` 缺席时新建，插入位按 mybatis-config DTD 习惯序（properties/settings/typeAliases
     * 之后；否则后置元素之前；全空则追加）。MyBatis 的 XMLConfigBuilder 按 evalNode 按名取节、
     * 不做 DTD 校验，位置不影响解析——此处照顾人读习惯。已注册（幂等）或根不是
     * `<configuration>` → false（调用方不视为失败：幂等是预期态）。
     *
     * @return true = 实际写入了 XML；false = 幂等跳过或拒绝写入
     */
    fun registerPluginInConfig(xmlFile: XmlFile, fqcn: String): Boolean {
        val root = xmlFile.rootTag ?: return false
        if (root.name != "configuration") return false
        if (isPluginRegistered(root, fqcn)) return false
        var plugins = root.findFirstSubTag("plugins")
        if (plugins == null) {
            val created = root.createChildTag("plugins", null, null, false)
            val after = listOf("properties", "settings", "typeAliases")
                .mapNotNull { root.findFirstSubTag(it) }
                .lastOrNull()
            // createChildTag 产物是**游离**标签——addAfter/addBefore/addSubTag 返回挂载后的副本，
            // 后续 addSubTag 必须落在挂载副本上（对游离副本追加 = 静默丢失，fixture 实证）
            plugins = when {
                after != null -> root.addAfter(created, after) as XmlTag
                else -> {
                    val before = listOf(
                        "objectFactory", "objectWrapperFactory", "reflectorFactory",
                        "environments", "databaseIdProvider", "mappers",
                    ).mapNotNull { root.findFirstSubTag(it) }.firstOrNull()
                    when {
                        before != null -> root.addBefore(created, before) as XmlTag
                        else -> root.addSubTag(created, false)
                    }
                }
            }
        }
        val plugin = plugins.createChildTag("plugin", null, null, false)
        plugin.setAttribute("interceptor", fqcn)
        plugins.addSubTag(plugin, false)
        return true
    }
}
