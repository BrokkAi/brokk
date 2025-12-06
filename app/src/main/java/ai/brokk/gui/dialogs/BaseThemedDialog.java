package ai.brokk.gui.dialogs;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.theme.ThemeTitleBarManager;
import com.formdev.flatlaf.util.SystemInfo;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * Base dialog with automatic macOS themed title bar styling.
 *
 * <p>On macOS, reserves {@code BorderLayout.NORTH} of contentPane for the custom title bar.
 * Subclasses must add UI to {@link #getContentRoot()}, never directly to {@code getContentPane()}.
 *
 * <p>Usage:
 * <pre>
 * public class MyDialog extends BaseThemedDialog {
 *     public MyDialog(Window owner) {
 *         super(owner, "Title", Dialog.ModalityType.APPLICATION_MODAL);
 *         JPanel root = getContentRoot();
 *         root.add(content, BorderLayout.CENTER);
 *         root.add(buttons, BorderLayout.SOUTH);
 *     }
 * }
 * </pre>
 *
 * @see ThemeTitleBarManager
 */
public class BaseThemedDialog extends JDialog {
    private final JPanel contentRoot = new JPanel(new BorderLayout());

    /**
     * @param owner parent window (may be null)
     * @param title dialog title
     * @param modalityType modality (APPLICATION_MODAL, MODELESS, etc.)
     */
    public BaseThemedDialog(@Nullable Window owner, String title, Dialog.ModalityType modalityType) {
        super(owner, title, modalityType);

        var contentPane = getContentPane();
        contentPane.setLayout(new BorderLayout());
        contentPane.add(contentRoot, BorderLayout.CENTER);

        applyThemedTitleBar(title);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                ThemeTitleBarManager.removeDialog(BaseThemedDialog.this);
            }
        });
    }

    /** Convenience constructor for modal dialogs. */
    public BaseThemedDialog(@Nullable Window owner, String title) {
        this(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
    }

    /** Returns the panel where subclass UI should be added (uses BorderLayout). */
    public final JPanel getContentRoot() {
        return contentRoot;
    }

    /** Applies macOS full-window-content theming; no-op on other platforms. */
    private void applyThemedTitleBar(String title) {
        Chrome.applyMacOSFullWindowContent(this);
        if (SystemInfo.isMacOS && SystemInfo.isMacFullWindowContentSupported) {
            ThemeTitleBarManager.applyTitleBar(this, title);
        }
    }
}
