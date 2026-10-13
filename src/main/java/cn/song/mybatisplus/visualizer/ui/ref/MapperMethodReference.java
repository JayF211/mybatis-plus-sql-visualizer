// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlReferenceContributor.kt（MapperMethodReference）
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.Nullable;

/** statement {@code id} → namespace 接口的同名方法。 */
public class MapperMethodReference extends SoftXmlAttributeReference {

    public MapperMethodReference(XmlAttributeValue value) {
        super(value);
    }

    @Override
    @Nullable
    public PsiElement resolve() {
        String namespace = MybatisXmlTargets.rootNamespace(value.getContainingFile());
        if (namespace == null) return null;
        return MybatisXmlTargets.resolveInterfaceMethod(value.getProject(), namespace, value.getValue());
    }
}
