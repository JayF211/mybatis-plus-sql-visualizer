package cn.song.mybatisplus.sqlvis.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
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
 *
 * <p>COMPUTE_FRAMES 禁用（红线，20261006 dev 验收⑥ P0）：帧合并的 getCommonSuperClass
 * 按 Class.forName 解析类型层级，而 transform 回调运行于被织类自身 defineClass1 进行中
 * （ClassFileLoadHook 时点）——同线程重入 loadClass 时 findLoadedClass 返回 null、
 * findClass 二次 defineClass，宿主启动即炸 {@code LinkageError: attempted duplicate
 * class definition}（mysql 8.0.28 ConnectionImpl prepare 面实录）。本类固定
 * {@code new ZeroLoadClassWriter(COMPUTE_MAXS)}；[ZeroLoadClassWriter]
 * 仅作防误挂 COMPUTE_FRAMES 的零类加载兜底。
 *
 * <p>COMPUTE_MAXS-only 的帧语义（20261006 全套回归 7 例 VerifyError 实测定形）：出口直线
 * 插桩不新增存活局部，原方法 StackMapTable 原样有效（ClassNode 下帧挂 Label 锚点，序列化
 * 偏移随插桩自动平移）；但 EXEC 族**头插 token 槽（long）在原方法全部分支帧点存活**，原帧
 * 不含该槽 → 校验器按帧断言槽位为 top、lload 读 long 即 VerifyError。对策：读入固定
 * EXPAND_FRAMES（压缩帧 local 为 null 无法补），织入成功的 timed 方法把每个 FrameNode 的
 * local 数组（info 计数，long 占一元素——ASM Frame.setInputFrameFromApiFormat 契约）尾部
 * 追加一个 LONG：token 槽恰起于原 maxLocals（= 原 info 数末尾，与 long/double 形参无关），
 * 且自方法头至任一出口恒为已赋值 long，对所有帧点一致成立。
 */
public final class StatementWeaver implements Opcodes {

    static final String CALLBACK_CLASS = "cn/song/mybatisplus/sqlvis/agent/hook/SqlHookCallbacks";

    private static final String ENTER_SIG = "(Ljava/lang/Object;)J";
    /** 统一出口签名（EXEC 与 BATCH 同形：sql 槽 BATCH 传 null，op 名分支在回调内区分）。 */
    private static final String EXIT_SIG =
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;JLjava/lang/Object;Ljava/lang/Throwable;)V";
    private static final String ADD_BATCH_SIG = "(Ljava/lang/Object;Ljava/lang/String;)V";
    private static final String CLEAR_BATCH_SIG = "(Ljava/lang/Object;)V";
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
        /** clearBatch()——登记表批串清账（statement 面，F74：不登记则清批前残串串进下一事件）。 */
        CLEAR_BATCH,
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
        final Kind[] statementFace =
                {Kind.EXEC_PARAM, Kind.EXEC_CTX, Kind.BATCH, Kind.ADD_BATCH, Kind.CLEAR_BATCH};
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

        // EXPAND_FRAMES：压缩帧（same_frame 等）local 为 null 无法补帧；展开后为全量 info 数组，
        // 写出时由 MethodWriter 重压缩。帧补丁见 patchFramesWithTokenSlot。
        final ClassNode node = new ClassNode();
        new ClassReader(classfileBuffer).accept(node, ClassReader.EXPAND_FRAMES);
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
        final ClassWriter writer = new ZeroLoadClassWriter(ClassWriter.COMPUTE_MAXS);
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
            case CLEAR_BATCH:
                return "clearBatch".equals(name) && desc.equals("()V");
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
     * 前插出口；ADD_BATCH/CLEAR_BATCH/PREPARE：只在 RETURN/ARETURN 前插出口（无计时无 token）。
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
            } else if (kind == Kind.CLEAR_BATCH && op == RETURN) {
                final InsnList l = new InsnList();
                l.add(new VarInsnNode(ALOAD, 0));
                l.add(new MethodInsnNode(INVOKESTATIC, CALLBACK_CLASS, "onClearBatchExit", CLEAR_BATCH_SIG, false));
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
            // tokenSlot 在方法顶部已算好（= 原 maxLocals）；此处只按新布局重算 maxLocals
            mn.maxLocals = retSlot + 2; // token 2 槽 + 暂存 2 槽（long 返回占 2）
            if (timed) {
                patchFramesWithTokenSlot(mn, tokenSlot);
            }
            return true;
        }
        return false;
    }

    /**
     * timed 面头插 token(long) 槽在原方法全部分支帧点存活，原 StackMapTable 帧不含该槽
     * （校验器按帧断言为 top，lload 读 long 即 VerifyError）——给织入成功方法的每个
     * FrameNode 的 local 数组**按槽数补齐到 token 槽位后追加一个 LONG**。
     *
     * <p><b>勘误（T-DevAcceptFix2，20261006 第二轮验收本机复现）</b>：原实现直接
     * {@code local.add(LONG)}，其前提「token 槽恰起于原 info 数末尾」**是错的**——原帧 locals
     * 的 info 数可**小于** maxLocals（javac 不使用的尾部槽位在帧里不出现，语义即隐式 top）。
     * 实证：mysql-connector 8.0.28 {@code com.mysql.cj.jdbc.StatementImpl.execute(String,String[])}
     * 的 {@code stack=3, locals=4, args_size=3}，原帧 locals 只有 3 项（slot 3 未用）→ 追加 LONG 后
     * 帧声称 long 落在 slot 3-4，而头插 {@code LSTORE} 用 tokenSlot=4（写 slot 4-5）→ 恰错位一槽，
     * 首次加载被织类即 {@code VerifyError: Stack map does not match the one at exception handler}，
     * 宿主一建 Statement 就崩（真机现象＝开启增强捕获后应用起不来、0 事件）。
     *
     * <p>正确形态：按槽数（long/double 占 2，其余占 1）补齐 TOP 到 tokenSlot 再追加 LONG——
     * 与头插 {@code LSTORE tokenSlot} 的写入位置逐槽对齐。出口暂存槽（retSlot）只在最后一个帧点与
     * 返回指令之间瞬态使用，不进帧、无需补。
     */
    private static void patchFramesWithTokenSlot(MethodNode mn, int tokenSlot) {
        AbstractInsnNode insn = mn.instructions.getFirst();
        while (insn != null) {
            if (insn instanceof FrameNode) {
                final java.util.List<Object> local = ((FrameNode) insn).local;
                if (local != null) {
                    int slots = 0;
                    for (Object entry : local) {
                        slots += (entry == Opcodes.LONG || entry == Opcodes.DOUBLE) ? 2 : 1;
                    }
                    while (slots < tokenSlot) {
                        local.add(Opcodes.TOP);
                        slots++;
                    }
                    local.add(Opcodes.LONG);
                }
            }
            insn = insn.getNext();
        }
    }
}
