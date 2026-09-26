// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/XmlDirectText.kt 移植
package cn.song.mybatisplus.visualizer.xml;

import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XML 直属文本段提取与 CDATA 剥包裹（O-72 延伸修复：hbm 命名查询链同口径缺口）。
 *
 * <p>实测（O-72 判别性读数）：本平台 {@code XmlText.text} 对 CDATA 返回带 {@code <![CDATA[ ... ]]>}
 * 包裹的<b>字面全文</b>，并非展开内容——裸拼接会把包裹原样带进 SQL/HQL 转换。
 * 统一剥包裹后再拼（混排文本/多 CDATA 段均安全），普通文本段原样保留。</p>
 *
 * <p>Kotlin 文件内两个顶层函数 → 本门面类静态方法（unwrapCdata / directTextOf）。</p>
 */
public final class XmlDirectText {

    private static final Pattern CDATA_WRAPPER = Pattern.compile("(?s)<!\\[CDATA\\[(.*?)]]>");

    private XmlDirectText() {
    }

    /** 剥掉文本中的 {@code <![CDATA[ ... ]]>} 包裹（多段/混排均安全），普通文本原样返回。 */
    public static String unwrapCdata(String text) {
        Matcher matcher = CDATA_WRAPPER.matcher(text);
        return matcher.replaceAll("$1");
    }

    /** 直属 XmlText 拼接提取（CDATA 剥包裹，trim）；排除子标签内容（调用方按需）。 */
    public static String directTextOf(XmlTag tag) {
        StringBuilder sb = new StringBuilder();
        for (PsiElement child : tag.getChildren()) {
            if (child instanceof XmlText text) {
                sb.append(unwrapCdata(text.getText()));
            }
        }
        return sb.toString().trim();
    }
}
