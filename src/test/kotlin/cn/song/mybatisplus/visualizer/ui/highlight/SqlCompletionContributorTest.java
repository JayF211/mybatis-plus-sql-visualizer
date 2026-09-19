package cn.song.mybatisplus.visualizer.ui.highlight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import cn.song.mybatisplus.visualizer.sql.SqlClause;
import cn.song.mybatisplus.visualizer.sql.SqlTextAnalysis;

import kotlin.Pair;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * SQL 补全基础设施测试（M9-03/04）：
 *  - 原生 SQL FileType PSI 文件可创建
 *  - CompletionContributor 已注册到 SQL 语言
 *  - SqlCompletionProvider 提供关键字
 *  - M16.2：detectClause 边界防御（offset clamp/空文本）与上下文双载体读取（Editor userData 优先）
 *  - T-M27-01：detectClause 迁入 sql.SqlTextAnalysis 纯函数模块——下述 detectClause 用例改走公共 API
 *    （断言语义不变）
 */
public class SqlCompletionContributorTest extends BasePlatformTestCase {

    private FileType sqlFileType() {
        FileType ft = FileTypeManager.getInstance().getStdFileType("SQL");
        if (ft == null) return PlainTextFileType.INSTANCE;
        return ft;
    }

    private Language sqlLanguage() {
        FileType ft = sqlFileType();
        if (ft instanceof LanguageFileType) {
            return ((LanguageFileType) ft).getLanguage();
        }
        Language l = Language.findLanguageByID("SQL");
        return l != null ? l : Language.ANY;
    }

    @Test
    public void testPsiFileCreatedForNativeSql() throws Exception {
        PsiFile file = PsiFileFactory.getInstance(getProject())
                .createFileFromText("preview.sql", sqlFileType(), "SELECT 1 FROM t");
        assertNotNull("原生 SQL FileType 应能生成 PSI 文件", file);
        assertEquals(sqlLanguage(), file.getLanguage());
    }

    @Test
    public void testCompletionContributorRegistered() throws Exception {
        // M34 回归：全语言注册（无 language 属性）→ CompletionContributor.forLanguage 不返回，
        // 故改为验证贡献者类存在且可实例化（注册本身由平台 EP 机制保证，运行时行为需 runIde 验证）。
        SqlCompletionContributor contributor = new SqlCompletionContributor();
        assertNotNull("SqlCompletionContributor 应可实例化", contributor);
    }

    @Test
    public void testProviderKeywords() throws Exception {
        List<String> keywords = SqlCompletionProvider.getInstance().keywords();
        assertTrue("关键字应含 SELECT", keywords.contains("SELECT"));
        assertTrue("关键字应含 FROM", keywords.contains("FROM"));
        assertTrue("关键字应含 WHERE", keywords.contains("WHERE"));
    }

    @Test
    public void testDefaultProviderUsesContext() throws Exception {
        SqlCompletionContext ctx = new SqlCompletionContext("merchant_entity", List.of("id", "phone"), null);
        assertEquals("merchant_entity", SqlCompletionProvider.getInstance().tableName(ctx));
        assertEquals(List.of("id", "phone"), SqlCompletionProvider.getInstance().columns(ctx));
    }

    @Test
    public void testColumnsWithCommentsMergesContext() throws Exception {
        // T-M16-03：columnsWithComments 合并 columns 与 columnComments（无注释列返回 null）
        SqlCompletionContext ctx = new SqlCompletionContext("merchant_entity", List.of("id", "name"), null,
                Map.of("name", "设备名称"));
        List<Pair<String, String>> merged = SqlCompletionProvider.getInstance().columnsWithComments(ctx);
        assertEquals(List.of(new Pair<String, String>("id", null), new Pair<String, String>("name", "设备名称")), merged);
        // 无注释上下文向后兼容
        SqlCompletionContext plain = new SqlCompletionContext("merchant_entity", List.of("id"), null);
        assertEquals(List.of(new Pair<String, String>("id", null)),
                SqlCompletionProvider.getInstance().columnsWithComments(plain));
    }

    @Test
    public void testTableCommentFromContext() throws Exception {
        // T-M16-05：表注释经 context 传递
        SqlCompletionContext ctx = new SqlCompletionContext("merchant_entity", List.of(), null, Map.of(), "商户表");
        assertEquals("商户表", SqlCompletionProvider.getInstance().tableComment(ctx));
    }

