package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.FileSelectionPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.Messages;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class UpgradeAgentDialog extends JDialog {
    private final Chrome chrome;
    private JTextArea instructionsArea;
    private JComboBox<Service.FavoriteModel> modelComboBox;
    private JLabel tokenWarningLabel;
    private JCheckBox includeWorkspaceCheckbox;
    private JRadioButton entireProjectScopeRadioButton;
    private JRadioButton selectFilesScopeRadioButton;
    private ButtonGroup scopeButtonGroup;
    private JPanel scopeCardsPanel;
    private CardLayout scopeCardLayout;
    private JComboBox<String> languageComboBox;
    private JComboBox<String> relatedClassesCombo;
    private JTextField perFileCommandTextField;
    private static final String ALL_LANGUAGES_OPTION = "All Languages";
    private static final int TOKEN_SAFETY_MARGIN = 32768;
    private FileSelectionPanel fileSelectionPanel;
    private JTable selectedFilesTable;
    private javax.swing.table.DefaultTableModel tableModel;

    public UpgradeAgentDialog(Frame owner, Chrome chrome) {
        super(owner, "Upgrade Agent", true);
        this.chrome = chrome;
        initComponents();
        setupKeyBindings();
        modelComboBox.addActionListener(e -> updateTokenWarningLabel());
        includeWorkspaceCheckbox.addActionListener(e -> updateTokenWarningLabel());
        updateTokenWarningLabel();
        pack();
        setLocationRelativeTo(owner);
    }

    private void initComponents() {
        setLayout(new BorderLayout(10, 10));

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new GridBagLayout());
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Explanation Label
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JLabel explanationLabel = new JLabel("The instructions will be applied independently to every file in the project.");
        contentPanel.add(explanationLabel, gbc);

        // Instructions Label
        gbc.gridy++;
        gbc.gridwidth = 1;
        contentPanel.add(new JLabel("Instructions:"), gbc);

        // Instructions TextArea
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        instructionsArea = new JTextArea(4, 50);
        instructionsArea.setLineWrap(true);
        instructionsArea.setWrapStyleWord(true);
        JScrollPane instructionsScrollPane = new JScrollPane(instructionsArea);
        contentPanel.add(instructionsScrollPane, gbc);

        // Model Row
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        contentPanel.add(new JLabel("Model:"), gbc);
        
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        List<Service.FavoriteModel> favoriteModels = MainProject.loadFavoriteModels();
        modelComboBox = new JComboBox<>(favoriteModels.toArray(new Service.FavoriteModel[0]));
        modelComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Service.FavoriteModel fav) {
                    setText(fav.alias());
                }
                return this;
            }
        });
        if (!favoriteModels.isEmpty()) {
            modelComboBox.setSelectedIndex(0);
        }
        contentPanel.add(modelComboBox, gbc);

        // Token Warning Label
        gbc.gridy++;
        gbc.gridx = 1;
        tokenWarningLabel = new JLabel();
        tokenWarningLabel.setForeground(Color.RED);
        tokenWarningLabel.setVisible(false);
        contentPanel.add(tokenWarningLabel, gbc);

        // Scope Row
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        // Scope Cards Panel
        // This is now populated before being placed into the scope selection panel below
        scopeCardLayout = new CardLayout();
        scopeCardsPanel = new JPanel(scopeCardLayout);

        // "Entire Project" Card
        JPanel entireProjectPanel = new JPanel(new GridBagLayout());
        GridBagConstraints entireGbc = new GridBagConstraints();
        entireGbc.insets = new Insets(0, 0, 5, 5);
        entireGbc.anchor = GridBagConstraints.NORTHWEST;
        entireGbc.gridx = 0;
        entireGbc.gridy = 0;
        entireProjectPanel.add(new JLabel("Restrict to Language:"), entireGbc);

        entireGbc.gridx = 1;
        entireGbc.weightx = 1.0;
        entireGbc.fill = GridBagConstraints.HORIZONTAL;
        languageComboBox = new JComboBox<>();
        languageComboBox.addItem(ALL_LANGUAGES_OPTION);
        chrome.getProject().getAnalyzerLanguages().stream()
              .map(Language::toString)
              .sorted()
              .forEach(languageComboBox::addItem);
        languageComboBox.setSelectedItem(ALL_LANGUAGES_OPTION);
        entireProjectPanel.add(languageComboBox, entireGbc);

        // Spacer to push content to the top
        var gbcSpacer = new GridBagConstraints();
        gbcSpacer.gridy = 1;
        gbcSpacer.gridwidth = 2;
        gbcSpacer.weighty = 1.0;
        gbcSpacer.fill = GridBagConstraints.VERTICAL;
        entireProjectPanel.add(new JPanel(), gbcSpacer);

        scopeCardsPanel.add(entireProjectPanel, "ENTIRE");

        // "Select Files" Card
        var fspConfig = new FileSelectionPanel.Config(
                chrome.getProject(),
                false, // no external files
                File::isFile,
                CompletableFuture.completedFuture(
                        chrome.getProject().getRepo().getTrackedFiles().stream()
                              .map(ProjectFile::absPath).map(Path::toAbsolutePath).toList()),
                true, // multi-select
                __ -> {},
                true, // include project files in autocomplete
                "Ctrl-Enter to add files to the list below."
        );
        fileSelectionPanel = new FileSelectionPanel(fspConfig);
        var inputComponent = fileSelectionPanel.getFileInputComponent();
        var addFilesAction = new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                addSelectedFilesToTable();
            }
        };
        var ctrlEnterStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
        inputComponent.getInputMap(JComponent.WHEN_FOCUSED).put(ctrlEnterStroke, "addFiles");
        inputComponent.getActionMap().put("addFiles", addFilesAction);

        JPanel selectFilesCardPanel = new JPanel(new BorderLayout(0, 5));
        selectFilesCardPanel.add(fileSelectionPanel, BorderLayout.NORTH);

        tableModel = new javax.swing.table.DefaultTableModel(new String[]{"File"}, 0);
        selectedFilesTable = new JTable(tableModel);

        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> removeSelectedFilesFromTable());
        popupMenu.add(removeItem);
        selectedFilesTable.setComponentPopupMenu(popupMenu);

        selectedFilesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }
            private void showPopup(MouseEvent e) {
                int row = selectedFilesTable.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    // if the right-clicked row is not in the current selection, update the selection
                    if (!selectedFilesTable.isRowSelected(row)) {
                        selectedFilesTable.setRowSelectionInterval(row, row);
                    }
                }
                popupMenu.show(e.getComponent(), e.getX(), e.getY());
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(selectedFilesTable);
        tableScrollPane.setPreferredSize(new Dimension(450, 120)); // Smaller height for the table
        selectFilesCardPanel.add(tableScrollPane, BorderLayout.CENTER);

        JPanel removeButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton removeButton = new JButton("Remove Selected");
        removeButton.addActionListener(e -> removeSelectedFilesFromTable());
        removeButtonPanel.add(removeButton);
        selectFilesCardPanel.add(removeButtonPanel, BorderLayout.SOUTH);

        scopeCardsPanel.add(selectFilesCardPanel, "SELECT");

        // Per-file command Row
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        contentPanel.add(new JLabel("Per-file command:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        perFileCommandTextField = new JTextField();

        JLabel perFileCommandExplanation = new JLabel("<html>Command to run for each file (if specified). Use <code>{{filepath}}</code> for the file path.</html>");
        JPanel explanationPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        explanationPanel.add(perFileCommandExplanation);

        JPanel combinedPerFilePanel = new JPanel(new BorderLayout(0,3));
        combinedPerFilePanel.add(perFileCommandTextField, BorderLayout.NORTH);
        combinedPerFilePanel.add(explanationPanel, BorderLayout.CENTER);

        contentPanel.add(combinedPerFilePanel, gbc);

        // Related Files Row
        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        contentPanel.add(new JLabel("Include related files:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        relatedClassesCombo = new JComboBox<>(new String[]{"0", "5", "10", "20"});
        relatedClassesCombo.setEditable(true);
        relatedClassesCombo.setSelectedItem("0");
        contentPanel.add(relatedClassesCombo, gbc);

        // Explanation for Related Files, placed under the combobox
        gbc.gridy++;
        gbc.gridx = 1; // Align under the combobox
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel relatedFilesExplanation = new JLabel("Includes summaries of the most-closely-related files with each target file");
        contentPanel.add(relatedFilesExplanation, gbc);

        // Include Workspace Row
        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        contentPanel.add(new JLabel("Include Workspace:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        includeWorkspaceCheckbox = new JCheckBox("Include the current Workspace contents with each file");
        contentPanel.add(includeWorkspaceCheckbox, gbc);

        // Scope Panel at the bottom
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weighty = 0.1;
        gbc.fill = GridBagConstraints.BOTH;

        JPanel scopePanel = new JPanel(new BorderLayout(0, 5));
        scopePanel.setBorder(BorderFactory.createTitledBorder("Scope"));

        entireProjectScopeRadioButton = new JRadioButton("Entire Project");
        selectFilesScopeRadioButton = new JRadioButton("Select Files");
        selectFilesScopeRadioButton.setSelected(true);

        scopeButtonGroup = new ButtonGroup();
        scopeButtonGroup.add(entireProjectScopeRadioButton);
        scopeButtonGroup.add(selectFilesScopeRadioButton);

        JPanel scopeRadioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        scopeRadioPanel.add(entireProjectScopeRadioButton);
        scopeRadioPanel.add(selectFilesScopeRadioButton);

        scopePanel.add(scopeRadioPanel, BorderLayout.NORTH);
        scopePanel.add(scopeCardsPanel, BorderLayout.CENTER);

        contentPanel.add(scopePanel, gbc);

        // Listeners to switch cards
        java.awt.event.ActionListener scopeListener = e -> {
            String command = entireProjectScopeRadioButton.isSelected() ? "ENTIRE" : "SELECT";
            scopeCardLayout.show(scopeCardsPanel, command);
        };
        entireProjectScopeRadioButton.addActionListener(scopeListener);
        selectFilesScopeRadioButton.addActionListener(scopeListener);
        scopeCardLayout.show(scopeCardsPanel, "SELECT");


        add(contentPanel, BorderLayout.CENTER);

        // Buttons Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        okButton.addActionListener(e -> onOK());
        cancelButton.addActionListener(e -> setVisible(false));

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void updateTokenWarningLabel() {
        if (!includeWorkspaceCheckbox.isSelected()) {
            tokenWarningLabel.setVisible(false);
            return;
        }

        var cm = chrome.getContextManager();
        var service = cm.getService();

        var favModel = (Service.FavoriteModel) modelComboBox.getSelectedItem();
        if (favModel == null) {
            tokenWarningLabel.setVisible(false);
            return;
        }

        var model = service.getModel(favModel.modelName(), favModel.reasoning());
        if (model == null) {
            tokenWarningLabel.setVisible(false);
            return;
        }
        var maxTokens = service.getMaxInputTokens(model);

        var workspaceTokens = Messages.getApproximateTokens(CodePrompts.instance.getWorkspaceContentsMessages(cm.topContext()));
        var historyTokens = Messages.getApproximateTokens(cm.getHistoryMessages());

        long remaining = (long) maxTokens - workspaceTokens - historyTokens;

        if (remaining < TOKEN_SAFETY_MARGIN) {
            tokenWarningLabel.setText(
                    "<html><b>Warning:</b> The selected model has a " + maxTokens +
                    "-token window. Your workspace (" + workspaceTokens + " tokens) " +
                    "+ history (" + historyTokens + " tokens) leaves only " + remaining +
                    " tokens, which is below the " + TOKEN_SAFETY_MARGIN + " token safety margin. " +
                    "Trim your workspace or choose a model with a larger context window.</html>"
            );
            tokenWarningLabel.setVisible(true);
        } else {
            tokenWarningLabel.setVisible(false);
        }
    }

    private void setupKeyBindings() {
        // Allow Shift+Enter to insert a newline in the text area
        var inputMap = instructionsArea.getInputMap(JComponent.WHEN_FOCUSED);
        var actionMap = instructionsArea.getActionMap();
        var enterAction = actionMap.get("insert-break");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break-shifted");
        actionMap.put("insert-break-shifted", enterAction);

        // Allow Esc to close the dialog
        var rootPane = getRootPane();
        var escapeStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        java.awt.event.ActionListener actionListener = e -> setVisible(false);
        rootPane.registerKeyboardAction(actionListener, escapeStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void addSelectedFilesToTable() {
        List<BrokkFile> resolvedFiles = fileSelectionPanel.resolveAndGetSelectedFiles();
        if (resolvedFiles.isEmpty()) {
            return;
        }

        // Use a Set to prevent duplicate entries
        Set<String> existingPaths = new HashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            existingPaths.add((String) tableModel.getValueAt(i, 0));
        }

        for (BrokkFile file : resolvedFiles) {
            if (file instanceof ProjectFile pf) {
                String relPath = pf.getRelPath().toString();
                if (!existingPaths.contains(relPath)) {
                    tableModel.addRow(new Object[]{relPath});
                }
            }
        }
        fileSelectionPanel.setInputText("");
    }

    private void removeSelectedFilesFromTable() {
        int[] selectedRows = selectedFilesTable.getSelectedRows();
        // Remove rows in reverse order to prevent index shifting issues
        for (int i = selectedRows.length - 1; i >= 0; i--) {
            tableModel.removeRow(selectedRows[i]);
        }
    }

    private void onOK() {
        String instructions = instructionsArea.getText().trim();
        if (instructions.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Instructions cannot be empty.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Service.FavoriteModel selectedFavorite = (Service.FavoriteModel) modelComboBox.getSelectedItem();
        if (selectedFavorite == null) {
            JOptionPane.showMessageDialog(this, "Please select a model.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Determine scope and get files to process
        boolean isEntireProjectScope = entireProjectScopeRadioButton.isSelected();
        List<ProjectFile> filesToProcessList = Collections.emptyList();

        if (isEntireProjectScope) {
            var filesToProcess = chrome.getProject().getRepo().getTrackedFiles().stream()
                    .filter(Objects::nonNull)
                    .filter(ProjectFile::isText);

            String selectedLanguageString = (String) languageComboBox.getSelectedItem();
            if (selectedLanguageString != null && !ALL_LANGUAGES_OPTION.equals(selectedLanguageString)) {
                filesToProcess = filesToProcess.filter(pf -> {
                    Language lang = pf.getLanguage();
                    return selectedLanguageString.equals(lang.toString());
                });
            }
            filesToProcessList = filesToProcess.collect(Collectors.toList());

            if (filesToProcessList.isEmpty()) {
                String message = (ALL_LANGUAGES_OPTION.equals(languageComboBox.getSelectedItem()) || languageComboBox.getSelectedItem() == null)
                                 ? "No text files found in the project to process."
                                 : "No text files found for the selected language (" + languageComboBox.getSelectedItem() + ").";
                JOptionPane.showMessageDialog(this, message, "No Files", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        } else { // Select Files
            if (tableModel.getRowCount() == 0) {
                JOptionPane.showMessageDialog(this, "No files have been selected.", "No Files", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            List<ProjectFile> selectedFiles = new java.util.ArrayList<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String relPath = (String) tableModel.getValueAt(i, 0);
                selectedFiles.add(chrome.getContextManager().toFile(relPath));
            }
            filesToProcessList = selectedFiles;
        }

        Integer relatedK = null;
        var txt = Objects.toString(relatedClassesCombo.getEditor().getItem(), "").trim();
        if (!txt.isEmpty()) {
            try {
                int kValue = Integer.parseInt(txt);
                if (kValue < 0) {
                    throw new NumberFormatException("Value must be non-negative.");
                }
                if (kValue > 0) {
                    relatedK = kValue;
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this,
                                              "Value for 'Include related classes' must be a non-negative integer.",
                                              "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        String perFileCommandTemplate = perFileCommandTextField.getText().trim();
        if (perFileCommandTemplate.isEmpty()) {
            perFileCommandTemplate = null;
        }

        boolean includeWorkspace = includeWorkspaceCheckbox.isSelected();

        setVisible(false); // Hide this dialog

        // Show progress dialog
        var progressDialog = new UpgradeAgentProgressDialog(
                (Frame) getOwner(),
                instructions,
                selectedFavorite,
                filesToProcessList,
                chrome,
                relatedK,
                perFileCommandTemplate,
                includeWorkspace
        );
        progressDialog.setVisible(true);
    }
}
