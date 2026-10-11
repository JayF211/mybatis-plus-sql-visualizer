package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SqlPreviewDialog 弹窗测试（T-M23.6）：底部按钮区（执行 SQL / 复制 SQL / 关闭）+
 * 面板「关闭 SQL 面板」回调链路注入（dispose）。
 */
public class SqlPreviewDialogTest extends BasePlatformTestCase {

    private PreviewResult previewResult() {
        return new PreviewResult(
                "SELECT id\nFROM tbl",
                "SELECT id FROM tbl",
                List.of(),
                null,
                WrapperType.LAMBDA_QUERY,
                "tbl",
                List.of("id"));
    }

    public void testClosePanelCallbackInjectedToPanel() throws Exception {
        // T-M23.6：面板 onClosePanelRequested 已被弹窗注入（复制/执行 SQL 提示点「关闭 SQL 面板」
        // → 回调 dispose 关闭预览弹窗）；面板持有与对话框一致的当前结果
        SqlPreviewDialog dialog = new SqlPreviewDialog(getProject(), previewResult());
        SqlPreviewPanel panel = dialog.panelForTest();
        assertNotNull("弹窗应注入关闭面板回调", panel);
        if (panel.onClosePanelRequested != null) {
            panel.onClosePanelRequested.run();
        }
        assertNotNull("dispose 调用后对话框仍可引用（回调链路不抛异常）", dialog);
        // 释放编辑器（面板生命周期约定，同 SqlPreviewPanelTest 的 clear 收尾）
        SqlPreviewPanelSqlViewKt.clear(dialog.panelForTest());
    }

    public void testRowOfContentFidelityAndTruncation() throws Exception {
        // T-M23.8：列表行纯函数；T-CrudSql 原生化改造：Row.of 纯文本行（截断 80 字符加省略号；
        // HTML 转义由渲染器 html.disable 承担，摘要/详情原样保留）
        SqlSelectionDialog.Row shortRow = SqlSelectionDialog.Row.of("L3 LAMBDA_QUERY  tbl", "service.getById(id);");
        assertEquals("摘要原样保留", "L3 LAMBDA_QUERY  tbl", shortRow.getSummary());
        assertEquals("详情原样保留", "service.getById(id);", shortRow.getDetail());
        String longLine = "x".repeat(100);
        SqlSelectionDialog.Row truncated = SqlSelectionDialog.Row.of("L1 BATCH", longLine);
        assertTrue("超长行应截断加省略号", truncated.getDetail().endsWith("…"));
        assertFalse("截断后不应保留完整长行", truncated.getDetail().contains("x".repeat(81)));
        SqlSelectionDialog.Row special = SqlSelectionDialog.Row.of("L1 BATCH", "w.eq(\"a<b&c>d\");");
        assertEquals("特殊字符原样保留（渲染层禁 HTML 解析）", "w.eq(\"a<b&c>d\");", special.getDetail());
    }

    public void testDoOkActionInvokesCallbackWithSelectedCandidate() throws Exception {
        // T-CrudSql 回归防护：OK（doOKAction）必须把选中候选交给 onSelect 回调——
        // 真机曾因回调内同步开 modeless 预览被模态上下文吞掉而表现"OK 无响应"
        List<String> candidates = List.of("A", "B");
        List<String> selected = new ArrayList<>();
        SqlSelectionDialog<String> dialog = new SqlSelectionDialog<>(
                getProject(), candidates,
                List.of(new SqlSelectionDialog.Row("PA", ""), new SqlSelectionDialog.Row("PB", "")),
                selected::add);
        dialog.listForTest().setSelectedIndex(1);
        dialog.doOKAction();
        assertEquals("doOKAction 应把选中候选交给回调", List.of("B"), selected);
    }

