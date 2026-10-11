// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperParserDefinition.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.ASTFactory;
import com.intellij.lang.ASTNode;
import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.PlainTextParserDefinition;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PlainTextTokenTypes;
import com.intellij.psi.tree.IFileElementType;
import org.jetbrains.annotations.NotNull;

/**
 * T-PerfSweep16 F37 伴随发现：{@link SqlMapperLanguage} 的 ParserDefinition（lexer-only 语言的落地件）。
 *
 * <p>缘起：243 平台 {@code InjectionRegistrarImpl.parseFile} 对注入语言缺失 ParserDefinition 直接
 * assert 失败（源码 jar 实证）——注入器从死代码接线为活代码后必须补上，否则首次注入即崩。
 *
 * <p>形态：根元素按整段单叶（PLAIN_TEXT）解析、不产语法树——着色走 syntaxHighlighterFactory
 * 的 {@link SqlMapperSyntaxHighlighter}，词法即 {@link SqlMapperHighlightingLexer}。
 *
 * <p>关键坑（真红实证）：不能复用 PlainTextParserDefinition.createFile 的 PsiPlainTextFileImpl——
 * 其语言是 PlainTextLanguage 而 viewProvider baseLanguage 是 MpSqlMapper，InjectionRegistrarImpl
 * 按 {@code psiFile.getLanguage() == viewProvider.getBaseLanguage()} 决定是否进注入结果，不等则
 * 整个注入静默丢弃（visits=0）。故 createFile 自建 {@link SqlMapperFile}（语言=MpSqlMapper）。
 *
 * <p>注册不走 plugin.xml EP（本轮 EP 声明冻结），由 {@link SqlMapperInjector} 首次注入前编程注册
 * （进程内一次）。
 */
public class SqlMapperParserDefinition extends PlainTextParserDefinition {

    private static final IFileElementType FILE_ELEMENT_TYPE = new IFileElementType(SqlMapperLanguage.INSTANCE) {
        @Override
        public ASTNode parseContents(ASTNode chameleon) {
            return ASTFactory.leaf(PlainTextTokenTypes.PLAIN_TEXT, chameleon.getChars());
        }
    };

    /** 当前显式注册实例（进程内一次；卸载摘除/测试清理按同实例 identity 摘）。 */
    private static volatile SqlMapperParserDefinition registered = null;

    @Override
    public IFileElementType getFileNodeType() {
        return FILE_ELEMENT_TYPE;
    }

    @Override
    public PsiFile createFile(FileViewProvider viewProvider) {
        return new SqlMapperFile(viewProvider);
    }

    /** 语言=MpSqlMapper 的单叶 PSI 文件（叶子形态承 PlainTextParserDefinition，语言必须=注入语言）。 */
    private static final class SqlMapperFile extends PsiFileBase {

        private SqlMapperFile(FileViewProvider viewProvider) {
            super(viewProvider, SqlMapperLanguage.INSTANCE);
        }

        @Override
        public FileType getFileType() {
            return PlainTextFileType.INSTANCE;
        }

        @Override
        public String toString() {
            return "MpSqlMapper:" + getName();
        }

        /**
         * T-PerfSweep16 修复：片段末尾边界（offset == textLength）也返回叶子。
         *
         * <p>平台把光标落在注入区间内（含两端边界，243 实测）的编辑器路由成 EditorWindow，此后
         * 「caret 处元素」类查询全部按片段坐标进行；EOF 是合法编辑位（语句文本最后一字符之后），
         * 单叶文件默认在该位答 null，令以 caret 定位的下游链（补全参数构建等）无从起步。
         * 越界（offset &gt; textLength）仍为 null，不放宽其他边界语义。
         */
        @Override
        public PsiElement findElementAt(int offset) {
            if (offset == getTextLength() && getTextLength() > 0) {
                return super.findElementAt(getTextLength() - 1);
            }
            return super.findElementAt(offset);
        }
    }

    /**
     * 编程式注册（进程内一次，幂等；注册表已在位即跳过）。
     *
     * <p>F48（T-PerfQuick5）：注册挂 {@link SqlMapperInjectionBarrierHost}（插件应用级宿主）为
     * Disposer parent——243 平台 KeyedExtensionCollector.addExplicitExtension 三参重载
     * （V39 源码实证）内部 {@code Disposer.register(parent) { removeExplicitExtension(key, t) }}，
     * 动态卸载容器级联即摘除，旧 classloader 不再被注册表钉住；摘除路径已登记
     * {@code cn.song.mybatisplus.visualizer.update.BarrierRegistry}。测试环境无应用容器时
     * 退回两参裸注册（无卸载语义，测试自清理）。
     */
    public static synchronized void ensureRegistered() {
        LanguageParserDefinitions registry = LanguageParserDefinitions.INSTANCE;
        if (registry.forLanguage(SqlMapperLanguage.INSTANCE) != null) {
            return;
        }
        SqlMapperParserDefinition def = new SqlMapperParserDefinition();
        SqlMapperInjectionBarrierHost host = null;
        try {
            if (ApplicationManager.getApplication() != null) {
                host = ApplicationManager.getApplication()
                        .getService(SqlMapperInjectionBarrierHost.class);
            }
        } catch (Throwable ignored) {
            host = null;
        }
        if (host != null) {
            registry.addExplicitExtension(SqlMapperLanguage.INSTANCE, def, host);
        } else {
            registry.addExplicitExtension(SqlMapperLanguage.INSTANCE, def);
        }
        registered = def;
    }

    /** 测试缝：显式摘除（V39 运行时证据——removeExplicitExtension 对显式注册真实生效）。 */
    public static synchronized void unregisterForTest() {
        SqlMapperParserDefinition def = registered;
        if (def != null) {
            LanguageParserDefinitions.INSTANCE.removeExplicitExtension(SqlMapperLanguage.INSTANCE, def);
        }
        registered = null;
    }
}
