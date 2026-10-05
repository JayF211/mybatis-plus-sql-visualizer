// T-SyncK2JMaster 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/psi/jpa/HqlEntityNameExtractor.kt（8f89c72..master 波A）
package cn.song.mybatisplus.visualizer.psi.jpa;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HQL/JPQL 语句实体名提取器（T-HqlUpdateEntityMap S1）。
 *
 * <p>缺陷背景：实体名提取原先散落三处（{@link HqlQueryLocator}
 * 的 createQuery 实体 fallback、SqlPreviewService 的 resolveHqlTable 类名来源、
 * HqlConverter 的兜底表名替换），全部只认 {@code FROM 实体} 正则——
 * {@code update WmsDockBooking booking set ...} 这类<b>无 FROM 子句</b>的 UPDATE 语句提取不到
 * 实体名，resolveHqlTable 三路径全跳过、TableInfo 落空，表名/属性映射整链跳过，
 * 预览产物退化为仅参数替换的原样透传（用户报障 demo 形态 17）。</p>
 *
 * <p>提取口径＝<b>语句头优先</b>：头部是 UPDATE / DELETE / INSERT 时按头提取实体名，先于
 * FROM——{@code UPDATE ... SET x = (SELECT ... FROM y ...)} 形态下 FROM 提取会命中子查询
 * 实体 y 造成错表映射，语句头优先天然规避；其余（SELECT / 裸 from）沿用既有 FROM
 * 口径，行为不变。</p>
 *
 * <p>来源先例：CustomDaoHqlLocator.extractEntityClassNameFromHql（FROM 优先的三段提取）；
 * 本提取器把口径统一为语句头优先，两个通道共用同一份实现。</p>
 */
public final class HqlEntityNameExtractor {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final HqlEntityNameExtractor INSTANCE = new HqlEntityNameExtractor();

    private HqlEntityNameExtractor() {
    }

    private static final Pattern HEAD_UPDATE_ENTITY =
            Pattern.compile("\\bUPDATE\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_DELETE_FROM_ENTITY =
            Pattern.compile("\\bDELETE\\s+FROM\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);

    /** HQL 宽松形态 {@code delete EntityName}（无 FROM；JPQL 标准要求 FROM，Hibernate 允许省略）。 */
    private static final Pattern HEAD_DELETE_ENTITY =
            Pattern.compile("\\bDELETE\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_INSERT_ENTITY =
            Pattern.compile("\\bINSERT\\s+INTO\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_ENTITY =
            Pattern.compile("\\bFROM\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 从 HQL/JPQL 提取实体类名（驼峰标识符，按语句头分派）。
     *
     * @return 实体类名；语句头与 FROM 都提取不到时返回 null（调用方走既有降级）
     */
    public static String extract(String hql) {
        String head = trimStart(hql);
        if (startsWithIgnoreCase(head, "UPDATE")) {
            // 畸形头宽容降级：头部 UPDATE 形态失配时回落 FROM
            String matched = firstGroup(HEAD_UPDATE_ENTITY, head);
            return matched != null ? matched : firstGroup(FROM_ENTITY, head);
        }
        if (startsWithIgnoreCase(head, "DELETE")) {
            // 带 FROM 版先试，防 `from` 被当实体名
            String matched = firstGroup(HEAD_DELETE_FROM_ENTITY, head);
            return matched != null ? matched : firstGroup(HEAD_DELETE_ENTITY, head);
        }
        if (startsWithIgnoreCase(head, "INSERT")) {
            // INSERT ... SELECT 结构回落 FROM
            String matched = firstGroup(HEAD_INSERT_ENTITY, head);
            return matched != null ? matched : firstGroup(FROM_ENTITY, head);
        }
        return firstGroup(FROM_ENTITY, head);
    }

    // ------------------------------------------------------------------
    // 私有辅助
    // ------------------------------------------------------------------

    /** 取正则第 1 捕获组首匹配；无匹配返回 null。 */
    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** {@code startsWith} 的大小写不敏感形态（对齐 Kotlin {@code startsWith(ignoreCase = true)}）。 */
    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** {@code trimStart} 等价：去前导空白。 */
    private static String trimStart(String text) {
        return text.stripLeading();
    }
}
