# 方案 A：自动生成 SqlSourceInterceptor 配置类 开发计划

> **创建日期**：2026-09-02
> **优先级**：P1（用户反馈）
> **状态**：待实施
> **依赖**：`20260902-1700-SqlSourceInterceptor自动注入可行性分析.md`（方案 A 已确认可行）

---

## 1. 目标

将 `SqlSourceInterceptor` 做成设置开关，用户只需在设置页开启「自动注入源码捕获拦截器」，插件自动在用户项目中生成拦截器类和配置类，重启应用后即可获得精确的源码跳转能力。

### 1.1 用户体验

```
设置页开启开关
  → 弹窗确认项目类型和生成位置
  → 用户确认
  → 插件自动生成文件
  → 提示「已生成，请重启应用」
  → 用户重启后，所有实时日志携带源码位置
  → 「跳转到源码」精确跳转到调用处
```

---

## 2. 改动文件清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `src/main/kotlin/.../config/PluginSettings.kt` | **修改** | 新增 `autoInjectSqlSourceInterceptor` 设置项 |
| `src/main/kotlin/.../config/PluginSettingsPanel.kt` | **修改** | 设置页新增开关 UI + 注入触发逻辑 |
| `src/main/kotlin/.../log/ProjectTypeDetector.kt` | **新增** | 项目类型检测（Spring Boot / MyBatis 原生） |
| `src/main/kotlin/.../log/SqlSourceInterceptorAutoInjector.kt` | **新增** | 自动注入核心逻辑（文件生成 + 配置注册） |
| `src/main/kotlin/.../log/SourceInterceptorTemplates.kt` | **新增** | 拦截器类 + 配置类代码模板 |
| `src/main/resources/messages/MpSqlVisualizerBundle_zh.properties` | **修改** | 新增 i18n 文案 |
| `src/main/resources/messages/MpSqlVisualizerBundle.properties` | **修改** | 新增 i18n 文案（英文） |
| `src/test/kotlin/.../log/ProjectTypeDetectorTest.kt` | **新增** | 项目类型检测单元测试 |
| `src/test/kotlin/.../log/SqlSourceInterceptorAutoInjectorTest.kt` | **新增** | 自动注入核心逻辑测试 |
| `src/test/kotlin/.../log/SourceInterceptorTemplatesTest.kt` | **新增** | 代码模板生成测试 |

---

## 3. 详细设计

### 3.1 设置项扩展

**改动文件**：`src/main/kotlin/.../config/PluginSettings.kt`

```kotlin
data class PluginSettingsState(
    // ... 现有设置 ...
    
    /**
     * F-44-7：自动注入 SqlSourceInterceptor。
     * 开启后，插件在用户项目中自动生成拦截器类和配置类，
     * 应用重启后实时日志携带源码位置，实现精确跳转。
     */
    var autoInjectSqlSourceInterceptor: Boolean = false,
)
```

### 3.2 项目类型检测

**新增文件**：`src/main/kotlin/.../log/ProjectTypeDetector.kt`

