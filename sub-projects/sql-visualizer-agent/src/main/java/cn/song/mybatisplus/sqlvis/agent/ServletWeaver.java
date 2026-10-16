package cn.song.mybatisplus.sqlvis.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.Arrays;

/** Public HttpServlet.service only. No Filter/override/async completeness claim; no Servlet linkage. */
public final class ServletWeaver implements Opcodes {
    private static final String CALLBACK = "cn/song/mybatisplus/sqlvis/agent/hook/RequestHooks";
    public static boolean matches(String name) {
        return "javax.servlet.http.HttpServlet".equals(name) || "jakarta.servlet.http.HttpServlet".equals(name);
    }
    public static byte[] weave(ClassLoader loader, String name, byte[] bytes) {
        if (!matches(name)) return bytes;
        try { (loader == null ? ClassLoader.getSystemClassLoader() : loader).loadClass(CALLBACK.replace('/', '.')); }
        catch (Throwable unavailable) { return bytes; }
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        String family = name.startsWith("javax") ? "javax" : "jakarta";
        String descriptor = "(L" + family + "/servlet/ServletRequest;L" + family + "/servlet/ServletResponse;)V";
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!"service".equals(method.name) || !descriptor.equals(method.desc)
                    || (method.access & (ACC_STATIC | ACC_ABSTRACT | ACC_NATIVE)) != 0) continue;
            int token = method.maxLocals, error = token + 2;
            LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
            InsnList head = new InsnList();
            head.add(new VarInsnNode(ALOAD, 1));
            head.add(new MethodInsnNode(INVOKESTATIC, CALLBACK, "enter", "(Ljava/lang/Object;)J", false));
            head.add(new VarInsnNode(LSTORE, token)); head.add(start);
            method.instructions.insertBefore(method.instructions.getFirst(), head);
            for (AbstractInsnNode instruction = start.getNext(); instruction != null; instruction = instruction.getNext()) {
                if (instruction.getOpcode() == RETURN) {
                    InsnList exit = new InsnList(); exit.add(new VarInsnNode(LLOAD, token));
                    exit.add(new InsnNode(ACONST_NULL));
                    exit.add(new MethodInsnNode(INVOKESTATIC, CALLBACK, "exit", "(JLjava/lang/Throwable;)V", false));
                    method.instructions.insertBefore(instruction, exit);
                }
            }
            method.instructions.add(end); method.instructions.add(handler);
            Object[] locals = new Object[token]; Arrays.fill(locals, TOP);
            method.instructions.add(new FrameNode(F_NEW, locals.length, locals, 1, new Object[]{"java/lang/Throwable"}));
            method.instructions.add(new VarInsnNode(ASTORE, error));
            method.instructions.add(new VarInsnNode(LLOAD, token)); method.instructions.add(new VarInsnNode(ALOAD, error));
            method.instructions.add(new MethodInsnNode(INVOKESTATIC, CALLBACK, "exit", "(JLjava/lang/Throwable;)V", false));
            method.instructions.add(new VarInsnNode(ALOAD, error)); method.instructions.add(new InsnNode(ATHROW));
            method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof FrameNode) {
                java.util.List<Object> local = ((FrameNode) instruction).local;
                int slots = 0;
                for (Object value : local) slots += value == LONG || value == DOUBLE ? 2 : 1;
                while (slots++ < token) local.add(TOP);
                local.add(LONG);
            }
            method.maxLocals = error + 1; changed = true;
        }
        if (!changed) return bytes;
        ClassWriter writer = new ZeroLoadClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }
    private ServletWeaver() { }
}
