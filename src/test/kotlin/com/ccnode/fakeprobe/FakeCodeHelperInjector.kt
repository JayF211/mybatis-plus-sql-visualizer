package com.ccnode.fakeprobe

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

/**
 * T-XmlSynHi D68 探针测试专用假注入器：包名以 `com.ccnode.` 开头 = MCH-Pro 族排除组（其行为
 * 由三方让位线负责，不进 per-host 探测）。只供
 * [cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGatePlatformProbeTest] 经
 * `XmlSynHiGate.platformInjectorsWouldInject` 缝直注清单，不注册任何 EP。
 */
class FakeCodeHelperInjector(
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
