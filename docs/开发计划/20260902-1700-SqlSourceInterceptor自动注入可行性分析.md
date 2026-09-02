# SqlSourceInterceptor 自动注入可行性分析

> **创建日期**：2026-09-02
> **状态**：待实施
> **目的**：分析将 `SqlSourceInterceptor` 做成设置开关（用户只需开启，无需手动操作）的可行性

---

## 1. 核心挑战

`SqlSourceInterceptor` 运行在**应用 JVM**（用户正在运行的 Spring Boot/Java 应用），而插件运行在 **IDE JVM**。两个进程隔离，插件无法直接修改运行中的应用。

```
┌─────────────────┐         ┌─────────────────┐
│   IDE JVM       │         │  应用 JVM       │
│  (插件进程)     │  ──╳──  │  (用户应用)     │
│                 │  无法    │                 │
│  设置开关       │  直接    │  MyBatis-Plus   │
│  状态变更       │  注入    │  SqlSession     │
└─────────────────┘         └─────────────────┘
```

**可行路径**：在应用启动前/启动时，通过某种机制将拦截器注入到应用中。

---

## 2. 方案对比

### 方案 A：自动生成配置类（推荐）

**原理**：插件在用户项目中创建拦截器类和 Spring 配置类，应用启动时自动加载。

**流程**：
```
用户开启设置开关
  → 插件检测项目类型（Spring Boot / MyBatis 原生）
  → 在项目源码目录创建 SqlSourceInterceptor.java
  → 创建/修改 @Configuration 类注册拦截器
  → 提示用户重启应用
```

**可行性分析**：

| 维度 | 评估 |
|------|------|
| 技术可行性 | ✅ 高。插件已有 WriteCommandAction + VFS 能力 |
| 项目类型检测 | ✅ 可行。通过 PSI 搜索 `@SpringBootApplication` 或 `mybatis-config.xml` |
| 源码目录定位 | ✅ 可行。通过 `ModuleRootManager` 获取 `src/main/java` |
| 配置注册 | ✅ 可行。Spring Boot 用 `@Configuration`，原生 MyBatis 用 `mybatis-config.xml` |
| 用户体验 | ⚠️ 中等。需要重启应用，但无需手动编码 |
| 适用范围 | ✅ 广。IDE 运行、命令行 `java -jar`、生产环境均生效 |

**优点**：
- 一次配置，永久生效
- 不依赖 IDE 运行（命令行/生产也有效）
- 代码对用户可见，可审查和修改
- 不污染用户代码（独立配置类）

**缺点**：
- 需要重启应用才能生效
- 需要检测项目结构（包名、源码目录）
- 对非标准项目结构可能误判

---

### 方案 B：Java Agent 注入（IDE 运行场景）

**原理**：插件内置一个 Java Agent JAR，通过修改 IDE Run Configuration 添加 `-javaagent` 参数，在应用启动时动态拦截 MyBatis 类加载。

**流程**：
```
用户开启设置开关
  → 插件检测 IDE Run Configuration
  → 自动添加 -javaagent:path/to/sql-visualizer-agent.jar
  → 应用启动时 Agent 注册 ClassFileTransformer
  → 动态修改 MyBatis Executor 字节码插入源码捕获逻辑
```

**可行性分析**：

| 维度 | 评估 |
|------|------|
| 技术可行性 | ⚠️ 中等。需开发 Java Agent + 字节码修改（ByteBuddy/ASM） |
| Run Configuration 修改 | ✅ 可行。`RunManager` + `RunConfiguration` API |
| 字节码修改 | ⚠️ 复杂。需精确识别 MyBatis Executor 类，版本兼容性 |
| 用户体验 | ✅ 好。IDE 内运行完全透明 |
| 适用范围 | ❌ 窄。仅 IDE 运行有效，命令行/生产无效 |

**优点**：
- 零代码修改（不改用户项目）
- 完全透明
- 可动态启停

**缺点**：
- 开发复杂度高（Java Agent + 字节码工程）
- 仅 IDE 运行有效
- MyBatis 版本兼容性风险
- Agent JAR 体积增加插件大小

---

### 方案 C：Spring Boot Starter 依赖注入

**原理**：发布一个 `sql-visualizer-spring-boot-starter` artifact，用户添加依赖后自动配置拦截器。

**流程**：
```
用户开启设置开关
  → 插件检测 pom.xml / build.gradle
  → 自动添加 sql-visualizer-spring-boot-starter 依赖
  → Spring Boot 自动配置机制注册拦截器
  → 提示用户刷新 Maven/Gradle 并重启
```

**可行性分析**：

| 维度 | 评估 |
|------|------|
| 技术可行性 | ⚠️ 中等。需发布和维护 Maven artifact |
| 依赖自动添加 | ✅ 可行。修改 pom.xml/build.gradle 并触发刷新 |
| 自动配置 | ✅ 可行。`spring.factories` / `AutoConfiguration` |
| 用户体验 | ⚠️ 中等。需联网下载依赖 |
| 适用范围 | ⚠️ 窄。仅 Spring Boot 项目 |

