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
 * Shows a button with the current selection; clicking opens a popup with search field
 * and filtered list, similar to BranchSelectorButton.
 *
 * @param <T> The type of items in the combo box
 */
public class FuzzyComboBox<T> extends JPanel {
    private final List<T> allItems;
    private final Function<T, String> displayMapper;
    private final MaterialButton button;
    private @Nullable T selectedItem;

    /**
     * Creates a new FuzzyComboBox with the given items.
     *
     * @param items The items to display
     * @param displayMapper Function to convert items to display strings
     */
    public FuzzyComboBox(List<T> items, Function<T, String> displayMapper) {
        super(new BorderLayout());
        this.allItems = new ArrayList<>(items);
        this.displayMapper = displayMapper;

        button = new MaterialButton("");
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.addActionListener(e -> showPopup());

        add(button, BorderLayout.CENTER);

        if (!allItems.isEmpty()) {
            setSelectedItem(allItems.getFirst());
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

    private void showPopup() {
        var menu = new JPopupMenu();

        var model = new DefaultListModel<T>();
        for (T item : allItems) {
            model.addElement(item);
        }

        var list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(-1);
        list.setFocusable(true);

        final FuzzyMatcher[] currentMatcher = {null};

        var searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search...");
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 4, 4, 4), searchField.getBorder()));
        menu.add(searchField);

        list.setCellRenderer(new DefaultListCellRenderer() {
            @SuppressWarnings("unchecked")
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String text = displayMapper.apply((T) value);
                var matcher = currentMatcher[0];
                if (matcher != null) {
                    var fragments = matcher.getMatchingFragments(text);
                    if (fragments != null && !fragments.isEmpty()) {
                        setText(highlightMatches(text, fragments));
                    }
                } else {
                    setText(text);
                }
                return this;
            }
        });

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

            private void filterItems() {
                String query = searchField.getText().trim();
                model.clear();
                if (query.isEmpty()) {
                    currentMatcher[0] = null;
                    for (T item : allItems) {
                        model.addElement(item);
                    }
                } else {
                    var matcher = new FuzzyMatcher(query);
                    currentMatcher[0] = matcher;
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
                    list.setSelectedIndex(0);
                }
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
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (!model.isEmpty()) {
                        int idx = list.getSelectedIndex();
                        if (idx < 0) idx = 0;
                        selectItem(model.getElementAt(idx), menu);
                    }
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    if (!searchField.getText().isEmpty()) {
                        searchField.setText("");
                        e.consume();
                    } else {
                        menu.setVisible(false);
                    }
                }
            }
        });

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    selectItem(model.getElementAt(idx), menu);
                }
            }
        });

        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    int idx = list.getSelectedIndex();
                    if (idx >= 0) {
                        selectItem(model.getElementAt(idx), menu);
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    if (!searchField.getText().isEmpty()) {
                        searchField.setText("");
                        searchField.requestFocusInWindow();
                        e.consume();
                    } else {
                        menu.setVisible(false);
                    }
                }
            }
        });

        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(searchField::requestFocusInWindow);
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        var scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        int maxHeight = Math.min(200, allItems.size() * 20 + 20);
        int prefWidth = Math.max(button.getWidth(), 200);
        scrollPane.setPreferredSize(new Dimension(prefWidth, maxHeight));

        menu.add(scrollPane);

        if (selectedItem != null) {
            list.setSelectedValue(selectedItem, true);
        } else if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }

        menu.show(button, 0, button.getHeight());
    }

    private void selectItem(T item, JPopupMenu menu) {
        setSelectedItem(item);
        menu.setVisible(false);
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
        return selectedItem;
    }

    /**
     * Sets the selected item and updates the button text.
     */
    public void setSelectedItem(@Nullable T item) {
        this.selectedItem = item;
        button.setText(item != null ? displayMapper.apply(item) : "");
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        button.setEnabled(enabled);
    }

    /**
     * Returns the button for additional customization.
     */
    public MaterialButton getButton() {
        return button;
    }
}
