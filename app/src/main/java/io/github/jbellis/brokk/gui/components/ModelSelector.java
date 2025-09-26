package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsGlobalPanel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public class ModelSelector {

    private final Chrome chrome;
    private final JComboBox<Object> combo;
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private volatile boolean dialogOpen = false;
    private @Nullable Service.FavoriteModel lastSelected;
    private final List<Consumer<Service.ModelConfig>> selectionListeners = new ArrayList<>();

    public ModelSelector(Chrome chrome) {
        this.chrome = chrome;
        this.combo = new JComboBox<>();
        this.combo.setToolTipText("Select a quick model or configure custom models");
        this.combo.setEditable(false);
        this.combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Object display = (value instanceof Service.FavoriteModel fm) ? fm.alias() : value;
                return super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus);
            }
        });
        this.combo.addActionListener(e -> {
            if (updating.get()) {
                return;
            }
            Object sel = combo.getSelectedItem();
            if (sel instanceof Service.FavoriteModel fm) {
                lastSelected = fm;
                // notify listeners of selection changes
                for (var listener : selectionListeners) {
                    listener.accept(fm.config());
                }
            } else if (sel instanceof String s && "Manage...".equals(s)) {
                openCustomDialog();
            }
        });

        refresh();
    }

    public JComponent getComponent() {
        return combo;
    }

    public void addSelectionListener(Consumer<Service.ModelConfig> listener) {
        selectionListeners.add(listener);
    }

    /**
     * Attempt to programmatically select the given model configuration.
     *
     * @param desired the configuration to select
     * @return true if the configuration was found and selected, false otherwise
     */
    public boolean selectConfig(Service.ModelConfig desired) {
        if (dialogOpen) {
            return false; // don't interfere with an open dialog
        }

        refresh(); // make sure the combo is up to date

        for (int i = 0; i < combo.getItemCount(); i++) {
            Object item = combo.getItemAt(i);
            if (item instanceof Service.FavoriteModel fm && fm.config().equals(desired)) {
                updating.set(true);
                try {
                    combo.setSelectedIndex(i);
                    lastSelected = fm;
                } finally {
                    updating.set(false);
                }
                return true;
            }
        }

        return false; // not found
    }

    public Service.ModelConfig getModel() {
        if (dialogOpen) {
            throw new IllegalStateException("Model configuration in progress");
        }
        Object sel = combo.getSelectedItem();
        if (sel instanceof Service.FavoriteModel fm) {
            return fm.config();
        }
        throw new IllegalStateException("No favorite model selected");
    }

    private void refresh() {
        Runnable task = () -> {
            updating.set(true);
            try {
                combo.removeAllItems();
                var favorites = MainProject.loadFavoriteModels();
                if (favorites.isEmpty()) {
                    combo.addItem("(No favorite models)");
                } else {
                    favorites.forEach(combo::addItem);
                }
                combo.addItem("Manage...");
                if (lastSelected != null) {
                    // Try to re-select the last favorite if present
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        Object it = combo.getItemAt(i);
                        if (it instanceof Service.FavoriteModel fm && fm.equals(lastSelected)) {
                            combo.setSelectedIndex(i);
                            return;
                        }
                    }
                }
                if (combo.getItemCount() > 0) {
                    combo.setSelectedIndex(0);
                }
            } finally {
                updating.set(false);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    private void openCustomDialog() {
        dialogOpen = true;
        var service = chrome.getContextManager().getService();
        @Nullable Service.FavoriteModel priorSelection = lastSelected;

        try {
            // Preserve previous "no models available" warning behavior
            String[] availableModelNames =
                    service.getAvailableModels().keySet().stream().sorted().toArray(String[]::new);
            if (availableModelNames.length == 0) {
                JOptionPane.showMessageDialog(
                        chrome.getFrame(),
                        "No models available to configure",
                        "No Models",
                        JOptionPane.WARNING_MESSAGE);
                revertSelection(priorSelection);
                return;
            }

            // Snapshot favorites before opening settings
            var before = MainProject.loadFavoriteModels();

            // Open the full Settings dialog to the Favorite Models tab
            SettingsDialog.showSettingsDialog(chrome, SettingsGlobalPanel.MODELS_TAB_TITLE);

            // After the modal dialog closes, reload favorites and detect any new favorite
            var after = MainProject.loadFavoriteModels();
            var maybeNew = after.stream()
                    .filter(a -> before.stream().noneMatch(b -> b.equals(a)))
                    .findFirst();

            if (maybeNew.isPresent()) {
                lastSelected = maybeNew.get();
                refresh();
                // Ensure combo box selection happens on EDT after refresh populates it
                SwingUtilities.invokeLater(() -> {
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        Object item = combo.getItemAt(i);
                        if (item instanceof Service.FavoriteModel fm && fm.equals(lastSelected)) {
                            combo.setSelectedIndex(i);
                            return;
                        }
                    }
                });
            } else {
                // No new favorite created: restore prior selection (or default)
                revertSelection(priorSelection);
            }
        } finally {
            dialogOpen = false;
        }
    }

    private void revertSelection(@Nullable Service.FavoriteModel priorSelection) {
        // Reset back to last valid selection on cancel
        refresh();
        if (priorSelection != null) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                Object it = combo.getItemAt(i);
                if (it instanceof Service.FavoriteModel fm && fm.equals(priorSelection)) {
                    combo.setSelectedIndex(i);
                    return;
                }
            }
        }
        if (combo.getItemCount() > 0) {
            combo.setSelectedIndex(0);
        }
    }
}