**优点**：
- 符合 Spring Boot 生态习惯
- 自动配置，无需手动编码
- 版本管理清晰

**缺点**：
- 需要发布和维护 Maven 仓库（Maven Central 或私有仓库）
- 仅适用于 Spring Boot 项目
- 需要联网下载
- 增加插件分发复杂度

---

### 方案 D：IDE Run Configuration 编程注入（混合方案）

**原理**：结合方案 A 和 B 的优点——对于 IDE 运行场景使用 Java Agent，对于其他场景使用自动生成的配置类。

**流程**：
```
用户开启设置开关
  → 插件检测项目类型
  → 如果是 Spring Boot：
    → 检测是否通过 IDE 运行
      → 是：注入 Java Agent（零配置）
      → 否：生成配置类（需重启）
  → 如果是 MyBatis 原生：
    → 生成 mybatis-config.xml 配置
```

**可行性分析**：

| 维度 | 评估 |
|------|------|
| 技术可行性 | ⚠️ 中等。需同时实现方案 A 和 B |
| 用户体验 | ✅ 好。IDE 内完全透明，外部运行需重启 |
| 适用范围 | ✅ 广。覆盖所有场景 |
| 开发成本 | ❌ 高。两套实现 + 场景检测逻辑 |

---

## 3. 推荐方案：方案 A（自动生成配置类）+ 方案 B 作为增强

### 3.1 推荐策略

**主路径（方案 A）**：自动生成配置类，适用于所有场景
- 一次设置，永久生效
- 不依赖 IDE 运行
- 代码对用户透明可审查

**增强路径（方案 B，后续）**：IDE 运行场景使用 Java Agent
- 完全零配置
- 作为方案 A 的补充体验优化

### 3.2 方案 A 详细设计

#### 3.2.1 设置项

```kotlin
// PluginSettings.kt
data class PluginSettingsState(
    // ... 现有设置 ...
    
    /** 是否自动注入 SqlSourceInterceptor（源码位置捕获） */
    var autoInjectSqlSourceInterceptor: Boolean = false,
)
```

#### 3.2.2 项目类型检测

```kotlin
object ProjectTypeDetector {
    /** 检测项目类型 */
    fun detect(project: Project): ProjectType {
        return when {
            isSpringBootProject(project) -> ProjectType.SPRING_BOOT
            isMyBatisNativeProject(project) -> ProjectType.MYBATIS_NATIVE
            else -> ProjectType.UNSUPPORTED
        }
    }
    
    /** 是否为 Spring Boot 项目（存在 @SpringBootApplication 注解类） */
    private fun isSpringBootProject(project: Project): Boolean {
        // 使用 AnnotatedElementsSearch 搜索 @SpringBootApplication
        // 或检测 spring-boot-starter 依赖
    }
    
    /** 是否为 MyBatis 原生项目（存在 mybatis-config.xml 或 SqlSessionFactory 配置） */
    private fun isMyBatisNativeProject(project: Project): Boolean {
        // 搜索 mybatis-config.xml 或 SqlSessionFactory Bean 配置
    }
    
    /** 获取项目主包名（用于 @ComponentScan 和 interceptor 包） */
    fun getBasePackage(project: Project): String? {
        // 从 @SpringBootApplication 所在包提取
        // 或从 ModuleSourceOrderEntry 的 src/main/java 第一层包推断
    }
    
    /** 获取源码目录（src/main/java 的 VirtualFile） */
    fun getSourceRoot(project: Project): VirtualFile? {
        // 通过 ModuleRootManager 获取 sourceRoots
        // 筛选出 src/main/java
    }
}
```

#### 3.2.3 配置类生成

