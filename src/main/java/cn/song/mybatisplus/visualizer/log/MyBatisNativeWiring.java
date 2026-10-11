package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * R24：MyBatis 原生（非 Spring Boot）项目的 SqlSourceInterceptor 装配内核。
 *
 * <p>Spring Boot 路径的装配点 = Spring 容器（{@code @Configuration} + SmartInitializingSingleton 把拦截器
 * 追加进 MybatisPlusInterceptor bean）；原生路径的装配点 = <b>mybatis-config.xml 的 {@code <plugins>}</b>
 * ——MyBatis 读 {@code <plugin interceptor="FQCN">} 时按无参构造实例化并挂进拦截器链。拦截器本体
 * （{@code SqlSourceInterceptor implements InnerInterceptor}）不是原生 MyBatis 的 {@code Interceptor}，
 * 不能直接写进 {@code <plugin>}，故由一个薄包装（{@link #NATIVE_PLUGIN_FILE_NAME}，实现
 * org.apache.ibatis.plugin.Interceptor）持有并分发——两文件见 {@link SourceInterceptorTemplates}。
 *
 * <p>fail-closed 边界（任务口径）：检测不到 config、config 多处、根标签不是 mybatis 的
 * {@code <configuration>}、插件已注册——一律不写 XML；前三者回 {@link Decision} 交调用方走既有
 * UnsupportedProject 兜底（引导文案不变），不猜不抢。
 *
 * <p>纯决策与 XML 编辑在此分层：{@link #decide}/{@link #pluginFqcn} 是平台无关纯函数（裸 JUnit 直测），
 * {@link #findConfigCandidates}/{@link #registerPluginInConfig} 是 VFS/XML PSI 薄接线（fixture 直测）。
 */
public final class MyBatisNativeWiring {

    private MyBatisNativeWiring() {
    }

    /** 原生包装插件类文件名（落在 {@code <basePackage>.config} 包，与拦截器本体同包）。 */
    public static final String NATIVE_PLUGIN_FILE_NAME = "SqlVisualizerNativePlugin.java";

    /** mybatis-config.xml 的候选文件名（MyBatis / MyBatis-Plus 官方文档惯用名，精确匹配）。 */
    public static final Set<String> CONFIG_FILE_CANDIDATE_NAMES =
            new TreeSet<>(List.of("mybatis-config.xml", "mybatis.xml"));

    /** 原生包装插件的全限定名（{@code <plugin interceptor="…">} 的注册值）。 */
    public static String pluginFqcn(String basePackage) {
        String fileName = NATIVE_PLUGIN_FILE_NAME;
        if (fileName.endsWith(".java")) {
            fileName = fileName.substring(0, fileName.length() - ".java".length());
        }
        return basePackage + ".config." + fileName;
    }

    /** 装配决策（fail-closed 四路 + 一路放行）。 */
    public enum Decision {
        /** 检测不到 mybatis-config.xml——不注入（引导文案照旧）。 */
        NO_CONFIG,

        /** config 多处（多个候选文件）——无法判定运行时生效的是哪份，不注入。 */
        AMBIGUOUS_CONFIG,

        /** 唯一候选存在但根标签不是 {@code <configuration>}（不是 MyBatis 配置或解析不出根）。 */
        NOT_MYBATIS_CONFIG,

        /** {@code <plugins>} 里已注册本插件——幂等跳过（XML 不动，文件补齐仍由调用方处理）。 */
        ALREADY_REGISTERED,

        /** 放行：唯一 config、根 {@code <configuration>}、未注册——执行注入。 */
        INJECT,
    }

    /**
     * 纯决策：候选数 / 唯一候选的根标签名 / 已注册与否 → {@link Decision}。
     * 顺序即优先级：先判 config 存在性与唯一性（结构性 fail-closed 最优先），
     * 再判根标签，最后幂等。
     */
    public static Decision decide(int configCount, String rootTagName, boolean alreadyRegistered) {
        if (configCount <= 0) {
            return Decision.NO_CONFIG;
        }
        if (configCount > 1) {
            return Decision.AMBIGUOUS_CONFIG;
        }
        if (!"configuration".equals(rootTagName)) {
            return Decision.NOT_MYBATIS_CONFIG;
        }
        if (alreadyRegistered) {
            return Decision.ALREADY_REGISTERED;
        }
        return Decision.INJECT;
    }

    /**
     * 在 {@code sourceRoot}（模块主源根，{@code src/main/java}）的<b>同级 resources 目录</b>下递归找
     * {@link #CONFIG_FILE_CANDIDATE_NAMES} 精确同名文件（运行时 classpath 根的惯用位置）。
     * 找不到 resources 目录 → 空清单（fail-closed）；同名多份（含不同目录）→ 全部返回，
     * 由 {@link #decide} 按 AMBIGUOUS_CONFIG 拒绝。
     */
    public static List<VirtualFile> findConfigCandidates(VirtualFile sourceRoot) {
        VirtualFile parent = sourceRoot != null ? sourceRoot.getParent() : null;
        VirtualFile resources = parent != null ? parent.findChild("resources") : null;
        if (resources == null || !resources.isDirectory()) {
            return List.of();
        }
        List<VirtualFile> out = new ArrayList<>();
        walk(resources, out);
        return out;
    }

    private static void walk(VirtualFile dir, List<VirtualFile> out) {
        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                walk(child, out);
            } else if (CONFIG_FILE_CANDIDATE_NAMES.contains(child.getName())) {
                out.add(child);
            }
        }
    }

    /** 候选文件解析为 XmlFile 并取根标签名（非 XML / 无根 → null，交 {@link #decide} 判 NOT_MYBATIS_CONFIG）。 */
    public static String rootTagName(PsiManager psiManager, VirtualFile file) {
        PsiManager manager = PsiManager.getInstance(psiManager.getProject());
        if (!(manager.findFile(file) instanceof XmlFile xmlFile)) {
            return null;
        }
        XmlTag rootTag = xmlFile.getRootTag();
        return rootTag != null ? rootTag.getName() : null;
    }

    /** {@code <plugins>} 里是否已注册 {@code fqcn}（幂等判据；无 {@code <plugins>} 即未注册）。 */
    public static boolean isPluginRegistered(XmlTag rootTag, String fqcn) {
        XmlTag plugins = rootTag.findFirstSubTag("plugins");
        if (plugins == null) {
            return false;
        }
        for (XmlTag plugin : plugins.findSubTags("plugin")) {
            if (fqcn.equals(plugin.getAttributeValue("interceptor"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 {@code <plugin interceptor="[fqcn]"/>} 注册进 {@code xmlFile} 的 {@code <configuration>/<plugins>}。
     *
     * <p>{@code <plugins>} 缺席时新建，插入位按 mybatis-config DTD 习惯序（properties/settings/typeAliases
     * 之后；否则后置元素之前；全空则追加）。MyBatis 的 XMLConfigBuilder 按 evalNode 按名取节、
     * 不做 DTD 校验，位置不影响解析——此处照顾人读习惯。已注册（幂等）或根不是
     * {@code <configuration>} → false（调用方不视为失败：幂等是预期态）。
     *
     * @return true = 实际写入了 XML；false = 幂等跳过或拒绝写入
     */
    public static boolean registerPluginInConfig(XmlFile xmlFile, String fqcn) {
        XmlTag root = xmlFile.getRootTag();
        if (root == null) {
            return false;
        }
        if (!"configuration".equals(root.getName())) {
            return false;
        }
        if (isPluginRegistered(root, fqcn)) {
            return false;
        }
        XmlTag plugins = root.findFirstSubTag("plugins");
        if (plugins == null) {
            XmlTag created = root.createChildTag("plugins", null, null, false);
            XmlTag after = null;
            for (String name : List.of("properties", "settings", "typeAliases")) {
                XmlTag found = root.findFirstSubTag(name);
                if (found != null) {
                    after = found;
                }
            }
            // createChildTag 产物是**游离**标签——addAfter/addBefore/addSubTag 返回挂载后的副本，
            // 后续 addSubTag 必须落在挂载副本上（对游离副本追加 = 静默丢失，fixture 实证）
            if (after != null) {
                plugins = (XmlTag) root.addAfter(created, after);
            } else {
                XmlTag before = null;
                for (String name : List.of("objectFactory", "objectWrapperFactory", "reflectorFactory",
                        "environments", "databaseIdProvider", "mappers")) {
                    XmlTag found = root.findFirstSubTag(name);
                    if (found != null) {
                        before = found;
                        break;
                    }
                }
                if (before != null) {
                    plugins = (XmlTag) root.addBefore(created, before);
                } else {
                    plugins = root.addSubTag(created, false);
                }
            }
        }
        XmlTag plugin = plugins.createChildTag("plugin", null, null, false);
        plugin.setAttribute("interceptor", fqcn);
        plugins.addSubTag(plugin, false);
        return true;
    }
}
