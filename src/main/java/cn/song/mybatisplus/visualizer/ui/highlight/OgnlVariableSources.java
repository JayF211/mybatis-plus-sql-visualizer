// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/OgnlVariableSources.kt
package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T-XmlGap W5-4：OGNL 变量来源收集（除既有的方法参数 + 主实体字段之外的全部来源）。
 *
 * <p>收集项（按需求逐条）：</p>
 * <ol>
 *   <li>{@code <bind name="x">} —— 在<b>其之后</b>的位置可见（同语句/{@code <sql>} 片段内、文档顺序在光标之前）；</li>
 *   <li>{@code <foreach item="i" index="k" collection="c">} —— {@code i}/{@code k} 在该 foreach <b>内</b>可见；
 *       {@code c}（collection 属性值）若是标识符形态也收录（可指向 bind 变量或参数）；</li>
 *   <li>{@code include} 的 {@code <property name="p">} —— 在<b>被 include 的 {@code <sql>} 片段内</b>可见
 *       （按 {@code refid} 反查本文件里引用该 {@code <sql>} 的 include 节点；同文件解析，跨文件见边界说明）；</li>
 *   <li>mybatis-config.xml {@code <properties>} 的 key（全项目聚合，XmlCompletionSymbols.configPropertyKeys 带缓存）；</li>
 *   <li>MyBatis 内置 {@code _databaseId}、{@code _parameter}。</li>
 * </ol>
 *
 * <p>边界（如实登记）：</p>
 * <ul>
 *   <li>include property 只反查<b>本文件</b>的 {@code <include refid>}（跨文件反查需索引往返，W7 引用导航另做）；</li>
 *   <li>bind 可见性按「文档顺序在光标之前」判定，不做 if/choose 分支内的作用域收窄（MyBatis bind 实际
 *       是语句级绑定，顺序判定已符合其语义）；</li>
 *   <li>全部外部调用 runCatching 包裹，失败降级空列表。</li>
 * </ul>
 */
public final class OgnlVariableSources {

    private OgnlVariableSources() {
    }

    /**
     * 收集变量（去重保序）。
     *
     * @param includeConfigProperties 是否并入 mybatis-config.xml {@code <properties>} 的 key
     *        （OGNL/{@code ${}} 位置为 true；{@code #{}} 位置为 false——config properties 是解析期替换的，
     *        不是语句参数映射的来源）。
     */
    public static java.util.List<String> collect(
            PsiElement caretElement, XmlTag enclosingTag, Project project, boolean includeConfigProperties) {
        Set<String> out = new LinkedHashSet<>();
        try {
            int caretOffset = caretElement.getTextRange().getStartOffset();
            collectBinds(enclosingTag, caretOffset, out);
            collectForeachAncestors(caretElement, enclosingTag, out);
            collectIncludeProperties(caretElement, out);
            if (includeConfigProperties) out.addAll(XmlCompletionSymbols.configPropertyKeys(project));
        } catch (Throwable ignored) {
            // 收集链任一来源失败 → 降级为已收集部分（不抛、不阻塞补全）
        }
        // MyBatis 内置变量（恒可用，无需解析）
        out.add("_databaseId");
        out.add("_parameter");
        return java.util.List.copyOf(out);
    }

    /** {@code <bind name>}：同作用域内、文档顺序在光标之前的 bind 变量。 */
    private static void collectBinds(XmlTag enclosingTag, int caretOffset, Set<String> out) {
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(enclosingTag, XmlTag.class)) {
            if (!tag.getName().equals("bind")) continue;
            if (tag.getTextRange().getStartOffset() >= caretOffset) continue;
            String name = tag.getAttributeValue("name");
            if (name != null && !name.isBlank()) out.add(name);
        }
    }

    /** foreach 祖先的 item/index（及 collection 属性值的标识符形态）。 */
    private static void collectForeachAncestors(PsiElement caretElement, XmlTag enclosingTag, Set<String> out) {
        XmlTag current = PsiTreeUtil.getParentOfType(caretElement, XmlTag.class, false);
        while (current != null) {
            XmlTag tag = current;
            if (tag.getName().equals("foreach")) {
                for (String attr : java.util.List.of("item", "index")) {
                    String value = tag.getAttributeValue(attr);
                    if (value != null && !value.isBlank()) out.add(value);
                }
                String collection = tag.getAttributeValue("collection");
                if (collection != null) {
                    String trimmed = collection.trim();
                    if (IDENTIFIER_PATH.matcher(trimmed).matches()) out.add(trimmed);
                }
            }
            if (tag == enclosingTag) break;
            current = tag.getParentTag();
        }
    }

    /**
     * include property：若光标位于某 {@code <sql id="X">} 片段内，反查本文件里 {@code refid} 指向 X 的
     * {@code <include>}，收集其 {@code <property name>} 值（include 传入的参数名）。
     */
    private static void collectIncludeProperties(PsiElement caretElement, Set<String> out) {
        XmlTag sqlTag = ancestorSqlTag(caretElement);
        if (sqlTag == null) return;
        String rawId = sqlTag.getAttributeValue("id");
        String sqlId = rawId != null ? rawId.trim() : null;
        if (sqlId == null || sqlId.isBlank()) return;
        XmlTag top = sqlTag;
        while (top.getParentTag() != null) top = top.getParentTag();
        for (XmlTag include : PsiTreeUtil.findChildrenOfType(top, XmlTag.class)) {
            if (!include.getName().equals("include")) continue;
            if (!refidMatches(include.getAttributeValue("refid"), sqlId)) continue;
            for (XmlTag property : PsiTreeUtil.findChildrenOfType(include, XmlTag.class)) {
                if (!property.getName().equals("property")) continue;
                String name = property.getAttributeValue("name");
                if (name != null && !name.isBlank()) out.add(name);
            }
        }
    }

    /** 光标祖先链上最近的 {@code <sql>} 标签（用于 include property 可见域判定）。 */
    private static XmlTag ancestorSqlTag(PsiElement caretElement) {
        XmlTag current = PsiTreeUtil.getParentOfType(caretElement, XmlTag.class, false);
        while (current != null) {
            if (current.getName().equals("sql")) return current;
            current = current.getParentTag();
        }
        return null;
    }

    /** {@code refid} 是否指向 {@code sqlId}（精确或 {@code namespace.sqlId} 尾段匹配）。 */
    private static boolean refidMatches(String refid, String sqlId) {
        if (refid == null) return false;
        String ref = refid.trim();
        if (ref.isBlank()) return false;
        return ref.equals(sqlId) || ref.endsWith("." + sqlId);
    }

    /** 标识符路径（可含点，如 {@code user.deptId}）。 */
    private static final Pattern IDENTIFIER_PATH = Pattern.compile("^[\\w$]+(\\.[\\w$]+)*$");
}
