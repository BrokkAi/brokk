package ai.brokk.gui.components;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;
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
    private @Nullable Consumer<T> selectionChangeListener;

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
        var panel = new FuzzySearchListPanel<>(allItems, displayMapper);

        panel.setSelectionListener(item -> {
            setSelectedItem(item);
            menu.setVisible(false);
        });

        menu.add(panel.getSearchField());

        var scrollPane = new JScrollPane(panel.getList());
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        int maxHeight = Math.min(200, allItems.size() * 20 + 20);
        int prefWidth = Math.max(button.getWidth(), 200);
        scrollPane.setPreferredSize(new Dimension(prefWidth, maxHeight));

        menu.add(scrollPane);

        if (selectedItem != null) {
            panel.setSelectedItem(selectedItem);
        }

        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(panel::focusSearchField);
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        menu.show(button, 0, button.getHeight());
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
        if (selectionChangeListener != null && item != null) {
            selectionChangeListener.accept(item);
        }
    }

    /**
     * Sets a listener that is called when the selected item changes.
     */
    public void setSelectionChangeListener(@Nullable Consumer<T> listener) {
        this.selectionChangeListener = listener;
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
