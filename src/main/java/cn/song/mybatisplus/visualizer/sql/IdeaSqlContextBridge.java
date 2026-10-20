package cn.song.mybatisplus.visualizer.sql;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.lang.Language;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DatabaseTools 只读上下文桥：反射隔离可选插件，不持久化 JDBC URL 或平台对象。
 *
 * <p>master 的 {@code internal object IdeaSqlContextBridge} 的 Java 承载（全 static 形态）。</p>
 */
public final class IdeaSqlContextBridge {

    private IdeaSqlContextBridge() {
    }

    public enum Availability { AVAILABLE, DATABASE_TOOLS_UNAVAILABLE, FAILED }

    public enum ScopeOrigin { PROVIDED, DEFAULT_ALL_DATA_SOURCES, NONE, UNAVAILABLE }

    public record DataSourceCandidate(
            String stableId,
            String displayName,
            @Nullable String dialectName,
            @Nullable String platformLanguageId,
            boolean valid
    ) {
    }

    public record DialectFacts(
            Availability availability,
            @Nullable String effectiveDialectId,
            @Nullable String configuredDialectId,
            @Nullable String immediateDialectId,
            @Nullable String diagnostic
    ) {
        public DialectFacts(Availability availability, @Nullable String effectiveDialectId,
                            @Nullable String configuredDialectId, @Nullable String immediateDialectId) {
            this(availability, effectiveDialectId, configuredDialectId, immediateDialectId, null);
        }
    }

    public record Snapshot(
            Availability availability,
            boolean actualSqlPsi,
            ScopeOrigin scopeOrigin,
            boolean configuredScopePresent,
            List<String> providerCandidateIds,
            List<DataSourceCandidate> finalCandidates,
            @Nullable String effectiveDialectId,
            @Nullable String configuredDialectId,
            @Nullable String immediateDialectId,
            @Nullable String diagnostic,
            /** 平台返回但缺稳定 UUID 的候选数；不得把其余候选误判为唯一。 */
            int unidentifiedCandidateCount
    ) {
        public Snapshot {
            providerCandidateIds = providerCandidateIds == null ? List.of() : List.copyOf(providerCandidateIds);
            finalCandidates = finalCandidates == null ? List.of() : List.copyOf(finalCandidates);
        }

        public Snapshot(Availability availability, boolean actualSqlPsi, ScopeOrigin scopeOrigin,
                        boolean configuredScopePresent, List<String> providerCandidateIds,
                        List<DataSourceCandidate> finalCandidates, @Nullable String effectiveDialectId,
                        @Nullable String configuredDialectId, @Nullable String immediateDialectId) {
            this(availability, actualSqlPsi, scopeOrigin, configuredScopePresent, providerCandidateIds,
                    finalCandidates, effectiveDialectId, configuredDialectId, immediateDialectId, null, 0);
        }
    }

    static record RawCandidate(
            @Nullable String stableId,
            @Nullable String displayName,
            @Nullable String jdbcUrl,
            @Nullable String platformLanguageId,
            boolean valid
    ) {
    }

    static record RawFacts(
            List<RawCandidate> provided,
            List<RawCandidate> finalCandidates,
            boolean configuredScopePresent,
            @Nullable String effectiveDialectId,
            @Nullable String configuredDialectId,
            @Nullable String immediateDialectId
    ) {
        public RawFacts {
            provided = provided == null ? List.of() : List.copyOf(provided);
            finalCandidates = finalCandidates == null ? List.of() : List.copyOf(finalCandidates);
        }

        public RawFacts() {
            this(List.of(), List.of(), false, null, null, null);
        }
    }

    // ---------- 测试缝（Kotlin internal var → 包私有 volatile 静态字段） ----------

    public interface ActualSqlFilePredicate {
        boolean isActualSqlFile(PsiFile file);
    }

    public interface RawFactsProvider {
        RawFacts provide(Project project, PsiFile sqlPsiFile, VirtualFile sqlDialectFile);
    }

    public interface DialectFactsProvider {
        DialectFacts provide(Project project, VirtualFile file);
    }

    public static volatile ActualSqlFilePredicate actualSqlFileForTest = null;

    public static volatile RawFactsProvider rawFactsForTest = null;

    public static volatile DialectFactsProvider dialectFactsForTest = null;

