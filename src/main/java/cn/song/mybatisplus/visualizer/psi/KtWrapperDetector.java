package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import com.intellij.psi.PsiFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kotlin Wrapper 链检测器（F-4 MVP）。
 *
 * <p>扫描 Kotlin 文件中的 MyBatis-Plus Wrapper 调用链，基于方法名模式匹配（与 Java 一致），
 * 不做深度 PSI resolve，保证 O(n) 性能（n = 文件行数）。
 *
 * <p>Kotlin 中的 MP 调用形态：
 * <ul>
 *   <li>{@code service.ktQuery().eq(User::name, "x").like(User::email, "@").list()}（IService 扩展函数）</li>
 *   <li>{@code service.ktUpdate().set(User::name, "y").eq(User::id, 1).update()}</li>
 *   <li>{@code LambdaQueryWrapper<User>().eq(User::name, "x").list()}（直接构造）</li>
 * </ul>
 *
 * <p>MVP 策略：按行扫描源码文本，用正则匹配链起点（ktQuery/ktUpdate/LambdaQueryWrapper 构造），
 * 收集同行链上调用（eq/like/set 等）；深度参数解析（KtLambdaArgument、属性引用）留待后续迭代。
 *
 * <p>由 Kotlin {@code object KtWrapperDetector} 转换而来。
 */
public final class KtWrapperDetector {

    public static final KtWrapperDetector INSTANCE = new KtWrapperDetector();

    /** 链式终结点方法名（消费方法，非 Wrapper 操作）：与 Java 端一致。 */
    private static final Set<String> CHAIN_TERMINAL_METHOD_NAMES = Set.of(
            "one", "oneOpt", "list", "count", "exists", "page",
            "getOne", "getMap", "listMaps", "listObjs", "getObj", "map",
            "first", "mapOne", "mapFirst", "mapList", "mapPage",
            "update", "updateAndNull", "remove");

    /** Wrapper 操作条件方法名（用于判定链式调用是否为 Wrapper 链）。 */
    private static final Set<String> WRAPPER_OPERATION_METHOD_NAMES = Set.of(
            "eq", "ne", "gt", "ge", "lt", "le",
            "like", "notLike", "likeLeft", "likeRight",
            "notIn", "in", "between", "notBetween",
            "isNull", "isNotNull",
            "groupBy", "orderByAsc", "orderByDesc", "orderBy",
            "having", "or", "and", "apply", "last",
            "select", "set", "setSql");

    /**
     * Wrapper 构造函数名 → 类型映射。
     *
     * <p>用 {@link LinkedHashMap} 保留声明顺序：检测时按序首个命中即返回，
     * Java {@code Map.of()} 迭代顺序未定义，会改变行为。
     */
    private static final Map<String, WrapperType> WRAPPER_CONSTRUCTOR_TYPES = createConstructorTypes();

    /** Kotlin 扩展函数工厂方法名 → 类型映射（同样需保留声明顺序）。 */
    private static final Map<String, WrapperType> EXTENSION_FACTORY_METHODS = createExtensionFactoryMethods();

    /** 链上方法调用匹配模式：`.methodName(`。 */
    private static final Pattern CHAIN_CALL_PATTERN = Pattern.compile("\\.(\\w+)\\s*\\(");

    private KtWrapperDetector() {
    }

    private static Map<String, WrapperType> createConstructorTypes() {
        Map<String, WrapperType> map = new LinkedHashMap<>();
        map.put("LambdaQueryWrapper", WrapperType.LAMBDA_QUERY);
        map.put("LambdaUpdateWrapper", WrapperType.LAMBDA_UPDATE);
        map.put("LambdaDeleteWrapper", WrapperType.LAMBDA_DELETE);
        map.put("QueryWrapper", WrapperType.QUERY);
        map.put("UpdateWrapper", WrapperType.UPDATE);
        map.put("MPJLambdaWrapper", WrapperType.MPJ_LAMBDA);
        map.put("MPJQueryWrapper", WrapperType.MPJ_QUERY);
        return map;
    }

    private static Map<String, WrapperType> createExtensionFactoryMethods() {
        Map<String, WrapperType> map = new LinkedHashMap<>();
        map.put("ktQuery", WrapperType.LAMBDA_QUERY);
        map.put("ktUpdate", WrapperType.LAMBDA_UPDATE);
        return map;
    }

    /**
     * 扫描 Kotlin 文件中的 Wrapper 调用链。
     *
     * @param psiFile 目标 PSI 文件
     * @return 检测到的 Wrapper 链列表（可能为空）
     */
    public static List<WrapperChain> detect(PsiFile psiFile) {
        String fileExtension = psiFile.getVirtualFile() != null
                ? psiFile.getVirtualFile().getExtension() : null;
        if (fileExtension == null) {
            fileExtension = "";
        }
        if (!fileExtension.equals("kt") && !fileExtension.equals("kts")) {
            return List.of();
        }

        List<WrapperChain> chains = new ArrayList<>();
        String text = psiFile.getText();
        if (text == null) {
            return List.of();
        }
        String[] lines = text.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过空行和注释
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }

            // 检测 ktQuery() / ktUpdate() 扩展函数链
            WrapperType extensionType = detectLineExtensionChain(trimmed);
            if (extensionType != null) {
                List<WrapperCall> calls = extractChainCalls(trimmed);
                if (!calls.isEmpty()) {
                    chains.add(new WrapperChain(extensionType, null, calls, null, false, false, false, false, null));
                }
            }

