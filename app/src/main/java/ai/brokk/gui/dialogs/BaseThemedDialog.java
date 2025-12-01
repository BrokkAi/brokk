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
 * <p><b>Opting into theming:</b> All new Brokk dialogs should normally follow one of two patterns:
 * <ul>
 *   <li><b>Extend BaseThemedDialog directly:</b> For reusable or complex dialogs (e.g., SettingsDialog,
 *     AboutDialog), create a dedicated subclass that calls the {@code super(owner, title, modality)}
 *     constructor and builds UI within {@code getContentRoot()}.
 *   <li><b>Use {@link Chrome#newThemedDialog(Window, String, boolean)} factory:</b> For one-off or
 *     simple dialogs, use the factory method and build UI directly on the returned instance.
 * </ul>
 * <p>Legacy dialogs that still extend {@code JDialog} may instead call
 * {@link Chrome#newDialog(Window, String, boolean, boolean)} or
 * {@link Chrome#applyDialogTitleBar(JDialog, String)} / {@link ThemeTitleBarManager#applyTitleBar(JDialog, String)}
 * directly. However, this is considered a migration/edge path; new work should prefer BaseThemedDialog.
 * <p>Important: Subclasses must <b>not</b> set Apple client properties
 * ({@code apple.awt.fullWindowContent}, {@code apple.awt.transparentTitleBar}) or call
 * {@link ThemeTitleBarManager} directly. All macOS configuration is handled by BaseThemedDialog's
 * constructor and {@code applyThemedTitleBar()} method.
 *
 * <p><b>Layout structure:</b> The dialog's contentPane uses BorderLayout:
 * - NORTH: Reserved for the themed title bar (added by ThemeTitleBarManager on macOS only)
 * - CENTER: The contentRoot panel where subclasses place their UI
 * - Other regions: Available for subclass use if needed
 *
 * <p><b>Content root vs contentPane:</b> BaseThemedDialog reserves {@code BorderLayout.NORTH}
 * of the content pane for the custom title bar on macOS. Dialog UI <b>must</b> be added to
 * {@code contentRoot} (via {@link #getContentRoot()}) which lives in {@code BorderLayout.CENTER},
 * and <b>never</b> directly to {@code getContentPane()}.
 * <p>Subclasses must <b>never</b> add components to {@code BorderLayout.NORTH} of the dialog's
 * content pane, or they will conflict with the title bar injected by ThemeTitleBarManager.
 * <p>Correct pattern (do this):
 * <pre>
 * public class MyDialog extends BaseThemedDialog {
 *     public MyDialog(Window owner) {
 *         super(owner, "My Dialog", Dialog.ModalityType.APPLICATION_MODAL);
 *         buildLayout();
 *     }
 *
 *     private void buildLayout() {
 *         JPanel root = getContentRoot();
 *         root.setLayout(new BorderLayout());
 *         root.add(headerPanel, BorderLayout.NORTH);
 *         root.add(mainPanel, BorderLayout.CENTER);
 *         root.add(buttonPanel, BorderLayout.SOUTH);
 *     }
 * }
 * </pre>
 * <p>Anti-pattern (do NOT do this):
 * <pre>
 * // Wrong: adds header to content pane NORTH, which conflicts with the title bar
 * getContentPane().add(headerPanel, BorderLayout.NORTH);
 * // Correct: add header to contentRoot instead
 * getContentRoot().add(headerPanel, BorderLayout.NORTH);
 * </pre>
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
 * <p><b>Factory creation:</b> Use {@link Chrome#newThemedDialog(Window, String, boolean)} or
 * {@link Chrome#newThemedDialog(Window, String)} to create instances via the convenience factory.
 *
 * <p><b>Manual verification (for debugging):</b> To inspect a running BaseThemedDialog and verify
 * correct configuration, you can check these properties at runtime:
 * <pre>
 * BaseThemedDialog dialog = Chrome.newThemedDialog(frame, "Test", true);
 *
 * // Check the content pane layout
 * Container cp = dialog.getContentPane();
 * LayoutManager layout = cp.getLayout();
 * System.out.println("Layout: " + layout.getClass().getSimpleName()); // should print "BorderLayout"
 *
 * // On macOS, inspect the NORTH component (should be the custom title bar)
 * if (SystemInfo.isMacOS && layout instanceof BorderLayout bl) {
 *     Component north = bl.getLayoutComponent(BorderLayout.NORTH);
 *     Component center = bl.getLayoutComponent(BorderLayout.CENTER);
 *     System.out.println("NORTH: " + (north != null ? north.getClass().getSimpleName() : "null"));
 *     System.out.println("CENTER: " + (center != null ? center.getClass().getSimpleName() : "null"));
 *     // Expected on macOS: NORTH = "JPanel" (title bar), CENTER = "JPanel" (contentRoot)
 * } else {
 *     System.out.println("(Not on macOS; native title bar is used instead)");
 * }
 * </pre>
 *
 * @see ThemeTitleBarManager
 * @see Chrome#applyDialogTitleBar(JDialog, String)
 * @see Chrome#newThemedDialog(Window, String, boolean)
 */
public class BaseThemedDialog extends JDialog {
    /**
     * The panel where callers place their UI content.
     * Uses BorderLayout and occupies the CENTER region of the dialog's contentPane.
     */
    private final JPanel contentRoot = new JPanel(new BorderLayout());

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
    public BaseThemedDialog(@Nullable Window owner, String title, Dialog.ModalityType modalityType) {
        super(owner, title, modalityType);

        // Set up the dialog's content pane with a BorderLayout structure.
        // This is intentional: NORTH is reserved for the custom title bar (on macOS),
        // while CENTER holds contentRoot where subclasses place their UI.
        // Subclasses must never add components to BorderLayout.NORTH directly.
        Container contentPane = getContentPane();
        contentPane.setLayout(new BorderLayout());

        // Add the contentRoot to CENTER; subclasses will populate it
        contentPane.add(contentRoot, BorderLayout.CENTER);

        // Apply macOS-specific theming: sets apple.awt.* properties, hides the native title,
        // and delegates to ThemeTitleBarManager to attach the custom Swing title bar.
        // Subclasses must not duplicate this logic or set apple.awt.* properties directly.
        applyThemedTitleBar(title);
    }

    /**
     * Constructs a BaseThemedDialog with APPLICATION_MODAL modality.
     * Convenience constructor for the common case of modal dialogs.
     *
     * @param owner The parent window
     * @param title The dialog title
     */
    public BaseThemedDialog(@Nullable Window owner, String title) {
        this(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
    }

    /**
     * Returns the content root panel where callers should place their UI.
     *
     * <p>This panel uses BorderLayout and is located at CENTER of the dialog's
     * contentPane. On macOS, the themed title bar occupies the NORTH region of
     * the contentPane, so placing UI in contentRoot avoids layout conflicts.
     *
     * @return The panel where dialog UI should be added
     */
    public final JPanel getContentRoot() {
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
