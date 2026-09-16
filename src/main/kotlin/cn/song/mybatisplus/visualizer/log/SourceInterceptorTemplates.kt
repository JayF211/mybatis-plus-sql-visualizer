package cn.song.mybatisplus.visualizer.log

/**
 * F-44-7：SqlSourceInterceptor 和配置类的代码模板。
 *
 * 生成可直接编译的 Java 源码，用户项目重启后自动生效。
 * 标记行格式与 [SqlSourceMarker] 保持一致：
 * `SQLVIS-SOURCE|<相对路径>|<行号>`（3 段，旧版存量形态）
 * `SQLVIS-SOURCE|<相对路径>|<行号>|<mapper 语句 id>`（4 段，M56 T-M56-00 起）
 * `SQLVIS-SOURCE|<相对路径>|<行号>|<mapper 语句 id>|<请求标签>`（5 段，MDC 兜底可选）
 */
object SourceInterceptorTemplates {

    /** 拦截器类文件名。 */
    const val INTERCEPTOR_FILE_NAME = "SqlSourceInterceptor.java"

    /** 配置类文件名。 */
    const val CONFIG_FILE_NAME = "SqlVisualizerConfig.java"

    /** 生成拦截器类代码。 */
    fun generateInterceptorClass(basePackage: String): String = """
package ${basePackage}.config;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.SQLException;
import java.util.Set;

/**
 * SQL Visualizer 源码位置捕获拦截器（自动生成）。
 *
 * 在 SQL 执行时捕获调用栈，打印源码标记行到控制台。
 * 插件解析后可实现「跳转到源码」精确跳转。
 *
 * 标记行格式：SQLVIS-SOURCE|&lt;相对路径&gt;|&lt;行号&gt;[|&lt;mapper 语句 id&gt;[|&lt;请求标签&gt;]]
 * 例：SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|87|com.example.mapper.UserMapper.selectById
 *
 * 自动生成于 SQL Visualizer 插件，可安全删除（删除后实时日志不再携带源码位置）。
 */
public class SqlSourceInterceptor implements InnerInterceptor {

    private final Set<String> appPackages;

    public SqlSourceInterceptor(Set<String> appPackages) {
        this.appPackages = appPackages;
    }

    /**
     * 查询前拦截——打印源码标记行。
     * 注意：不加 @Override，兼容不同 MP 版本的 InnerInterceptor 接口签名差异。
     */
    @SuppressWarnings("unused")
    public void beforeQuery(Executor executor, MappedStatement ms,
            Object parameter, RowBounds rowBounds,
            ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        emitSource(ms == null ? null : ms.getId());
    }

    /**
     * 更新前拦截——打印源码标记行。
     */
    @SuppressWarnings("unused")
    public void beforeUpdate(Executor executor, MappedStatement ms,
            Object parameter) throws SQLException {
        emitSource(ms == null ? null : ms.getId());
    }

    /**
     * 把当前线程调用栈里的真实调用点打印成标记行（3~5 段）：
     * 第 4 段为 mapper 语句 id（ms.getId()，null 省略）；第 5 段为请求标签（MDC 兜底，
     * 仅在第 4 段在场时输出——段位固定不可跳位）。
     */
    private void emitSource(String mapperStatement) {
        String[] src = locateSource();
        if (src != null) {
            StringBuilder sb = new StringBuilder("SQLVIS-SOURCE|")
                    .append(src[0]).append('|').append(src[1]);
            if (mapperStatement != null && !mapperStatement.isEmpty()) {
                sb.append('|').append(mapperStatement);
                String tag = requestTagFromMdc();
                if (tag != null && !tag.isEmpty()) {
                    sb.append('|').append(tag);
                }
            }
            System.out.println(sb);
        }
    }

    /** 反射读取 MDC 请求标签（org.slf4j.MDC.get("sqlvisRequestTag")），无 slf4j 时静默返回 null。 */
    private String requestTagFromMdc() {
        try {
            Class<?> mdc = Class.forName("org.slf4j.MDC");            Object v = mdc.getMethod("get", String.class).invoke(null, "sqlvisRequestTag");
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿调用栈跳过框架帧和拦截器自身，取第一个用户代码帧。返回 [相对路径, 行号] 或 null。 */
    private String[] locateSource() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cn = frame.getClassName();
            if (isFrameworkFrame(cn)) continue;
            if (isSelf(cn)) continue;  // 跳过拦截器自身
            if (!appPackages.isEmpty() && startsWithNone(cn)) continue;
            String fileName = frame.getFileName();
            if (fileName == null) continue;
            String pkg = substringBeforeLast(cn, '.');
            String rel = pkg.isEmpty() ? fileName : pkg.replace('.', '/') + "/" + fileName;
            int line = frame.getLineNumber();
            if (line < 0) continue;
            return new String[]{rel, String.valueOf(line)};
        }
        return null;
    }

    /**
     * 是否为拦截器自身（跳过，避免捕获到自己的位置）。
     * 用运行时类名而非生成期包名常量——用户拷贝/改包/改类名后自跳过依旧生效
     * （历史缺陷：包名常量与运行类分叉时拦截器自身帧被当成调用点上报）。
     */
    private boolean isSelf(String cn) {
        return cn.equals(getClass().getName());
    }

    /** 取 str 中 delimiter 最后一次出现之前的子串（兼容 Java 8+，无 Kotlin 扩展方法）。 */
    private static String substringBeforeLast(String str, char delimiter) {
        int idx = str.lastIndexOf(delimiter);
        return idx < 0 ? "" : str.substring(0, idx);
    }

    /** 检查 cn 是否不以任何 appPackage 开头。 */
    private boolean startsWithNone(String cn) {
        for (String pkg : appPackages) {
            if (cn.startsWith(pkg)) return false;
        }
        return true;
    }

    /** 框架 / 基础设施帧一律跳过（不视为调用点）。 */
    private boolean isFrameworkFrame(String cn) {
        return cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") ||
                cn.startsWith("sun.") || cn.startsWith("com.intellij.") || cn.startsWith("org.jetbrains.") ||
                cn.startsWith("cn.song.mybatisplus.visualizer.") || cn.startsWith("com.baomidou.") ||
                cn.startsWith("org.apache.ibatis.") || cn.startsWith("org.hibernate.") ||
                cn.startsWith("com.zaxxer.") || cn.startsWith("org.springframework.") ||
                cn.startsWith("com.sun.proxy.") || cn.startsWith("${'$'}Proxy");
    }
}
""".trimIndent()