            // 检测直接构造 Wrapper 链
            WrapperType constructorType = detectLineConstructorChain(trimmed);
            if (constructorType != null) {
                List<WrapperCall> calls = extractChainCalls(trimmed);
                if (!calls.isEmpty()) {
                    chains.add(new WrapperChain(constructorType, null, calls, null, false, false, false, false, null));
                }
            }
        }

        return chains;
    }

    /**
     * 主动快捷入口的精确降级判据：只认光标所在行，避免文件内别处存在 Kotlin Wrapper 时误提示。
     * 这里仍不承诺生成 SQL；命中只表示"用户确实压在当前 MVP 能识别的 Kotlin Wrapper 链上"。
     */
    public static boolean detectAt(PsiFile psiFile, int offset) {
        String text = kotlinTextOrNull(psiFile);
        if (text == null) return false;
        if (offset < 0 || offset > text.length()) return false;
        int lineStart = offset <= 0 ? 0 : nextLineStart(text, text.lastIndexOf('\n', offset - 1));
        int nl = text.indexOf('\n', Math.min(offset, text.length()));
        int lineEnd = nl < 0 ? text.length() : nl;
        return isWrapperLine(text.substring(lineStart, lineEnd));
    }

    /** 选择区主动预览同款判据；只检查与选区相交的行，不把文件其他位置的链冒充本次命中。 */
    public static boolean detectInRange(PsiFile psiFile, int startOffset, int endOffset) {
        String text = kotlinTextOrNull(psiFile);
        if (text == null) return false;
        int start = Math.max(0, Math.min(startOffset, text.length()));
        int end = Math.max(start, Math.min(endOffset, text.length()));
        int lineStart = start <= 0 ? 0 : nextLineStart(text, text.lastIndexOf('\n', start - 1));
        int nl = text.indexOf('\n', end);
        int lineEnd = nl < 0 ? text.length() : nl;
        String region = text.substring(lineStart, lineEnd);
        for (String line : region.split("\n", -1)) {
            if (isWrapperLine(line)) return true;
        }
        return false;
    }

    /** Kotlin 文件校验 + 取文本（非 kt/kts 返回 null）。 */
    private static String kotlinTextOrNull(PsiFile psiFile) {
        String extension = psiFile.getVirtualFile() != null ? psiFile.getVirtualFile().getExtension() : null;
        if (!"kt".equals(extension) && !"kts".equals(extension)) return null;
        return psiFile.getText();
    }

    /** 行是否为 MVP 能识别的 Kotlin Wrapper 链（扩展工厂链或构造链，且能提取出调用）。 */
    private static boolean isWrapperLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("//")) return false;
        WrapperType type = detectLineExtensionChain(trimmed);
        if (type == null) type = detectLineConstructorChain(trimmed);
        if (type == null) return false;
        return !extractChainCalls(trimmed).isEmpty();
    }

    /** lastIndexOf 结果（-1）归一为行首 0，行起点 = 换行符后一位。 */
    private static int nextLineStart(String text, int lastNl) {
        return lastNl < 0 ? 0 : lastNl + 1;
    }

    /**
     * 检测单行是否包含 ktQuery()/ktUpdate() 扩展函数链起点。
     * 匹配模式：{@code xxx.ktQuery()...} 或 {@code xxx.ktUpdate()...}
     */
    private static WrapperType detectLineExtensionChain(String line) {
        for (Map.Entry<String, WrapperType> entry : EXTENSION_FACTORY_METHODS.entrySet()) {
            // 匹配 `.ktQuery(` 或 `.ktUpdate(` 模式（前面有 qualifier）
            if (line.contains("." + entry.getKey() + "(")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 检测单行是否包含直接构造 Wrapper 链起点。
     * 匹配模式：{@code LambdaQueryWrapper<X>()...} 等。
     */
    private static WrapperType detectLineConstructorChain(String line) {
        for (Map.Entry<String, WrapperType> entry : WRAPPER_CONSTRUCTOR_TYPES.entrySet()) {
            // 匹配 `LambdaQueryWrapper(` 模式（行首或前面是 `=` / `(` 等）
            if (line.contains(entry.getKey() + "<") || line.contains(entry.getKey() + "(")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 从单行提取链上方法调用。
     * 扫描 {@code .methodName(} 模式，过滤终结点和非 Wrapper 操作。
     */
    private static List<WrapperCall> extractChainCalls(String line) {
        List<WrapperCall> calls = new ArrayList<>();
        Matcher matcher = CHAIN_CALL_PATTERN.matcher(line);

        while (matcher.find()) {
            String methodName = matcher.group(1);
            // 跳过终结点方法
            if (CHAIN_TERMINAL_METHOD_NAMES.contains(methodName)) {
                continue;
            }
            // 只保留 Wrapper 操作方法
            if (!WRAPPER_OPERATION_METHOD_NAMES.contains(methodName)) {
                continue;
            }

            // MVP：参数仅做简单提取（文本占位）
            // 找到匹配位置之后的参数文本
            List<WrapperArg> args = extractCallArgs(line, matcher.start());
            calls.add(new WrapperCall(methodName, args));
        }

        return calls;
    }

    /**
     * 提取调用的参数（MVP）：简单提取括号内的文本作为占位值。
     */
    private static List<WrapperArg> extractCallArgs(String line, int dotIndex) {
        // 找到 .methodName( 之后的括号内容
        int parenStart = line.indexOf('(', dotIndex);
        if (parenStart < 0) {
            return List.of();
        }

        // 简单提取到行尾或下一个 `.methodName(` 之前
        String restOfLine = line.substring(parenStart + 1).trim();
        if (restOfLine.isEmpty()) {
            return List.of();
        }

        // MVP：参数文本占位
        String placeholder = restOfLine.length() > 50 ? restOfLine.substring(0, 50) : restOfLine;
        return List.of(new WrapperArg.ValueArg(new SqlValue.Placeholder(placeholder)));
    }
}
