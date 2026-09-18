package cn.song.mybatisplus.visualizer.xml.fidelity

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets

/**
 * 真引擎渲染产物（T-FS-01）：SQL 文本 + `#{}` 参数队列。
 *
 * [parameterProperties] 为 `BoundSql.getParameterMappings()` 逐条的 `getProperty()`，
 * 顺序即 SQL 文本中 `?` 的出现顺序（foreach 内为 `__frch_<名>_<n>` 形态）。
 */
internal data class RealBoundSql(
    val sql: String,
    val parameterProperties: List<String>,
)

/** 真引擎不可用（jar 定位失败 / 类加载失败）——测试侧据此断言红，不静默跳过。 */
internal class RealEngineUnavailableException(message: String) : RuntimeException(message)

/**
 * 真引擎自身抛错（mapper 解析失败 / 表达式求值失败）的载体。
 *
 * 真引擎的失败形态是**硬失败**（`BuilderException` 等），与自研渲染器的降级形态（默认展开 + 错误记录）
 * 不同——对照场景需要断言「真引擎是否抛、抛什么」，故把异常类全名单独带出（[causeClassName]）。
 */
internal class RealEngineException(
    val causeClassName: String,
    message: String,
    cause: Throwable?,
) : RuntimeException("真引擎抛 $causeClassName: $message", cause)

/**
 * 真 MyBatis 引擎驱动（T-TailSweep2 T-FS-01）：隔离 classloader + 全反射，零编译期 mybatis 依赖。
 *
 * 本文件**不 import 任何 `org.apache.ibatis.*` 类**——真引擎侧全部经
 * `Class.forName(name, true, isolated)` + `Method/Constructor` 从声明类取的方式驱动，与
 * `ui/highlight/DatabaseSchemaResolver.kt` 的跨 classloader 反射纪律同款（实现类在非导出包时
 * 反射 invoke 抛 `IllegalAccessException`，故 Method 一律从声明类获取，禁 `obj.javaClass.getMethod`）。
 *
 * 驱动链（对齐评估报告 §3.2 spike）：
 * `Configuration` 构造 → `XMLMapperBuilder(InputStream, Configuration, resource, sqlFragments).parse()`
 * 注册 mapper XML → `Configuration.getMappedStatement(namespace + "." + id)` →
 * `MappedStatement.getBoundSql(params)` → `BoundSql.getSql()` / `getParameterMappings()`。
 *
 * 隔离策略（[ChildFirstIsolatedLoader]）：`org.apache.ibatis.` 与 `ognl.` 前缀 child-first（从 jar 载入），
 * 其余委派宿主 loader（用户实体/DTO/测试类由宿主提供）。故即使宿主 classpath 上也有 mybatis，
 * 真引擎类与其内置 OGNL 的静态状态仍落在隔离 loader 内（不污染宿主，也不被宿主遮蔽）。
 *
 * 依赖来源（计划 §二 T-FS-01 首个决策点，实测销账）：真引擎 jar **经 `testImplementation(libs.mp.core)`
 * 传递带入**——`com.baomidou:mybatis-plus-core:3.5.17` 编译期依赖 `org.mybatis:mybatis:3.5.19`，该传递项
 * 已在 `testRuntimeClasspath` 上可达（`./gradlew dependencies --configuration testRuntimeClasspath` 可复核），
 * 故**无需显式声明 `testImplementation("org.mybatis:mybatis:3.5.19")`**（构建改动为零，计划 §二 的 (a)/(b) 两
 * 备选均不需要）。红线边界＝**零 mybatis 依赖只约束 `implementation`（生产域），测试域不受限**。
 * 传递版本有静默漂移风险，由对照集 `RealEngineFidelityTest` 的版本钉住断言兜底。
 * jar 定位以宿主 classpath 资源为准，Gradle 缓存路径仅作兜底（见 [locateMybatisJar]）。
 */