```kotlin
package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

/** 项目类型枚举。 */
enum class ProjectType {
    /** Spring Boot 项目（存在 @SpringBootApplication 注解类）。 */
    SPRING_BOOT,
    /** MyBatis 原生项目（存在 mybatis-config.xml 或 SqlSessionFactory 配置）。 */
    MYBATIS_NATIVE,
    /** 不支持的项目类型。 */
    UNSUPPORTED,
}

/** 项目结构信息。 */
data class ProjectStructure(
    val type: ProjectType,
    val basePackage: String?,
    val sourceRoot: VirtualFile,
)

object ProjectTypeDetector {
    
    /** 检测项目类型和结构。 */
    fun detect(project: Project): ProjectStructure? {
        val modules = ModuleManager.getInstance(project).modules
        if (modules.isEmpty()) return null
        
        // 获取主源码目录
        val sourceRoot = findMainSourceRoot(modules) ?: return null
        
        // 检测 Spring Boot
        if (isSpringBootProject(project, sourceRoot)) {
            val basePackage = findBasePackage(project, sourceRoot)
            if (basePackage != null) {
                return ProjectStructure(ProjectType.SPRING_BOOT, basePackage, sourceRoot)
            }
        }
        
        // 检测 MyBatis 原生
        if (isMyBatisNativeProject(project, sourceRoot)) {
            val basePackage = findBasePackage(project, sourceRoot)
            if (basePackage != null) {
                return ProjectStructure(ProjectType.MYBATIS_NATIVE, basePackage, sourceRoot)
            }
        }
        
        return ProjectStructure(ProjectType.UNSUPPORTED, null, sourceRoot)
    }
    
    /** 查找主源码目录（src/main/java）。 */
    private fun findMainSourceRoot(modules: Array<com.intellij.openapi.module.Module>): VirtualFile? {
        for (module in modules) {
            val rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module)
            for (sourceRoot in rootManager.sourceRoots) {
                if (sourceRoot.path.endsWith("src/main/java") || 
                    sourceRoot.path.endsWith("src/main/java/")) {
                    return sourceRoot
                }
            }
        }
        // 回退：取第一个源码目录
        for (module in modules) {
            val rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module)
            val sourceRoots = rootManager.getSourceRoots(false)
            if (sourceRoots.isNotEmpty()) return sourceRoots[0]
        }
        return null
    }
    
    /** 检测是否为 Spring Boot 项目。 */
    private fun isSpringBootProject(project: Project, sourceRoot: VirtualFile): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        
        // 方法 1：搜索 @SpringBootApplication 注解
        try {
            val annotationClass = facade.findClass(
                "org.springframework.boot.autoconfigure.SpringBootApplication", 
                scope
            ) ?: return false
            
            val annotated = AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope)
            return annotated.find() != null
        } catch (_: Exception) {
            // 方法 2：回退检测 spring-boot 依赖（通过 PSI 搜索 @Configuration + @EnableAutoConfiguration）
            return hasSpringBootMarkers(project, scope)
        }
    }
    
    /** 回退检测 Spring Boot 标记。 */
    private fun hasSpringBootMarkers(project: Project, scope: GlobalSearchScope): Boolean {
        val facade = JavaPsiFacade.getInstance(project)
        val configClass = facade.findClass("org.springframework.context.annotation.Configuration", scope)
        val enableAutoConfigClass = facade.findClass("org.springframework.boot.autoconfigure.EnableAutoConfiguration", scope)
        
        return configClass != null && enableAutoConfigClass != null
    }
    
    /** 检测是否为 MyBatis 原生项目。 */
    private fun isMyBatisNativeProject(project: Project, sourceRoot: VirtualFile): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        
        // 检测 mybatis 核心类是否在 classpath 中
        val sqlSessionFactory = facade.findClass("org.apache.ibatis.session.SqlSessionFactory", scope)
        return sqlSessionFactory != null
    }
    
    /** 查找项目主包名。 */
    private fun findBasePackage(project: Project, sourceRoot: VirtualFile): String? {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        
        // 方法 1：从 @SpringBootApplication 所在包提取
        try {
            val annotationClass = facade.findClass(
                "org.springframework.boot.autoconfigure.SpringBootApplication", 
                scope
            ) ?: return inferPackageFromSourceRoot(project, sourceRoot)
            
            val annotated = AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope)
            val psiClass = annotated.find() ?: return inferPackageFromSourceRoot(project, sourceRoot)
            
            val qualifiedName = psiClass.qualifiedName ?: return inferPackageFromSourceRoot(project, sourceRoot)
            // @SpringBootApplication 所在包即为 basePackage
            return qualifiedName.substringBeforeLast('.')
        } catch (_: Exception) {
            return inferPackageFromSourceRoot(project, sourceRoot)
        }
    }
    
    /** 从源码目录推断包名（取第一个 Java 文件的包名）。 */
    private fun inferPackageFromSourceRoot(project: Project, sourceRoot: VirtualFile): String? {
        val psiManager = PsiManager.getInstance(project)
        val psiDirectory = psiManager.findDirectory(sourceRoot) ?: return null
        
        // 递归查找第一个 .java 文件，提取其包名
        return findFirstPackage(psiDirectory)
    }
    
    /** 递归查找第一个 Java 文件的包名。 */
    private fun findFirstPackage(directory: PsiDirectory): String? {
        val project = directory.project
        val manager = PsiManager.getInstance(project)
        
        // 先搜索当前目录的文件
        for (file in directory.files) {
            if (file.name.endsWith(".java")) {
                val psiFile = file as? PsiFile ?: continue
                // 提取包名
                val text = psiFile.text
                val pkgMatch = Regex("""^\s*package\s+([\w.]+)\s*;""").find(text)
                if (pkgMatch != null) return pkgMatch.groupValues[1]
            }
        }
        
        // 递归子目录
        for (subDir in directory.subdirectories) {
            val pkg = findFirstPackage(subDir)
            if (pkg != null) return pkg
        }
        
        return null
    }
}
```

### 3.3 代码模板

**新增文件**：`src/main/kotlin/.../log/SourceInterceptorTemplates.kt`

