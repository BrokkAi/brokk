package ai.brokk.gui.components;

import ai.brokk.MainProject;
import ai.brokk.Service;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dialogs.SettingsDialog;
import ai.brokk.gui.dialogs.SettingsGlobalPanel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public class ModelSelector {

    private final Chrome chrome;
    private final SplitButton splitButton;
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private volatile boolean dialogOpen = false;
    private @Nullable Service.FavoriteModel lastSelected;
    private final List<Consumer<Service.ModelConfig>> selectionListeners = new ArrayList<>();

    public ModelSelector(Chrome chrome) {
        this.chrome = chrome;
        this.splitButton = new SplitButton("No Model", true);
        this.splitButton.setToolTipText("Select a quick model or configure custom models");

        Supplier<JPopupMenu> menuSupplier = () -> {
            var menu = new JPopupMenu();
            var favorites = MainProject.loadFavoriteModels();

            if (favorites.isEmpty()) {
                JMenuItem noFavorites = new JMenuItem("(No favorite models)");
                noFavorites.setEnabled(false);
                menu.add(noFavorites);
            } else {
                for (var fm : favorites) {
                    JMenuItem item = new JMenuItem(fm.alias());
                    item.addActionListener(e -> {
                        if (!updating.get()) {
                            selectFavorite(fm);
                        }
                    });
                    menu.add(item);
                }
            }

            menu.add(new JSeparator());
            JMenuItem manage = new JMenuItem("Manage...");
            manage.addActionListener(e -> openCustomDialog());
            menu.add(manage);

            return menu;
        };

        splitButton.setMenuSupplier(menuSupplier);
        splitButton.addActionListener(e -> splitButton.showPopupMenuInternal());

        refresh();
    }

    private void selectFavorite(Service.FavoriteModel fm) {
        lastSelected = fm;
        splitButton.setText(fm.alias());
        for (var listener : selectionListeners) {
            listener.accept(fm.config());
        }
    }

    public JComponent getComponent() {
        return splitButton;
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

        refresh(); // make sure the button is up to date

        var favorites = MainProject.loadFavoriteModels();
        for (var fm : favorites) {
            if (fm.config().equals(desired)) {
                updating.set(true);
                try {
                    lastSelected = fm;
                    splitButton.setText(fm.alias());
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
        if (lastSelected == null) {
            throw new IllegalStateException("No model selected");
        }
        return lastSelected.config();
    }

    private void refresh() {
        Runnable task = () -> {
            updating.set(true);
            try {
                var favorites = MainProject.loadFavoriteModels();
                if (lastSelected != null) {
                    // Try to re-select the last favorite if present
                    for (var fm : favorites) {
                        if (fm.equals(lastSelected)) {
                            splitButton.setText(fm.alias());
                            return;
                        }
                    }
                }
                // No last selected or it's gone; select first available or show placeholder
                if (!favorites.isEmpty()) {
                    var first = favorites.getFirst();
                    lastSelected = first;
                    splitButton.setText(first.alias());
                } else {
                    lastSelected = null;
                    splitButton.setText("(No favorite models)");
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
                splitButton.setText(lastSelected.alias());
                // Notify listeners of the new selection
                for (var listener : selectionListeners) {
                    listener.accept(lastSelected.config());
                }
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
        if (priorSelection != null) {
            lastSelected = priorSelection;
            splitButton.setText(priorSelection.alias());
        } else {
            refresh();
        }
    }
}
