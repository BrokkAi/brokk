package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.BuildInfo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Native-style “About Brokk” dialog.
 *
 * • Modeless so the macOS Application menu stays enabled.
 * • Can be invoked from any thread; creation is marshalled to the EDT.
 */
public final class AboutDialog extends JDialog {
    private AboutDialog(@Nullable Window owner)
    {
        super(owner, "About Brokk", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setName("aboutDialog");
        buildUi();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void buildUi()
    {
        var content = new JPanel(new BorderLayout(15, 0));
        content.setBorder(new EmptyBorder(20, 20, 20, 20));

        // App icon (scaled)
        var iconUrl = AboutDialog.class.getResource("/brokk-icon.png");
        if (iconUrl != null)
        {
            var base = new ImageIcon(iconUrl);
            var img  = base.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
            content.add(new JLabel(new ImageIcon(img)), BorderLayout.WEST);
        }

        // Text info
        var text = """
                   <html>
                     <h2 style='margin-top:0'>Brokk</h2>
                     Version %s<br>
                     &copy; 2025 Brokk&nbsp;Inc.
                   </html>
                   """.formatted(BuildInfo.version);
        content.add(new JLabel(text), BorderLayout.CENTER);

        add(content);
        Chrome.applyIcon(this);    // sets Dock/task-bar icon where applicable
    }

    /**
     * Show the dialog (reuse one instance per invocation to honour Desktop handler semantics).
     */
    public static void showAboutDialog(@Nullable Window owner)
    {
        SwingUtil.runOnEdt(() -> new AboutDialog(owner).setVisible(true));
    }

    private static final long serialVersionUID = 1L;
}