```kotlin
package cn.song.mybatisplus.visualizer.log

/** SqlSourceInterceptor 和配置类的代码模板。 */
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
 * 标记行格式：SQLVIS-SOURCE|&lt;相对路径&gt;|&lt;行号&gt;
 * 例：SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|87
 * 
 * 自动生成于 SQL Visualizer 插件，可安全删除（删除后源码跳转回退为表名搜索）。
 */
public class SqlSourceInterceptor implements InnerInterceptor {

    private final Set<String> appPackages;

    public SqlSourceInterceptor(Set<String> appPackages) {
        this.appPackages = appPackages;
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms,
            Object parameter, RowBounds rowBounds,
            ResultHandler<?> resultHandler, BoundSql boundSql) throws SQLException {
        emitSource();
    }

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms,
            Object parameter) throws SQLException {
        emitSource();
    }

    /** 把当前线程调用栈里的真实调用点打印成标记行。 */
    private void emitSource() {
        Pair<String, Integer> src = locateSource();
        if (src != null) {
            System.out.println("SQLVIS-SOURCE|" + src.first + "|" + src.second);
        }
    }

    /** 沿调用栈跳过框架帧，取第一个用户代码帧。 */
    private Pair<String, Integer> locateSource() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cn = frame.getClassName();
            if (isFrameworkFrame(cn)) continue;
            if (!appPackages.isEmpty() && appPackages.stream().noneMatch(cn::startsWith)) continue;
            String fileName = frame.getFileName();
            if (fileName == null) continue;
            String pkg = cn.substringBeforeLast('.').replace('.', '/');
            String rel = pkg.isEmpty() ? fileName : pkg + "/" + fileName;
            int line = frame.getLineNumber();
            if (line < 0) continue;
            return new Pair<>(rel, line);
        }
        return null;
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

    /** 简单键值对（避免引入外部依赖）。 */
    private static class Pair<F, S> {
        final F first;
        final S second;
        Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }
    }
}
""".trimIndent()

    /** 生成 Spring Boot 配置类代码。 */
    fun generateSpringBootConfigClass(basePackage: String): String = """
package ${basePackage}.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * SQL Visualizer 源码位置捕获配置（自动生成）。
 * 
 * 注册 SqlSourceInterceptor 到 MyBatis-Plus 拦截器链，
 * 使实时日志携带源码位置信息，支持精确跳转。
 * 
 * 自动生成于 SQL Visualizer 插件，可安全删除。
 */
@Configuration
public class SqlVisualizerConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(
                new SqlSourceInterceptor(Collections.singleton("${basePackage}."))
        );
        return interceptor;
    }
}
""".trimIndent()
}
```

### 3.4 自动注入核心逻辑

**新增文件**：`src/main/kotlin/.../log/SqlSourceInterceptorAutoInjector.kt`

