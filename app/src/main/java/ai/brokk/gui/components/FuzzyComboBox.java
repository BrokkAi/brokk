package ai.brokk.gui.components;

import ai.brokk.FuzzyMatcher;
import ai.brokk.gui.mop.ThemeColors;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jetbrains.annotations.Nullable;

/**
 * A combo box replacement that supports fuzzy search with IntelliJ-style highlighting.
 * Shows a search field and a scrollable list of items that are filtered as the user types.
 *
 * @param <T> The type of items in the combo box
 */
public class FuzzyComboBox<T> extends JPanel {
    private final List<T> allItems;
    private final Function<T, String> displayMapper;
    private final JTextField searchField;
    private final JList<T> list;
    private final DefaultListModel<T> model;
    private final JScrollPane scrollPane;
    @Nullable private FuzzyMatcher currentMatcher;

    /**
     * Creates a new FuzzyComboBox with the given items.
     *
     * @param items The items to display
     * @param displayMapper Function to convert items to display strings
     */
    public FuzzyComboBox(List<T> items, Function<T, String> displayMapper) {
        super(new BorderLayout(0, 2));
        this.allItems = new ArrayList<>(items);
        this.displayMapper = displayMapper;

        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search...");
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(2, 2, 2, 2), searchField.getBorder()));

        model = new DefaultListModel<>();
        for (T item : allItems) {
            model.addElement(item);
        }

        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(Math.min(8, items.size()));

        list.setCellRenderer(new DefaultListCellRenderer() {
            @SuppressWarnings("unchecked")
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String text = displayMapper.apply((T) value);
                if (currentMatcher != null) {
                    var fragments = currentMatcher.getMatchingFragments(text);
                    if (fragments != null && !fragments.isEmpty()) {
                        setText(highlightMatches(text, fragments));
                    }
                } else {
                    setText(text);
                }
                return this;
            }
        });

        scrollPane = new JScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(200, 150));

        add(searchField, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        setupListeners();

        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    /**
     * Creates a new FuzzyComboBox for String items.
     *
     * @param items The string items to display
     * @return A FuzzyComboBox for strings
     */
    public static FuzzyComboBox<String> forStrings(List<String> items) {
        return new FuzzyComboBox<>(items, Function.identity());
    }

    private void setupListeners() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterItems();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterItems();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterItems();
            }
        });

        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    list.requestFocusInWindow();
                    if (list.getSelectedIndex() < 0 && !model.isEmpty()) {
                        list.setSelectedIndex(0);
                    }
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    list.requestFocusInWindow();
                    if (!model.isEmpty()) {
                        int idx = list.getSelectedIndex();
                        if (idx <= 0) {
                            list.setSelectedIndex(model.size() - 1);
                        }
                    }
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    if (!searchField.getText().isEmpty()) {
                        searchField.setText("");
                        e.consume();
                    }
                }
            }
        });

        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    if (!searchField.getText().isEmpty()) {
                        searchField.setText("");
                        searchField.requestFocusInWindow();
                        e.consume();
                    }
                } else if (Character.isLetterOrDigit(e.getKeyChar())) {
                    searchField.requestFocusInWindow();
                    searchField.dispatchEvent(e);
                }
            }
        });

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int idx = list.locationToIndex(e.getPoint());
                    if (idx >= 0) {
                        list.setSelectedIndex(idx);
                    }
                }
            }
        });
    }

    private void filterItems() {
        String query = searchField.getText().trim();
        T previousSelection = list.getSelectedValue();
        model.clear();

        if (query.isEmpty()) {
            currentMatcher = null;
            for (T item : allItems) {
                model.addElement(item);
            }
        } else {
            var matcher = new FuzzyMatcher(query);
            currentMatcher = matcher;
            var matches = new ArrayList<T>();
            for (T item : allItems) {
                if (matcher.matches(displayMapper.apply(item))) {
                    matches.add(item);
                }
            }
            matches.sort(Comparator.comparingInt(item -> matcher.score(displayMapper.apply(item))));
            for (T item : matches) {
                model.addElement(item);
            }
        }

        if (!model.isEmpty()) {
            int prevIdx = previousSelection != null ? model.indexOf(previousSelection) : -1;
            list.setSelectedIndex(prevIdx >= 0 ? prevIdx : 0);
        }
    }

    private String highlightMatches(String text, List<FuzzyMatcher.TextRange> fragments) {
        fragments.sort(Comparator.comparingInt(FuzzyMatcher.TextRange::getStartOffset));
        Color highlightColor = ThemeColors.getColor(ThemeColors.SEARCH_HIGHLIGHT);
        String hexColor = String.format(
                "#%02x%02x%02x", highlightColor.getRed(), highlightColor.getGreen(), highlightColor.getBlue());
        Color fg = ThemeColors.getColor(ThemeColors.SEARCH_HIGHLIGHT_TEXT);
        String fgHex = String.format("#%02x%02x%02x", fg.getRed(), fg.getGreen(), fg.getBlue());
        var result = new StringBuilder("<html>");
        int lastEnd = 0;
        for (var range : fragments) {
            if (range.getStartOffset() > lastEnd) {
                result.append(escapeHtml(text.substring(lastEnd, range.getStartOffset())));
            }
            result.append("<span style='background-color:")
                    .append(hexColor)
                    .append(";color:")
                    .append(fgHex)
                    .append("'>")
                    .append(escapeHtml(text.substring(range.getStartOffset(), range.getEndOffset())))
                    .append("</span>");
            lastEnd = range.getEndOffset();
        }
        if (lastEnd < text.length()) {
            result.append(escapeHtml(text.substring(lastEnd)));
        }
        return result.append("</html>").toString();
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Returns the currently selected item, or null if nothing is selected.
     */
    @Nullable
    public T getSelectedItem() {
        return list.getSelectedValue();
    }

    /**
     * Sets the selected item. If the item is not in the list, selection is cleared.
     */
    public void setSelectedItem(@Nullable T item) {
        if (item == null) {
            list.clearSelection();
        } else {
            list.setSelectedValue(item, true);
        }
    }

    /**
     * Returns the index of the selected item, or -1 if nothing is selected.
     */
    public int getSelectedIndex() {
        return list.getSelectedIndex();
    }

    /**
     * Sets the selected index.
     */
    public void setSelectedIndex(int index) {
        if (index >= 0 && index < model.size()) {
            list.setSelectedIndex(index);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        searchField.setEnabled(enabled);
        list.setEnabled(enabled);
        scrollPane.setEnabled(enabled);
    }

    /**
     * Requests focus on the search field.
     */
    @Override
    public void requestFocus() {
        searchField.requestFocusInWindow();
    }

    /**
     * Returns the underlying JList for additional customization.
     */
    public JList<T> getList() {
        return list;
    }

    /**
     * Returns the search field for additional customization.
     */
    public JTextField getSearchField() {
        return searchField;
    }
}
