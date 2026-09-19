// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/update/UpdateZipGuard.kt 移植
package cn.song.mybatisplus.visualizer.update;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 自更新包解压有界化守卫（T-UpdateZipBounds，O-29，DBX b0d30e6 有界解压吸收）。
 *
 * <p>解压面的两个经典风险与「来源可信」正交：①zip bomb（高压缩比撑爆磁盘/内存）；②条目头
 * 声明大小与实际不符（谎报小头解出巨量字节——JVM zip 读取器不按声明大小截断输出）。
 * 本插件更新 zip 的真解压由平台执行（动态装载/重启安装），插件侧职责是<b>把异常包拦在
 * 平台解压器之前</b>：中央目录三重上限 + 逐条目「头声明值 +1 字节」截断读取（读到第
 * declared+1 字节即判定头谎报，快速失败）。
 *
 * <p>常量按实际包体量级 ×10 余量（2026-09-17 实测 beta.4 包：5 条目 / 解压 7.3MB / 最大
 * 条目插件 jar 3.4MB），超限错误信息含实际值便于调参；未来包体自然增长触发误杀时
 * 调常量即可（单一定义点）。
 */
public final class UpdateZipGuard {

    /** Kotlin internal object 单例等价。 */
    public static final UpdateZipGuard INSTANCE = new UpdateZipGuard();

    private UpdateZipGuard() {
    }

    /** 条目数上限（实测 5 → ×10 余量取 64）。 */
    public static final int MAX_ENTRIES = 64;

    /** 单条目解压字节上限（实测最大 3.4MB → 64MB）。 */
    public static final long MAX_SINGLE_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;

    /** 全包解压字节上限（实测 7.3MB → 128MB）。 */
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 128L * 1024 * 1024;

    /**
     * 守卫结论：{@link Ok} 放行；{@link Reject} 携带面向日志与用户的原因（含实际值）。
     *
     * <p>permits 子句省略（对齐 master Kotlin {@code sealed interface GuardResult} 的隐式许可）：
     * 两个子类型都声明在同一编译单元内，Java 由编译器隐式推断为许可子类——显式写
     * {@code permits Ok, Reject} 时简单名在 permits 子句作用域内解析不到嵌套类型，javac 报
     * 「找不到符号: 类 Ok」并连带把 Ok/Reject 判成非许可子类。
     */
    public sealed interface GuardResult {

        /** 放行（Kotlin data object 等价单例）。 */
        final class Ok implements GuardResult {
            public static final Ok INSTANCE = new Ok();
            private Ok() {
            }
        }

        /**
         * 拒绝。
         *
         * @param reason 面向日志与用户的原因（含实际值）
         */
        record Reject(String reason) implements GuardResult {
        }
    }

    /**
     * 有界校验更新包：中央目录计数/总量/单条目三重上限 + 逐条目有界读取防头谎报。
     *
     * <p>头谎报机制说明（java.util.zip 实现差异）：「声明小实际大」的暴露形态因 JDK 而异——
     * 本机 JBR17 实测为读超声明字节（截断读取命中），部分实现按声明截断+CRC 校验以读取
     * 异常暴露——故逐条目读取的<b>异常</b>与<b>读超声明+1</b>两条路都判拒；声明缺失（-1）的
     * 罕见形态按单条目上限截断读（实际读取仍有界）。全程解压量有界
     * （≤ {@link #MAX_TOTAL_UNCOMPRESSED_BYTES} + 每条目 1 字节探针），恶意包最坏代价 = 有界的
     * CPU/内存；正常包一次全量解压（更新链一次性动作，~10MB 级）。目录条目不读流。
     *
     * @return {@link GuardResult.Ok} 或 {@link GuardResult.Reject}（原因可读，含实际值）
     */
    public static GuardResult check(Path zip) {
        try {
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                List<ZipEntry> entries = new ArrayList<>();
                java.util.Enumeration<? extends ZipEntry> it = zf.entries();
                while (it.hasMoreElements()) {
                    ZipEntry e = it.nextElement();
                    if (!e.isDirectory()) entries.add(e);
                }
                if (entries.size() > MAX_ENTRIES) {
                    return new GuardResult.Reject(
                            "too many entries: " + entries.size() + " > " + MAX_ENTRIES);
                }
                long total = 0L;
                for (ZipEntry entry : entries) {
                    long declared = entry.getSize();
                    if (declared > MAX_SINGLE_UNCOMPRESSED_BYTES) {
                        return new GuardResult.Reject(
                                "entry '" + entry.getName() + "' declares " + declared
                                        + " bytes > single-entry cap " + MAX_SINGLE_UNCOMPRESSED_BYTES);
                    }
                    total += Math.max(declared, 0);
                    if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        return new GuardResult.Reject(
                                "declared total " + total + " bytes > total cap " + MAX_TOTAL_UNCOMPRESSED_BYTES);
                    }
                }
                // 逐条目有界读取：读超「声明值+1」判谎报；读取异常（size/CRC 不一致——ZipFile
                // 按声明截断+CRC 校验，谎报头的实际暴露形态）同样判拒，绝不让异常包过闸
                for (ZipEntry entry : entries) {
                    long cap = entry.getSize() >= 0 ? entry.getSize() + 1 : MAX_SINGLE_UNCOMPRESSED_BYTES + 1;
                    String lie = null;
                    Throwable readError = null;
                    try {
                        long read = 0L;
                        try (InputStream stream = zf.getInputStream(entry)) {
                            byte[] buf = new byte[64 * 1024];
                            while (true) {
                                int n = stream.read(buf);
                                if (n < 0) break;
                                read += n;
                                if (read > cap) {
                                    lie = "lies about size: declared " + entry.getSize() + " bytes "
                                            + "but more data follows";
                                    break;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        readError = t;
                    }
                    if (lie != null) {
                        return new GuardResult.Reject("entry '" + entry.getName() + "' " + lie);
                    }
                    if (readError != null) {
                        return new GuardResult.Reject(
                                "entry '" + entry.getName() + "' unreadable (size/CRC mismatch — likely lying "
                                        + "header or truncated data): " + readError.getMessage());
                    }
                }
                return GuardResult.Ok.INSTANCE;
            }
        } catch (Throwable t) {
            return new GuardResult.Reject("zip guard read failed: " + t.getMessage());
        }
    }
}
