package cn.song.mybatisplus.visualizer.sql

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/** DatabaseTools 只读上下文桥：反射隔离可选插件，不持久化 JDBC URL 或平台对象。 */
internal object IdeaSqlContextBridge {
    enum class Availability { AVAILABLE, DATABASE_TOOLS_UNAVAILABLE, FAILED }
    enum class ScopeOrigin { PROVIDED, DEFAULT_ALL_DATA_SOURCES, NONE, UNAVAILABLE }

    data class DataSourceCandidate(
        val stableId: String,
        val displayName: String,
        val dialectName: String?,
        val platformLanguageId: String?,
        val valid: Boolean,
    )

    data class DialectFacts(
        val availability: Availability,
        val effectiveDialectId: String?,
        val configuredDialectId: String?,
        val immediateDialectId: String?,
        val diagnostic: String? = null,
    )

    data class Snapshot(
        val availability: Availability,
        val actualSqlPsi: Boolean,
        val scopeOrigin: ScopeOrigin,
        val configuredScopePresent: Boolean,
        val providerCandidateIds: List<String>,
        val finalCandidates: List<DataSourceCandidate>,
        val effectiveDialectId: String?,
        val configuredDialectId: String?,
        val immediateDialectId: String?,
        val diagnostic: String? = null,
        /** 平台返回但缺稳定 UUID 的候选数；不得把其余候选误判为唯一。 */
        val unidentifiedCandidateCount: Int = 0,
    )

    internal data class RawCandidate(
        val stableId: String?,
        val displayName: String?,
        val jdbcUrl: String?,
        val platformLanguageId: String?,
        val valid: Boolean,
    )

    internal data class RawFacts(
        val provided: List<RawCandidate> = emptyList(),
        val finalCandidates: List<RawCandidate> = emptyList(),
        val configuredScopePresent: Boolean = false,
        val effectiveDialectId: String? = null,
        val configuredDialectId: String? = null,
        val immediateDialectId: String? = null,
    )

    @Volatile
    internal var actualSqlFileForTest: ((PsiFile) -> Boolean)? = null

    @Volatile
    internal var rawFactsForTest: ((Project, PsiFile, VirtualFile) -> RawFacts)? = null

    @Volatile
    internal var dialectFactsForTest: ((Project, VirtualFile) -> DialectFacts)? = null

    fun resolveDialect(project: Project, file: VirtualFile): DialectFacts {
        dialectFactsForTest?.let { return it(project, file) }
        val loader = IdeaSqlContextBridge::class.java.classLoader
        val mappingsClass = runCatching {
            Class.forName("com.intellij.sql.dialects.SqlDialectMappings", false, loader)
        }.getOrNull() ?: return DialectFacts(Availability.DATABASE_TOOLS_UNAVAILABLE, null, null, null)
        return runCatching {
            val mappings = mappingsClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val effective = mappingsClass.getMethod("getMapping", Project::class.java, VirtualFile::class.java)
                .invoke(null, project, file)
            val configured = mappings.javaClass.getMethod("getConfiguredMapping", VirtualFile::class.java)
                .invoke(mappings, file)
            val immediate = mappings.javaClass.getMethod("getImmediateMapping", VirtualFile::class.java)
                .invoke(mappings, file)
            DialectFacts(Availability.AVAILABLE, languageId(effective), languageId(configured), languageId(immediate))
        }.getOrElse { error ->
            DialectFacts(Availability.FAILED, null, null, null, error.javaClass.simpleName)
        }
    }

    fun resolve(project: Project, sqlPsiFile: PsiFile?, dialectFile: VirtualFile?): Snapshot {
        if (sqlPsiFile == null || dialectFile == null) return unavailable(actualSqlPsi = false)
        val actual = isActualSqlFile(sqlPsiFile)
        if (!actual) return unavailable(actualSqlPsi = false)
        // injected SQL 的 VirtualFileWindow 直接交给平台 mappings；平台负责 delegate/original 归一化。
        val sqlDialectFile = sqlPsiFile.virtualFile ?: dialectFile
        rawFactsForTest?.let { provider ->
            return fromRaw(provider(project, sqlPsiFile, sqlDialectFile), Availability.AVAILABLE, true)
        }
        val loader = IdeaSqlContextBridge::class.java.classLoader
        val dataSourceMappingsClass = runCatching {
            Class.forName("com.intellij.sql.dialects.SqlDataSourceMappings", false, loader)
        }.getOrNull() ?: return unavailable(true)
        return runCatching {
            val dataSourceMappings = dataSourceMappingsClass.getMethod("getInstance", Project::class.java)
                .invoke(null, project)
            val provided = invokeDataSources(dataSourceMappingsClass, dataSourceMappings, "getProvidedDataSources", sqlPsiFile)
            val finalCandidates = invokeDataSources(dataSourceMappingsClass, dataSourceMappings, "getDataSources", sqlPsiFile)

            val resolveMappingsClass = Class.forName("com.intellij.sql.dialects.SqlResolveMappings", false, loader)
            val resolveMappings = resolveMappingsClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val configuredScope = resolveMappings.javaClass
                .getMethod("getConfiguredMapping", VirtualFile::class.java).invoke(resolveMappings, sqlDialectFile)

            val dialectMappingsClass = Class.forName("com.intellij.sql.dialects.SqlDialectMappings", false, loader)
            val dialectMappings = dialectMappingsClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val effective = dialectMappingsClass.getMethod("getMapping", Project::class.java, VirtualFile::class.java)
                .invoke(null, project, sqlDialectFile)
            val configured = dialectMappings.javaClass
                .getMethod("getConfiguredMapping", VirtualFile::class.java).invoke(dialectMappings, sqlDialectFile)
            val immediate = dialectMappings.javaClass
                .getMethod("getImmediateMapping", VirtualFile::class.java).invoke(dialectMappings, sqlDialectFile)

            fromRaw(
                RawFacts(
                    provided = provided,
                    finalCandidates = finalCandidates,
                    configuredScopePresent = configuredScope != null,
                    effectiveDialectId = languageId(effective),
                    configuredDialectId = languageId(configured),
                    immediateDialectId = languageId(immediate),
                ),
                Availability.AVAILABLE,
                true,
            )
        }.getOrElse { error ->
            unavailable(true, Availability.FAILED, error.javaClass.simpleName)
        }
    }

