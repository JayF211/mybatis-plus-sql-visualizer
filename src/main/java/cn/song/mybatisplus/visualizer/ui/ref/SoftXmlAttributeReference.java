// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlReferenceContributor.kt（引用基类部分）
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;

/**
 * 引用基类：全部 <b>soft</b>（解析不到不标红，红线要求）。
 *
 * <p>移植注记：Kotlin 抽象类（构造位 soft=true）→ Java 同形；companion 的
 * {@link #valueRangeInElement} 提升为 static。</p>
 */
public abstract class SoftXmlAttributeReference extends PsiReferenceBase<XmlAttributeValue> {

    protected final XmlAttributeValue value;

    protected SoftXmlAttributeReference(XmlAttributeValue value) {
        super(value, valueRangeInElement(value), true);
        this.value = value;
    }

    @Override
    @NotNull
    public String getCanonicalText() {
        return value.getValue();
    }

    @Override
    public Object @NotNull [] getVariants() {
        return new Object[0];
    }

    /** 属性值元素内的<b>值区间</b>（剥引号；相对坐标）。 */
    public static TextRange valueRangeInElement(XmlAttributeValue value) {
        TextRange abs = value.getValueTextRange();
        int base = value.getTextRange().getStartOffset();
        int start = Math.max(abs.getStartOffset() - base, 0);
        int end = Math.min(abs.getEndOffset() - base, value.getTextLength());
        return start < end ? new TextRange(start, end) : new TextRange(0, value.getTextLength());
    }
}
