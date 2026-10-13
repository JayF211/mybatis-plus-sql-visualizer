// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlReferenceContributor.kt
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;

/**
 * T-XmlGap W7：mapper XML 内的引用贡献器（跳转 + 查找用法），<b>不做任何错误标注</b>。
 *
 * <p>红线：本文件族只产引用（resolve），不注册 inspection/annotator；且所有引用都是 <b>soft</b>
 * （PsiReferenceBase 的 soft 构造位），目标解析不到时平台不会标红，符合计划 §六 W7
 * 「无目标时零报红」的验收判据。覆盖面与守卫细节见
 * {@link MybatisXmlAttributeReferenceProvider} 的类注。</p>
 */
public class MybatisXmlReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlAttributeValue.class),
                MybatisXmlAttributeReferenceProvider.INSTANCE);
    }
}