    public void testDoubleClickOnCandidateConfirmsSelection() throws Exception {
        // T-CrudSql 用户反馈：双击候选直接确认预览（对齐 IDEA 原生弹窗交互）
        List<String> candidates = List.of("A", "B");
        List<String> selected = new ArrayList<>();
        SqlSelectionDialog<String> dialog = new SqlSelectionDialog<>(
                getProject(), candidates,
                List.of(new SqlSelectionDialog.Row("PA", ""), new SqlSelectionDialog.Row("PB", "")),
                selected::add);
        javax.swing.JList<SqlSelectionDialog.Row> list = dialog.listForTest();
        assertTrue("列表应注册鼠标监听（双击确认）", list.getMouseListeners().length > 0);
        list.dispatchEvent(
                new java.awt.event.MouseEvent(
                        list, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                        10, 10, 2, false, java.awt.event.MouseEvent.BUTTON1));
        assertEquals("双击第一项应触发回调并选中该项", List.of("A"), selected);
        assertEquals("双击后选中索引应落在双击项上", 0, list.getSelectedIndex());
    }

    public void testDialogRendersPrecomputedTextsWithoutPsiResolve() throws Exception {
        // T-M23.8 增量修复（EDT 慢操作回归防护）：弹窗构造只渲染调用方后台预计算的显示文本——
        // 若构造内仍从 PSI 重新计算摘要（旧实现，EDT 上会被 2025.3+ SlowOperations 守卫禁止），
        // 传入的预计算文本会被覆盖，逐项断言即失败
        myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    public void m(OrderService service, Order order) {
                        service.getById(order.getId());
                        service.getById(1001L);
                    }

                    static class Order {
                        private Long id;
                    }

                    static class OrderService {
                        public void getById(Object id) {}
                    }
                }
                """.stripTrailing());
        com.intellij.psi.PsiFile file = myFixture.getFile();
        List<PsiMethodCallExpression> candidates =
                WrapperLocator.sqlCandidatesInRange(file, 0, file.getTextLength());
        assertTrue("fixture 应含多个候选（触发多选弹窗分支）", candidates.size() >= 2);
        List<SqlSelectionDialog.Row> rows = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            rows.add(SqlSelectionDialog.Row.of("PRE-" + i, ""));
        }
        SqlSelectionDialog<PsiMethodCallExpression> dialog =
                new SqlSelectionDialog<>(getProject(), candidates, rows, t -> {});
        javax.swing.ListModel<SqlSelectionDialog.Row> model = dialog.listForTest().getModel();
        assertEquals("列表项数应与预计算行数一致", rows.size(), model.getSize());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals("列表第 " + i + " 项应原样渲染预计算行（不重新解析 PSI）", rows.get(i), model.getElementAt(i));
        }
    }

    public void testDialogRendersXmlTagCandidates() throws Exception {
        // T-M25-01 泛化回归补测（T-M29.6）：XmlTag 语句候选同样走预计算文本渲染——
        // 弹窗构造不触碰 PSI，XML 语句候选与 Wrapper 候选行为一致（选择区/全文件候选共用）
        myFixture.configureByText(
                "OrderMapper.xml",
                """
                <mapper namespace="com.example.OrderMapper">
                  <select id="selectList">SELECT * FROM t</select>
                  <select id="selectByName">SELECT * FROM t WHERE name = #{name}</select>
                </mapper>
                """.stripTrailing());
        XmlFile file = (XmlFile) myFixture.getFile();
        List<XmlTag> candidates =
                XmlSqlGutterLineMarkerProvider.statementsInRange(file, 0, file.getTextLength());
        assertTrue("fixture 应含多条语句（触发多选弹窗分支）", candidates.size() >= 2);
        List<SqlSelectionDialog.Row> rows = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            rows.add(SqlSelectionDialog.Row.of("PRE-" + i, ""));
        }
        SqlSelectionDialog<XmlTag> dialog =
                new SqlSelectionDialog<>(getProject(), candidates, rows, t -> {});
        javax.swing.ListModel<SqlSelectionDialog.Row> model = dialog.listForTest().getModel();
        assertEquals("列表项数应与预计算行数一致", rows.size(), model.getSize());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals("列表第 " + i + " 项应原样渲染预计算行（不重新解析 PSI）", rows.get(i), model.getElementAt(i));
        }
    }
}
