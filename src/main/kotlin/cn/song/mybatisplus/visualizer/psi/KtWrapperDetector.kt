package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.psi.PsiFile

/**
 * Kotlin Wrapper 链检测器（F-4 MVP）。
 *
 * 扫描 Kotlin 文件中的 MyBatis-Plus Wrapper 调用链，基于方法名模式匹配（与 Java 一致），
 * 不做深度 PSI resolve，保证 O(n) 性能（n = 文件行数）。
 *
 * Kotlin 中的 MP 调用形态：
 *  - `service.ktQuery().eq(User::name, "x").like(User::email, "@").list()`（IService 扩展函数）
 *  - `service.ktUpdate().set(User::name, "y").eq(User::id, 1).update()`
 *  - `LambdaQueryWrapper<User>().eq(User::name, "x").list()`（直接构造）
 *
 * MVP 策略：按行扫描源码文本，用正则匹配链起点（ktQuery/ktUpdate/LambdaQueryWrapper 构造），
 * 收集同行链上调用（eq/like/set 等）；深度参数解析（KtLambdaArgument、属性引用）留待后续迭代。
 */
object KtWrapperDetector {

    /** 链式终结点方法名（消费方法，非 Wrapper 操作）：与 Java 端一致。 */
    private val chainTerminalMethodNames: Set<String> = setOf(
        "one", "oneOpt", "list", "count", "exists", "page",
        "getOne", "getMap", "listMaps", "listObjs", "getObj", "map",
        "first", "mapOne", "mapFirst", "mapList", "mapPage",
        "update", "updateAndNull", "remove",
    )

    /** Wrapper 操作条件方法名（用于判定链式调用是否为 Wrapper 链）。 */
    private val wrapperOperationMethodNames: Set<String> = setOf(
        "eq", "ne", "gt", "ge", "lt", "le",
        "like", "notLike", "likeLeft", "likeRight",
        "notIn", "in", "between", "notBetween",
        "isNull", "isNotNull",
        "groupBy", "orderByAsc", "orderByDesc", "orderBy",
        "having", "or", "and", "apply", "last",
        "select", "set", "setSql",
    )

    /** Wrapper 构造函数名 → 类型映射。 */
    private val wrapperConstructorTypes: Map<String, WrapperType> = mapOf(
        "LambdaQueryWrapper" to WrapperType.LAMBDA_QUERY,
        "LambdaUpdateWrapper" to WrapperType.LAMBDA_UPDATE,
        "LambdaDeleteWrapper" to WrapperType.LAMBDA_DELETE,
        "QueryWrapper" to WrapperType.QUERY,
        "UpdateWrapper" to WrapperType.UPDATE,
        "MPJLambdaWrapper" to WrapperType.MPJ_LAMBDA,
        "MPJQueryWrapper" to WrapperType.MPJ_QUERY,
    )

    /** Kotlin 扩展函数工厂方法名 → 类型映射。 */
    private val extensionFactoryMethods: Map<String, WrapperType> = mapOf(
        "ktQuery" to WrapperType.LAMBDA_QUERY,
        "ktUpdate" to WrapperType.LAMBDA_UPDATE,
    )

    /**
     * 扫描 Kotlin 文件中的 Wrapper 调用链。
     *
     * @param psiFile 目标 PSI 文件
     * @return 检测到的 Wrapper 链列表（可能为空）
     */
    fun detect(psiFile: PsiFile): List<WrapperChain> {
        val fileExtension = psiFile.virtualFile?.extension ?: ""
        if (fileExtension != "kt" && fileExtension != "kts") return emptyList()

        val chains = mutableListOf<WrapperChain>()
        val text = psiFile.text ?: return emptyList()
        val lines = text.split("\n")

        for ((lineIndex, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // 跳过空行和注释
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue

            // 检测 ktQuery() / ktUpdate() 扩展函数链
            detectLineExtensionChain(trimmed)?.let { wrapperType ->
                val calls = extractChainCalls(trimmed)
                if (calls.isNotEmpty()) {
                    chains += WrapperChain(wrapperType, null, calls, null, false, false, false, false, null)
                }
            }

            // 检测直接构造 Wrapper 链
            detectLineConstructorChain(trimmed)?.let { wrapperType ->
                val calls = extractChainCalls(trimmed)
                if (calls.isNotEmpty()) {
                    chains += WrapperChain(wrapperType, null, calls, null, false, false, false, false, null)
                }
            }
        }

        return chains
    }

    /**
     * 检测单行是否包含 ktQuery()/ktUpdate() 扩展函数链起点。
     * 匹配模式：`xxx.ktQuery()...` 或 `xxx.ktUpdate()...`
     */
    private fun detectLineExtensionChain(line: String): WrapperType? {
        for ((methodName, wrapperType) in extensionFactoryMethods) {
            // 匹配 `.ktQuery(` 或 `.ktUpdate(` 模式（前面有 qualifier）
            if (line.contains(".${methodName}(")) {
                return wrapperType
            }
        }
        return null
    }

    /**
     * 检测单行是否包含直接构造 Wrapper 链起点。
     * 匹配模式：`LambdaQueryWrapper<X>()...` 等。
     */
    private fun detectLineConstructorChain(line: String): WrapperType? {
        for ((constructorName, wrapperType) in wrapperConstructorTypes) {
            // 匹配 `LambdaQueryWrapper(` 模式（行首或前面是 `=` / `(` 等）
            if (line.contains("$constructorName<") || line.contains("$constructorName(")) {
                return wrapperType
            }
        }
        return null
    }

    /**
     * 从单行提取链上方法调用。
     * 扫描 `.methodName(` 模式，过滤终结点和非 Wrapper 操作。
     */
    private fun extractChainCalls(line: String): List<WrapperCall> {
        val calls = mutableListOf<WrapperCall>()
        // 正则匹配 `.methodName(` 模式
        val pattern = Regex("""\.(\w+)\s*\(""")
        val matches = pattern.findAll(line)

        for (match in matches) {
            val methodName = match.groupValues[1]
            // 跳过终结点方法
            if (methodName in chainTerminalMethodNames) continue
            // 只保留 Wrapper 操作方法
            if (methodName !in wrapperOperationMethodNames) continue

            // MVP：参数仅做简单提取（文本占位）
            // 找到匹配位置之后的参数文本
            val args = extractCallArgs(line, match.range.first)
            calls += WrapperCall(methodName, args)
        }

        return calls
    }

    /**
     * 提取调用的参数（MVP）：简单提取括号内的文本作为占位值。
     */
    private fun extractCallArgs(line: String, dotIndex: Int): List<WrapperArg> {
        // 找到 .methodName( 之后的括号内容
        val parenStart = line.indexOf('(', dotIndex)
        if (parenStart < 0) return emptyList()

        // 简单提取到行尾或下一个 `.methodName(` 之前
        val restOfLine = line.substring(parenStart + 1).trim()
        if (restOfLine.isEmpty()) return emptyList()

        // MVP：参数文本占位
        return listOf(WrapperArg.ValueArg(SqlValue.Placeholder(restOfLine.take(50))))
    }
}
