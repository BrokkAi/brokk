package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.context.Context;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class UpgradeAgentDialog extends JDialog {
    private final Chrome chrome;
    private JTextArea instructionsArea;
    private JComboBox<Service.FavoriteModel> modelComboBox;
    private JComboBox<String> languageComboBox;
    private JRadioButton entireProjectScopeRadioButton;
    private JRadioButton workspaceFilesScopeRadioButton;
    private ButtonGroup scopeButtonGroup;
    private JCheckBox includeRelatedClassesCheckBox;
    private JComboBox<String> relatedClassesCombo;
    private JCheckBox perFileCommandCheckBox;
    private JTextField perFileCommandTextField;
    private static final String ALL_LANGUAGES_OPTION = "All Languages";

    public UpgradeAgentDialog(Frame owner, Chrome chrome) {
        super(owner, "Upgrade Agent", true);
        this.chrome = chrome;
        initComponents();
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

        // Scope Row
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        contentPanel.add(new JLabel("Scope:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        entireProjectScopeRadioButton = new JRadioButton("Entire Project");
        entireProjectScopeRadioButton.setSelected(true);
        workspaceFilesScopeRadioButton = new JRadioButton("Workspace Files");

        scopeButtonGroup = new ButtonGroup();
        scopeButtonGroup.add(entireProjectScopeRadioButton);
        scopeButtonGroup.add(workspaceFilesScopeRadioButton);

        JPanel scopePanel = new JPanel(new GridLayout(2, 1, 0, 5));
        scopePanel.add(entireProjectScopeRadioButton);
        scopePanel.add(workspaceFilesScopeRadioButton);
        contentPanel.add(scopePanel, gbc);

        // Language Row
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        contentPanel.add(new JLabel("Restrict to Language:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        languageComboBox = new JComboBox<>();
        languageComboBox.addItem(ALL_LANGUAGES_OPTION);
        chrome.getProject().getAnalyzerLanguages().stream()
              .map(Language::toString) // Assuming Language.toString() is user-friendly
              .sorted()
              .forEach(languageComboBox::addItem);
        languageComboBox.setSelectedItem(ALL_LANGUAGES_OPTION);
        contentPanel.add(languageComboBox, gbc);

        // Related Classes Row
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        contentPanel.add(new JLabel("Include related classes:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        includeRelatedClassesCheckBox = new JCheckBox();
        includeRelatedClassesCheckBox.setSelected(false);

        relatedClassesCombo = new JComboBox<>(new String[]{"5", "10", "20"});
        relatedClassesCombo.setEditable(true);
        relatedClassesCombo.setEnabled(false); // Initially disabled
        includeRelatedClassesCheckBox.addActionListener(e -> relatedClassesCombo.setEnabled(includeRelatedClassesCheckBox.isSelected()));

        JPanel relatedPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        relatedPanel.add(includeRelatedClassesCheckBox);
        relatedPanel.add(Box.createHorizontalStrut(5)); // Small gap
        relatedPanel.add(relatedClassesCombo);
        contentPanel.add(relatedPanel, gbc);

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
        JPanel perFileCommandPanel = new JPanel(new BorderLayout(5, 5));
        perFileCommandCheckBox = new JCheckBox("Enable");
        perFileCommandTextField = new JTextField();
        perFileCommandTextField.setEnabled(false); // Initially disabled

        perFileCommandCheckBox.addActionListener(e -> perFileCommandTextField.setEnabled(perFileCommandCheckBox.isSelected()));

        perFileCommandPanel.add(perFileCommandCheckBox, BorderLayout.WEST);
        perFileCommandPanel.add(perFileCommandTextField, BorderLayout.CENTER);

        JLabel perFileCommandExplanation = new JLabel("<html>Command to run for each file. Use <code>{{filepath}}</code> for the file path.</html>");
        JPanel explanationPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        explanationPanel.add(perFileCommandExplanation);


        JPanel combinedPerFilePanel = new JPanel(new BorderLayout(0,3));
        combinedPerFilePanel.add(perFileCommandPanel, BorderLayout.NORTH);
        combinedPerFilePanel.add(explanationPanel, BorderLayout.CENTER);


        contentPanel.add(combinedPerFilePanel, gbc);

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
        var filesToProcess = chrome.getProject().getRepo().getTrackedFiles().stream()
                .filter(Objects::nonNull)
                .filter(ProjectFile::isText);

        if (isEntireProjectScope) {
            String selectedLanguageString = (String) languageComboBox.getSelectedItem();
            if (selectedLanguageString != null && !ALL_LANGUAGES_OPTION.equals(selectedLanguageString)) {
                filesToProcess = filesToProcess.filter(pf -> {
                    Language lang = pf.getLanguage();
                    return lang != null && selectedLanguageString.equals(lang.toString());
                });
            }
        } else { // Workspace Files
            Context topCtx = chrome.getContextManager().topContext();
            var workspaceFiles = topCtx.allFragments()
                    .filter(f -> f.getType().isPathFragment() && "PROJECT_PATH".equals(f.getType().toString()))
                    .flatMap(f -> f.files().stream())
                    .collect(Collectors.toSet());
            filesToProcess = filesToProcess.filter(f -> workspaceFiles.contains(f));
        }

        List<ProjectFile> filesToProcessList = filesToProcess.collect(Collectors.toList());

        if (filesToProcessList.isEmpty()) {
            String message;
            if (isEntireProjectScope) {
                message = (ALL_LANGUAGES_OPTION.equals(languageComboBox.getSelectedItem()) || languageComboBox.getSelectedItem() == null) ?
                          "No text files found in the project to process." :
                          "No text files found for the selected language (" + languageComboBox.getSelectedItem() + ").";
            } else {
                message = "No text files found in the workspace to process.";
            }
            JOptionPane.showMessageDialog(this, message, "No Files", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Integer relatedK = null;
        if (includeRelatedClassesCheckBox.isSelected()) {
            var txt = Objects.toString(relatedClassesCombo.getEditor().getItem(), "").trim();
            try {
                int kValue = Integer.parseInt(txt);
                if (kValue <= 0) {
                    throw new NumberFormatException("Value must be positive.");
                }
                relatedK = kValue;
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this,
                                              "Value for 'Include related classes' must be a positive integer.",
                                              "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        String perFileCommandTemplate = null;
        if (perFileCommandCheckBox.isSelected()) {
            perFileCommandTemplate = perFileCommandTextField.getText().trim();
            if (perFileCommandTemplate.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                                              "Per-file command cannot be empty when enabled.",
                                              "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        setVisible(false); // Hide this dialog

        // Show progress dialog
        var progressDialog = new UpgradeAgentProgressDialog(
                (Frame) getOwner(),
                instructions,
                selectedFavorite,
                filesToProcessList,
                chrome,
                relatedK,
                perFileCommandTemplate
        );
        progressDialog.setVisible(true);
    }
}
