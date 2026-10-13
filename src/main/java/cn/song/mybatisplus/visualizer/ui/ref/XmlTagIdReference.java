// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlReferenceContributor.kt（各引用类）
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.Nullable;

/** {@code <sql id>} / {@code <resultMap id>} / {@code <parameterMap id>} 目标引用。 */
public class XmlTagIdReference extends SoftXmlAttributeReference {

    private final String targetTagName;

    public XmlTagIdReference(XmlAttributeValue value, String targetTagName) {
        super(value);
        this.targetTagName = targetTagName;
    }

    @Override
    @Nullable
    public PsiElement resolve() {
        return MybatisXmlTargets.resolveIdReference(
                value.getProject(),
                value.getContainingFile(),
                targetTagName,
                value.getValue());
    }
}
