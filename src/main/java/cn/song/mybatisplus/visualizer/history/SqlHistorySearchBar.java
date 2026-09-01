package cn.song.mybatisplus.visualizer.history;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.AbstractAction;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.BorderFactory;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentListener;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * SQL 历史记录搜索栏（F-7 Phase C）：搜索框 + 标签筛选下拉 + 收藏筛选。
 *
 * <p>搜索逻辑：子串匹配（SQL 文本，忽略大小写），输入即时触发 [SearchListener] 回调。
 * 标签筛选与收藏筛选经回调驱动外层面板刷新。
 */
public class SqlHistorySearchBar extends JPanel {

    /** 搜索回调：输入变化即触发，参数为当前搜索词。 */
    public interface SearchListener {
        void onSearch(String query);
    }

    /** 筛选变更回调：筛选下拉变化即触发。 */
    public interface FilterListener {
        void onFilterChange();
    }

    /** 收藏筛选的内部标记值。 */
    public static final String FAVORITES_FILTER = "__favorites__";

    private final JBTextField searchField;
    private final JComboBox<String> filterCombo;
    private final JComboBox<String> tagCombo;
    private final SearchListener onSearch;
    private final FilterListener onFilterChange;

    public SqlHistorySearchBar(SearchListener onSearch, FilterListener onFilterChange) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 4));
        this.onSearch = onSearch;
        this.onFilterChange = onFilterChange;

        searchField = new JBTextField(20);
        searchField.getEmptyText().setText(MpSqlVisualizerBundle.message("history.panel.search"));
        searchField.setBorder(BorderFactory.createCompoundBorder(
                searchField.getBorder(), JBUI.Borders.empty(2, 4)));

        filterCombo = new JComboBox<>();
        tagCombo = new JComboBox<>();

        setBorder(new EmptyBorder(4, 4, 4, 4));

        searchField.addActionListener(e -> emitSearch());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                emitSearch();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                emitSearch();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                emitSearch();
            }
        });
        // ESC 清空
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear");
        searchField.getActionMap().put("clear", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchField.setText("");
                emitSearch();
            }
        });

        filterCombo.addItem(MpSqlVisualizerBundle.message("history.filter.all"));
        filterCombo.addItem(MpSqlVisualizerBundle.message("history.filter.favorites"));
        filterCombo.addActionListener(e -> onFilterChange.onFilterChange());

        tagCombo.addItem(MpSqlVisualizerBundle.message("history.filter.all"));
        tagCombo.addActionListener(e -> onFilterChange.onFilterChange());

        add(searchField);
        add(new JLabel(MpSqlVisualizerBundle.message("history.filter.all") + ":"));
        add(filterCombo);
        add(new JLabel("Tag:"));
        add(tagCombo);
    }

    private void emitSearch() {
        String text = searchField.getText();
        onSearch.onSearch(text != null ? text.trim() : "");
    }

    /** 当前搜索词。 */
    public String getSearchQuery() {
        String text = searchField.getText();
        return text != null ? text.trim() : "";
    }

    /**
     * 当前筛选模式：null = 全部，"__favorites__" = 收藏，其他 = 标签名。
     */
    public String getActiveFilter() {
        if (filterCombo.getSelectedIndex() == 1) {
            return FAVORITES_FILTER;
        }
        int tagIdx = tagCombo.getSelectedIndex();
        return tagIdx <= 0 ? null : tagCombo.getItemAt(tagIdx);
    }

    /** 刷新标签下拉选项（保留当前选中若仍存在）。 */
    public void refreshTags(Set<String> allTags) {
        String current = (String) tagCombo.getSelectedItem();
        tagCombo.removeAllItems();
        tagCombo.addItem(MpSqlVisualizerBundle.message("history.filter.all"));
        List<String> sorted = new ArrayList<>(allTags);
        Collections.sort(sorted);
        for (String tag : sorted) {
            tagCombo.addItem(tag);
        }
        if (current != null && allTags.contains(current)) {
            tagCombo.setSelectedItem(current);
        }
    }
}
