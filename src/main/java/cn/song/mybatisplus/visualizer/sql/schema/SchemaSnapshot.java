package cn.song.mybatisplus.visualizer.sql.schema;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 元数据快照（键与列名统一小写归一，比对大小写不敏感）。由 IDE 侧
 * {@code SchemaValidationGate} 从数据源元数据缓存构造。
 */
public record SchemaSnapshot(Map<String, Set<String>> tables) {

    public SchemaSnapshot {
        tables = tables == null ? Map.of() : Map.copyOf(tables);
    }

    /** 取某表的列集；未在快照内返回 null（= 不可判定，调用方跳过）。 */
    public @Nullable Set<String> columnsOf(@NotNull String table) {
        return tables.get(table.toLowerCase());
    }

    /** 归一化构造：表名/列名统一小写；空名条目丢弃。 */
    public static SchemaSnapshot of(@NotNull Map<String, ? extends Collection<String>> tables) {
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Collection<String>> entry : tables.entrySet()) {
            if (entry.getKey().isBlank()) {
                continue;
            }
            Set<String> columns = new LinkedHashSet<>();
            for (String column : entry.getValue()) {
                String trimmed = column.trim().toLowerCase();
                if (!trimmed.isEmpty()) {
                    columns.add(trimmed);
                }
            }
            normalized.put(entry.getKey().trim().toLowerCase(), columns);
        }
        return new SchemaSnapshot(normalized);
    }
}