```kotlin
object SqlSourceInterceptorAutoInjector {
    private const val INTERCEPTOR_CLASS_NAME = "SqlSourceInterceptor"
    private const val CONFIG_CLASS_NAME = "SqlVisualizerConfig"
    
    /** 自动注入入口 */
    fun inject(project: Project): InjectionResult {
        val type = ProjectTypeDetector.detect(project)
        val basePackage = ProjectTypeDetector.getBasePackage(project)
        val sourceRoot = ProjectTypeDetector.getSourceRoot(project)
        
        if (type == ProjectType.UNSUPPORTED) {
            return InjectionResult.UnsupportedProject
        }
        if (basePackage == null || sourceRoot == null) {
            return InjectionResult.CannotDetectStructure
        }
        
        return when (type) {
            ProjectType.SPRING_BOOT -> injectSpringBoot(project, basePackage, sourceRoot)
            ProjectType.MYBATIS_NATIVE -> injectMyBatisNative(project, basePackage, sourceRoot)
            else -> InjectionResult.UnsupportedProject
        }
    }
    
    /** Spring Boot 项目注入：创建 @Configuration 类 + 拦截器类 */
    private fun injectSpringBoot(project: Project, basePackage: String, sourceRoot: VirtualFile): InjectionResult {
        WriteCommandAction.runWriteCommandAction(project) {
            // 1. 创建拦截器类文件
            createInterceptorClass(project, basePackage, sourceRoot)
            // 2. 创建配置类文件
            createConfigClass(project, basePackage, sourceRoot)
        }
        return InjectionResult.Success("已生成 SqlSourceInterceptor 和 SqlVisualizerConfig，重启应用后生效")
    }
    
    /** 创建拦截器类 */
    private fun createInterceptorClass(project: Project, basePackage: String, sourceRoot: VirtualFile) {
        val pkgDir = createPackageDirectories(project, sourceRoot, basePackage)
        val fileContent = generateInterceptorClass(basePackage)
        // 使用 PsiFileFactory 创建 Java 文件
        // 或使用 VFS 创建文件后导入
    }
    
    /** 生成拦截器类代码 */
    private fun generateInterceptorClass(basePackage: String): String = """
        package $basePackage.config;
        
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
         * 在 SQL 执行时捕获调用栈，打印源码标记行到控制台。
         * 插件解析后可实现「跳转到源码」精确跳转。
         */
        public class SqlSourceInterceptor implements InnerInterceptor {
            // ... 完整实现 ...
        }
    """.trimIndent()
    
    /** 生成配置类代码 */
    private fun generateConfigClass(basePackage: String): String = """
        package $basePackage.config;
        
        import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        import java.util.Collections;
        
        @Configuration
        public class SqlVisualizerConfig {
            @Bean
            public MybatisPlusInterceptor mybatisPlusInterceptor() {
                MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
                interceptor.addInnerInterceptor(
                    new SqlSourceInterceptor(Collections.singleton("$basePackage."))
                );
                return interceptor;
            }
        }
    """.trimIndent()
}
```

#### 3.2.4 幂等性保证

- 注入前检查是否已存在 `SqlSourceInterceptor` 类
- 已存在则跳过（避免重复创建）
- 提供「撤销注入」选项（删除生成的文件）

#### 3.2.5 用户交互

```
设置页开关开启
  → 检测项目类型
    → Spring Boot：
      → 弹窗确认：「将在 src/main/java/{package}/config/ 下生成 SqlSourceInterceptor.java 和 SqlVisualizerConfig.java，重启应用后生效。是否继续？」
      → 用户确认 → 生成文件 → 提示「已生成，请重启应用」
    → MyBatis 原生：
      → 弹窗确认：「将修改 mybatis-config.xml 注册拦截器。是否继续？」
      → 用户确认 → 修改配置 → 提示「已配置，请重启应用」
    → 不支持：
      → 提示「无法识别项目类型，请手动配置 SqlSourceInterceptor」
```

---

## 4. 可行性结论

| 方案 | 可行性 | 推荐度 | 说明 |
|------|--------|--------|------|
| A. 自动生成配置类 | ✅ 高 | ⭐⭐⭐ | 一次配置永久生效，适用范围广，开发成本适中 |
| B. Java Agent 注入 | ⚠️ 中 | ⭐⭐ | 仅 IDE 运行有效，开发复杂度高，适合后续增强 |
| C. Spring Boot Starter | ⚠️ 中 | ⭐ | 需发布 artifact，仅 Spring Boot，增加分发复杂度 |
| D. 混合方案 | ⚠️ 中 | ⭐⭐ | 体验最好但开发成本高，适合远期 |

### 推荐实施路径

```
Phase 1（本次）：方案 A — 自动生成配置类
  → 设置开关 + 项目类型检测 + 配置类生成
  → 预估工时：4h

Phase 2（后续）：方案 B — Java Agent 增强
  → IDE 运行场景零配置
  → 预估工时：6h（含 Agent 开发 + 字节码工程）

Phase 3（远期）：方案 C — Spring Boot Starter
  → 发布 artifact，生态化分发
  → 预估工时：8h（含发布流程 + 版本维护）
```

---

## 5. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 项目类型检测误判 | 生成错误配置 | 弹窗确认后再生成；提供撤销功能 |
| 非标准项目结构 | 无法定位源码目录 | 弹窗让用户手动选择目录 |
| 用户已自定义 MybatisPlusInterceptor Bean | 配置冲突 | 检测现有 Bean，提供合并配置选项 |
| MyBatis 版本兼容性 | 拦截器 API 不兼容 | 生成时检测 MP 版本，适配不同 API |
| 用户不希望修改项目 | 侵入性 | 提供纯手动配置选项（现有方案） |

---

## 6. 参考资料

- `docs/instrumentation/SqlSourceInterceptor.kt` — 当前手动配置示例
- `src/main/kotlin/.../log/SqlSourceMarker.kt` — 标记格式定义
- `src/main/kotlin/.../log/CallerSourceLocator.kt` — 调用点定位纯函数
- `src/main/kotlin/.../log/SqlLogConsoleBridge.kt:547-577` — 标记解析逻辑
- `src/main/kotlin/.../config/PluginSettings.kt` — 现有设置模型
- IntelliJ Platform SDK: `ModuleRootManager`, `AnnotatedElementsSearch`, `PsiFileFactory`
