// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlReferenceContributor.kt（JavaClassReference）
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.Nullable;

/** 类名属性（{@code resultType} 族 / {@code namespace}）→ PsiClass。 */
public class JavaClassReference extends SoftXmlAttributeReference {

    public JavaClassReference(XmlAttributeValue value) {
        super(value);
    }

    @Override
    @Nullable
    public PsiElement resolve() {
        return MybatisXmlTargets.resolveClass(value.getProject(), value, value.getValue());
    }
}