    internal fun fromRaw(raw: RawFacts, availability: Availability = Availability.AVAILABLE,
                         actualSqlPsi: Boolean = true): Snapshot {
        val provided = raw.provided.mapNotNull(::candidate).distinctBy { it.stableId }
        val final = raw.finalCandidates.mapNotNull(::candidate).distinctBy { it.stableId }
        val origin = when {
            raw.provided.isNotEmpty() -> ScopeOrigin.PROVIDED
            // getDataSources 返回方言筛选候选，不承诺已按 SqlResolveMappings 缩窄。
            // 在没有真实 provider 的情况下，仅“存在配置”不能升级为精确绑定证据。
            raw.configuredScopePresent -> ScopeOrigin.UNAVAILABLE
            raw.finalCandidates.isNotEmpty() -> ScopeOrigin.DEFAULT_ALL_DATA_SOURCES
            else -> ScopeOrigin.NONE
        }
        return Snapshot(
            availability = availability,
            actualSqlPsi = actualSqlPsi,
            scopeOrigin = origin,
            configuredScopePresent = raw.configuredScopePresent,
            providerCandidateIds = provided.map { it.stableId }.sorted(),
            finalCandidates = final.sortedBy { it.stableId },
            effectiveDialectId = raw.effectiveDialectId,
            configuredDialectId = raw.configuredDialectId,
            immediateDialectId = raw.immediateDialectId,
            unidentifiedCandidateCount = raw.finalCandidates.count { it.stableId.isNullOrBlank() },
        )
    }

    private fun unavailable(actualSqlPsi: Boolean, availability: Availability = Availability.DATABASE_TOOLS_UNAVAILABLE,
                            diagnostic: String? = null): Snapshot = Snapshot(
        availability = availability,
        actualSqlPsi = actualSqlPsi,
        scopeOrigin = ScopeOrigin.UNAVAILABLE,
        configuredScopePresent = false,
        providerCandidateIds = emptyList(),
        finalCandidates = emptyList(),
        effectiveDialectId = null,
        configuredDialectId = null,
        immediateDialectId = null,
        diagnostic = diagnostic,
    )

    private fun isActualSqlFile(file: PsiFile): Boolean = actualSqlFileForTest?.invoke(file) ?: runCatching {
        val sqlFile = Class.forName("com.intellij.sql.psi.SqlFile", false, IdeaSqlContextBridge::class.java.classLoader)
        sqlFile.isInstance(file)
    }.getOrDefault(false)

    private fun invokeDataSources(ownerClass: Class<*>, owner: Any, method: String, file: PsiFile): List<RawCandidate> {
        val values = ownerClass.getMethod(method, PsiFile::class.java).invoke(owner, file) as? List<*> ?: return emptyList()
        return values.filterNotNull().map(::readCandidate)
    }

    private fun readCandidate(value: Any): RawCandidate {
        val loader = IdeaSqlContextBridge::class.java.classLoader
        val dbDataSource = Class.forName("com.intellij.database.psi.DbDataSource", false, loader)
        val id = dbDataSource.getMethod("getUniqueId").invoke(value) as? String
        val name = dbDataSource.getMethod("getName").invoke(value) as? String
        val language = dbDataSource.getMethod("getQueryLanguage").invoke(value)
        val valid = (dbDataSource.getMethod("isValid").invoke(value) as? Boolean) == true
        val config = dbDataSource.getMethod("getConnectionConfig").invoke(value)
        val url = config?.let {
            val rawConfig = Class.forName("com.intellij.database.model.RawConnectionConfig", false, loader)
            rawConfig.getMethod("getUrl").invoke(it) as? String
        }
        return RawCandidate(id, name, url, languageId(language), valid)
    }

    private fun candidate(raw: RawCandidate): DataSourceCandidate? {
        val id = raw.stableId?.takeIf { it.isNotBlank() } ?: return null
        val dialect = UrlSubprotocolDialects.dialectName(raw.jdbcUrl)
            ?: pluginDialectName(raw.platformLanguageId)
        return DataSourceCandidate(
            stableId = id,
            displayName = raw.displayName.orEmpty(),
            dialectName = dialect,
            platformLanguageId = raw.platformLanguageId,
            valid = raw.valid,
        )
    }

    private fun languageId(value: Any?): String? = (value as? com.intellij.lang.Language)?.id

    internal fun pluginDialectName(languageId: String?): String? = when {
        languageId.isNullOrBlank() -> null
        languageId.equals("TSQL", ignoreCase = true) -> "SQL Server"
        languageId.equals("GenericSQL", ignoreCase = true) ||
            languageId.equals("SQL", ignoreCase = true) ||
            languageId.equals("SQL92", ignoreCase = true) -> null
        else -> DialectRegistry.dialects.firstOrNull { it.name.equals(languageId, ignoreCase = true) }?.name
    }
}
