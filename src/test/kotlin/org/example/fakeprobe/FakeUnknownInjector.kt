package org.example.fakeprobe

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

/**
 * T-XmlSynHi D68 探针测试专用假注入器：包名非 JetBrains 系白名单前缀 = 「非平台」排除组。
 * 只供 [cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGatePlatformProbeTest] 经
 * `XmlSynHiGate.platformInjectorsWouldInject` 缝直注清单，不注册任何 EP。
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
