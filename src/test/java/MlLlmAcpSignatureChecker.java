import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * T-MlLlmSignatureGuard ml-llm（AI Assistant）ACP internal 类型跨版本签名检查器。
 *
 * **为什么是字节码解析而不是反射（与 McpDbSignatureChecker 的关键分歧）**：反射对本链**不可能**成立，
 * 两处实跑实证（JBR 25.0.4，2026-09-22，见计划 §八）：
 *  1. `@ApiStatus.Internal` 是 **CLASS retention**（发行版 `lib/annotations.jar` 里 `ApiStatus$Internal`
 *     自身属性 = `@Retention(RetentionPolicy.CLASS)`），落在 provider 类的 `RuntimeInvisibleAnnotations`
 *     ——`Class.getAnnotations()` / `isAnnotationPresent()` **永远读不到**（实跑输出
 *     `visibleAnnots=[@kotlin.Metadata(...)]`）。断言 ③ 用反射只会假 MISSING。
 *  2. `AcpIdeaMcpTransport` 是 Kotlin enum，常量池引 `kotlin/enums/EnumEntriesKt`，而 ml-llm 全部 jar
 *     都不含 kotlin-stdlib（该类只在发行版 `lib/util-8.jar`）——`Class.forName(...,true)` 与
 *     `getEnumConstants()` 直接 `NoClassDefFoundError: kotlin/enums/EnumEntriesKt`（实跑）；provider
 *     更连平台类都缺（`NoClassDefFoundError: com/intellij/openapi/extensions/ExtensionPointName`）。
 * 故本检查器**只读 class 文件字节**（常量池 + 访问标志 + 字段/方法 + 类属性），零平台依赖、零
 * classpath 装配、任意 JDK 可运行（解析字节不需要 JVM 支持该 class 版本——major 69 也照读）。
 *
 * **按包路径分组**：两份 ml-llm 制品的 `AcpAgentId` 不在同一个包——装机 262.10968.97 在
 * `com.intellij.ml.llm.agents.acp.api`（在 ml-llm jar 内）；gradle transforms 的 262.9437.276 在
 * `com.intellij.platform.acp`（**平台**类型，只存在于配对发行版
 * `plugins/platform-acp-plugin/lib/modules/intellij.platform.acp.jar`，ml-llm 目录内零命中）。
 * 故期望包名由调用方按「制品→期望包名」登记表传入：provider 描述符声明的包与期望不符即判红
 * （上游改包必须人工复核后更新登记表，不得静默通过）。AcpAgentId 类在制品内找不到时回落 companion
 * 根，命中即打印来源 jar（`AGENT_ID_CLASS` / `COMPANION_AGENT_ID_CLASS` 两行可区分）。
 *
 * 用法：java -cp &lt;checker目录&gt; MlLlmAcpSignatureChecker &lt;制品根&gt; &lt;期望AcpAgentId包名&gt; [companion根 ...]
 * 退出码：0 = 全部成立；1 = 有断言不成立（末行 `RESULT MISSING n`）；3 = 制品根本没携带 ml-llm ACP 模块
 * （与 McpDbSignatureChecker 的「平台 jar 缺失」同款独立码，供测试区分「制品不完整」与「签名漂移」）。
 */
public class MlLlmAcpSignatureChecker {