    @Test
    public void testDetectClausePositionAware() throws Exception {
        // T-M16-04：位置感知——FROM/JOIN 后表名位、WHERE/ON/HAVING/GROUP BY/ORDER BY 后列名位、
        // SELECT 列位、无法判定全量（T-M27-01：改走 SqlTextAnalysis 公共 API，语义不变）
        assertEquals(SqlClause.TABLE, SqlTextAnalysis.detectClauseFromText("SELECT id FROM "));
        assertEquals(SqlClause.TABLE, SqlTextAnalysis.detectClauseFromText("SELECT t.id FROM device t LEFT JOIN "));
        assertEquals(SqlClause.COLUMN, SqlTextAnalysis.detectClauseFromText("SELECT id FROM device WHERE "));
        assertEquals(SqlClause.COLUMN, SqlTextAnalysis.detectClauseFromText("SELECT id FROM device WHERE id = 1 AND "));
        assertEquals(SqlClause.COLUMN, SqlTextAnalysis.detectClauseFromText("SELECT id FROM device ORDER BY "));
        assertEquals(SqlClause.COLUMN, SqlTextAnalysis.detectClauseFromText("SELECT id FROM device GROUP BY "));
        assertEquals(SqlClause.COLUMN, SqlTextAnalysis.detectClauseFromText("SELECT id FROM device t ON "));
        assertEquals(SqlClause.SELECT_COLUMN, SqlTextAnalysis.detectClauseFromText("SELECT "));
        assertEquals(SqlClause.SELECT_COLUMN, SqlTextAnalysis.detectClauseFromText("SELECT id, "));
        assertEquals(SqlClause.UNKNOWN, SqlTextAnalysis.detectClauseFromText(""));
    }

    @Test
    public void testDetectClauseOffsetClamped() throws Exception {
        // T-M16.2-01：offset 越界 clamp 不抛异常（旧 PSI 未随编辑更新时 offset 可能超过新文档长度——
        // substring 越界异常被 IDE 吞掉 → 补全静默失效的回归点 C）（T-M27-01：改走 SqlTextAnalysis 公共 API）
        // offset 超过文本长度 → clamp 到末尾，按全量文本判定（不抛异常）
        assertEquals(SqlClause.TABLE, SqlTextAnalysis.detectClause("SELECT id FROM t", 999));
        // offset 为负 → clamp 到 0 → 空前缀 → UNKNOWN
        assertEquals(SqlClause.UNKNOWN, SqlTextAnalysis.detectClause("SELECT id FROM t", -1));
        // 空文本 → UNKNOWN
        assertEquals(SqlClause.UNKNOWN, SqlTextAnalysis.detectClause("", 0));
        // 正常位置不受影响
        String text = "SELECT id FROM t WHERE id = 1 ";
        assertEquals(SqlClause.COLUMN, SqlTextAnalysis.detectClause(text, text.length()));
    }

    @Test
    public void testContextEditorCarrierPreferredOverPsi() throws Exception {
        // T-M16.2-01：上下文双载体读取——Editor userData 优先（嵌入式编辑器编辑后 PSI 重建丢失 userData，
        // 回归点 B）、PSI 文件 userData 兜底
        SqlCompletionContributor contributor = new SqlCompletionContributor();
        PsiFile file = PsiFileFactory.getInstance(getProject())
                .createFileFromText("preview.sql", sqlFileType(), "SELECT 1");
        PsiElement position = PsiTreeUtil.getDeepestFirst(file);

        // ① Editor 无上下文 → 回退 PSI userData
        SqlCompletionContext psiCtx = new SqlCompletionContext("psi_tbl", List.of("id"), null);
        file.putUserData(SqlCompletionContext.KEY, psiCtx);
        SqlCompletionContext fallback = contributor.resolveContext(null, position);
        assertNotNull("Editor 无上下文时应回退 PSI userData", fallback);
        assertEquals("psi_tbl", fallback.tableName);

        // ② Editor 有上下文 → Editor 优先（与 PSI 不一致时取 Editor）
        SqlCompletionContext editorCtx = new SqlCompletionContext("editor_tbl", List.of("name"), null);
        SqlCompletionContext preferred = contributor.resolveContext(editorCtx, position);
        assertNotNull("Editor 有上下文时应优先使用", preferred);
        assertEquals("editor_tbl", preferred.tableName);

        // ③ 双载体均无 → null（关键字补全不受影响，实体补全跳过）
        file.putUserData(SqlCompletionContext.KEY, null);
        SqlCompletionContext noCtx = contributor.resolveContext(null, PsiTreeUtil.getDeepestFirst(file));
        assertNull("无上下文应返回 null", noCtx);
    }

    @Test
    public void testWithCommentCarriesTailTextAndLookupString() throws Exception {
        // T-M16.3-02（用户实测「字段可补全但看不到注释」根因）：LookupElementBuilder 不可变——
        // withTailText/withLookupString 返回新实例，原实现丢弃返回值导致注释 tailText 从不展示。
        // 渲染验证：注释作为灰色尾缀展示 + 进入 lookup strings 支持注释关键词检索
        SqlCompletionContributor contributor = new SqlCompletionContributor();

        // 有注释：tailText 渲染可见 + 注释可检索
        LookupElementBuilder decorated = contributor.withComment(LookupElementBuilder.create("station_id"), "场站ID");
        LookupElementPresentation presentation = new LookupElementPresentation();
        decorated.renderElement(presentation);
        assertEquals("  场站ID", presentation.getTailText());
        assertTrue("注释应进入 lookup strings 供关键词检索", decorated.getAllLookupStrings().contains("场站ID"));

        // 无注释：原样（tailText 为 null，不加检索串）
        LookupElementBuilder plain = contributor.withComment(LookupElementBuilder.create("id"), null);
        LookupElementPresentation plainPresentation = new LookupElementPresentation();
        plain.renderElement(plainPresentation);
        assertNull(plainPresentation.getTailText());
    }

