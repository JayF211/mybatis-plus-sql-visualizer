package cn.song.mybatisplus.sqlvis.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * 语句面/prepare 面织入器（T-StatementWeave，ASM Tree API）——「attach 即生效」的直接落点：
 * 钩子挂在 execute 族**方法体**上，随每次执行触发，与连接/语句对象的创建时间无关
 * （retransform 已加载语句类后，存量对象的下一次 execute 即带钩子）。
 *
 * <p>织入形态（与 [ConnectWeaver] 同契约）：只改方法体、不增删成员（retransform 合规）；
 * 注入零分支直线 invokestatic，回调自吞一切 Throwable；**织入期判定可见性**——定义类 loader
 * 解析不到回调类则整类不织入（安全降级）。入口+出口双插桩拿精确耗时（计划 §四.4 主案），
 * 出口覆盖 ARETURN 与 ATHROW 两路径（异常也产事件，原样重抛语义不变）。
 *
 * <p>回调签名被本类字节码硬编码，改动须两侧同步（SqlHookCallbacks javadoc 互指）。
 * spec 驱动：类名 → 允许的织入形态（[Kind]），签名不匹配安全跳过（登记表契约）。
 */
public final class StatementWeaver implements Opcodes {

    static final String CALLBACK_CLASS = "cn/song/mybatisplus/sqlvis/agent/hook/SqlHookCallbacks";

