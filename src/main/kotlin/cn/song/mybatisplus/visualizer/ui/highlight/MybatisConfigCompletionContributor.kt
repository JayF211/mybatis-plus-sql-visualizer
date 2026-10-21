package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * T-XmlGap W6：mybatis-config.xml（配置文件）内的补全。
 *
 * 与 mapper XML 的分工：`XmlSqlCompletionContributor` 的根守卫是「最近语句标签 + 根 == mapper」，
 * 配置文件整类落在它之外（原状是零候选）。本类**独立成文件**（不改 `XmlSqlCompletionContributor`
 * ——W4/W5 波次正在改它，避免同文件冲突），根守卫为「根标签 == configuration」。
 *
 * 硬编码表来源（**全部实读 MyBatis 3.5.19 jar，禁凭记忆**）：
 *  - 29 个 `<setting name>` 键：`org.apache.ibatis.builder.xml.XMLConfigBuilder` 常量池中带
 *    `setXxx` 对应者的字面量（`javap -p -c` / 常量池扫描实测；`multipleResultSetsEnabled`
 *    在该版**已不存在**，故不入表）；
 *  - 布尔型 13 项、枚举型 6 项的值集：`javap -cp mybatis-3.5.19.jar org.apache.ibatis.session.
 *    {ExecutorType,AutoMappingBehavior,AutoMappingUnknownColumnBehavior,LocalCacheScope}、
 *    org.apache.ibatis.mapping.ResultSetType` 的 `public static final` 常量实测；
 *  - `jdbcTypeForNull` 的 41 个候选：`org.apache.ibatis.type.JdbcType` 常量实测。
 *
 * 形态对齐既有约定：`CompletionContributor` + `DumbAware`（T-XmlCmpl2 教训：缺该接口会在索引期
 * 被平台整体丢弃）；所有候选经 `prefixMatcher` 过滤；零索引依赖（纯静态表 + 同标签属性读取）。
 */
class MybatisConfigCompletionContributor : CompletionContributor(), DumbAware {

