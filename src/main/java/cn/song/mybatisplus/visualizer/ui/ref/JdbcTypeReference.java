// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlReferenceContributor.kt（JdbcTypeReference）
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.Nullable;

/** {@code jdbcType="VARCHAR"} → JdbcType 枚举常量字段。 */
public class JdbcTypeReference extends SoftXmlAttributeReference {

    public JdbcTypeReference(XmlAttributeValue value) {
        super(value);
    }

    @Override
    @Nullable
    public PsiElement resolve() {
        return MybatisXmlTargets.resolveJdbcTypeField(value.getProject(), value.getValue());
    }
}
