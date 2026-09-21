// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inlay/PreviewInsertColumnInlayProvider.kt 移植（PreviewInlayScope 对象——Java 一文件一公共类，从同文件拆出）
package cn.song.mybatisplus.visualizer.ui.inlay;

import cn.song.mybatisplus.visualizer.xml.XmlStatementKind;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * 预览语句类型载体 + 作用域锚点（T-InsertColumnInlay O-44b）。
 *
 * <p><strong>为什么需要作用域判定</strong>：inlay 的 EP（{@code com.intellij.codeInsight.inlayProvider}）
 * 是 <strong>language 级</strong>的——一旦注册 {@code language="SQL"}，IDE 内<strong>所有</strong> SQL 编辑器
 * （含用户自己的 {@code .sql} 文件）都会向本 provider 索取提示。本任务唯一的真实风险即「作用域泄漏到
 * 用户文件」，故 provider 首件事就是判定「这个编辑器是不是本面板的预览编辑器」。
 *
 * <p><strong>双锚点（两条都成立才算命中）</strong>：
 * <ol>
 *  <li><strong>文件名</strong> = 本插件预览文件名形态（一级按形态匹配
 *      {@link #isPrimaryPreviewFileName(String)} / 二级 {@link #MEMORY_PREVIEW_FILE_NAME} 精确匹配）；</li>
 *  <li><strong>userData</strong> = {@link #KEY} 语句类型载体（Editor 优先、PSI 文件兜底，同 M16.2 补全
 *      上下文双载体口径）。</li>
 * </ol>
 *
 * <p>单靠 userData 不够：它只证明「有人往这个编辑器写过本插件的键」，锚点二把「本插件预览文件」
 * 这个事实也钉上，两者同时成立才放行——用户自己的 {@code .sql} 文件即使被误挂了键（或文件恰好同名但
 * 非本面板产物）也不满足全部条件。反之单靠文件名也不够（同名用户文件会误命中），故取合取。
 *
 * <p><strong>文件名取值口径（T-FixN2 合并后订正）</strong>：预览文件有<strong>三个不同形态</strong>——
 * <ol>
 *  <li><strong>一级</strong>（项目内临时 VFS 文件，inlay 真正会渲染的那一级）：
 *      {@code mpsql-preview-<pid>-<seq>.sql}，名字<strong>每次预热唯一</strong>（T-FixN2 消除平台
 *      File Cache Conflict 模态，见 {@code SqlPreviewPanel} 的 {@code previewTempFileName} 调用处），
 *      故锚点按<strong>形态</strong>匹配而不是固定名；</li>
 *  <li><strong>二级</strong>（内存 PSI，降级链第二级）：{@code mpsql-preview-memory.sql}，固定名
 *      （无 VFS 文件，不受唯一命名影响），精确匹配——它<strong>不</strong>满足一级形态
 *      （{@code memory} 不是数字序号），不会被误判成一级；</li>
 *  <li>测试缝文件名 {@code preview.sql}（{@code SqlPreviewPanel.createPreviewFileForTest}）
 *      <strong>有意不列入</strong>：它是最像用户文件的普通名，列入即把「用户自己的 {@code .sql} 文件」
 *      判据打穿。</li>
 * </ol>
 *
 * <p><strong>为什么不用宽松前缀判据</strong>（如 {@code startsWith("mpsql-preview")}）：那会把无关名字
 * 一起放进来——{@code mpsql-preview-memory.sql} 会被当成一级名，用户自造的同前缀文件也满足文件名锚点，
 * 判据形同虚设。形态判据必须紧致：前缀 + 数字 owner + 数字序号 + 扩展名，四段全中才算一级。原生控制台
 * 临时文件 {@code mpsql-exec.sql}（{@code DataSourceSelector.projectExecFile}）<strong>不</strong>满足该形态
 * （前缀不同）——它在锚点处被拦下是<strong>已知边界</strong>（该路径 inlay 不渲染），不得因放宽判据而变成
 * 「错误地渲染」。
 */
public final class PreviewInlayScope {

    /** Kotlin object 单例等价。 */
    public static final PreviewInlayScope INSTANCE = new PreviewInlayScope();

    private PreviewInlayScope() {
    }

    /**
     * 一级预览文件名<strong>前缀</strong>（项目内临时 VFS 文件，inlay 真正会渲染的那一级）。
     *
     * <p>名字<strong>不是固定值</strong>：T-FixN2 起每次预热换唯一名（消除平台 File Cache Conflict 模态），
     * 故此处给出的是<strong>形态常量</strong>——名字由 {@link #previewTempFileName(String, int)} 生成、
     * 由 {@link #isPrimaryPreviewFileName(String)} 判定，生成与判定<strong>共用同一份常量</strong>
     * （{@code SqlPreviewPanel.nextPreviewTempFileName} 也引用它）。这是「面板建文件处」与「锚点判据」
     * 之间唯一的事实源：两侧各自硬编码字面量会在改名时静默打穿锚点——锚点不报错，只是 inlay 永不出现。
     */
    public static final String PREVIEW_FILE_NAME_PREFIX = "mpsql-preview-";

    /** 一级预览文件名<strong>扩展名</strong>（同 {@link #PREVIEW_FILE_NAME_PREFIX} 的形态常量口径）。 */
    public static final String PREVIEW_FILE_NAME_EXT = ".sql";

    /** 二级预览文件（内存 PSI，降级链第二级）名——固定名，无 VFS 文件，不受唯一命名影响。 */
    public static final String MEMORY_PREVIEW_FILE_NAME = "mpsql-preview-memory.sql";

    /**
     * 一级预览文件名形态样例（{@code mpsql-preview-<owner>-<seq>.sql} 的一个合法实例）。
     *
     * <p>供文档与测试构造一个「形态合法」的一级名用（测试用 {@code configureByText} 造 fixture 文件时，
     * 生产名由 {@code SqlPreviewPanel.nextPreviewTempFileName} 生成，两者形态一致）。<strong>不是</strong>
     * 生产使用的固定名——生产名每轮预热都不同。
     */
    public static final String PREVIEW_FILE_NAME_SAMPLE = previewTempFileName("12480", 1);

    /**
     * 一级预览文件名紧致形态判据：{@code <前缀><数字 owner>-<数字序号><扩展名>}（owner = pid）。
     *
     * <p>四段全中才算一级：{@link #PREVIEW_FILE_NAME_PREFIX} + {@code \d+} + {@code -} + {@code \d+}
     * + {@link #PREVIEW_FILE_NAME_EXT}。前缀与扩展名由共享常量 {@link java.util.regex.Pattern#quote}
     * 转义后拼入，杜绝判据与生成漂移；owner 段<strong>不</strong>与当前 pid 比对——跨会话残留
     * （同 pid 复用 / 已退出会话的残留文件）经 {@code SqlPreviewPanel.projectPreviewFile} 的兜底分支
     * 仍会被本面板使用，按 pid 比对会让那一支静默失去 inlay。
     */
    private static final Pattern PRIMARY_PREVIEW_FILE_NAME_PATTERN = Pattern.compile(
            "^" + Pattern.quote(PREVIEW_FILE_NAME_PREFIX) + "\\d+-\\d+" + Pattern.quote(PREVIEW_FILE_NAME_EXT) + "$");

    /** 预览语句类型载体（值 = 当前预览的语句类型；null = 非本面板预览 / 无语句类型语义）。 */
    public static final Key<XmlStatementKind> KEY = Key.create("MP_PREVIEW_STATEMENT_KIND");

    /**
     * 按共享形态常量拼出一个预览临时文件名（形态的<strong>唯一生成入口</strong>）。
     *
     * @param owner 会话段（本 IDE 进程 pid，取不到时调用方给 {@code 0}）
     * @param seq   本 JVM 内预览序号（同一 owner 下唯一）
     */
    public static String previewTempFileName(String owner, int seq) {
        return PREVIEW_FILE_NAME_PREFIX + owner + "-" + seq + PREVIEW_FILE_NAME_EXT;
    }

    /** 是否为本插件<strong>一级</strong>预览文件名（项目内临时 VFS 文件，见 {@link #PRIMARY_PREVIEW_FILE_NAME_PATTERN}）。 */
    public static boolean isPrimaryPreviewFileName(String name) {
        return PRIMARY_PREVIEW_FILE_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * 是否为本插件预览文件名（一级形态匹配 <strong>或</strong> 二级内存名精确匹配）。
     *
     * <p>注意是<strong>紧致</strong>判据：不是 {@code startsWith(前缀)}——内存级
     * {@code mpsql-preview-memory.sql}、原生控制台临时文件 {@code mpsql-exec.sql}、测试缝名
     * {@code preview.sql} 都不满足一级形态。
     */
    public static boolean isPreviewFileName(String name) {
        return MEMORY_PREVIEW_FILE_NAME.equals(name) || isPrimaryPreviewFileName(name);
    }

    /** 写载体（Editor 与 PSI 文件双写，面板收口处调用）；{@code kind} 为 null 即清除。 */
    public static void attach(UserDataHolder target, @Nullable XmlStatementKind kind) {
        target.putUserData(KEY, kind);
    }

    /**
     * 双锚点判定 + 取语句类型：命中返回语句类型，未命中返回 null（provider 据此不产任何提示）。
     *
     * @param file   平台传入的 PSI 文件（裸 Document 降级级无 PSI ⇒ provider 不被调用，天然不渲染）
     * @param editor 平台传入的编辑器（userData 主载体：用户输入后 PSI 可能被 commit 重建而丢 userData，
     *               Editor 对象编辑期间稳定）
     */
    @Nullable
    public static XmlStatementKind previewStatementKind(@Nullable PsiFile file, @Nullable Editor editor) {
        if (file == null || !isPreviewFileName(file.getName())) return null;
        // Editor 优先、PSI 文件兜底（Editor 载体为 null 时回落文件载体）
        XmlStatementKind fromEditor = editor == null ? null : editor.getUserData(KEY);
        return fromEditor != null ? fromEditor : file.getUserData(KEY);
    }
}