internal class RealEngineDriver private constructor(
    private val isolated: ClassLoader,
    private val jarPath: String,
    private val configClass: Class<*>,
    private val mapperBuilderClass: Class<*>,
    private val mappedStatementClass: Class<*>,
    private val boundSqlClass: Class<*>,
    private val parameterMappingClass: Class<*>,
) {

    /** 隔离 loader（测试侧据此断言真引擎类确由隔离 loader 载入）。 */
    val classLoader: ClassLoader get() = isolated

    /** 真引擎 jar 绝对路径（证据：对照集吃的是哪个 jar）。 */
    val mybatisJarPath: String get() = jarPath

    /**
     * 注册并渲染：每个场景用**全新** `Configuration`（场景间零状态串扰，mapper 命名空间不互相覆盖）。
     *
     * @param mapperXml  完整 mapper 文档文本（含 DOCTYPE 亦可）
     * @param namespace  mapper 根 `namespace`
     * @param statementId 语句 id（不含 namespace）
     * @param params     真引擎参数对象——Map / POJO / List / Integer 等**真实值**，非 mock 字符串
     */
    fun render(mapperXml: String, namespace: String, statementId: String, params: Any?): RealBoundSql {
        val config = newConfiguration()
        registerMapper(config, mapperXml, "fidelity/${namespace.replace('.', '/')}.xml")
        val fullId = "$namespace.$statementId"
        return unwrap {
            val statement = configClass.getMethod("getMappedStatement", String::class.java).invoke(config, fullId)
                ?: throw RealEngineUnavailableException("真引擎未注册语句 $fullId")
            val bound = mappedStatementClass.getMethod("getBoundSql", Any::class.java).invoke(statement, params)
            val sql = boundSqlClass.getMethod("getSql").invoke(bound) as String
            val mappings = boundSqlClass.getMethod("getParameterMappings").invoke(bound) as List<*>
            val properties = mappings.map { parameterMappingClass.getMethod("getProperty").invoke(it) as String }
            RealBoundSql(sql, properties)
        }
    }

    /** 再建一个一次性隔离 loader（OGNL 全局状态隔离核验用；与 [classLoader] 不同实例）。 */
    fun freshClassLoader(): ClassLoader = newIsolatedLoader(jarPath)

    /** 从隔离 loader 载入类（测试侧探针；真引擎驱动内部同样只走这条路）。 */
    fun loadIsolated(className: String): Class<*> = Class.forName(className, true, isolated)

    private fun newConfiguration(): Any {
        val config = configClass.getDeclaredConstructor().newInstance()
        // 测试 classpath 无 slf4j/log4j，真引擎日志落 JUL → 关掉（NoLoggingImpl），避免噪声混进测试输出
        runCatching {
            val noLogging = Class.forName("org.apache.ibatis.logging.nologging.NoLoggingImpl", true, isolated)
            configClass.getMethod("setLogImpl", Class::class.java).invoke(config, noLogging)
        }
        return config
    }

    private fun registerMapper(config: Any, mapperXml: String, resource: String) {
        val fragments = configClass.getMethod("getSqlFragments").invoke(config) as MutableMap<Any?, Any?>
        val ctor = mapperBuilderClass.getConstructor(
            InputStream::class.java,
            configClass,
            String::class.java,
            Map::class.java,
        )
        val stream = ByteArrayInputStream(mapperXml.toByteArray(StandardCharsets.UTF_8))
        unwrap {
            val builder = ctor.newInstance(stream, config, resource, fragments)
            mapperBuilderClass.getMethod("parse").invoke(builder)
        }
    }

    /** 反射调用统一收口：递归剥掉反射包装（`InvocationTargetException` 可能嵌套），真引擎异常转 [RealEngineException]。 */
    private inline fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        var cause: Throwable = e
        while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
        throw RealEngineException(cause.javaClass.name, cause.message ?: "", cause)
    }

    private class ChildFirstIsolatedLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name)
            if (loaded == null) {
                if (CHILD_FIRST_PREFIXES.any { name.startsWith(it) }) {
                    loaded = runCatching { findClass(name) }.getOrNull()
                }
                if (loaded == null) loaded = super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        companion object {
            /** child-first 前缀：真引擎与其内置（shaded）OGNL 一律从 jar 载入。 */
            private val CHILD_FIRST_PREFIXES = listOf("org.apache.ibatis.", "ognl.")
        }
    }

    companion object {

        private const val CONFIG_CLASS = "org.apache.ibatis.session.Configuration"
        private const val MAPPER_BUILDER_CLASS = "org.apache.ibatis.builder.xml.XMLMapperBuilder"
        private const val MAPPED_STATEMENT_CLASS = "org.apache.ibatis.mapping.MappedStatement"
        private const val BOUND_SQL_CLASS = "org.apache.ibatis.mapping.BoundSql"
        private const val PARAMETER_MAPPING_CLASS = "org.apache.ibatis.mapping.ParameterMapping"

        /** 内置（shaded）OGNL 运行期类——隔离核验探针用。 */
        const val OGNL_RUNTIME_CLASS = "org.apache.ibatis.ognl.OgnlRuntime"

        /**
         * 建驱动；jar 或类不可用时抛 [RealEngineUnavailableException]（**不返回 null**——
         * 调用方断言红，禁止静默跳过：跳过=对照集形同不存在）。
         */
        fun create(): RealEngineDriver {
            val jarPath = locateMybatisJar()
                ?: throw RealEngineUnavailableException(
                    "未定位到 mybatis jar：宿主 classpath 资源 / java.class.path / Gradle 缓存三路均未命中" +
                        "（真引擎 jar 应经 testImplementation(libs.mp.core) → mybatis-plus-core 传递带入）",
                )
            val isolated = newIsolatedLoader(jarPath)
            return try {
                RealEngineDriver(
                    isolated = isolated,
                    jarPath = jarPath,
                    configClass = Class.forName(CONFIG_CLASS, true, isolated),
                    mapperBuilderClass = Class.forName(MAPPER_BUILDER_CLASS, true, isolated),
                    mappedStatementClass = Class.forName(MAPPED_STATEMENT_CLASS, true, isolated),
                    boundSqlClass = Class.forName(BOUND_SQL_CLASS, true, isolated),
                    parameterMappingClass = Class.forName(PARAMETER_MAPPING_CLASS, true, isolated),
                )
            } catch (e: ClassNotFoundException) {
                throw RealEngineUnavailableException("隔离 loader 未能载入真引擎类：$jarPath（${e.message}）")
            }
        }

        private fun newIsolatedLoader(jarPath: String): ClassLoader =
            ChildFirstIsolatedLoader(
                arrayOf(File(jarPath).toURI().toURL()),
                RealEngineDriver::class.java.classLoader,
            )

        /**
         * 定位 mybatis jar：① 宿主 classpath 资源（Gradle 长 classpath 会退化为 manifest-only jar，
         * 此时 `java.class.path` 只有一项，资源路径仍可达）；② `java.class.path` 逐项匹配；
         * ③ `~/.gradle/caches/modules-2/files-2.1/org.mybatis/mybatis/` 兜底（本地缓存已有 3.5.19）。
         */
        private fun locateMybatisJar(): String? {
            val fromResource = RealEngineDriver::class.java.classLoader
                .getResource("org/apache/ibatis/session/Configuration.class")
                ?.takeIf { it.protocol == "jar" }
                ?.let { url -> runCatching { (url.openConnection() as JarURLConnection).jarFileURL }.getOrNull() }
                ?.let { jarUrl -> runCatching { File(jarUrl.toURI()) }.getOrNull() }
                ?.takeIf { it.isFile }
            if (fromResource != null) return fromResource.absolutePath

            val fromClassPath = System.getProperty("java.class.path")
                .orEmpty()
                .split(File.pathSeparator)
                .map { File(it) }
                .firstOrNull { it.isFile && it.name.matches(Regex("mybatis-3\\..*\\.jar")) }
            if (fromClassPath != null) return fromClassPath.absolutePath

            val cacheRoot = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.mybatis/mybatis")
            return cacheRoot.walkTopDown()
                .filter { it.isFile && it.name.matches(Regex("mybatis-3\\..*\\.jar")) }
                .sortedByDescending { it.parentFile?.parentFile?.name ?: "" }
                .firstOrNull()
                ?.absolutePath
        }
    }
}
