package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.gui.Chrome;

import javax.swing.*;
import java.awt.*;

public class CreatePullRequestDialog extends JDialog {

    private final Chrome chrome;

    public CreatePullRequestDialog(Frame owner, Chrome chrome) {
        super(owner, "Create a Pull Request", true);
        this.chrome = chrome;
        
        initializeDialog();
        buildLayout();
    }

    private void initializeDialog() {
        setSize(400, 300);
        setLocationRelativeTo(getOwner());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void buildLayout() {
        setLayout(new BorderLayout());
        
        // Placeholder content - will be expanded in future iterations
        JLabel placeholderLabel = new JLabel("Pull Request creation dialog - Coming soon!", SwingConstants.CENTER);
        add(placeholderLabel, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);
        
        add(buttonPanel, BorderLayout.SOUTH);
    }

    public static void show(Frame owner, Chrome chrome) {
        CreatePullRequestDialog dialog = new CreatePullRequestDialog(owner, chrome);
        dialog.setVisible(true);
    }
}