```kotlin
package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

/** 注入结果。 */
sealed class InjectionResult {
    /** 注入成功。 */
    data class Success(val message: String) : InjectionResult()
    /** 项目类型不支持。 */
    data object UnsupportedProject : InjectionResult()
    /** 无法检测项目结构。 */
    data object CannotDetectStructure : InjectionResult()
    /** 文件已存在（幂等跳过）。 */
    data object AlreadyExists : InjectionResult()
}

object SqlSourceInterceptorAutoInjector {
    
    private val LOG = Logger.getInstance(SqlSourceInterceptorAutoInjector::class.java)
    
    /** 自动注入入口。 */
    fun inject(project: Project): InjectionResult {
        // 1. 检测项目结构
        val structure = ProjectTypeDetector.detect(project)
            ?: return InjectionResult.CannotDetectStructure
        
        if (structure.type == ProjectType.UNSUPPORTED) {
            return InjectionResult.UnsupportedProject
        }
        
        val basePackage = structure.basePackage
            ?: return InjectionResult.CannotDetectStructure
        
        val sourceRoot = structure.sourceRoot
        
        // 2. 检查是否已存在（幂等性）
        if (interceptorExists(project, sourceRoot, basePackage)) {
            return InjectionResult.AlreadyExists
        }
        
        // 3. 生成文件
        return when (structure.type) {
            ProjectType.SPRING_BOOT -> injectSpringBoot(project, basePackage, sourceRoot)
            ProjectType.MYBATIS_NATIVE -> injectMyBatisNative(project, basePackage, sourceRoot)
            else -> InjectionResult.UnsupportedProject
        }
    }
    
    /** 检查拦截器类是否已存在。 */
    private fun interceptorExists(project: Project, sourceRoot: VirtualFile, basePackage: String): Boolean {
        val configDir = "${basePackage.replace('.', '/')}/config"
        val interceptorPath = "${sourceRoot.path}/$configDir/${SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME}"
        val file = LocalFileSystem.getInstance().findFileByPath(interceptorPath)
        return file != null && file.exists()
    }
    
    /** Spring Boot 项目注入。 */
    private fun injectSpringBoot(project: Project, basePackage: String, sourceRoot: VirtualFile): InjectionResult {
        return try {
            WriteCommandAction.runWriteCommandAction<InjectionResult>(project) {
                // 创建 config 包目录
                val configDir = createPackageDirectories(project, sourceRoot, "$basePackage.config")
                    ?: return@runWriteCommandAction InjectionResult.CannotDetectStructure
                
                // 创建拦截器类
                createJavaFile(
                    project, configDir,
                    SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME,
                    SourceInterceptorTemplates.generateInterceptorClass(basePackage)
                )
                
                // 创建配置类
                createJavaFile(
                    project, configDir,
                    SourceInterceptorTemplates.CONFIG_FILE_NAME,
                    SourceInterceptorTemplates.generateSpringBootConfigClass(basePackage)
                )
                
                InjectionResult.Success(
                    "已生成 ${SourceInterceptorTemplates.INTERCEPTOR_FILE_NAME} 和 ${SourceInterceptorTemplates.CONFIG_FILE_NAME}，" +
                    "位于 ${basePackage}.config 包下，重启应用后生效。"
                )
            }
        } catch (e: Exception) {
            LOG.warn("SqlSourceInterceptorAutoInjector 注入失败", e)
            InjectionResult.CannotDetectStructure
        }
    }
    
    /** MyBatis 原生项目注入（TODO：后续实现）。 */
    private fun injectMyBatisNative(project: Project, basePackage: String, sourceRoot: VirtualFile): InjectionResult {
        // TODO：检测 mybatis-config.xml 并添加拦截器配置
        return InjectionResult.UnsupportedProject
    }
    
    /** 创建包目录（递归）。 */
    private fun createPackageDirectories(project: Project, sourceRoot: VirtualFile, packageQualifiedName: String): PsiDirectory? {
        val psiManager = PsiManager.getInstance(project)
        var currentDir = psiManager.findDirectory(sourceRoot) ?: return null
        
        for (pkgPart in packageQualifiedName.split('.')) {
            val existing = currentDir.findSubdirectory(pkgPart)
            if (existing != null) {
                currentDir = existing
            } else {
                currentDir = currentDir.createSubdirectory(pkgPart)
            }
        }
        
        return currentDir
    }
    
    /** 创建 Java 文件（PSI）。 */
    private fun createJavaFile(project: Project, directory: PsiDirectory, fileName: String, content: String) {
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(fileName, com.intellij.lang.Language.findLanguageByID("JAVA")!!, content)
        
        // 代码格式化
        CodeStyleManager.getInstance(project).reformat(psiFile)
        
        // 添加到目录
        directory.add(psiFile)
    }
}
```

### 3.5 设置页 UI

**改动文件**：`src/main/kotlin/.../config/PluginSettingsPanel.kt`

在设置页「日志」区域新增：

```kotlin
// 自动注入源码捕获拦截器
val injectCheckbox = JCheckBox(MpSqlVisualizerBundle.message("settings.log.autoInjectInterceptor"))
injectCheckbox.isSelected = state.autoInjectSqlSourceInterceptor
injectCheckbox.addActionListener {
    state.autoInjectSqlSourceInterceptor = injectCheckbox.isSelected
    if (injectCheckbox.isSelected) {
        // 触发注入
        val result = SqlSourceInterceptorAutoInjector.inject(project)
        when (result) {
            is InjectionResult.Success -> {
                Messages.showInfoMessage(project, result.message, "源码跳转配置")
            }
            is InjectionResult.AlreadyExists -> {
                Messages.showInfoMessage(project, "配置已存在，无需重复生成。", "源码跳转配置")
            }
            is InjectionResult.UnsupportedProject -> {
                Messages.showWarningDialog(
                    project,
                    "无法识别项目类型。请确保项目是 Spring Boot + MyBatis-Plus 项目。\n\n" +
                    "也可手动配置：参考 docs/instrumentation/SqlSourceInterceptor.kt",
                    "源码跳转配置"
                )
                injectCheckbox.isSelected = false
                state.autoInjectSqlSourceInterceptor = false
            }
            is InjectionResult.CannotDetectStructure -> {
                Messages.showWarningDialog(
                    project,
                    "无法检测项目结构。请确保项目存在 src/main/java 源码目录。",
                    "源码跳转配置"
                )
                injectCheckbox.isSelected = false
                state.autoInjectSqlSourceInterceptor = false
            }
        }
    }
}
```