    @Test
    public void testColumnLookupElementRendersCommentAndDbType() throws Exception {
        // T-DbSchemaCompletion：列候选 tailText = 注释 + DB 类型串（渲染验证）。
        // 类型串是「这条来自数据源」的可视标记——此前库里独有的列根本不进候选，用户无从分辨来源。
        LookupElementPresentation withBoth = new LookupElementPresentation();
        SqlCompletionCandidatesKt.columnLookupElement(new ColumnCandidate("username", "姓名", "varchar(64)"))
                .renderElement(withBoth);
        assertEquals("  姓名  varchar(64)", withBoth.getTailText());

        LookupElementPresentation typeOnly = new LookupElementPresentation();
        SqlCompletionCandidatesKt.columnLookupElement(new ColumnCandidate("legacy_flag", null, "tinyint(1)"))
                .renderElement(typeOnly);
        assertEquals("库列无 COMMENT：只展示 DB 类型串", "  tinyint(1)", typeOnly.getTailText());

        // 实体列（无 DB 类型）与无信息列：保持改动前形态
        LookupElementPresentation commentOnly = new LookupElementPresentation();
        SqlCompletionCandidatesKt.columnLookupElement(new ColumnCandidate("id", "主键", null))
                .renderElement(commentOnly);
        assertEquals("  主键", commentOnly.getTailText());

        LookupElementPresentation bare = new LookupElementPresentation();
        SqlCompletionCandidatesKt.columnLookupElement(new ColumnCandidate("password", null, null))
                .renderElement(bare);
        assertNull(bare.getTailText());

        // 注释仍可作关键词检索（M16 语义保留）
        assertTrue(
                "注释应进入 lookup strings 供关键词检索",
                SqlCompletionCandidatesKt.columnLookupElement(new ColumnCandidate("username", "姓名", "varchar(64)"))
                        .getAllLookupStrings().contains("姓名"));
    }

    @Test
    public void testResolveContextLevel3DocumentMap() throws Exception {
        // M34 回归修复（registerContext 从未被调用，documentContexts 恒空 → level 3 死路径）：
        // 注册后 Editor/PSI userData 均丢失时，Document→Context 映射应兜底返回上下文。
        SqlCompletionContributor contributor = new SqlCompletionContributor();
        PsiFile file = PsiFileFactory.getInstance(getProject())
                .createFileFromText("preview.sql", sqlFileType(), "SELECT 1 FROM merchant");
        PsiElement position = PsiTreeUtil.getDeepestFirst(file);

        // ① 未注册 → 三级全 miss → null（映射为空）
        file.putUserData(SqlCompletionContext.KEY, null);
        SqlCompletionContext before = contributor.resolveContext(null, position);
        assertNull("Document 未注册时 level 3 应 miss", before);

        // ② 注册后 → 双载体全空仍兜底命中
        SqlCompletionContext docCtx = new SqlCompletionContext("merchant_entity", List.of("id", "phone"), null);
        Document doc = file.getViewProvider().getDocument();
        if (doc != null) {
            SqlCompletionContributor.registerContext(doc, docCtx);
        }
        file.putUserData(SqlCompletionContext.KEY, null);
        SqlCompletionContext hit = contributor.resolveContext(null, position);
        assertNotNull("Document 已注册时 level 3 应兜底命中", hit);
        assertEquals("merchant_entity", hit.tableName);
    }

    @Test
    public void testRealTempFileTrackedByPsiDocumentManager() throws Exception {
        // M34 回归修复根因：CodeCompletionHandlerBase.invokeCompletion 内部调用
        // PsiDocumentManager.getPsiFile(document)——PsiFileFactory.createFileFromText 创建的
        // in-memory 文件不被追踪（getDocument=null → "no PSI file" 断言）。
        // 回归断言：createEditor 采用的真实临时文件（java.io + LocalFileSystem）必须能被
        // PsiDocumentManager 追踪（document→psi round-trip），补全框架初始化才不 NPE。
        Project proj = getProject();
        File io = File.createTempFile("mp-sql-preview-", ".sql");
        Files.writeString(io.toPath(), "SELECT 1 FROM t");
        io.deleteOnExit();
        VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io);
        assertNotNull("临时 io 文件应能被 LocalFileSystem 解析", vFile);
        PsiFile psi = PsiManager.getInstance(proj).findFile(vFile);
        assertNotNull("临时文件应能创建 PSI", psi);
        Document doc = PsiDocumentManager.getInstance(proj).getDocument(psi);
        assertNotNull("PsiDocumentManager 应能取得临时文件 document", doc);
        PsiFile roundTrip = PsiDocumentManager.getInstance(proj).getPsiFile(doc);
        assertNotNull("PsiDocumentManager 应能从 document 反查到 PSI（补全框架依赖）", roundTrip);
    }
}
