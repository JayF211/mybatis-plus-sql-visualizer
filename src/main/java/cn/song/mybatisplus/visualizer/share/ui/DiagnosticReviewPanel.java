// 自 master share/ui/DiagnosticReviewPanel.kt 移植（R94 逐段审阅 + 显式同意）
package cn.song.mybatisplus.visualizer.share.ui;

import cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft;
import cn.song.mybatisplus.visualizer.share.ShareContentProjection;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R94：诊断草稿审阅面板——逐段勾选、正文可编辑、字节预算读数、显式同意。
 *
 * <p>任何勾选变化或正文编辑都会撤销同意（{@link #revokeConsent()}）：
 * 同意授权的是「当下这一份文本」，不是后续编辑与重试。</p>
 */
public final class DiagnosticReviewPanel extends JPanel {

    private final DiagnosticShareDraft draft;
    private final JTextArea editor;
    public final JCheckBox consent = new JCheckBox(MpSqlVisualizerBundle.message("share.review.consent"));
    private final JBLabel bytes = new JBLabel();
    public final Map<String, JCheckBox> sections = new LinkedHashMap<>();

    public DiagnosticReviewPanel(@NotNull DiagnosticShareDraft draft, @NotNull JTextArea editor) {
        this.draft = draft;
        this.editor = editor;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(explanation("share.review.destination"));
        add(explanation("share.review.sectionReset"));
        for (DiagnosticShareDraft.Section section : draft.getSections()) {
            JCheckBox box = new JCheckBox(section.title(), true);
            sections.put(section.id(), box);
            box.addActionListener(e -> {
                draft.include(section.id(), box.isSelected());
                editor.setText(draft.getBody());
                revokeConsent();
            });
            add(box);
        }
        editor.setText(draft.getBody());
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }

            private void changed() {
                draft.edit(editor.getText());
                revokeConsent();
            }
        });
        consent.addActionListener(e -> draft.approve(consent.isSelected()));
        add(bytes);
        add(consent);
        refreshBytes();
    }

    public void revokeConsent() {
        consent.setSelected(false);
        draft.approve(false);
        refreshBytes();
    }

    @Nullable
    public String validationKey() {
        if (draft.getByteSize() > ShareContentProjection.MAX_OUTPUT_BYTES) {
            return "share.review.tooLarge";
        }
        if (!draft.isApproved()) {
            return "share.review.needConsent";
        }
        return null;
    }

    @Nullable
    public DiagnosticShareDraft.Snapshot takeSubmission() {
        DiagnosticShareDraft.Snapshot snapshot = draft.takeSubmission();
        consent.setSelected(false);
        return snapshot;
    }

    private static JTextArea explanation(String key) {
        JBTextArea area = new JBTextArea(MpSqlVisualizerBundle.message(key), 3, 40);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setPreferredSize(JBUI.size(620, 52));
        return area;
    }

    private void refreshBytes() {
        bytes.setText(MpSqlVisualizerBundle.message(
                "share.review.bytes", draft.getByteSize(), ShareContentProjection.MAX_OUTPUT_BYTES));
    }
}
