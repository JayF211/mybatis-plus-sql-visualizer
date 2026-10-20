package cn.song.mybatisplus.visualizer.history;

import cn.song.mybatisplus.visualizer.log.LogSqlModelsKt;
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模板键供后续消费者检索；比较必须同时命中 exact，不能把不同参数冒充同一 SQL。
 *
 * <p>master 的 data class 的 Java 承载（record）。</p>
 */
public record ExplainPlanKey(
        String projectIdentity,
        String dataSourceUuid,
        String dialect,
        String format,
        String templateFingerprint,
        String exactFingerprint
) {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Set<String> PERSISTABLE_FORMATS = Set.of(
            ExplainOutputFormat.TABLE.name(), ExplainOutputFormat.JSON.name());

    public boolean isPersistable() {
        return projectIdentity != null && !projectIdentity.isBlank()
                && dataSourceUuid != null && UUID_PATTERN.matcher(dataSourceUuid).matches()
                && dialect != null && !dialect.isBlank()
                && format != null && PERSISTABLE_FORMATS.contains(format)
                && templateFingerprint != null && !templateFingerprint.isBlank()
                && exactFingerprint != null && !exactFingerprint.isBlank();
    }

    /** Kotlin 属性访问器等价（Kotlin 调用方按属性名读取）。 */
    public boolean getPersistable() {
        return isPersistable();
    }

    public static String projectIdentity(@Nullable String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "";
        }
        String normalized = Paths.get(basePath).toAbsolutePath().normalize().toString().replace('\\', '/');
        return hash(normalized);
    }

    public static ExplainPlanKey create(String projectIdentity, @Nullable String uuid, String dialect,
                                        ExplainOutputFormat format, String sql) {
        String normalizedUuid = uuid == null ? "" : uuid.toLowerCase(Locale.ROOT);
        return new ExplainPlanKey(projectIdentity, normalizedUuid, dialect, format.name(),
                hash(LogSqlModelsKt.structureFingerprint(sql)), hash(sql));
    }

    private static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
