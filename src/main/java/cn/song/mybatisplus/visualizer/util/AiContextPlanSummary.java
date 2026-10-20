package cn.song.mybatisplus.visualizer.util;

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter;
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** R91: an allowlist projection of already acquired data, never the UI tree's label/extras/raw fallback. */
public final class AiContextPlanSummary {

    private AiContextPlanSummary() {
    }

    private static final Set<String> NODE_TYPES = Set.of(
            "Seq Scan", "Index Scan", "Index Only Scan", "Bitmap Heap Scan", "Bitmap Index Scan",
            "Nested Loop", "Hash Join", "Merge Join", "Hash", "Aggregate", "Group", "Sort", "Incremental Sort",
            "Limit", "Result", "Append", "Merge Append", "Gather", "Gather Merge", "Materialize", "Memoize",
            "Unique", "WindowAgg", "Subquery Scan", "CTE Scan", "Function Scan", "Values Scan", "SetOp",
            "LockRows", "ModifyTable", "Recursive Union", "WorkTable Scan", "BitmapAnd", "BitmapOr");

    private static final Set<String> ACCESS_TYPES = Set.of(
            "system", "const", "eq_ref", "ref", "fulltext", "ref_or_null",
            "index_merge", "unique_subquery", "index_subquery", "range", "index", "ALL");

    private static final Pattern NUMBER =
            Pattern.compile("[0-9]{1,16}(?:\\.[0-9]{1,8})?(?:[eE][+-]?[0-9]{1,3})?");

    public static @Nullable String summarize(McpExplainFormatter.RawResult raw, ExplainOutputFormat format) {
        // An incomplete acquisition cannot be presented as a complete plan.
        if (raw.truncated() || raw.rows().size() > 512) {
            return null;
        }
        List<String> lines;
        if (format == ExplainOutputFormat.TABLE) {
            List<String> table = new ArrayList<>();
            for (List<String> row : raw.rows()) {
                java.util.function.Function<String, String> value = name -> {
                    int idx = -1;
                    for (int j = 0; j < raw.headers().size(); j++) {
                        if (raw.headers().get(j).equalsIgnoreCase(name)) {
                            idx = j;
                            break;
                        }
                    }
                    if (idx < 0 || idx >= row.size()) {
                        return null;
                    }
                    return row.get(idx);
                };
                List<String> fields = new ArrayList<>();
                String type = value.apply("type");
                if (type != null && ACCESS_TYPES.contains(type)) {
                    fields.add("type=" + type);
                }
                String rows = numeric(value.apply("rows"));
                if (rows != null) {
                    fields.add("rows=" + rows);
                }
                String cost = numeric(value.apply("cost"));
                if (cost != null) {
                    fields.add("cost=" + cost);
                }
                String filtered = numeric(value.apply("filtered"));
                if (filtered != null) {
                    fields.add("filteredPercent=" + filtered);
                }
                if (!fields.isEmpty()) {
                    table.add(String.join("; ", fields));
                }
            }
            lines = table;
        } else if (format == ExplainOutputFormat.JSON) {
            long totalChars = 0;
            for (List<String> row : raw.rows()) {
                for (String cell : row) {
                    totalChars += cell.length();
                }
            }
            if (totalChars > 131_072) {
                return null;
            }
            List<String> output = new ArrayList<>();
            class Visitor {
                void visit(JsonElement element, int depth) {
                    if (depth > 64 || output.size() > 512) {
                        throw new IllegalStateException("bounded");
                    }
                    if (element.isJsonArray()) {
                        for (JsonElement item : element.getAsJsonArray()) {
                            visit(item, depth + 1);
                        }
                    }
                    if (!element.isJsonObject()) {
                        return;
                    }
                    var obj = element.getAsJsonObject();
                    java.util.function.Function<String, String> value = name -> {
                        JsonElement el = obj.get(name);
                        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
                    };
                    if (obj.has("Node Type")) {
                        List<String> fields = new ArrayList<>();
                        String node = value.apply("Node Type");
                        if (node != null && NODE_TYPES.contains(node)) {
                            fields.add("node=" + node);
                        }
                        String rows = numeric(value.apply("Plan Rows"));
                        if (rows != null) {
                            fields.add("rows=" + rows);
                        }
                        String cost = numeric(value.apply("Total Cost"));
                        if (cost != null) {
                            fields.add("cost=" + cost);
                        }
                        if (!fields.isEmpty()) {
                            output.add(String.join("; ", fields));
                        }
                        JsonElement plans = obj.get("Plans");
                        if (plans != null) {
                            visit(plans, depth + 1);
                        }
                    } else {
                        JsonElement plan = obj.get("Plan");
                        if (plan != null) {
                            visit(plan, depth + 1);
                        }
                    }
                }
            }
            try {
                for (List<String> row : raw.rows()) {
                    for (String cell : row) {
                        new Visitor().visit(JsonParser.parseString(cell), 0);
                    }
                }
            } catch (Exception e) {
                return null;
            }
            lines = output;
        } else {
            return null;
        }
        if (lines.isEmpty()) {
            return null;
        }
        return String.join("\n", lines);
    }

    private static @Nullable String numeric(@Nullable String value) {
        if (value == null) {
            return null;
        }
        if (!NUMBER.matcher(value).matches()) {
            return null;
        }
        try {
            double d = Double.parseDouble(value);
            if (!Double.isFinite(d)) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return value;
    }
}
