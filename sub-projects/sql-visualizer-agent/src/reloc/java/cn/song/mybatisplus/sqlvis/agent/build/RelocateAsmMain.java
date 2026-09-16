package cn.song.mybatisplus.sqlvis.agent.build;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * fat jar 打包 + ASM 自 relocate（构建期工具，跑在构建 JVM）：
 * ① agent 类原样入 jar；② ASM jar 的类经 ClassRemapper 把 {@code org.objectweb.asm} 前缀
 * 改名 {@code cn.song.mybatisplus.sqlvis.agent.asm}（asm/asm.commons/asm.signature/asm.tree 全前缀覆盖）；
 * ③ module-info / 签名文件剔除（自包含 jar 不参与模块系统）；④ manifest 写 attach 四键。
 * 不引入 shadow 插件：ASM 依赖面固定为 2 个 jar，自重写 ~40 行可控。
 */
public final class RelocateAsmMain {

    static final String ASM_PREFIX_OLD = "org/objectweb/asm";
    static final String ASM_PREFIX_NEW = "cn/song/mybatisplus/sqlvis/agent/asm";

    public static void main(String[] args) throws Exception {
        String asmPaths = null, agentJar = null, outPath = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--asm": asmPaths = args[i + 1]; break;
                case "--agent": agentJar = args[i + 1]; break;
                case "--out": outPath = args[i + 1]; break;
                default: break;
            }
        }
        if (asmPaths == null || agentJar == null || outPath == null) {
            throw new IllegalArgumentException("usage: --asm <p1;p2> --agent <jar> --out <jar>");
        }

        final Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName.equals(ASM_PREFIX_OLD) || internalName.startsWith(ASM_PREFIX_OLD + "/")) {
                    return ASM_PREFIX_NEW + internalName.substring(ASM_PREFIX_OLD.length());
                }
                return internalName;
            }
        };

        final Manifest manifest = new Manifest();
        final Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(new Attributes.Name("Premain-Class"), "cn.song.mybatisplus.sqlvis.agent.AgentMain");
        attrs.put(new Attributes.Name("Agent-Class"), "cn.song.mybatisplus.sqlvis.agent.AgentMain");
        attrs.put(new Attributes.Name("Can-Redefine-Classes"), "true");
        attrs.put(new Attributes.Name("Can-Retransform-Classes"), "true");

        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(outPath), manifest)) {
            // 1) agent 主类：字节码内的 org.objectweb.asm 引用同样重映射
            //    （否则 ConnectWeaver 运行期 NCDFE——fat jar 内已无明文 ASM）
            try (JarFile jf = new JarFile(agentJar)) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.isDirectory() || skip(e.getName())) {
                        continue;
                    }
                    final byte[] bytes = readAll(jf.getInputStream(e));
                    final byte[] mapped;
                    if (e.getName().endsWith(".class") && !e.getName().endsWith("module-info.class")) {
                        final ClassReader cr = new ClassReader(bytes);
                        final ClassWriter cw = new ClassWriter(cr, 0);
                        cr.accept(new ClassRemapper(cw, remapper), 0);
                        mapped = cw.toByteArray();
                    } else {
                        mapped = bytes;
                    }
                    out.putNextEntry(new ZipEntry(e.getName()));
                    out.write(mapped);
                    out.closeEntry();
                }
            }
            // 2) ASM（relocate）
            for (String path : asmPaths.split(";")) {
                if (path.isBlank()) {
                    continue;
                }
                try (JarFile jf = new JarFile(new File(path))) {
                    var entries = jf.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry e = entries.nextElement();
                        final String name = e.getName();
                        if (e.isDirectory() || skip(name)) {
                            continue;
                        }
                        final String newName = name.startsWith(ASM_PREFIX_OLD) || name.startsWith(ASM_PREFIX_OLD + "/")
                                ? ASM_PREFIX_NEW + name.substring(ASM_PREFIX_OLD.length()) : name;
                        final byte[] bytes = readAll(jf.getInputStream(e));
                        final byte[] mapped;
                        if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                            final ClassReader cr = new ClassReader(bytes);
                            final ClassWriter cw = new ClassWriter(cr, 0);
                            cr.accept(new ClassRemapper(cw, remapper), 0);
                            mapped = cw.toByteArray();
                        } else {
                            mapped = bytes;
                        }
                        out.putNextEntry(new ZipEntry(newName));
                        out.write(mapped);
                        out.closeEntry();
                    }
                }
            }
        }
        System.out.println("agent fat jar written: " + outPath);
    }

    private static boolean skip(String name) {
        if (name.equals("module-info.class") || name.startsWith("META-INF/")) {
            return true;
        }
        return false;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private RelocateAsmMain() {
    }
}