    /** 生成 Spring Boot 配置类代码。 */
    fun generateSpringBootConfigClass(basePackage: String): String = """
package ${basePackage}.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Map;

/**
 * SQL Visualizer 源码位置捕获配置（自动生成）。
 *
 * 将 SqlSourceInterceptor 添加到 MyBatis-Plus 拦截器链中，
 * 使实时日志携带源码位置信息，支持精确跳转。
 *
 * 兼容三种场景：
 *  1. 用户已定义 MybatisPlusInterceptor bean（如 MyBatisPlusConfig）→ 追加拦截器
 *  2. MP 自动配置创建了 MybatisPlusInterceptor → 追加拦截器
 *  3. 无任何 MybatisPlusInterceptor → 自动创建并注册到容器
 *
 * 使用 SmartInitializingSingleton 确保所有 bean 初始化完成后注册，避免时序问题。
 *
 * 自动生成于 SQL Visualizer 插件，可安全删除。
 */
@Configuration
public class SqlVisualizerConfig {

    /** 定义 SqlSourceInterceptor bean（不创建 MybatisPlusInterceptor，避免 bean 名称冲突）。 */
    @Bean
    public SqlSourceInterceptor sqlSourceInterceptor() {
        return new SqlSourceInterceptor(Collections.singleton("${basePackage}."));
    }

    /**
     * 在所有 singleton bean 初始化完成后，将 SqlSourceInterceptor 添加到 MybatisPlusInterceptor。
     *
     * - 遍历所有 MybatisPlusInterceptor bean 并追加（兼容多数据源）
     * - 无任何 MybatisPlusInterceptor 时，自动创建并注册到容器
     * - 去重检查避免重复添加
     */
    @Bean
    public SmartInitializingSingleton sqlVisualizerInterceptorRegistrar(
            ApplicationContext applicationContext) {
        return () -> {
            SqlSourceInterceptor sqlSourceInterceptor =
                    applicationContext.getBean(SqlSourceInterceptor.class);
            Map<String, MybatisPlusInterceptor> beans =
                    applicationContext.getBeansOfType(MybatisPlusInterceptor.class);

            if (beans.isEmpty()) {
                // 无任何 MybatisPlusInterceptor → 创建并注册
                MybatisPlusInterceptor newInterceptor = new MybatisPlusInterceptor();
                newInterceptor.addInnerInterceptor(sqlSourceInterceptor);
                ConfigurableListableBeanFactory factory =
                        ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
                factory.registerSingleton("sqlVisualizerMybatisPlusInterceptor", newInterceptor);
            } else {
                // 追加到所有已有的 MybatisPlusInterceptor
                // 注意：不去重（兼容旧版 MP 无 getInnerInterceptors 方法）；
                // 重复添加仅导致重复打印标记行，插件只取第一个标记
                for (MybatisPlusInterceptor mpInterceptor : beans.values()) {
                    mpInterceptor.addInnerInterceptor(sqlSourceInterceptor);
                }
            }
        };
    }
}
""".trimIndent()
}