    private static final String ENTER_SIG = "(Ljava/lang/Object;)J";
    /** 统一出口签名（EXEC 与 BATCH 同形：sql 槽 BATCH 传 null，op 名分支在回调内区分）。 */
    private static final String EXIT_SIG =
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;JLjava/lang/Object;Ljava/lang/Throwable;)V";
    private static final String ADD_BATCH_SIG = "(Ljava/lang/Object;Ljava/lang/String;)V";
    private static final String PREPARE_SIG = "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)V";

    private static final String STR_DESC = "(Ljava/lang/String;";

    /** 织入形态（按类允许面；包内可见——DriverRegistry 面谓词与测试按名取 spec，单一真相）。 */
    enum Kind {
        /** execute(String)/executeQuery(String)/…——SQL 取首参。 */
        EXEC_PARAM,
        /** PS 无参 execute()/executeQuery()/…——SQL 取登记表（S3），无登记即盲区跳过。 */
        EXEC_CTX,
        /** executeBatch()/executeLargeBatch()——SQL 取登记批串（statement 面）或 preparedSql（PS 面）。 */
        BATCH,
        /** addBatch(String)——登记表批串累积（statement 面）。 */
        ADD_BATCH,
        /** prepareStatement(String)/prepareCall(String) 出口——登记「PS 实例 → SQL + URL」。 */
        PREPARE,
    }

    private static final String[] EXEC_NAMES =
            {"execute", "executeQuery", "executeUpdate", "executeLargeUpdate"};
    private static final String[] BATCH_NAMES = {"executeBatch", "executeLargeBatch"};

    /** 类名（点分）→ 允许形态；驱动语句面（计划 §三 S1）与 prepare 面（S3）。 */
    private static final Map<String, Kind[]> SPECS = new HashMap<String, Kind[]>();

    private static void putSpec(Kind[] kinds, String... classes) {
        for (String c : classes) {
            SPECS.put(c, kinds);
        }
    }

    static {
        final Kind[] statementFace = {Kind.EXEC_PARAM, Kind.EXEC_CTX, Kind.BATCH, Kind.ADD_BATCH};
        putSpec(statementFace,
                "com.mysql.cj.jdbc.StatementImpl",
                "com.mysql.cj.jdbc.ClientPreparedStatement",
                "com.mysql.cj.jdbc.ServerPreparedStatement",
                "com.mysql.jdbc.StatementImpl",
                "com.mysql.jdbc.PreparedStatement",
                "com.mysql.jdbc.ServerPreparedStatement",
                "org.h2.jdbc.JdbcStatement",
                "org.h2.jdbc.JdbcPreparedStatement",
                "org.h2.jdbc.JdbcCallableStatement");
        final Kind[] prepareFace = {Kind.PREPARE};
        putSpec(prepareFace,
                "com.mysql.cj.jdbc.ConnectionImpl",
                "com.mysql.jdbc.ConnectionImpl",
                "org.h2.jdbc.JdbcConnection");
    }

    private StatementWeaver() {
    }

    /** 类名（点分）→ 织入形态 spec（单一真相：DriverRegistry 两面谓词委托本表判定）。 */
    static Kind[] specOf(String dottedClassName) {
        return SPECS.get(dottedClassName);
    }

    public static byte[] weave(ClassLoader definingLoader, String dottedClassName, byte[] classfileBuffer) {
        return weaveSpec(specOf(dottedClassName), definingLoader, classfileBuffer);
    }

    /** 按给定 spec 织入（测试缝：夹具类不在登记表，直接传形态面）。 */
    static byte[] weaveSpec(Kind[] allowed, ClassLoader definingLoader, byte[] classfileBuffer) {
        if (allowed == null) {
            return classfileBuffer; // 未登记（transformer 侧也应拦住）——防御性直返
        }
        // 织入期可见性判定（同 ConnectWeaver §4.2 #6）：定义类 loader（null=bootstrap → system）不可见回调则不织入
        final ClassLoader loader = definingLoader != null
                ? definingLoader
                : ClassLoader.getSystemClassLoader();
        try {
            if (loader != ClassLoader.getSystemClassLoader()) {
                loader.loadClass(CALLBACK_CLASS.replace('/', '.'));
            }
        } catch (Throwable t) {
            return classfileBuffer;
        }

        final ClassNode node = new ClassNode();
        new ClassReader(classfileBuffer).accept(node, 0);
        boolean changed = false;
        for (MethodNode mn : node.methods) {
            if ((mn.access & ACC_ABSTRACT) != 0 || (mn.access & ACC_NATIVE) != 0
                    || (mn.access & ACC_STATIC) != 0 // 语句/连接面回调取 this，静态方法不织（防御）
                    || mn.name.startsWith("<")) {
                continue;
            }
            final Kind kind = kindOf(allowed, mn.name, mn.desc);
            if (kind == null) {
                continue;
            }
            if (weaveMethod(mn, kind)) {
                changed = true;
            }
        }
        if (!changed) {
            return classfileBuffer;
        }
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static Kind kindOf(Kind[] allowed, String name, String desc) {
        for (Kind k : allowed) {
            if (matches(k, name, desc)) {
                return k;
            }
        }
        return null;
    }

    private static boolean matches(Kind kind, String name, String desc) {
        switch (kind) {
            case EXEC_PARAM:
                return in(name, EXEC_NAMES) && desc.startsWith(STR_DESC);
            case EXEC_CTX:
                return in(name, EXEC_NAMES) && desc.startsWith("()");
            case BATCH:
                return in(name, BATCH_NAMES) && desc.startsWith("()");
            case ADD_BATCH:
                return "addBatch".equals(name) && desc.equals("(Ljava/lang/String;)V");
            case PREPARE:
                return ("prepareStatement".equals(name) || "prepareCall".equals(name))
                        && desc.startsWith(STR_DESC)
                        && (desc.contains(")Ljava/sql/PreparedStatement;")
                            || desc.contains(")Ljava/sql/CallableStatement;"));
            default:
                return false;
        }
    }

    private static boolean in(String name, String[] names) {
        for (String n : names) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * EXEC_PARAM/EXEC_CTX/BATCH：方法头插入口（token=nanoTime，0=短路），每个 ARETURN/ATHROW
     * 前插出口；ADD_BATCH/PREPARE：只在 RETURN/ARETURN 前插出口（无计时无 token）。
     * 新槽：token 2 槽 + 出口暂存 1 槽（ARETURN 结果与 ATHROW 异常不同路径可共用）。
     */
    /**
     * EXEC 族返回类型分派（2026-10-05 现场缺陷修正）：executeUpdate→int（IRETURN）、execute→boolean
     * （IRETURN）、executeLargeUpdate→long（LRETURN）——出口若只认 ARETURN，基本类型返回的方法
     * 整个出口钩子插不上（探针实证：织入体执行但零事件）。出口按返回 opcode 分派：基本类型先
     * 存槽、回调处装箱（Integer.valueOf/Long.valueOf）、调用后恢复原值再返回。
     */
    private static boolean isTimedExit(int op) {
        return op == IRETURN || op == LRETURN || op == ARETURN || op == ATHROW;
    }

    private static boolean weaveMethod(MethodNode mn, Kind kind) {
        final boolean timed = kind == Kind.EXEC_PARAM || kind == Kind.EXEC_CTX || kind == Kind.BATCH;
        final int tokenSlot = mn.maxLocals;
        final int retSlot = tokenSlot + 2; // 出口暂存（结果/异常共用；引用与基本类型不同路径不冲突）
        int inserted = 0;

        if (timed) {
            final InsnList head = new InsnList();
            head.add(new VarInsnNode(ALOAD, 0));
            head.add(new MethodInsnNode(INVOKESTATIC, CALLBACK_CLASS, "onStatementEnter", ENTER_SIG, false));
            head.add(new VarInsnNode(LSTORE, tokenSlot));
            mn.instructions.insertBefore(mn.instructions.getFirst(), head);
        }

        AbstractInsnNode insn = mn.instructions.getFirst();
        while (insn != null) {
            final AbstractInsnNode next = insn.getNext();
            final int op = insn.getOpcode();
            if (kind == Kind.ADD_BATCH && op == RETURN) {
                final InsnList l = new InsnList();
                l.add(new VarInsnNode(ALOAD, 0));
                l.add(new VarInsnNode(ALOAD, 1));
                l.add(new MethodInsnNode(INVOKESTATIC, CALLBACK_CLASS, "onAddBatchExit", ADD_BATCH_SIG, false));
                mn.instructions.insertBefore(insn, l);
                inserted++;
            } else if (kind == Kind.PREPARE && op == ARETURN) {
                final InsnList l = new InsnList();
                l.add(new VarInsnNode(ASTORE, retSlot));
                l.add(new VarInsnNode(ALOAD, retSlot));
                l.add(new VarInsnNode(ALOAD, 1));
                l.add(new VarInsnNode(ALOAD, 0));
                l.add(new MethodInsnNode(INVOKESTATIC, CALLBACK_CLASS, "onStatementPrepare", PREPARE_SIG, false));
                l.add(new VarInsnNode(ALOAD, retSlot));
                mn.instructions.insertBefore(insn, l);
                inserted++;
            } else if (timed && isTimedExit(op)) {
                final boolean throwing = op == ATHROW;
                final InsnList l = new InsnList();
                // 1) 返回值/异常先存槽
                if (op == IRETURN) {
                    l.add(new VarInsnNode(ISTORE, retSlot));
                } else if (op == LRETURN) {
                    l.add(new VarInsnNode(LSTORE, retSlot));
                } else {
                    l.add(new VarInsnNode(ASTORE, retSlot)); // ARETURN 引用结果 / ATHROW 异常
                }
                // 2) 回调参数：stmt, sql, op, token, result, failure（BATCH 的 sql 槽传 null，
                //    批 SQL 由回调按 op 名从登记表批串/preparedSql 取——出口块保持单一签名）
                l.add(new VarInsnNode(ALOAD, 0));
                if (kind == Kind.EXEC_PARAM) {
                    l.add(new VarInsnNode(ALOAD, 1)); // sql = 首参
                } else {
                    l.add(new InsnNode(ACONST_NULL)); // EXEC_CTX/BATCH：SQL 走登记表
                }
                l.add(new LdcInsnNode(mn.name)); // op 名（织入期常量）
                l.add(new VarInsnNode(LLOAD, tokenSlot));
                if (throwing) {
                    l.add(new InsnNode(ACONST_NULL)); // result
                    l.add(new VarInsnNode(ALOAD, retSlot)); // failure
                } else if (op == IRETURN) {
                    l.add(new VarInsnNode(ILOAD, retSlot)); // 先压 int 参
                    l.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf",
                            "(I)Ljava/lang/Integer;", false)); // 装箱为结果
                    l.add(new InsnNode(ACONST_NULL)); // failure
                } else if (op == LRETURN) {
                    l.add(new VarInsnNode(LLOAD, retSlot)); // 先压 long 参
                    l.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf",
                            "(J)Ljava/lang/Long;", false));
                    l.add(new InsnNode(ACONST_NULL)); // failure
                } else {
                    l.add(new VarInsnNode(ALOAD, retSlot)); // result（引用）
                    l.add(new InsnNode(ACONST_NULL)); // failure
                }
                l.add(new MethodInsnNode(INVOKESTATIC, CALLBACK_CLASS, "onExecuteExit", EXIT_SIG, false));
                // 3) 恢复返回值
                if (op == IRETURN) {
                    l.add(new VarInsnNode(ILOAD, retSlot));
                } else if (op == LRETURN) {
                    l.add(new VarInsnNode(LLOAD, retSlot));
                } else {
                    l.add(new VarInsnNode(ALOAD, retSlot));
                }
                mn.instructions.insertBefore(insn, l);
                inserted++;
            }
            insn = next;
        }
        if (inserted > 0) {
            mn.maxLocals = retSlot + 2; // token 2 槽 + 暂存 2 槽（long 返回占 2）
            return true;
        }
        return false;
    }
}
