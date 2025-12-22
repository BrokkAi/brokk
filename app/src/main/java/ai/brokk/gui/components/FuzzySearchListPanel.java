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
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jetbrains.annotations.Nullable;

/**
 * A reusable panel containing a search field and filtered JList with fuzzy matching
 * and IntelliJ-style highlighting. Used by FuzzyComboBox and BranchSelectorButton.
 *
 * @param <T> The type of items in the list
 */
public class FuzzySearchListPanel<T> {
    private static final int SEARCH_DEBOUNCE_MS = 200;

    private final List<T> allItems;
    private final Function<T, String> displayMapper;
    private final JTextField searchField;
    private final JList<T> list;
    private final DefaultListModel<T> model;
    private final Timer searchDebounceTimer;
    private @Nullable FuzzyMatcher currentMatcher;
    private @Nullable Consumer<T> selectionListener;

    /**
     * Creates a new FuzzySearchListPanel with the given items.
     *
     * @param items The items to display
     * @param displayMapper Function to convert items to display strings
     */
    public FuzzySearchListPanel(List<T> items, Function<T, String> displayMapper) {
        this.allItems = new ArrayList<>(items);
        this.displayMapper = displayMapper;

        // Debounce timer for search filtering
        searchDebounceTimer = new Timer(SEARCH_DEBOUNCE_MS, e -> filterItems());
        searchDebounceTimer.setRepeats(false);

        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search...");
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 4, 4, 4), searchField.getBorder()));

        model = new DefaultListModel<>();
        for (T item : allItems) {
            model.addElement(item);
        }

        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(-1);
        list.setFocusable(true);

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
                        var bg = ThemeColors.getColor(ThemeColors.SEARCH_HIGHLIGHT);
                        var fg = ThemeColors.getColor(ThemeColors.SEARCH_HIGHLIGHT_TEXT);
                        setText(FuzzyMatcher.toHighlightedHtml(text, fragments, bg, fg));
                    }
                } else {
                    setText(text);
                }
                return this;
            }
        });

        setupListeners();

        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    /**
     * Creates a new FuzzySearchListPanel for String items.
     */
    public static FuzzySearchListPanel<String> forStrings(List<String> items) {
        return new FuzzySearchListPanel<>(items, Function.identity());
    }

    private void setupListeners() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleFilter();
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
                        fireSelection(model.getElementAt(idx));
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

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    fireSelection(model.getElementAt(idx));
                }
            }
        });

        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    int idx = list.getSelectedIndex();
                    if (idx >= 0) {
                        fireSelection(model.getElementAt(idx));
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
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
    }

    private void scheduleFilter() {
        searchDebounceTimer.restart();
    }

    private void filterItems() {
        String query = searchField.getText().trim();
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
            list.setSelectedIndex(0);
        }
    }

    private void fireSelection(T item) {
        if (selectionListener != null) {
            selectionListener.accept(item);
        }
    }

    /**
     * Sets a listener that is called when an item is selected (via Enter or click).
     */
    public void setSelectionListener(@Nullable Consumer<T> listener) {
        this.selectionListener = listener;
    }

    /**
     * Returns the search field component.
     */
    public JTextField getSearchField() {
        return searchField;
    }

    /**
     * Returns the list component.
     */
    public JList<T> getList() {
        return list;
    }

    /**
     * Returns the list model.
     */
    public DefaultListModel<T> getModel() {
        return model;
    }

    /**
     * Returns the currently selected item, or null if nothing is selected.
     */
    @Nullable
    public T getSelectedItem() {
        return list.getSelectedValue();
    }

    /**
     * Sets the selected item.
     */
    public void setSelectedItem(@Nullable T item) {
        if (item != null) {
            list.setSelectedValue(item, true);
        } else {
            list.clearSelection();
        }
    }

    /**
     * Clears the search field and resets the filter.
     */
    public void clearSearch() {
        searchField.setText("");
    }

    /**
     * Requests focus on the search field.
     */
    public void focusSearchField() {
        searchField.requestFocusInWindow();
    }
}
