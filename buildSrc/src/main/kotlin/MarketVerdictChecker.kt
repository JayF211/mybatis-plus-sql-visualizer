package mpv.verification

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * verifyMarketplace 的判定逻辑（20260923 T-MarketCompat 起用）。
 *
 * 独立文件而非 gradle.kts 顶层函数的原因（实测）：
 *   - 逻辑内嵌 doLast 的嵌套 lambda（捕获可变集合 + 局部 data class）触发 Gradle Kotlin DSL 编译器
 *     「Exception while generating code for」；
 *   - 抽成脚本顶层函数后，doLast 对其的调用又触发配置缓存「cannot serialize Gradle script object
 *     references」。
 *   - 独立 .kt 文件的顶层函数编译为普通类（本文件名 → MarketVerdictCheckerKt），配置缓存可序列化。
 *
 * 判定语义：判文本而非退出码——verifier-cli 带 compatibility problems 仍可能退出 0（实测）。
 * 单包定案下旧版目标上的「可选依赖未解析」问题机理上不可归零，故采用「问题块基线」：
 *   1. compatibility-problems.txt 按块首行（顶格且以 Class /Method /Package 起头）切问题块；
 *   2. 每块必须命中基线文件里同目标的登记条目，任何未登记的新问题块 → 失败（防再次失明）；
 *   3. 全部命中 → 通过（打印覆盖计数）；未命中的基线条目打 WARN（提示可能已修复）。
 */
fun checkMarketplaceVerdicts(
    reportsDir: File,
    baselineFile: File,
    logger: Logger,
) {
    val verdicts = reportsDir.walkTopDown()
        .filter { it.name == "verification-verdict.txt" }
        .sortedBy { it.path }
        .toList()
    if (verdicts.isEmpty()) {
        throw GradleException("verifyMarketplace: 未找到 verification-verdict.txt，请检查 $reportsDir")
    }

    data class Rule(val target: String, val regex: Regex)
    val baseline = if (baselineFile.isFile) {
        baselineFile.readLines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val sep = t.indexOf('\t')
            if (sep <= 0) error("基线文件格式错误（应为「目标<TAB>正则」）：$baselineFile 第 ${baselineFile.readLines().indexOf(line) + 1} 行")
            Rule(t.take(sep).trim(), Regex(t.substring(sep + 1).trim()))
        }
    } else {
        emptyList()
    }

    val failures = mutableListOf<String>()
    var coveredBlocks = 0
    val usedRules = mutableSetOf<Int>()
    verdicts.forEach { v ->
        // 目标名 = reportsDir 相对路径的首段（IU-243.28141.41 这类 IDE 名）。
        // 注意不能用 v.parentFile.parentFile.name——那层是插件名（cn.song...），verifyGate
        // 只打印不判定所以没暴露；本任务按 target 匹配基线，层级必须对。
        // Windows 上 relativize().toString() 用反斜杠分隔，须按 File.separator 切，不能硬编码 '/'
        val rel = reportsDir.toPath().relativize(v.toPath()).toString()
        val target = rel.substringBefore(File.separator)
        val verdictText = v.readText().trim()
        logger.lifecycle("verifyMarketplace verdict [$target] ${verdictText.replace('\n', ' ').take(110)}")
        if (verdictText.startsWith("Compatible")) return@forEach
        val problemsFile = v.parentFile.resolve("compatibility-problems.txt")
        if (!problemsFile.isFile) {
            failures += "$target: verdict 报兼容问题但无 compatibility-problems.txt"
            return@forEach
        }
        val headerLines = problemsFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith(" ") }
            .filter { it.startsWith("Class ") || it.startsWith("Method ") || it.startsWith("Package ") }
        val rules = baseline.filter { it.target == target }
        headerLines.forEachIndexed { idx, header ->
            val hit = rules.indexOfFirst { it.regex.containsMatchIn(header) }
            if (hit < 0) {
                failures += "$target: 未登记的兼容问题块 #$idx → $header"
            } else {
                coveredBlocks++
                usedRules += baseline.indexOf(rules[hit])
            }
        }
        if (headerLines.isEmpty()) {
            failures += "$target: verdict 报兼容问题但未解析出问题块（文件格式变化？请人工检查 $problemsFile）"
        }
    }
    baseline.forEachIndexed { idx, rule ->
        if (idx !in usedRules) {
            logger.warn("verifyMarketplace: 基线条目从未命中当前问题（可能已修复，建议清理）：[${rule.target}] ${rule.regex.pattern.take(80)}")
        }
    }
    if (failures.isNotEmpty()) {
        throw GradleException(
            "verifyMarketplace 失败：${failures.size} 条未登记的兼容问题（Marketplace 同口径）。\n" +
                failures.joinToString("\n") { "  - $it" } +
                "\n处理：修掉它，或确认运行期安全后在 $baselineFile 登记条目（逐块、带论证注释）。" +
                "基线只收「已查明运行期被片段加载条件或反射探测隔离」的项。",
        )
    }
    logger.lifecycle("verifyMarketplace 通过：${verdicts.size} 个目标，$coveredBlocks 个已知问题块全部被基线覆盖")
}
