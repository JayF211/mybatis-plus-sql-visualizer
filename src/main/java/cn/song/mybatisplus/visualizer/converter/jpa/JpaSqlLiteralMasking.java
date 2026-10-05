// T-SyncK2JMaster 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/converter/jpa/JpaSqlLiteralMasking.kt（8f89c72..master 波A）
package cn.song.mybatisplus.visualizer.converter.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单引号字面量掩码助手（T-JpaDeleteWhereGuard 同构普查批）。
 *
 * <p>缺陷背景：同一转换管道内属性映射（{@link JpaAliasTableResolver.JpaAliasPropertyMapper}）用「字面量分支在前」
 * 的交替正则保护单引号字面量，而实体名→表名替换端（HqlConverter/JpqlConverter 的
 * mapEntityToTable、HqlConverter.mapFallbackEntityToTable/mapAssociationPath、
 * {@link JpaFromEntityMapper} 的 FROM/JOIN 扫描）是纯文本正则全文命中——
 * {@code u.remark like '%UserEntity%'} 会被改成 {@code '%user%'}、
 * {@code select OwnerInfoHelper.toString(x) from OwnerInfo o} 的裸 replaceFirst 会产出
 * {@code unknown_tableHelper}、{@code set note = 'copy from OrderEntity x'} 里的字面量会驱动
 * 错表映射。修法统一为「掩码 → 替换 → 还原」两段式：字面量先换成哨兵占位，
 * 各正则在掩码文本上工作（哨兵非标识符字符，不影响 {@code \b} 词边界语义），完成后还原。</p>
 *
 * <p>字面量口径沿用 {@link JpaAliasTableResolver.JpaAliasPropertyMapper#STRING_LITERAL}（本仓唯一一份单引号字面量
 * 正则，标准 {@code ''} 转义天然切成相邻两段、两段均被掩码保护）。</p>
 *
 * <p>无状态单例，纯函数逻辑。</p>
 *
 * <p>移植注记：master 的 Kotlin {@code internal object} 按本仓互操作惯例落为
 * {@code final class} + {@link #INSTANCE} 单例；{@code mask} 的
 * {@code Pair<String, List<String>>} 返回值落为本文件嵌套 {@link Masked} 载体
 * （record 访问器无 get 前缀，与仓内既有 record 惯例一致）。</p>
 */
public final class JpaSqlLiteralMasking {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpaSqlLiteralMasking INSTANCE = new JpaSqlLiteralMasking();

    private JpaSqlLiteralMasking() {
    }

    private static final String SENTINEL = "\u0000";
    private static final Pattern SENTINEL_PATTERN = Pattern.compile("\u0000(\\d+)\u0000");

    /** {@code mask} 返回值载体：掩码后文本 + 按出现序排列的字面量原文。 */
    public record Masked(String text, List<String> literals) {
    }

    /**
     * 掩码语句中的全部单引号字面量。
     *
     * @return 掩码后文本与按出现序排列的字面量原文（须与 {@link #unmask} 配对使用）
     */
    public static Masked mask(String sql) {
        List<String> literals = new ArrayList<>();
        Matcher matcher = JpaAliasTableResolver.JpaAliasPropertyMapper.STRING_LITERAL.matcher(sql);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            literals.add(matcher.group());
            matcher.appendReplacement(masked,
                    Matcher.quoteReplacement(SENTINEL + (literals.size() - 1) + SENTINEL));
        }
        matcher.appendTail(masked);
        return new Masked(masked.toString(), List.copyOf(literals));
    }

    /** 按哨兵序号还原字面量原文（须与 {@link #mask} 的字面量清单配对使用）。 */
    public static String unmask(String sql, List<String> literals) {
        Matcher matcher = SENTINEL_PATTERN.matcher(sql);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String restored = index >= 0 && index < literals.size() ? literals.get(index) : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(restored));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