    public static DialectFacts resolveDialect(Project project, VirtualFile file) {
        DialectFactsProvider testProvider = dialectFactsForTest;
        if (testProvider != null) {
            return testProvider.provide(project, file);
        }
        ClassLoader loader = IdeaSqlContextBridge.class.getClassLoader();
        Class<?> mappingsClass;
        try {
            mappingsClass = Class.forName("com.intellij.sql.dialects.SqlDialectMappings", false, loader);
        } catch (Throwable t) {
            if (t instanceof LinkageError && cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return new DialectFacts(Availability.DATABASE_TOOLS_UNAVAILABLE, null, null, null);
        }
        try {
            Object mappings = mappingsClass.getMethod("getInstance", Project.class).invoke(null, project);
            Object effective = mappingsClass.getMethod("getMapping", Project.class, VirtualFile.class)
                    .invoke(null, project, file);
            Object configured = mappings.getClass().getMethod("getConfiguredMapping", VirtualFile.class)
                    .invoke(mappings, file);
            Object immediate = mappings.getClass().getMethod("getImmediateMapping", VirtualFile.class)
                    .invoke(mappings, file);
            return new DialectFacts(Availability.AVAILABLE, languageId(effective), languageId(configured),
                    languageId(immediate));
        } catch (Throwable error) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(error)) {
                throw (Error) error;
            }
            return new DialectFacts(Availability.FAILED, null, null, null, error.getClass().getSimpleName());
        }
    }

    public static Snapshot resolve(Project project, @Nullable PsiFile sqlPsiFile, @Nullable VirtualFile dialectFile) {
        if (sqlPsiFile == null || dialectFile == null) {
            return unavailable(false, Availability.DATABASE_TOOLS_UNAVAILABLE, null);
        }
        if (!isActualSqlFile(sqlPsiFile)) {
            return unavailable(false, Availability.DATABASE_TOOLS_UNAVAILABLE, null);
        }
        // injected SQL 的 VirtualFileWindow 直接交给平台 mappings；平台负责 delegate/original 归一化。
        VirtualFile sqlDialectFile = sqlPsiFile.getVirtualFile() != null ? sqlPsiFile.getVirtualFile() : dialectFile;
        RawFactsProvider testProvider = rawFactsForTest;
        if (testProvider != null) {
            return fromRaw(testProvider.provide(project, sqlPsiFile, sqlDialectFile), Availability.AVAILABLE, true);
        }
        ClassLoader loader = IdeaSqlContextBridge.class.getClassLoader();
        Class<?> dataSourceMappingsClass;
        try {
            dataSourceMappingsClass = Class.forName("com.intellij.sql.dialects.SqlDataSourceMappings", false, loader);
        } catch (Throwable t) {
            if (t instanceof LinkageError && cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return unavailable(true, Availability.DATABASE_TOOLS_UNAVAILABLE, null);
        }
        try {
            Object dataSourceMappings = dataSourceMappingsClass.getMethod("getInstance", Project.class)
                    .invoke(null, project);
            List<RawCandidate> provided = invokeDataSources(dataSourceMappingsClass, dataSourceMappings,
                    "getProvidedDataSources", sqlPsiFile);
            List<RawCandidate> finalCandidates = invokeDataSources(dataSourceMappingsClass, dataSourceMappings,
                    "getDataSources", sqlPsiFile);

            Class<?> resolveMappingsClass = Class.forName("com.intellij.sql.dialects.SqlResolveMappings", false, loader);
            Object resolveMappings = resolveMappingsClass.getMethod("getInstance", Project.class)
                    .invoke(null, project);
            Object configuredScope = resolveMappings.getClass()
                    .getMethod("getConfiguredMapping", VirtualFile.class).invoke(resolveMappings, sqlDialectFile);

            Class<?> dialectMappingsClass = Class.forName("com.intellij.sql.dialects.SqlDialectMappings", false, loader);
            Object dialectMappings = dialectMappingsClass.getMethod("getInstance", Project.class)
                    .invoke(null, project);
            Object effective = dialectMappingsClass.getMethod("getMapping", Project.class, VirtualFile.class)
                    .invoke(null, project, sqlDialectFile);
            Object configured = dialectMappings.getClass()
                    .getMethod("getConfiguredMapping", VirtualFile.class).invoke(dialectMappings, sqlDialectFile);
            Object immediate = dialectMappings.getClass()
                    .getMethod("getImmediateMapping", VirtualFile.class).invoke(dialectMappings, sqlDialectFile);

            return fromRaw(
                    new RawFacts(provided, finalCandidates, configuredScope != null,
                            languageId(effective), languageId(configured), languageId(immediate)),
                    Availability.AVAILABLE,
                    true);
        } catch (Throwable error) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(error)) {
                throw (Error) error;
            }
            return unavailable(true, Availability.FAILED, error.getClass().getSimpleName());
        }
    }

    static Snapshot fromRaw(RawFacts raw, Availability availability, boolean actualSqlPsi) {
        List<DataSourceCandidate> provided = new ArrayList<>(new LinkedHashSet<>(
                mapCandidates(raw.provided())));
        List<DataSourceCandidate> finalCandidates = new ArrayList<>(new LinkedHashSet<>(
                mapCandidates(raw.finalCandidates())));
        ScopeOrigin origin;
        if (!raw.provided().isEmpty()) {
            origin = ScopeOrigin.PROVIDED;
        } else if (raw.configuredScopePresent()) {
            // getDataSources 返回方言筛选候选，不承诺已按 SqlResolveMappings 缩窄。
            // 在没有真实 provider 的情况下，仅「存在配置」不能升级为精确绑定证据。
            origin = ScopeOrigin.UNAVAILABLE;
        } else if (!raw.finalCandidates().isEmpty()) {
            origin = ScopeOrigin.DEFAULT_ALL_DATA_SOURCES;
        } else {
            origin = ScopeOrigin.NONE;
        }
        List<String> providerIds = new ArrayList<>();
        for (DataSourceCandidate c : provided) {
            providerIds.add(c.stableId());
        }
        providerIds.sort(Comparator.naturalOrder());
        List<DataSourceCandidate> sortedFinal = new ArrayList<>(finalCandidates);
        sortedFinal.sort(Comparator.comparing(DataSourceCandidate::stableId));
        int unidentified = 0;
        for (RawCandidate c : raw.finalCandidates()) {
            if (c.stableId() == null || c.stableId().isBlank()) {
                unidentified++;
            }
        }
        return new Snapshot(availability, actualSqlPsi, origin, raw.configuredScopePresent(),
                providerIds, sortedFinal, raw.effectiveDialectId(), raw.configuredDialectId(),
                raw.immediateDialectId(), null, unidentified);
    }

    private static List<DataSourceCandidate> mapCandidates(List<RawCandidate> raws) {
        List<DataSourceCandidate> result = new ArrayList<>();
        for (RawCandidate raw : raws) {
            DataSourceCandidate c = candidate(raw);
            if (c != null) {
                result.add(c);
            }
        }
        return result;
    }

    private static Snapshot unavailable(boolean actualSqlPsi, Availability availability,
                                        @Nullable String diagnostic) {
        return new Snapshot(availability, actualSqlPsi, ScopeOrigin.UNAVAILABLE, false,
                List.of(), List.of(), null, null, null, diagnostic, 0);
    }

    private static boolean isActualSqlFile(PsiFile file) {
        ActualSqlFilePredicate testPredicate = actualSqlFileForTest;
        if (testPredicate != null) {
            return testPredicate.isActualSqlFile(file);
        }
        try {
            Class<?> sqlFile = Class.forName("com.intellij.sql.psi.SqlFile", false,
                    IdeaSqlContextBridge.class.getClassLoader());
            return sqlFile.isInstance(file);
        } catch (Throwable t) {
            if (t instanceof LinkageError && cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return false;
        }
    }

    private static List<RawCandidate> invokeDataSources(Class<?> ownerClass, Object owner, String method,
                                                        PsiFile file) throws Exception {
        Object values = ownerClass.getMethod(method, PsiFile.class).invoke(owner, file);
        if (!(values instanceof List<?> list)) {
            return List.of();
        }
        List<RawCandidate> result = new ArrayList<>();
        for (Object value : list) {
            if (value != null) {
                result.add(readCandidate(value));
            }
        }
        return result;
    }

    private static RawCandidate readCandidate(Object value) throws Exception {
        ClassLoader loader = IdeaSqlContextBridge.class.getClassLoader();
        Class<?> dbDataSource = Class.forName("com.intellij.database.psi.DbDataSource", false, loader);
        String id = asString(dbDataSource.getMethod("getUniqueId").invoke(value));
        String name = asString(dbDataSource.getMethod("getName").invoke(value));
        Object language = dbDataSource.getMethod("getQueryLanguage").invoke(value);
        boolean valid = Boolean.TRUE.equals(dbDataSource.getMethod("isValid").invoke(value));
        Object config = dbDataSource.getMethod("getConnectionConfig").invoke(value);
        String url = null;
        if (config != null) {
            Class<?> rawConfig = Class.forName("com.intellij.database.model.RawConnectionConfig", false, loader);
            url = asString(rawConfig.getMethod("getUrl").invoke(config));
        }
        return new RawCandidate(id, name, url, languageId(language), valid);
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static DataSourceCandidate candidate(RawCandidate raw) {
        String id = raw.stableId() != null && !raw.stableId().isBlank() ? raw.stableId() : null;
        if (id == null) {
            return null;
        }
        String dialect = UrlSubprotocolDialects.dialectName(raw.jdbcUrl());
        if (dialect == null) {
            dialect = pluginDialectName(raw.platformLanguageId());
        }
        return new DataSourceCandidate(
                id,
                raw.displayName() == null ? "" : raw.displayName(),
                dialect,
                raw.platformLanguageId(),
                raw.valid());
    }

    private static String languageId(Object value) {
        return value instanceof Language language ? language.getID() : null;
    }

    public static String pluginDialectName(@Nullable String languageId) {
        if (languageId == null || languageId.isBlank()) {
            return null;
        }
        if ("TSQL".equalsIgnoreCase(languageId)) {
            return "SQL Server";
        }
        if ("GenericSQL".equalsIgnoreCase(languageId) || "SQL".equalsIgnoreCase(languageId)
                || "SQL92".equalsIgnoreCase(languageId)) {
            return null;
        }
        for (Dialect dialect : DialectRegistry.INSTANCE.getDialects()) {
            if (dialect.getName().equalsIgnoreCase(languageId)) {
                return dialect.getName();
            }
        }
        return null;
    }
}
