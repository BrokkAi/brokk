package ai.brokk.gui.dialogs;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dependencies.DependenciesPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.UIManager;

public class ManageDependenciesDialog extends JDialog {
    private final DependenciesPanel dependenciesPanel;

    public ManageDependenciesDialog(Chrome chrome) {
        super(chrome.getFrame(), "Manage Dependencies", true);
        this.dependenciesPanel = new DependenciesPanel(chrome);

        setLayout(new BorderLayout());
        add(dependenciesPanel, BorderLayout.CENTER);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new MaterialButton("OK");
        var cancelButton = new MaterialButton("Cancel");

        var order = UIManager.getString("OptionPane.buttonOrder");
        if (order == null) order = "OC";
        for (int i = 0; i < order.length(); i++) {
            switch (order.charAt(i)) {
                case 'O' -> buttons.add(okButton);
                case 'C' -> buttons.add(cancelButton);
                default -> {
                    /* ignore */
                }
            }
        }
        if (okButton.getParent() == null) buttons.add(okButton);
        if (cancelButton.getParent() == null) buttons.add(cancelButton);

        getRootPane().setDefaultButton(okButton);

        okButton.addActionListener(e -> {
            dependenciesPanel.saveChangesAsync();
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());

        add(buttons, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(chrome.getFrame());
    }

    public static void show(Chrome chrome) {
        var dialog = new ManageDependenciesDialog(chrome);
        dialog.setVisible(true);
    }
}
