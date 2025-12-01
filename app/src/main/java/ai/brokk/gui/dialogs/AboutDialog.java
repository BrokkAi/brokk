package ai.brokk.gui.dialogs;

import ai.brokk.BuildInfo;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.theme.ThemeTitleBarManager;
import com.formdev.flatlaf.util.SystemInfo;
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
public final class AboutDialog extends JDialog {
    private AboutDialog(@Nullable Window owner) {
        super(owner, "About Brokk", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setName("aboutDialog");

        // Configure macOS full-window content if supported
        if (SystemInfo.isMacOS && SystemInfo.isMacFullWindowContentSupported) {
            getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            if (SystemInfo.isJava_17_orLater) {
                getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
            } else {
                setTitle(null);
            }
        }

        buildUi();

        // Apply themed title bar on macOS (no-op on other platforms)
        ThemeTitleBarManager.applyTitleBar(this, "About Brokk");

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

        // Ensure content pane uses BorderLayout and add content in CENTER
        // (title bar will be in NORTH after ThemeTitleBarManager.applyTitleBar)
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(content, BorderLayout.CENTER);
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
    public static void showAboutDialog(@Nullable Window owner) {
        SwingUtil.runOnEdt(() -> new AboutDialog(owner).setVisible(true));
    }

    private static final long serialVersionUID = 1L;
}