### 3.6 i18n 文案

**改动文件**：`src/main/resources/messages/MpSqlVisualizerBundle_zh.properties`

```properties
settings.log.autoInjectInterceptor=自动注入源码捕获拦截器（SqlSourceInterceptor）
settings.log.autoInjectInterceptor.tooltip=开启后，插件自动生成拦截器配置，实时日志将携带源码位置信息
```

**改动文件**：`src/main/resources/messages/MpSqlVisualizerBundle.properties`

```properties
settings.log.autoInjectInterceptor=Auto-inject SqlSourceInterceptor
settings.log.autoInjectInterceptor.tooltip=Auto-generate interceptor config; real-time logs will carry source location
```

---

## 4. 测试计划

### 4.1 单元测试

| 测试类 | 测试内容 |
|--------|---------|
| `ProjectTypeDetectorTest` | Spring Boot 项目检测（有 @SpringBootApplication）；MyBatis 原生项目检测；不支持的项目；源码目录定位；包名提取 |
| `SqlSourceInterceptorAutoInjectorTest` | 注入成功生成两个文件；幂等性（重复注入返回 AlreadyExists）；不支持项目返回 UnsupportedProject；目录创建递归 |
| `SourceInterceptorTemplatesTest` | 生成代码包含关键类名；包名替换正确；代码格式合法 |

### 4.2 集成测试

| 场景 | 预期结果 |
|------|---------|
| Spring Boot + MP 项目开启开关 | 生成 SqlSourceInterceptor.java + SqlVisualizerConfig.java |
| 重启应用后执行 SQL | 日志条目携带 sourceFile |
| 右键「跳转到源码」 | 精确跳转到调用处 |
| 重复开启开关 | 提示「配置已存在」 |
| 非 Spring Boot 项目 | 提示「无法识别项目类型」 |

### 4.3 回归测试

- 已有全部设置页测试通过
- 已有全部日志面板测试通过
- 已有全部跳转测试通过

---

## 5. 改动文件依赖关系

```
PluginSettings.kt (新增设置项)
    ↓
PluginSettingsPanel.kt (新增 UI + 触发注入)
    ↓
SqlSourceInterceptorAutoInjector.kt (注入核心)
    ↓
ProjectTypeDetector.kt (项目检测)    SourceInterceptorTemplates.kt (代码模板)
```

---

## 6. 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 项目类型检测误判 | 生成错误配置 | 弹窗确认后再生成；提供撤销功能 |
| 非标准项目结构 | 无法定位源码目录 | 弹窗让用户手动选择目录 |
| 用户已自定义 MybatisPlusInterceptor Bean | 配置冲突 | 检测现有 Bean，提供合并配置选项 |
| 生成的代码与用户代码风格不一致 | 代码风格差异 | 使用 CodeStyleManager 格式化 |
| 用户不希望修改项目 | 侵入性 | 提供纯手动配置选项（现有方案） |

---

## 7. 里程碑

| 阶段 | 内容 | 预估工时 |
|------|------|---------|
| Phase 1 | `ProjectTypeDetector` 项目类型检测 + 测试 | 1h |
| Phase 2 | `SourceInterceptorTemplates` 代码模板 + 测试 | 0.5h |
| Phase 3 | `SqlSourceInterceptorAutoInjector` 注入核心 + 测试 | 1.5h |
| Phase 4 | `PluginSettingsPanel` UI + 设置项集成 | 0.5h |
| Phase 5 | i18n + 回归验证 | 0.5h |
| **合计** | | **4h** |

---

## 8. 参考资料

- `docs/instrumentation/SqlSourceInterceptor.kt` — 当前手动配置示例（拦截器实现参考）
- `src/main/kotlin/.../log/SqlSourceMarker.kt` — 标记格式定义
- `src/main/kotlin/.../log/CallerSourceLocator.kt` — 调用点定位纯函数
- `src/main/kotlin/.../log/SqlLogConsoleBridge.kt:547-577` — 标记解析逻辑
- `src/main/kotlin/.../config/PluginSettings.kt` — 现有设置模型
- `src/main/kotlin/.../config/PluginSettingsPanel.kt` — 现有设置面板
- IntelliJ Platform SDK: `ModuleRootManager`, `AnnotatedElementsSearch`, `PsiFileFactory`, `CodeStyleManager`
