package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.converter.jpa.JpaSqlLiteralMasking;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HQL/JPQL 语句根实体名提取器（T-HqlUpdateEntityMap / T-EntityNameScope）。
 *
 * <p>原 FROM 正则既漏掉无 FROM 的 UPDATE，也会让 SELECT 标量子查询的 Detail
 * 抢占外层 Inventory。预览和转换链现共用此实现，避免弱提取产出的 TableInfo 压过强提取。</p>
 * <p>字面量掩码 → 跳过括号内 FROM → 首个深度 0 匹配定根。
 * UPDATE / INSERT INTO 自然优先于其后的子查询；DELETE 允许省略 FROM。
 * 保留 FQN，且不按类名大小写过滤（update t_order 仍提取 t_order）。</p>
 */
public final class HqlEntityNameExtractor {
    public static final HqlEntityNameExtractor INSTANCE = new HqlEntityNameExtractor();
    private HqlEntityNameExtractor() {}

    private static final Pattern ROOT_ENTITY = Pattern.compile(
            "(?i)\\b(?:FROM|UPDATE|INTO|DELETE(?:\\s+FROM)?)\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)");

    /** @return 根实体标识符（可能带包前缀）；提取不到时 null。 */
    public static String extract(String hql) {
        String masked = JpaSqlLiteralMasking.mask(hql).text();
        int depth = 0;
        int offset = 0;
        Matcher match = ROOT_ENTITY.matcher(masked);
        while (match.find()) {
            while (offset < match.start()) {
                char c = masked.charAt(offset++);
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }
            if (depth == 0) return match.group(1);
        }
        return null;
    }
}
