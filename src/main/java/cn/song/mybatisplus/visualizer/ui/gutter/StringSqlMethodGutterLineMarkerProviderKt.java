package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.psi.PsiSnapshot;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiMethod;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code StringSqlMethodGutterLineMarkerProvider.kt} 顶层函数（T-PCP-02 方法锚点校验
 * {@code validatedMethodRows}）的 Java 承载类。
 */
public final class StringSqlMethodGutterLineMarkerProviderKt {

    /** gutter 侧诊断日志（快照失效按 debug 留痕——标记/候选缺失无害，不打扰用户）。 */
    private static final Logger METHOD_GUTTER_LOG =
            Logger.getInstance(StringSqlMethodGutterLineMarkerProvider.class);

    private StringSqlMethodGutterLineMarkerProviderKt() {
    }

    /**
     * T-PCP-02：方法锚点快照的<b>后台校验 + 候选/显示文本预计算</b>收口
     * （须在读权限内调用，生产侧由 {@code ReadAction.compute} 包裹）。
     *
     * <p>捕获与读取之间的写操作会让方法元素失效——校验不过即返回空候选对，调用方按「未找到」如实降级
     * （原实现直接读 {@code call.callExpression.containingFile.viewProvider.document} 算行号，方法区间失效时
     * 会算出错行号或抛失效异常）。守卫规则 {@code psiCapture}（convention/BannedApiSourceScanTest）要求
     * 跨线程读 PSI 的池化块出现本收口（或 {@code PsiSnapshot} 判定）。
     */
    @NotNull
    public static Pair<List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall>, List<SqlSelectionDialog.Row>> validatedMethodRows(
            @NotNull PsiMethod method,
            @Nullable PsiSnapshot snapshot) {
        if (snapshot == null) {
            return emptyRows();
        }
        PsiSnapshot.Validity validity = snapshot.validate();
        if (validity instanceof PsiSnapshot.Validity.Invalid invalid) {
            METHOD_GUTTER_LOG.debug("[gutter] 方法锚点快照已失效（" + invalid.reason().getDescription()
                    + "），跳过候选收集：" + snapshot.getFilePath());
            return emptyRows();
        }
        List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall> calls =
                JdbcTemplateSqlPreviewService.collectMethodCandidates(method);
        List<SqlSelectionDialog.Row> rows = new ArrayList<>();
        for (JdbcTemplateSqlLocator.JdbcTemplateSqlCall call : calls) {
            Document document = call.callExpression().getContainingFile().getViewProvider().getDocument();
            int line = document != null
                    ? document.getLineNumber(call.callExpression().getTextRange().getStartOffset()) + 1
                    : 0;
            rows.add(SqlSelectionDialog.Row.of(
                    call.methodName() + "（第 " + line + " 行）",
                    call.sqlString()));
        }
        return new Pair<>(calls, rows);
    }

    private static Pair<List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall>, List<SqlSelectionDialog.Row>> emptyRows() {
        return new Pair<>(Collections.emptyList(), Collections.emptyList());
    }
}
