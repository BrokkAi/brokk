package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.gui.Chrome;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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
            } else if (sel instanceof String s && "Custom...".equals(s)) {
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
        Objects.requireNonNull(desired, "desired config must not be null");
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
                combo.addItem("Custom...");
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

            var modelCombo = new JComboBox<>(availableModelNames);
            var reasoningCombo = new JComboBox<>(Service.ReasoningLevel.values());
            var processingCombo = new JComboBox<>(Service.ProcessingTier.values());

            String initialModel = (String) modelCombo.getSelectedItem();
            boolean supportsReasoning = initialModel != null && service.supportsReasoningEffort(initialModel);
            boolean supportsProcessing = initialModel != null && service.supportsProcessingTier(initialModel);
            reasoningCombo.setEnabled(supportsReasoning);
            processingCombo.setEnabled(supportsProcessing);

            modelCombo.addActionListener(e -> {
                var selModel = (String) modelCombo.getSelectedItem();
                if (selModel == null) return;
                reasoningCombo.setEnabled(service.supportsReasoningEffort(selModel));
                processingCombo.setEnabled(service.supportsProcessingTier(selModel));
            });

            var panel = new JPanel(new GridBagLayout());
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 5, 2, 5);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.gridx = 0;
            gbc.gridy = 0;
            panel.add(new JLabel("Model:"), gbc);
            gbc.gridx = 1;
            panel.add(modelCombo, gbc);
            gbc.gridx = 0;
            gbc.gridy++;
            panel.add(new JLabel("Reasoning:"), gbc);
            gbc.gridx = 1;
            panel.add(reasoningCombo, gbc);
            gbc.gridx = 0;
            gbc.gridy++;
            panel.add(new JLabel("Processing Tier:"), gbc);
            gbc.gridx = 1;
            panel.add(processingCombo, gbc);

            int result = JOptionPane.showConfirmDialog(
                    chrome.getFrame(),
                    panel,
                    "Configure Custom Model",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                revertSelection(priorSelection);
                return;
            }

            var selectedModel = (String) modelCombo.getSelectedItem();
            var selectedReasoning = (Service.ReasoningLevel) reasoningCombo.getSelectedItem();
            var selectedTier = (Service.ProcessingTier) processingCombo.getSelectedItem();

            if (!reasoningCombo.isEnabled()) {
                selectedReasoning = Service.ReasoningLevel.DEFAULT;
            }
            if (!processingCombo.isEnabled()) {
                selectedTier = Service.ProcessingTier.DEFAULT;
            }
            if (selectedModel == null) {
                revertSelection(priorSelection);
                return;
            }

            if (selectedReasoning == null) selectedReasoning = Service.ReasoningLevel.DEFAULT;
            if (selectedTier == null) selectedTier = Service.ProcessingTier.DEFAULT;

            String alias = selectedModel;
            if (selectedReasoning != Service.ReasoningLevel.DEFAULT) {
                alias = alias + " " + selectedReasoning.name().toLowerCase(Locale.ROOT);
            }
            if (selectedTier != Service.ProcessingTier.DEFAULT) {
                alias = alias + " [" + selectedTier.name().toLowerCase(Locale.ROOT) + "]";
            }

            var favorites = new ArrayList<>(MainProject.loadFavoriteModels());
            var newFav = new Service.FavoriteModel(
                    alias, new Service.ModelConfig(selectedModel, selectedReasoning, selectedTier));
            favorites.add(newFav);
            MainProject.saveFavoriteModels(favorites);

            // refresh and select the newly created favorite by alias
            lastSelected = newFav;
            refresh();

            final String aliasFinal = alias;
            SwingUtilities.invokeLater(() -> {
                for (int i = 0; i < combo.getItemCount(); i++) {
                    Object item = combo.getItemAt(i);
                    if (item instanceof Service.FavoriteModel fm && fm.alias().equals(aliasFinal)) {
                        combo.setSelectedIndex(i);
                        return;
                    }
                }
            });
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