    private static final String ACP_PACKAGE = "com/intellij/ml/llm/agents/acp/";
    private static final String PROVIDER_INTERNAL = ACP_PACKAGE + "client/AcpIdeaMcpTransportProvider";
    private static final String TRANSPORT_INTERNAL = ACP_PACKAGE + "client/AcpIdeaMcpTransport";
    private static final String PROVIDER_ENTRY = PROVIDER_INTERNAL + ".class";
    private static final String TRANSPORT_ENTRY = TRANSPORT_INTERNAL + ".class";
    private static final String API_STATUS_INTERNAL = "Lorg/jetbrains/annotations/ApiStatus$Internal;";
    private static final String REGISTRY_PREFIX = "acp.registry.";
    /** 断言 ② 的期望枚举常量集——绕行前提（STDIO 默认 / SSE 绕过）依赖恰这两个值。 */
    private static final List<String> EXPECTED_TRANSPORT_CONSTANTS = Arrays.asList("STDIO", "SSE");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: MlLlmAcpSignatureChecker <artifactRoot> <expectedAcpAgentIdPackage> [companionRoot ...]");
            System.out.println("RESULT MISSING 1");
            System.exit(3);
            return;
        }
        File root = new File(args[0]);
        String expectedPackage = args[1];
        System.out.println("ARTIFACT " + root.getAbsolutePath());
        System.out.println("EXPECTED_AGENT_ID_PACKAGE " + expectedPackage);

        Map<String, File> primary = index(root);
        Map<String, File> companions = new LinkedHashMap<String, File>();
        for (int i = 2; i < args.length; i++) {
            for (Map.Entry<String, File> e : index(new File(args[i])).entrySet()) {
                if (!companions.containsKey(e.getKey())) {
                    companions.put(e.getKey(), e.getValue());
                }
            }
        }

        if (!primary.containsKey(PROVIDER_ENTRY)) {
            System.out.println("MISS 制品未携带 ml-llm ACP 模块（" + PROVIDER_ENTRY + " 不在 " + root.getAbsolutePath() + "）");
            System.out.println("RESULT MISSING 1");
            System.exit(3);
            return;
        }

        int missing = 0;

        // ---- 断言 ③：provider 为接口、唯一抽象方法 getTransport(AcpAgentId): AcpIdeaMcpTransport?、类标 @ApiStatus.Internal
        ClassFile provider = read(primary.get(PROVIDER_ENTRY), PROVIDER_ENTRY);
        System.out.println("PROVIDER access=0x" + Integer.toHexString(provider.access)
            + " invisibleAnnotations=" + provider.invisibleAnnotations);
        if (!provider.isInterface()) {
            System.out.println("MISS " + PROVIDER_INTERNAL + " 不再是接口（access=0x" + Integer.toHexString(provider.access) + "）");
            missing++;
        }
        // 加固（复核 major㉝-2）：`method(name)` 只返回**首个**同名成员，而本断言的文案是
        // 「唯一抽象方法 getTransport(…)」。同名重载/bridge 且声明顺序靠前时，断言会读到另一个成员而漏判。
        // 现补两项：① 名为 getTransport 的成员必须唯一；② 抽象方法集合必须恰为 {getTransport}。
        List<Member> sameName = provider.methodsNamed("getTransport");
        if (sameName.size() != 1) {
            StringBuilder ds = new StringBuilder();
            for (Member m : sameName) {
                ds.append(ds.length() == 0 ? "" : ", ").append(m.name).append(m.descriptor);
            }
            System.out.println("MISS " + PROVIDER_INTERNAL + " 名为 getTransport 的成员有 " + sameName.size()
                + " 个（" + ds + "）——本检查器只取首个，重载/bridge 会让断言落空，须人工复核");
            missing++;
        }
        List<Member> abstracts = provider.abstractMethods();
        if (abstracts.size() != 1 || !"getTransport".equals(abstracts.get(0).name)) {
            StringBuilder ds = new StringBuilder();
            for (Member m : abstracts) {
                ds.append(ds.length() == 0 ? "" : ", ").append(m.name).append(m.descriptor);
            }
            System.out.println("MISS " + PROVIDER_INTERNAL + " 抽象方法集合不再是「唯一 getTransport」：实际 ["
                + ds + "]（上游新增抽象方法会让 provider 契约与绕行前提一并变化）");
            missing++;
        }
        Member getTransport = provider.method("getTransport");
        if (getTransport == null) {
            System.out.println("MISS " + PROVIDER_INTERNAL + ".getTransport 不存在");
            missing++;
        } else {
            String expectedDescriptor =
                "(L" + expectedPackage.replace('.', '/') + "/AcpAgentId;)L" + TRANSPORT_INTERNAL + ";";
            String declared = declaredAgentIdPackage(getTransport.descriptor);
            System.out.println("PROVIDER_DECLARED_GET_TRANSPORT " + getTransport.descriptor);
            System.out.println("PROVIDER_DECLARED_AGENT_ID_PACKAGE " + declared);
            if (!expectedDescriptor.equals(getTransport.descriptor)) {
                System.out.println("MISS " + PROVIDER_INTERNAL + ".getTransport 描述符漂移：期望 " + expectedDescriptor
                    + "，实际 " + getTransport.descriptor + "（制品声明的 AcpAgentId 包=" + declared
                    + "；上游改包须人工复核后更新「制品→期望包名」登记表）");
                missing++;
            }
            if (!getTransport.isAbstract()) {
                System.out.println("MISS " + PROVIDER_INTERNAL + ".getTransport 不再是抽象方法（access=0x"
                    + Integer.toHexString(getTransport.access) + "）");
                missing++;
            }
        }
        if (!provider.invisibleAnnotations.contains(API_STATUS_INTERNAL)) {
            System.out.println("MISS " + PROVIDER_INTERNAL + " 类上无 " + API_STATUS_INTERNAL
                + "（RuntimeInvisibleAnnotations=" + provider.invisibleAnnotations + "）");
            missing++;
        }

        // ---- 断言 ②：AcpIdeaMcpTransport 为 Enum 且枚举常量恰 [STDIO, SSE]
        // 加固（复核 major㉝-1）：此前这里直接 `read(primary.get(TRANSPORT_ENTRY), …)`——制品
        // 「有 provider 但缺 transport」时 `primary.get` 返回 null，`new ZipFile(null)` 抛
        // NullPointerException，验收①要求的「记 MISSING 并如实登记」诊断路径整条丢失（只剩栈）。
        // 现与 PROVIDER_ENTRY 同款先判存在，缺失即记 MISS。
        if (!primary.containsKey(TRANSPORT_ENTRY)) {
            System.out.println("MISS 制品未携带 " + TRANSPORT_ENTRY + "（provider 在而 transport 不在 = 制品不完整）");
            missing++;
        } else {
            ClassFile transport = read(primary.get(TRANSPORT_ENTRY), TRANSPORT_ENTRY);
            List<String> constants = transport.enumConstants();
            System.out.println("TRANSPORT access=0x" + Integer.toHexString(transport.access)
                + " super=" + transport.superName + " enumConstants=" + constants);
            if (!transport.isEnum()) {
                System.out.println("MISS " + TRANSPORT_INTERNAL + " 不再是 Enum（access=0x"
                    + Integer.toHexString(transport.access) + ", super=" + transport.superName + "）");
                missing++;
            }
            if (!constants.equals(EXPECTED_TRANSPORT_CONSTANTS)) {
                System.out.println("MISS " + TRANSPORT_INTERNAL + " 枚举常量漂移：期望 " + EXPECTED_TRANSPORT_CONSTANTS
                    + "，实际 " + constants + "（常量增减即绕行前提变化）");
                missing++;
            }
        }

        // ---- 断言 ①/④：AcpAgentId 为接口、含抽象 getRawId()/getFullId()、REGISTRY_PREFIX 仍为 "acp.registry."
        String agentIdEntry = expectedPackage.replace('.', '/') + "/AcpAgentId.class";
        File agentIdJar = primary.containsKey(agentIdEntry) ? primary.get(agentIdEntry) : companions.get(agentIdEntry);
        if (agentIdJar == null) {
            System.out.println("MISS " + agentIdEntry + " 在制品与全部 companion 中均未找到"
                + "（制品内 ACP 类条目 " + primary.size() + " 个；上游改包或制品不完整，不得静默跳过）");
            missing++;
        } else {
            System.out.println((primary.containsKey(agentIdEntry) ? "AGENT_ID_CLASS " : "COMPANION_AGENT_ID_CLASS ")
                + agentIdJar.getAbsolutePath());
            ClassFile agentId = read(agentIdJar, agentIdEntry);
            if (!agentId.isInterface()) {
                System.out.println("MISS " + agentIdEntry + " 不再是接口（access=0x" + Integer.toHexString(agentId.access) + "）");
                missing++;
            }
            for (String getter : new String[]{"getRawId", "getFullId"}) {
                Member m = agentId.method(getter);
                if (m == null || !m.isAbstract() || !"()Ljava/lang/String;".equals(m.descriptor)) {
                    System.out.println("MISS " + agentIdEntry + "." + getter + "()Ljava/lang/String; 抽象方法缺失或形态漂移："
                        + (m == null ? "不存在" : m.descriptor + " access=0x" + Integer.toHexString(m.access)));
                    missing++;
                }
            }
            Member prefix = agentId.field("REGISTRY_PREFIX");
            if (prefix == null || !REGISTRY_PREFIX.equals(prefix.constantValue)) {
                System.out.println("MISS " + agentIdEntry + ".REGISTRY_PREFIX 常量漂移：期望 \"" + REGISTRY_PREFIX
                    + "\"，实际 " + (prefix == null ? "字段不存在" : "\"" + prefix.constantValue + "\""));
                missing++;
            } else {
                System.out.println("AGENT_ID_REGISTRY_PREFIX \"" + prefix.constantValue + "\"");
            }
        }

        // ---- 断言 ⑤（加固，复核 major㉝-3）：EP 声明面。provider 类字节可以逐字节不变，而上游若
        // 删/改 ml-llm 自身 plugin.xml 里的 EP 声明，绕行就已不再被接线——只读 class 的检查器会**继续绿**。
        // 故补一条：制品树内找到的 plugin.xml 里必须出现 provider 的 FQCN。
        List<String> pluginXmls = collectPluginXmls(root);
        String providerFqcn = PROVIDER_INTERNAL.replace('/', '.');
        if (pluginXmls.isEmpty()) {
            System.out.println("NOTE 制品树内未找到任何 plugin.xml —— EP 面无法核验"
                + "（布局差异，非失败；class 面断言照常生效）");
        } else {
            boolean declared = false;
            for (String xml : pluginXmls) {
                if (xml.contains(providerFqcn)) {
                    declared = true;
                    break;
                }
            }
            if (declared) {
                System.out.println("EP_DECLARED " + providerFqcn + "（命中 " + pluginXmls.size() + " 份 plugin.xml）");
            } else {
                System.out.println("MISS " + providerFqcn + " 未出现在任何 plugin.xml 的 EP 声明中"
                    + "（扫过 " + pluginXmls.size() + " 份）——类字节可能未变，但绕行已不再被接线");
                missing++;
            }
        }

        System.out.println(missing == 0 ? "RESULT OK" : "RESULT MISSING " + missing);
        System.exit(missing == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------

    /** 从 `(L&lt;包&gt;/AcpAgentId;)L…;` 描述符取出 AcpAgentId 的包名（点号形式）；取不到返回 "?"。 */
    private static String declaredAgentIdPackage(String descriptor) {
        int at = descriptor.indexOf('L');
        int end = descriptor.indexOf("/AcpAgentId;");
        if (at < 0 || end < 0 || end <= at + 1) {
            return "?";
        }
        return descriptor.substring(at + 1, end).replace('/', '.');
    }

    /** 递归索引制品内 ACP 相关类（`com/intellij/ml/llm/agents/acp/` 与 `com/intellij/platform/acp/`）。 */
    private static Map<String, File> index(File root) {
        Map<String, File> out = new LinkedHashMap<String, File>();
        collect(root, out);
        return out;
    }

    private static void collect(File dir, Map<String, File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().endsWith(".jar")) {
                indexJar(f, out);
            }
        }
    }

    private static void indexJar(File jar, Map<String, File> out) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                if (!name.startsWith(ACP_PACKAGE) && !name.startsWith("com/intellij/platform/acp/")) {
                    continue;
                }
                if (!out.containsKey(name)) {
                    out.put(name, jar);
                }
            }
        } catch (IOException e) {
            // 非标准 jar 跳过（与 McpDbSignatureChecker 同款容错）
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响结论
                }
            }
        }
    }

    /** 收集制品树内的 plugin.xml 文本（jar 内 `META-INF/plugin.xml` + 树内独立 plugin.xml）。 */
    private static List<String> collectPluginXmls(File root) {
        List<String> out = new ArrayList<String>();
        collectPluginXmls(root, out);
        return out;
    }

    private static void collectPluginXmls(File dir, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectPluginXmls(f, out);
            } else if ("plugin.xml".equals(f.getName())) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                    out.add(new String(bytes, StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 读不到即当未找到（与 indexJar 同款容错）
                }
            } else if (f.getName().endsWith(".jar")) {
                ZipFile zip = null;
                try {
                    zip = new ZipFile(f);
                    ZipEntry e = zip.getEntry("META-INF/plugin.xml");
                    if (e != null) {
                        InputStream in = zip.getInputStream(e);
                        try {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                bos.write(buf, 0, n);
                            }
                            out.add(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                        } finally {
                            in.close();
                        }
                    }
                } catch (IOException ignored) {
                    // 非标准 jar 跳过
                } finally {
                    if (zip != null) {
                        try {
                            zip.close();
                        } catch (IOException ignored) {
                            // 关闭失败不影响结论
                        }
                    }
                }
            }
        }
    }

    private static ClassFile read(File jar, String entry) throws IOException {
        if (jar == null) {
            throw new IOException("no jar indexed for entry: " + entry + "（调用方应先判 containsKey）");
        }
        ZipFile zip = new ZipFile(jar);
        try {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) {
                throw new IOException("entry not found: " + entry + " in " + jar);
            }
            InputStream in = zip.getInputStream(e);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return ClassFile.parse(bos.toByteArray());
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
    }

    // ------------------------------------------------------------------
    // class 文件最小解析（只取本检查器要用的部分；Code 等属性整体跳过）
    // ------------------------------------------------------------------

    private static final class Member {
        final int access;
        final String name;
        final String descriptor;
        final String constantValue;

        Member(int access, String name, String descriptor, String constantValue) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
            this.constantValue = constantValue;
        }

        boolean isAbstract() {
            return (access & 0x0400) != 0;
        }
    }

    private static final class Attribute {
        final String name;
        final byte[] body;

        Attribute(String name, byte[] body) {
            this.name = name;
            this.body = body;
        }
    }

    private static final class ClassFile {
        private static final int ACC_INTERFACE = 0x0200;
        private static final int ACC_ENUM = 0x4000;

        final int access;
        final String name;
        final String superName;
        final List<Member> fields;
        final List<Member> methods;
        final List<String> invisibleAnnotations;

        private ClassFile(int access, String name, String superName, List<Member> fields,
                          List<Member> methods, List<String> invisibleAnnotations) {
            this.access = access;
            this.name = name;
            this.superName = superName;
            this.fields = fields;
            this.methods = methods;
            this.invisibleAnnotations = invisibleAnnotations;
        }

        boolean isInterface() {
            return (access & ACC_INTERFACE) != 0;
        }

        boolean isEnum() {
            return (access & ACC_ENUM) != 0 && "java/lang/Enum".equals(superName);
        }

        Member method(String name) {
            for (Member m : methods) {
                if (m.name.equals(name)) {
                    return m;
                }
            }
            return null;
        }

        /** 全部同名成员（加固②：`method(name)` 只给首个，重载/bridge 下断言会落空）。 */
        List<Member> methodsNamed(String name) {
            List<Member> out = new ArrayList<Member>();
            for (Member m : methods) {
                if (m.name.equals(name)) {
                    out.add(m);
                }
            }
            return out;
        }

        /** 抽象且非静态的**声明**方法集（加固②：「唯一抽象方法」文案的判据）。 */
        List<Member> abstractMethods() {
            List<Member> out = new ArrayList<Member>();
            for (Member m : methods) {
                if ((m.access & 0x0400) != 0 && (m.access & 0x0008) == 0) {
                    out.add(m);
                }
            }
            return out;
        }

        Member field(String name) {
            for (Member m : fields) {
                if (m.name.equals(name)) {
                    return m;
                }
            }
            return null;
        }

        /** 枚举常量（带 ACC_ENUM 的字段）名，按 class 文件内声明顺序。 */
        List<String> enumConstants() {
            List<String> out = new ArrayList<String>();
            for (Member m : fields) {
                if ((m.access & ACC_ENUM) != 0) {
                    out.add(m.name);
                }
            }
            return out;
        }

        static ClassFile parse(byte[] data) throws IOException {
            Reader r = new Reader(data);
            if (r.u4() != 0xCAFEBABE) {
                throw new IOException("not a class file");
            }
            r.u2(); // minor
            r.u2(); // major（major 69 也照读——解析字节不依赖 JVM 支持该版本）
            int cpCount = r.u2();
            int[] tags = new int[cpCount];
            String[] utf8 = new String[cpCount];
            int[] ref = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = r.u1();
                tags[i] = tag;
                switch (tag) {
                    case 1: // Utf8
                        utf8[i] = new String(r.bytes(r.u2()), StandardCharsets.UTF_8);
                        break;
                    case 7: // Class
                    case 8: // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        ref[i] = r.u2();
                        break;
                    case 15: // MethodHandle
                        r.u1();
                        r.u2();
                        break;
                    case 3: // Integer
                    case 4: // Float
                    case 9: // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 17: // Dynamic
                    case 18: // InvokeDynamic
                        ref[i] = r.u4();
                        break;
                    case 5: // Long
                    case 6: // Double
                        r.u4();
                        r.u4();
                        i++; // 占两个槽位
                        break;
                    default:
                        throw new IOException("bad constant pool tag " + tag + " at #" + i);
                }
            }
            int access = r.u2();
            String thisName = utf8[ref[r.u2()]];
            String superName = utf8[ref[r.u2()]];
            int interfaces = r.u2();
            for (int i = 0; i < interfaces; i++) {
                r.u2();
            }
            List<Member> fields = members(r, utf8, ref, tags, true);
            List<Member> methods = members(r, utf8, ref, tags, false);
            List<String> invisible = new ArrayList<String>();
            for (Attribute a : attributes(r, utf8)) {
                if ("RuntimeInvisibleAnnotations".equals(a.name)) {
                    readAnnotations(new Reader(a.body), utf8, invisible);
                }
            }
            return new ClassFile(access, thisName, superName, fields, methods, invisible);
        }

        private static List<Member> members(Reader r, String[] utf8, int[] ref, int[] tags, boolean isField)
            throws IOException {
            List<Member> out = new ArrayList<Member>();
            int count = r.u2();
            for (int i = 0; i < count; i++) {
                int access = r.u2();
                String name = utf8[r.u2()];
                String descriptor = utf8[r.u2()];
                String constant = null;
                for (Attribute a : attributes(r, utf8)) {
                    if (isField && "ConstantValue".equals(a.name) && a.body.length >= 2) {
                        int idx = ((a.body[0] & 0xff) << 8) | (a.body[1] & 0xff);
                        if (idx > 0 && idx < tags.length && tags[idx] == 8) { // CONSTANT_String
                            constant = utf8[ref[idx]];
                        } else if (idx > 0 && idx < tags.length && tags[idx] == 3) { // CONSTANT_Integer
                            constant = String.valueOf(ref[idx]);
                        }
                    }
                }
                out.add(new Member(access, name, descriptor, constant));
            }
            return out;
        }

        private static List<Attribute> attributes(Reader r, String[] utf8) {
            List<Attribute> out = new ArrayList<Attribute>();
            int count = r.u2();
            for (int i = 0; i < count; i++) {
                String name = utf8[r.u2()];
                int len = r.u4();
                out.add(new Attribute(name, r.bytes(len)));
            }
            return out;
        }

        private static void readAnnotations(Reader r, String[] utf8, List<String> out) {
            int count = r.u2();
            for (int i = 0; i < count; i++) {
                readAnnotation(r, utf8, out);
            }
        }

        private static void readAnnotation(Reader r, String[] utf8, List<String> out) {
            out.add(utf8[r.u2()]);
            int pairs = r.u2();
            for (int i = 0; i < pairs; i++) {
                r.u2(); // element_name_index
                skipElementValue(r, utf8);
            }
        }

        private static void skipElementValue(Reader r, String[] utf8) {
            int tag = r.u1();
            if (tag == '@') {
                readAnnotation(r, utf8, new ArrayList<String>());
            } else if (tag == '[') {
                int count = r.u2();
                for (int i = 0; i < count; i++) {
                    skipElementValue(r, utf8);
                }
            } else if (tag == 'e') {
                r.u2();
                r.u2();
            } else if (tag == 'c') {
                r.u2();
            } else {
                r.u2(); // B/C/D/F/I/J/S/Z/s：常量池下标
            }
        }
    }

    private static final class Reader {
        private final byte[] d;
        private int p;

        Reader(byte[] d) {
            this.d = d;
        }

        int u1() {
            return d[p++] & 0xff;
        }

        int u2() {
            int v = ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
            p += 2;
            return v;
        }

        int u4() {
            int v = ((d[p] & 0xff) << 24) | ((d[p + 1] & 0xff) << 16) | ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
            p += 4;
            return v;
        }

        byte[] bytes(int n) {
            byte[] v = new byte[n];
            System.arraycopy(d, p, v, 0, n);
            p += n;
            return v;
        }
    }
}
