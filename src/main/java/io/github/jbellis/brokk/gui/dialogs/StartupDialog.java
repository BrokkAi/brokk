package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.gui.components.BrowserLabel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class StartupDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(StartupDialog.class);

    private JFileChooser projectChooser;
    private JTextField keyField;
    private Path selectedProjectPath = null;
    private boolean keyInitiallyValid;
    private String initialKey;
    private DialogMode dialogMode;
    private Path initialProjectPath; // Used when mode is REQUIRE_KEY_ONLY

    public enum DialogMode {
        REQUIRE_KEY_ONLY,      // Valid project exists, need key
        REQUIRE_PROJECT_ONLY,  // Valid key exists, need project
        REQUIRE_BOTH           // Neither valid key nor project exists
    }

    private StartupDialog(Frame owner, String initialKey, boolean keyInitiallyValid, Path initialProjectPath, DialogMode mode) {
        super(owner, "Welcome to Brokk", true);
        io.github.jbellis.brokk.gui.Chrome.applyIcon(this);
        this.initialKey = initialKey;
        this.keyInitiallyValid = keyInitiallyValid;
        this.initialProjectPath = initialProjectPath;
        this.dialogMode = mode;

        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleExit();
            }
        });

        initComponents();
        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
    }

    private void initComponents() {
        setLayout(new BorderLayout(15, 15)); // Gaps between components
        getRootPane().setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15)); // Padding for the whole dialog

        // --- Icon Panel (West) ---
        var iconPanel = new JPanel();
        var iconUrl = Brokk.class.getResource(Brokk.ICON_RESOURCE);
        if (iconUrl != null) {
            var originalIcon = new ImageIcon(iconUrl);
            var image = originalIcon.getImage().getScaledInstance(128, 128, Image.SCALE_SMOOTH);
            iconPanel.add(new JLabel(new ImageIcon(image)));
        }
        add(iconPanel, BorderLayout.WEST);

        // --- Main Content Panel (Center) ---
        var outerMainPanel = new JPanel();
        outerMainPanel.setLayout(new BoxLayout(outerMainPanel, BoxLayout.PAGE_AXIS));

        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // --- Key Input Panel ---
        var keyInputPanel = new JPanel(new GridBagLayout());
        int yKey = 0;

        // Key Instructions
        gbc.gridx = 0;
        gbc.gridy = yKey++;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        keyInputPanel.add(new JLabel("<html>Please enter your Brokk Key.<br>" +
                                 "You can sign up for free at:</html>"), gbc);

        // Signup URL
        gbc.gridy = yKey++;
        var signupUrl = "https://brokk.ai";
        keyInputPanel.add(new BrowserLabel(signupUrl, signupUrl), gbc);

        // Key Label
        gbc.gridx = 0;
        gbc.gridy = yKey;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        keyInputPanel.add(new JLabel("Brokk Key:"), gbc);

        // Key Field
        gbc.gridx = 1;
        gbc.gridy = yKey++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        keyField = new JTextField(30);
        if (initialKey != null) {
            keyField.setText(initialKey);
        }
        keyInputPanel.add(keyField, gbc);
        gbc.weightx = 0; // Reset weightx

        // --- Project Chooser Panel ---
        var projectChooserPanel = new JPanel(new GridBagLayout());
        int yProject = 0;
        // Reset gbc for project panel if necessary (though here it's mostly fine)
        gbc.gridwidth = 2; // spans two columns
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // Project Chooser Label
        gbc.gridx = 0;
        gbc.gridy = yProject++;
        projectChooserPanel.add(new JLabel("Select a project directory to open:"), gbc);

        // Project Chooser
        gbc.gridy = yProject++;
        gbc.weighty = 1.0; // Allow chooser to take space
        gbc.fill = GridBagConstraints.BOTH;
        projectChooser = new JFileChooser();
        projectChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        projectChooser.setControlButtonsAreShown(false); // We have our own buttons
        // Try to set a sensible default directory
        if (this.initialProjectPath != null) {
            projectChooser.setCurrentDirectory(this.initialProjectPath.toFile());
        } else {
            var userHome = System.getProperty("user.home");
            if (userHome != null) {
                projectChooser.setCurrentDirectory(new File(userHome));
            }
        }
        projectChooserPanel.add(projectChooser, gbc);

        // Add sub-panels to outerMainPanel
        outerMainPanel.add(keyInputPanel);
        outerMainPanel.add(Box.createRigidArea(new Dimension(0, 10))); // Spacer
        outerMainPanel.add(projectChooserPanel);

        // Conditional visibility based on mode
        keyInputPanel.setVisible(dialogMode == DialogMode.REQUIRE_KEY_ONLY || dialogMode == DialogMode.REQUIRE_BOTH);
        projectChooserPanel.setVisible(dialogMode == DialogMode.REQUIRE_PROJECT_ONLY || dialogMode == DialogMode.REQUIRE_BOTH);

        if (dialogMode == DialogMode.REQUIRE_PROJECT_ONLY) {
            if (initialKey != null) { // Should always be true if this mode is chosen correctly
                keyField.setText(initialKey);
            }
            keyField.setEditable(false);
            // Optionally hide key instructions/signup if key is already valid and provided
            // For now, keyInputPanel remains visible but field is non-editable.
        }

        if (dialogMode == DialogMode.REQUIRE_KEY_ONLY) {
             // projectChooserPanel is hidden, project will be taken from initialProjectPath
        }


        add(outerMainPanel, BorderLayout.CENTER);
        // --- Button Panel (South) ---
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var openButton = new JButton("Open Project");
        var exitButton = new JButton("Exit");

        openButton.addActionListener(e -> handleOpenProject());
        exitButton.addActionListener(e -> handleExit());

        buttonPanel.add(exitButton);
        buttonPanel.add(openButton);
        add(buttonPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(openButton);
    }

    private void handleOpenProject() {
        String finalKeyToUse;

        // --- Determine the Brokk Key ---
        if (dialogMode == DialogMode.REQUIRE_KEY_ONLY || dialogMode == DialogMode.REQUIRE_BOTH) {
            String currentKeyText = keyField.getText().trim();
            if (currentKeyText.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter a Brokk Key.", "Key Required", JOptionPane.WARNING_MESSAGE);
                keyField.requestFocusInWindow();
                return;
            }

            if (!currentKeyText.equals(this.initialKey) || !this.keyInitiallyValid) {
                try {
                    Service.validateKey(currentKeyText);
                    finalKeyToUse = currentKeyText;
                    this.initialKey = currentKeyText; // Update internal state
                    this.keyInitiallyValid = true;    // Mark as now valid
                } catch (IllegalArgumentException ex) {
                    logger.warn("Invalid Brokk Key entered: {}", ex.getMessage());
                    JOptionPane.showMessageDialog(this, "Invalid Brokk Key: " + ex.getMessage(), "Invalid Key", JOptionPane.ERROR_MESSAGE);
                    keyField.requestFocusInWindow();
                    keyField.selectAll();
                    return;
                } catch (IOException ex) {
                    logger.warn("Network error validating Brokk Key: {}", ex.getMessage());
                    JOptionPane.showMessageDialog(this, "Network error validating key: " + ex.getMessage() + "\nPlease check your connection or try again later.", "Network Error", JOptionPane.ERROR_MESSAGE);
                    keyField.requestFocusInWindow();
                    keyField.selectAll();
                    return;
                }
            } else { // Key is unchanged and was initially valid
                finalKeyToUse = this.initialKey;
            }
        } else { // dialogMode == DialogMode.REQUIRE_PROJECT_ONLY
            assert this.keyInitiallyValid && this.initialKey != null && !this.initialKey.isEmpty() : "Invalid state for REQUIRE_PROJECT_ONLY mode: key must be initially valid and present.";
            finalKeyToUse = this.initialKey;
        }

        assert finalKeyToUse != null : "finalKeyToUse should have been set if no errors occurred.";
        MainProject.setBrokkKey(finalKeyToUse);

        // --- Determine the Project Path ---
        Path finalProjectPathToUse;
        if (dialogMode == DialogMode.REQUIRE_PROJECT_ONLY || dialogMode == DialogMode.REQUIRE_BOTH) {
            File selectedFile = projectChooser.getSelectedFile();
            if (selectedFile == null) {
                JOptionPane.showMessageDialog(this, "Please select a project directory.", "Project Required", JOptionPane.WARNING_MESSAGE);
                // Potentially focus projectChooser or a relevant component if possible
                return;
            }
            if (!selectedFile.isDirectory()) {
                JOptionPane.showMessageDialog(this, "Please select a valid project directory.", "Directory Required", JOptionPane.WARNING_MESSAGE);
                // Potentially focus projectChooser
                return;
            }
            finalProjectPathToUse = selectedFile.toPath();
        } else { // dialogMode == DialogMode.REQUIRE_KEY_ONLY
            assert this.initialProjectPath != null : "Invalid state for REQUIRE_KEY_ONLY mode: initialProjectPath must be set.";
            if (this.initialProjectPath == null) { // Defensive check, should be caught by assert
                logger.error("StartupDialog in REQUIRE_KEY_ONLY mode but initialProjectPath is null. This should not happen.");
                JOptionPane.showMessageDialog(this, "Internal error: Project path missing. Please restart.", "Error", JOptionPane.ERROR_MESSAGE);
                return; // Critical error
            }
            finalProjectPathToUse = this.initialProjectPath;
        }

        // --- Success: Set selected project path and close dialog ---
        this.selectedProjectPath = finalProjectPathToUse;
        dispose();
    }

    private void handleExit() {
        selectedProjectPath = null;
        dispose();
    }

    public static Path showDialog(Frame owner, String initialKey, boolean keyInitiallyValid, Path initialProjectPath, DialogMode mode) {
        var dialog = new StartupDialog(owner, initialKey, keyInitiallyValid, initialProjectPath, mode);
        dialog.setVisible(true); // Blocks until dispose() is called
        return dialog.selectedProjectPath;
    }
}
