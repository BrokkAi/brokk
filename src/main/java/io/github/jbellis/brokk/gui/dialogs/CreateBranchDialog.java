package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.Locale;

public class CreateBranchDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(CreateBranchDialog.class);

    private final ContextManager contextManager;
    private final Chrome chrome;
    private final String commitId;
    private final String shortCommitId;

    private JTextField branchNameField;
    private JCheckBox checkoutCheckBox;
    private JLabel feedbackLabel;
    private JButton okButton;

    public CreateBranchDialog(Frame owner, ContextManager contextManager, Chrome chrome, String commitId, String shortCommitId) {
        super(owner, "Create Branch from Commit " + shortCommitId, true);
        this.contextManager = contextManager;
        this.chrome = chrome;
        this.commitId = commitId;
        this.shortCommitId = shortCommitId;

        initComponents();
        layoutComponents();
        pack();
        setLocationRelativeTo(owner);
    }

    private void initComponents() {
        branchNameField = new JTextField(25);
        checkoutCheckBox = new JCheckBox("Checkout after creation", true);
        feedbackLabel = new JLabel(" "); // Placeholder for feedback
        okButton = new JButton("OK");
        okButton.setEnabled(false); // Initially disabled

        JButton cancelButton = new JButton("Cancel");

        branchNameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { validateInput(); }
            @Override public void removeUpdate(DocumentEvent e) { validateInput(); }
            @Override public void changedUpdate(DocumentEvent e) { validateInput(); }
        });

        okButton.addActionListener(e -> createBranch());
        cancelButton.addActionListener(e -> dispose());
    }

    private void layoutComponents() {
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Branch name:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        formPanel.add(branchNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 0;
        formPanel.add(feedbackLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        formPanel.add(checkoutCheckBox, gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(okButton);
        buttonPanel.add(new JButton("Cancel") {{ addActionListener(e -> dispose()); }});

        add(formPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private String performBasicBranchNameSanitization(String proposedName) {
        String sanitized = proposedName.trim().toLowerCase(Locale.ROOT);
        sanitized = sanitized.replaceAll("\\s+", "-");
        // Same regex as in GitRepo.sanitizeBranchName for consistency in allowed characters
        sanitized = sanitized.replaceAll("[^a-z0-9-/_]", "");
        sanitized = sanitized.replaceAll("^-+|-+$", "");
        // Unlike GitRepo.sanitizeBranchName, we don't default to "branch" here,
        // as an empty result from user input means it's invalid.
        return sanitized;
    }

    private void validateInput() {
        String currentText = branchNameField.getText();
        String sanitizedText = performBasicBranchNameSanitization(currentText);

        if (sanitizedText.isEmpty()) {
            feedbackLabel.setText("Branch name is invalid (empty or only illegal characters).");
            feedbackLabel.setForeground(UIManager.getColor("Label.errorForeground"));
            okButton.setEnabled(false);
            return;
        }

        try {
            List<String> localBranches = getRepo().listLocalBranches();
            if (localBranches.contains(sanitizedText)) {
                // Use GitRepo.sanitizeBranchName to predict the *actual* name if it needs further suffixing
                String finalNameSuggestion = getRepo().sanitizeBranchName(currentText); // This might add -1, -2 etc.
                feedbackLabel.setText("'" + sanitizedText + "' exists. Actual name may be: '" + finalNameSuggestion + "'.");
                feedbackLabel.setForeground(UIManager.getColor("Label.foreground"));
                okButton.setEnabled(true);
            } else {
                feedbackLabel.setText("Will create: '" + sanitizedText + "'.");
                feedbackLabel.setForeground(UIManager.getColor("Label.foreground"));
                okButton.setEnabled(true);
            }
        } catch (GitAPIException ex) {
            feedbackLabel.setText("Error checking branches: " + ex.getMessage());
            feedbackLabel.setForeground(UIManager.getColor("Label.errorForeground"));
            okButton.setEnabled(false);
        }
    }

    private void createBranch() {
        String userInputBranchName = branchNameField.getText();
        boolean checkoutAfter = checkoutCheckBox.isSelected();
        dispose();

        contextManager.submitUserTask("Creating branch", () -> {
            try {
                String finalBranchName = getRepo().sanitizeBranchName(userInputBranchName); // Ensures uniqueness and validity
                getRepo().createBranchFromCommit(finalBranchName, commitId);
                chrome.systemOutput("Successfully created branch '" + finalBranchName + "' from commit " + shortCommitId);

                if (checkoutAfter) {
                    getRepo().checkout(finalBranchName);
                    chrome.systemOutput("Checked out branch '" + finalBranchName + "'.");
                }
                // UI will refresh on the next user action; no direct call required here
            } catch (GitAPIException gitEx) {
                logger.error("Error creating or checking out branch: {}", gitEx.getMessage(), gitEx);
                chrome.toolError("Error: " + gitEx.getMessage());
            }
        });
    }

    private GitRepo getRepo() {
        return (GitRepo) contextManager.getProject().getRepo();
    }

    public static void showDialog(Frame owner, ContextManager contextManager, Chrome chrome, String commitId, String shortCommitId) {
        CreateBranchDialog dialog = new CreateBranchDialog(owner, contextManager, chrome, commitId, shortCommitId);
        dialog.setVisible(true);
    }
}
