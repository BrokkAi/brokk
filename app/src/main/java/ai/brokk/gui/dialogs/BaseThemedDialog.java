package ai.brokk.gui.dialogs;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.theme.ThemeTitleBarManager;
import com.formdev.flatlaf.util.SystemInfo;
import java.awt.*;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * A base class for dialogs that automatically applies themed title-bar styling on macOS
 * while remaining a standard JDialog on other platforms.
 *
 * <p><b>Purpose:</b> This class centralizes the boilerplate required to create a themed
 * dialog on macOS (setting Apple client properties, configuring BorderLayout structure,
 * and applying ThemeTitleBarManager). By extending this class, dialog subclasses can:
 * - Inherit a pre-configured content structure with no per-class layout patching
 * - Place their UI in the protected contentRoot panel without touching BorderLayout.NORTH
 * - Automatically benefit from macOS full-window-content theming and future theme updates
 *
 * <p><b>Layout structure:</b> The dialog's contentPane uses BorderLayout:
 * - NORTH: Reserved for the themed title bar (added by ThemeTitleBarManager on macOS only)
 * - CENTER: The contentRoot panel where subclasses place their UI
 * - Other regions: Available for subclass use if needed
 *
 * <p><b>macOS behavior:</b> On macOS with FlatLaf full-window-content support:
 * - Sets apple.awt.fullWindowContent and apple.awt.transparentTitleBar properties
 * - Hides the native title bar
 * - Applies a custom Swing title bar via ThemeTitleBarManager
 * - The custom title bar participates in theme changes (updateAllTitleBars)
 *
 * <p><b>Non-macOS behavior:</b> On other platforms, this class is effectively transparent:
 * - No macOS properties are set
 * - ThemeTitleBarManager.applyTitleBar returns early without side effects
 * - The dialog behaves as a standard JDialog with the contentRoot panel in CENTER
 *
 * <p><b>Usage example:</b>
 * <pre>
 * public class MyThemedDialog extends BaseThemedDialog {
 *     public MyThemedDialog(Window owner) {
 *         super(owner, "My Dialog", Dialog.ModalityType.APPLICATION_MODAL);
 *         // Other initialization
 *         buildLayout();
 *     }
 *
 *     private void buildLayout() {
 *         JPanel root = getContentRoot();
 *         root.setLayout(new BorderLayout());
 *         root.add(myHeaderPanel, BorderLayout.NORTH);
 *         root.add(myContentPanel, BorderLayout.CENTER);
 *         root.add(myButtonPanel, BorderLayout.SOUTH);
 *     }
 * }
 * </pre>
 *
 * <p><b>Modality and dialog properties:</b> Subclasses retain full control over:
 * - Dialog modality (APPLICATION_MODAL, MODELESS, etc.)
 * - Size, location, and resizing behavior
 * - Close operation (DISPOSE_ON_CLOSE, DO_NOTHING_ON_CLOSE, etc.)
 * - Any custom listeners, key bindings, or focus management
 *
 * <p><b>Theme updates:</b> When the application theme changes, ThemeTitleBarManager
 * automatically restyled all managed dialogs (including those extending BaseThemedDialog).
 * Subclasses do not need to handle theme updates for the title bar.
 *
 * @see ThemeTitleBarManager
 * @see Chrome#applyDialogTitleBar(JDialog, String)
 */
public abstract class BaseThemedDialog extends JDialog {
    /**
     * The panel where subclasses place their UI content.
     * Uses BorderLayout and occupies the CENTER region of the dialog's contentPane.
     */
    protected final JPanel contentRoot = new JPanel(new BorderLayout());

    /**
     * Constructs a BaseThemedDialog with the specified owner, title, and modality.
     *
     * <p>Initializes the dialog structure with:
     * - A BorderLayout on the contentPane with NORTH reserved for the title bar
     * - The contentRoot panel at CENTER for subclass UI
     * - On macOS: full-window-content configuration and custom title bar via ThemeTitleBarManager
     * - On non-macOS: standard JDialog with no title-bar theming
     *
     * @param owner The parent window (may be null for unowned dialogs)
     * @param title The title for the dialog (displayed in title bar on macOS, or native on other platforms)
     * @param modalityType The modality type (APPLICATION_MODAL, MODELESS, etc.)
     */
    protected BaseThemedDialog(@Nullable Window owner, String title, Dialog.ModalityType modalityType) {
        super(owner, title, modalityType);

        // Set up the dialog's content pane with a BorderLayout structure
        // NORTH is reserved for the title bar (on macOS)
        // CENTER holds the contentRoot where subclasses place their UI
        Container contentPane = getContentPane();
        contentPane.setLayout(new BorderLayout());

        // Add the contentRoot to CENTER; subclasses will populate it
        contentPane.add(contentRoot, BorderLayout.CENTER);

        // Apply macOS theming (or no-op on non-macOS)
        applyThemedTitleBar(title);
    }

    /**
     * Constructs a BaseThemedDialog with APPLICATION_MODAL modality.
     * Convenience constructor for the common case of modal dialogs.
     *
     * @param owner The parent window
     * @param title The dialog title
     */
    protected BaseThemedDialog(@Nullable Window owner, String title) {
        this(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
    }

    /**
     * Returns the content root panel where subclasses should place their UI.
     *
     * <p>This panel uses BorderLayout and is located at CENTER of the dialog's
     * contentPane. On macOS, the themed title bar occupies the NORTH region of
     * the contentPane, so placing UI in contentRoot avoids layout conflicts.
     *
     * @return The panel where subclass UI should be added
     */
    protected final JPanel getContentRoot() {
        return contentRoot;
    }

    /**
     * Applies macOS-specific theming (or performs a no-op on other platforms).
     *
     * <p>On macOS with FlatLaf full-window-content support:
     * - Sets apple.awt.fullWindowContent and apple.awt.transparentTitleBar
     * - Hides the native title bar text
     * - Calls ThemeTitleBarManager.applyTitleBar to add a custom Swing title bar
     *
     * <p>On non-macOS platforms, this method returns immediately without side effects.
     *
     * @param title The dialog title to display in the custom title bar (macOS only)
     */
    private void applyThemedTitleBar(String title) {
        // Check macOS and full-window-content capability
        if (!SystemInfo.isMacOS || !SystemInfo.isMacFullWindowContentSupported) {
            return; // No-op on non-macOS
        }

        // Configure Apple-specific properties for full-window content
        JRootPane rootPane = getRootPane();
        rootPane.putClientProperty("apple.awt.fullWindowContent", true);
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true);

        // Hide the native window title (required for Java 17+, fallback for older versions)
        if (SystemInfo.isJava_17_orLater) {
            rootPane.putClientProperty("apple.awt.windowTitleVisible", false);
        } else {
            setTitle(null);
        }

        // Apply the custom Swing title bar via ThemeTitleBarManager
        ThemeTitleBarManager.applyTitleBar(this, title);
    }
}
