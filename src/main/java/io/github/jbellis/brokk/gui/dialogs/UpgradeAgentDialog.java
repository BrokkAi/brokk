package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;

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
        gbc.anchor = GridBagConstraints.WEST;
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
        instructionsArea = new JTextArea(10, 50);
        instructionsArea.setLineWrap(true);
        instructionsArea.setWrapStyleWord(true);
        JScrollPane instructionsScrollPane = new JScrollPane(instructionsArea);
        contentPanel.add(instructionsScrollPane, gbc);

        // Model Label
        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        contentPanel.add(new JLabel("Model:"), gbc);
        
        // Model ComboBox
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
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

        // Scope Label
        gbc.gridy++;
        gbc.gridwidth = 2; // Span label across two columns
        contentPanel.add(new JLabel("Scope:"), gbc);

        // Scope RadioButtons
        gbc.gridy++;
        gbc.gridwidth = 1; // Reset gridwidth for individual radio buttons
        entireProjectScopeRadioButton = new JRadioButton("Entire Project");
        entireProjectScopeRadioButton.setSelected(true);
        workspaceFilesScopeRadioButton = new JRadioButton("Workspace Files");

        scopeButtonGroup = new ButtonGroup();
        scopeButtonGroup.add(entireProjectScopeRadioButton);
        scopeButtonGroup.add(workspaceFilesScopeRadioButton);

        JPanel scopePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        scopePanel.add(entireProjectScopeRadioButton);
        scopePanel.add(workspaceFilesScopeRadioButton);
        gbc.gridwidth = 2; // Span panel across two columns
        contentPanel.add(scopePanel, gbc);


        // Language Label (visible only for Entire Project)
        gbc.gridy++;
        gbc.gridwidth = 1;
        JLabel languageLabel = new JLabel("Restrict to Language:");
        contentPanel.add(languageLabel, gbc);

        // Language ComboBox (visible only for Entire Project)
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        languageComboBox = new JComboBox<>();
        languageComboBox.addItem(ALL_LANGUAGES_OPTION);
        chrome.getProject().getAnalyzerLanguages().stream()
              .map(Language::toString) // Assuming Language.toString() is user-friendly
              .sorted()
              .forEach(languageComboBox::addItem);
        languageComboBox.setSelectedItem(ALL_LANGUAGES_OPTION);
        contentPanel.add(languageComboBox, gbc);

        // Listener to toggle visibility of language components based on scope selection
        ActionListener scopeListener = e -> {
            boolean isEntireProject = entireProjectScopeRadioButton.isSelected();
            languageLabel.setVisible(isEntireProject);
            languageComboBox.setVisible(isEntireProject);
        };
        entireProjectScopeRadioButton.addActionListener(scopeListener);
        workspaceFilesScopeRadioButton.addActionListener(scopeListener);

        // Initial state
        languageLabel.setVisible(entireProjectScopeRadioButton.isSelected());
        languageComboBox.setVisible(entireProjectScopeRadioButton.isSelected());
        
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
            var workspaceFiles = chrome.getContextManager().topContext().allFragments()
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

        setVisible(false); // Hide this dialog

        // Show progress dialog
        var progressDialog = new UpgradeAgentProgressDialog(
                (Frame) getOwner(),
                instructions,
                selectedFavorite,
                filesToProcessList,
                chrome
        );
        progressDialog.setVisible(true);
    }
}
