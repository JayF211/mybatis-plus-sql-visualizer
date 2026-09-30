package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/**
 * F48（T-PerfQuick5）：SqlMapperParserDefinition 编程式注册的摘除挂靠宿主（应用级轻服务）。
 *
 * 挂靠定案与 [cn.song.mybatisplus.visualizer.execute.ExecDocAutosaveHost] 同款：编程式
 * `addExplicitExtension` 的 Disposer 摘除子节点挂到本服务（插件应用级容器成员）上，动态
 * 卸载/更新时平台 dispose 插件应用级容器并级联释放其中服务，摘除随之执行——旧 classloader
 * 不再被 LanguageParserDefinitions 注册表钉住（V39：243 源码 KeyedExtensionCollector.java
 * :80-92 三参重载即此路径）。不订阅 DynamicPluginListener 手动摘：卸载回调运行在将被卸载的
 * 旧 classloader 上，回调内反手触达自身类有时序风险（ExecDocAutosaveHost KDoc 实证口径）。
 *
 * 惰性实例化：首次注入注册 ParserDefinition 时才创建，插件未注入过则零成本。
 */
@Service(Service.Level.APP)
internal class SqlMapperInjectionBarrierHost : Disposable {
    override fun dispose() {
        // 摘除子节点由 addExplicitExtension(key, t, this) 内部 Disposer.register 而来，
        // ObjectTree 级联 dispose 即执行 removeExplicitExtension——本方法无需额外动作。
    }
}