    init {
        extend(
            CompletionType.BASIC,
            completionPositionPattern,
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val position = parameters.position
                    val file = position.containingFile as? XmlFile ?: return
                    if (file.rootTag?.name != "configuration") return
                    val prefix = result.prefixMatcher

                    // 1. 属性值位
                    val value = PsiTreeUtil.getParentOfType(position, XmlAttributeValue::class.java, false)
                    if (value != null) {
                        val attr = value.parent as? XmlAttribute ?: return
                        val tag = attr.parent as? XmlTag ?: return
                        when {
                            tag.name == "setting" && attr.name == "name" ->
                                addCandidates(result, prefix, SETTING_NAMES)
                            tag.name == "setting" && attr.name == "value" -> {
                                val settingName = tag.getAttributeValue("name").orEmpty()
                                addCandidates(result, prefix, settingValueCandidates(settingName))
                            }
                            tag.name == "property" && attr.name == "name" -> Unit // 用户自定义键，无候选
                            // 类名属性（type/class/interceptor/typeHandler）暂无有界候选来源 → 不产噪音
                            else -> Unit
                        }
                        return
                    }

                    // 2. 标签名位（光标前 `<`）：按父标签给子标签名
                    val tag = PsiTreeUtil.getParentOfType(position, XmlTag::class.java) ?: return
                    val parentName = if (position.parent is XmlTag) null else tag.name
                    val siblingNames = configChildTags(parentName ?: tag.name)
                    if (siblingNames.isNotEmpty()) addCandidates(result, prefix, siblingNames)
                    else addCandidates(result, prefix, configChildTags(tag.parentTag?.name))
                }
            },
        )
    }

    private fun addCandidates(result: CompletionResultSet, prefix: com.intellij.codeInsight.completion.PrefixMatcher, names: List<String>) {
        for (name in names) if (prefix.prefixMatches(name)) result.addElement(LookupElementBuilder.create(name))
    }

    /** `<setting value>` 的候选：布尔表 / 枚举表 / jdbcType 表；未知键返回空。 */
    internal fun settingValueCandidates(settingName: String): List<String> = when {
        settingName in SETTING_BOOLEANS -> BOOLEAN_VALUES
        SETTING_ENUMS.containsKey(settingName) -> SETTING_ENUMS.getValue(settingName)
        settingName == "jdbcTypeForNull" -> JDBC_TYPE_NAMES
        else -> emptyList()
    }

    /** 类名属性（`type`/`class`/`interceptor` 等）暂无有界候选来源，返回空（不产噪音）。 */
    internal fun configChildTags(parentTagName: String?): List<String> = CONFIG_CHILD_TAGS[parentTagName].orEmpty()

    internal companion object {
        /**
         * T-PerfSweep26 F101：补全位置模式——限定 XML 语言元素。
         *
         * 原为无约束 `PlatformPatterns.psiElement()`：全 IDE 任意语言每次补全调用都进 provider 体，
         * 靠首行 `as? XmlFile` O(1) 早退兜底。`withLanguage` 是 [PsiElementPattern] 上最小可用的
         * 语言约束（243 sources jar `PsiElementPattern.java:268`，accepts 即 `t.getLanguage().equals(language)`）；
         * mybatis-config.xml 是标准 XML 语言，文件内任意补全位（XmlToken 等）语言均为 XMLLanguage，
         * 约束后行为不变；注入片段（如 mapper.xml 里的 SQL）语言非 XML，本就不该进本贡献器。
         */
        internal val completionPositionPattern: PsiElementPattern.Capture<PsiElement> =
            PlatformPatterns.psiElement().withLanguage(XMLLanguage.INSTANCE)


        /** 29 个 `<setting name>` 键（MyBatis 3.5.19 实测，见类 KDoc）。 */
        internal val SETTING_NAMES: List<String> = listOf(
            "cacheEnabled", "lazyLoadingEnabled", "aggressiveLazyLoading", "useColumnLabel",
            "useGeneratedKeys", "autoMappingBehavior", "autoMappingUnknownColumnBehavior",
            "defaultExecutorType", "defaultStatementTimeout", "defaultFetchSize", "defaultResultSetType",
            "safeResultHandlerEnabled", "mapUnderscoreToCamelCase", "localCacheScope", "jdbcTypeForNull",
            "lazyLoadTriggerMethods", "defaultScriptingLanguage", "defaultEnumTypeHandler",
            "callSettersOnNulls", "useActualParamName", "returnInstanceForEmptyRow", "logPrefix",
            "logImpl", "vfsImpl", "configurationFactory", "shrinkWhitespacesInSql",
            "defaultSqlProviderType", "nullableOnForEach", "safeRowBoundsEnabled",
        )

        /** 布尔型设置（值候选 true/false）。 */
        internal val SETTING_BOOLEANS: Set<String> = setOf(
            "cacheEnabled", "lazyLoadingEnabled", "aggressiveLazyLoading", "useColumnLabel",
            "useGeneratedKeys", "safeResultHandlerEnabled", "mapUnderscoreToCamelCase",
            "callSettersOnNulls", "useActualParamName", "returnInstanceForEmptyRow",
            "shrinkWhitespacesInSql", "nullableOnForEach", "safeRowBoundsEnabled",
        )

        private val BOOLEAN_VALUES = listOf("true", "false")

        /** 枚举型设置的值候选（jar 内枚举常量实测）。 */
        internal val SETTING_ENUMS: Map<String, List<String>> = mapOf(
            "autoMappingBehavior" to listOf("NONE", "PARTIAL", "FULL"),
            "autoMappingUnknownColumnBehavior" to listOf("NONE", "WARNING", "FAILING"),
            "defaultExecutorType" to listOf("SIMPLE", "REUSE", "BATCH"),
            "defaultResultSetType" to listOf("DEFAULT", "FORWARD_ONLY", "SCROLL_INSENSITIVE", "SCROLL_SENSITIVE"),
            "localCacheScope" to listOf("SESSION", "STATEMENT"),
        )

        /** `jdbcTypeForNull`（以及 W4 的 `#{}` jdbcType 值表）——`org.apache.ibatis.type.JdbcType` 41 常量实测。 */
        internal val JDBC_TYPE_NAMES: List<String> = listOf(
            "ARRAY", "BIT", "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "FLOAT", "REAL", "DOUBLE",
            "NUMERIC", "DECIMAL", "CHAR", "VARCHAR", "LONGVARCHAR", "DATE", "TIME", "TIMESTAMP",
            "BINARY", "VARBINARY", "LONGVARBINARY", "NULL", "OTHER", "BLOB", "CLOB", "BOOLEAN",
            "CURSOR", "UNDEFINED", "NVARCHAR", "NCHAR", "NCLOB", "STRUCT", "JAVA_OBJECT", "DISTINCT",
            "REF", "DATALINK", "ROWID", "LONGNVARCHAR", "SQLXML", "DATETIMEOFFSET",
            "TIME_WITH_TIMEZONE", "TIMESTAMP_WITH_TIMEZONE",
        )

        private val CLASS_ATTRIBUTES = setOf("type", "class", "interceptor", "typeHandler")

        /** 根标签下一层的子标签（**必须先于 [CONFIG_CHILD_TAGS] 声明**——伴生对象按声明序初始化）。 */
        private val CONFIG_ROOT_CHILDREN = listOf(
            "properties", "settings", "typeAliases", "typeHandlers", "objectFactory",
            "objectWrapperFactory", "reflectorFactory", "plugins", "environments",
            "databaseIdProvider", "mappers",
        )

        /** 父子标签表：按父标签名给子标签名候选（`null` 键 = 根下一层）。 */
        internal val CONFIG_CHILD_TAGS: Map<String?, List<String>> = mapOf(
            null to CONFIG_ROOT_CHILDREN,
            "configuration" to CONFIG_ROOT_CHILDREN,
            "settings" to listOf("setting"),
            "typeAliases" to listOf("typeAlias", "package"),
            "typeHandlers" to listOf("typeHandler", "package"),
            "plugins" to listOf("plugin"),
            "objectFactory" to listOf("property"),
            "objectWrapperFactory" to emptyList(),
            "reflectorFactory" to emptyList(),
            "environments" to listOf("environment"),
            "environment" to listOf("transactionManager", "dataSource"),
            "transactionManager" to listOf("property"),
            "dataSource" to listOf("property"),
            "databaseIdProvider" to listOf("property"),
            "mappers" to listOf("mapper", "package"),
            "properties" to listOf("property"),
            "mapper" to emptyList(),
        )
    }
}
