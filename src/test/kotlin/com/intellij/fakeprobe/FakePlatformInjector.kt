package com.intellij.fakeprobe

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

/**
 * T-XmlSynHi D68 探针测试专用假注入器：包名以 `com.intellij.` 开头 = 「平台系」包前缀过滤
 * **命中组**。只供 [cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGatePlatformProbeTest] 经
 * `XmlSynHiGate.platformInjectorsWouldInject` 缝直注清单，不注册任何 EP。
 */
class FakePlatformInjector(
    private val fedTypes: List<Class<out PsiElement>>,
    private val start: Boolean = true,
    private val throwOnInject: Boolean = false,
    private val throwOnFeed: Boolean = false,
) : MultiHostInjector {

    /** 被咨询计数（喂入契约预筛不命中则不增——「不咨询」断言的证据）。 */
    var consulted: Int = 0
        private set

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        consulted++
        if (throwOnInject) throw IllegalStateException("fake injector boom")
        if (!start) return
        val host = context as? PsiLanguageInjectionHost ?: return
        registrar.startInjecting(PlainTextLanguage.INSTANCE)
            .addPlace(null, null, host, TextRange(0, host.textLength))
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> {
        if (throwOnFeed) throw IllegalStateException("fake feed boom")
        return fedTypes
    }
}
