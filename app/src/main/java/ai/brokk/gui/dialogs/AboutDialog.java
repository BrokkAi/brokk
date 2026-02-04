package ai.brokk.gui.dialogs;

import ai.brokk.BuildInfo;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.jetbrains.annotations.Nullable;

/**
 * Native-style "About Brokk" dialog.
 *
 * <p>• Modeless so the macOS Application menu stays enabled. • Can be invoked from any thread; creation is marshalled
 * to the EDT.
 */
public final class AboutDialog extends BaseThemedDialog {
    private final @Nullable Chrome chrome;

    private AboutDialog(@Nullable Window owner, @Nullable Chrome chrome) {
        super(owner, "About Brokk", Dialog.ModalityType.MODELESS);
        this.chrome = chrome;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setName("aboutDialog");

        buildUi();

        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        var content = new JPanel(new BorderLayout(15, 0));
        content.setBorder(new EmptyBorder(20, 20, 20, 20));

        // App icon (scaled)
        var iconUrl = AboutDialog.class.getResource("/brokk-icon.png");
        if (iconUrl != null) {
            var base = new ImageIcon(iconUrl);
            var img = base.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
            content.add(new JLabel(new ImageIcon(img)), BorderLayout.WEST);
        }

        // Text info
        var text =
                """
                   <html>
                     <h2 style='margin-top:0'>Brokk</h2>
                     Version %s<br>
                     &copy; 2025 Brokk&nbsp;Inc.
                   </html>
                   """
                        .formatted(BuildInfo.version);
        content.add(new JLabel(text), BorderLayout.CENTER);

        // Buttons at the bottom
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBorder(new EmptyBorder(0, 20, 10, 20));

        if (chrome != null) {
            var feedbackBtn = new MaterialButton("Send Feedback");
            SwingUtil.applyPrimaryButtonStyle(feedbackBtn);
            feedbackBtn.addActionListener(e -> {
                new FeedbackDialog(chrome.getFrame(), chrome).setVisible(true);
            });
            buttonPanel.add(feedbackBtn);
        }

        // Add content to the contentRoot managed by BaseThemedDialog
        var root = getContentRoot();
        root.setLayout(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(buttonPanel, BorderLayout.SOUTH);
        Chrome.applyIcon(this); // sets Dock/task-bar icon where applicable

        // Allow closing with ESC key
        var rootPane = getRootPane();
        rootPane.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog");
        rootPane.getActionMap().put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
    }

    /** Show the dialog. Creation is marshalled to the EDT. */
    public static void showAboutDialog(@Nullable Window owner, @Nullable Chrome chrome) {
        SwingUtil.runOnEdt(() -> new AboutDialog(owner, chrome).setVisible(true));
    }

    private static final long serialVersionUID = 1L;
}
