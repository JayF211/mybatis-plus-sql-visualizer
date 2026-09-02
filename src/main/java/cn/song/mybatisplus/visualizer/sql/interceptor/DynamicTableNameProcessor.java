package cn.song.mybatisplus.visualizer.sql.interceptor;

import cn.song.mybatisplus.visualizer.config.TableNameRule;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态表名替换引擎（F-6 Phase B）：按用户配置的正则规则替换预览 SQL 中的表名。
 *
 * <p>对齐 MP DynamicTableNameInnerInterceptor 运行时语义——运行时动态替换表名
 * （如 {@code order_2024} → {@code order_202401}）；预览时按规则静态近似。
 *
 * <p>性能：正则编译缓存（{@link #regexCache}，避免重复编译）；处理 &lt; 20ms。
 * 规则按列表顺序应用（首个匹配即停）；正则编译失败时跳过该规则并日志警告。
 *
 * <p>替换模板支持：
 * <ul>
 *  <li>{@code $1} / {@code $2} ... 引用正则捕获组</li>
 *  <li>{@code $tableName} 引用完整原始表名（变量引用，优先于捕获组解析）</li>
 * </ul>
 *
 * <p>本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
public final class DynamicTableNameProcessor {

    private static final Logger LOG = Logger.getInstance(DynamicTableNameProcessor.class);

    /** 正则编译缓存（pattern → Pattern?）；值 null 表示编译失败（缓存避免重复日志警告）。 */
    private static final Map<String, Pattern> regexCache = new HashMap<>();

    private DynamicTableNameProcessor() {
    }

    /**
     * 按规则列表替换表名。
     *
     * @param tableName 原始表名（预览 SQL 中的表名，已含前缀/后缀修饰）
     * @param rules     启用的规则列表（按顺序应用，首个匹配即停）
     * @return 替换后的表名（无规则命中时返回 null）
     */
    public static String process(String tableName, List<TableNameRule> rules) {
        for (TableNameRule rule : rules) {
            if (!rule.getEnabled() || rule.getPattern().trim().isEmpty()) continue;
            Pattern regex = compileRegex(rule.getPattern());
            if (regex == null) continue;
            Matcher match = regex.matcher(tableName);
            if (!match.find()) continue;
            return resolveReplacement(tableName, rule.getReplacement(), match);
        }
        return null;
    }

    /**
     * 编译正则（带缓存）；编译失败返回 null 并日志警告（仅首次，后续命中缓存跳过）。
     */
    private static Pattern compileRegex(String pattern) {
        if (regexCache.containsKey(pattern)) {
            return regexCache.get(pattern);
        }
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (Exception e) {
            LOG.warn("DynamicTableName: 正则编译失败，跳过规则 pattern=" + pattern + ", error=" + e.getMessage());
            regex = null;
        }
        regexCache.put(pattern, regex);
        return regex;
    }

    /**
     * 解析替换模板：先处理 {@code $tableName} 变量引用，再处理 {@code $1}/{@code $2} 捕获组引用。
     */
    private static String resolveReplacement(String tableName, String replacement, Matcher match) {
        String result = replacement.replace("$tableName", tableName);
        // 捕获组替换（$1 → group(1)，以此类推）
        for (int i = 1; i <= match.groupCount(); i++) {
            result = result.replace("$" + i, match.group(i));
        }
        return result;
    }

    /**
     * 清空正则缓存（设置变更时调用，防规则修改后命中旧缓存）。
     */
    public static void clearCache() {
        regexCache.clear();
    }
}
