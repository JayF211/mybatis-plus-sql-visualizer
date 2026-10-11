package org.example.fakeprobe

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

/**
 * T-XmlSynHi D68 / T-DevAcceptFix ④ 探针测试专用假注入器：包名属「未知三方」——20261006 验收后
 * per-host 探测域=除自身族外**全部** EP 在册项（不再按包名前缀白名单收窄），本夹具即该组的
 * 同权命中证据。只供 [cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGatePlatformProbeTest] 经
 * `XmlSynHiGate.foreignInjectorsWouldInject` 缝直注清单，不注册任何 EP。
 */
class FakeUnknownInjector(
    private val fedTypes: List<Class<out PsiElement>>,
) : MultiHostInjector {

    var consulted: Int = 0
        private set

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        consulted++
        val host = context as? PsiLanguageInjectionHost ?: return
        registrar.startInjecting(PlainTextLanguage.INSTANCE)
            .addPlace(null, null, host, TextRange(0, host.textLength))
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = fedTypes
}
