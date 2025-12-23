package ai.brokk.gui.components;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private List<T> allItems;
    private final Function<T, String> displayMapper;
    private final MaterialButton button;
    private @Nullable T selectedItem;
    private @Nullable Consumer<@Nullable T> selectionChangeListener;

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
        setSelectedItemInternal(item, true);
    }

    /**
     * Internal method to set selected item with optional listener firing.
     */
    private void setSelectedItemInternal(@Nullable T item, boolean fireListener) {
        T oldItem = this.selectedItem;
        this.selectedItem = item;
        button.setText(item != null ? displayMapper.apply(item) : "");

        if (fireListener && selectionChangeListener != null && !Objects.equals(oldItem, item)) {
            selectionChangeListener.accept(item);
        }
    }

    /**
     * Sets a listener that is called when the selected item changes.
     */
    public void setSelectionChangeListener(@Nullable Consumer<@Nullable T> listener) {
        this.selectionChangeListener = listener;
    }

    /**
     * Updates the items in this combo box.
     * <p>
     * If the currently selected item exists in the new items list, the selection
     * is preserved. Otherwise, the selection is cleared. If the new items list is
     * non-empty and no item is selected, the first item is automatically selected.
     * <p>
     * This method must be called on the EDT.
     *
     * @param items The new items to display
     */
    public void setItems(List<T> items) {
        assert SwingUtilities.isEventDispatchThread() : "setItems must be called on EDT";

        T oldSelection = this.selectedItem;
        this.allItems = new ArrayList<>(items);

        // Preserve selection if valid, otherwise clear and potentially auto-select
        if (selectedItem != null && !allItems.contains(selectedItem)) {
            setSelectedItemInternal(null, false);
        }

        if (selectedItem == null && !allItems.isEmpty()) {
            setSelectedItemInternal(allItems.getFirst(), false);
        }

        // Fire listener only if selection actually changed
        T newSelection = this.selectedItem;
        if (selectionChangeListener != null && !Objects.equals(oldSelection, newSelection)) {
            selectionChangeListener.accept(newSelection);
        }
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
