package ai.brokk.gui.terminal;

import ai.brokk.IConsoleIO;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.project.MainProject;
import ai.brokk.util.Environment;
import com.jediterm.pty.PtyProcessTtyConnector;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Settings provider that allows runtime modification of terminal colors. */
class MutableSettingsProvider extends DefaultSettingsProvider {
    private TerminalColor bg;
    private TerminalColor fg;
    private TerminalColor selBg;
    private TerminalColor selFg;

    public MutableSettingsProvider(Color bg, Color fg, Color selBg, Color selFg) {
        this.bg = toTerminalColor(bg);
        this.fg = toTerminalColor(fg);
        this.selBg = toTerminalColor(selBg);
        this.selFg = toTerminalColor(selFg);
    }

    private static TerminalColor toTerminalColor(Color c) {
        return new TerminalColor(c.getRed(), c.getGreen(), c.getBlue());
    }

    @Override
    public @NotNull TerminalColor getDefaultBackground() {
        // JediTerm will use this as the default background for cells that do not emit
        // ANSI background attributes. Return a true black to avoid accidental white lines.
        return bg;
    }

    @Override
    public @NotNull TerminalColor getDefaultForeground() {
        return fg;
    }

    @Override
    @SuppressWarnings("deprecation")
    public @NotNull TextStyle getDefaultStyle() {
        return new TextStyle(fg, bg);
    }

    @Override
    public float getTerminalFontSize() {
        return MainProject.getTerminalFontSize();
    }

    @Override
    public boolean useInverseSelectionColor() {
        // Explicit selection coloring instead of inverse
        return false;
    }

    @Override
    public @NotNull TextStyle getSelectionColor() {
        // Provide explicit selection background/foreground to ensure visibility
        return new TextStyle(selFg, selBg);
    }

    public void setBackground(TerminalColor c) {
        bg = c;
    }

    public void setForeground(TerminalColor c) {
        fg = c;
    }

    public void setSelectionBackground(TerminalColor c) {
        selBg = c;
    }

    public void setSelectionForeground(TerminalColor c) {
        selFg = c;
    }

    @Override
    public @NotNull TerminalActionPresentation getSelectAllActionPresentation() {
        // Preserve the default action name (for consistency/localization)
        TerminalActionPresentation def = super.getSelectAllActionPresentation();
        String name = def.getName();

        boolean isMac = Environment.isMacOs();
        KeyStroke ks;
        if (isMac) {
            int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(); // maps to Cmd on macOS
            ks = KeyStroke.getKeyStroke(KeyEvent.VK_A, menuMask);
        } else {
            ks = KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        }
        return new TerminalActionPresentation(name, ks);
    }
}

/**
 * JediTerm-backed terminal panel. - Spawns the system shell in a PTY and connects it to a JediTerm terminal emulator. -
 * Properly handles ANSI/VT sequences, backspace, cursor movement, and colors. - Provides a simple header with a Close
 * button that triggers the supplied callback.
 */
public class TerminalPanel extends JPanel implements ThemeAware {

    private static final Logger logger = LogManager.getLogger(TerminalPanel.class);

    private final Runnable onClose;
    private final IConsoleIO console;

    private final @Nullable Path initialCwd;

    private @Nullable PtyProcess process;
    private @Nullable TtyConnector connector;
    private final @Nullable BrokkJediTermWidget widget;
    private final @Nullable MutableSettingsProvider terminalSettings;
    private final CompletableFuture<TerminalPanel> readyFuture = new CompletableFuture<>();

    /**
     * New constructor that optionally omits the built-in header and sets initial working directory.
     *
     * @param console console IO callback owner (usually Chrome)
     * @param onClose runnable to execute when the terminal wants to close
     * @param showHeader whether to show the internal header (close button); set false when the terminal is hosted
     *     inside a drawer that already provides a close control
     * @param initialCwd initial working directory for the terminal process, or null to use system default
     */
    public TerminalPanel(IConsoleIO console, Runnable onClose, boolean showHeader, @Nullable Path initialCwd) {
        super(new BorderLayout());
        this.onClose = onClose;
        this.console = console;
        this.initialCwd = initialCwd;

        var cmd = getShellCommand();

        if (showHeader) {
            var header = buildHeader(cmd[0]);
            add(header, BorderLayout.NORTH);
        }

        // Resolve initial colors to avoid hard-coded defaults in the provider
        var colors = resolveThemeColors();

        // Create the terminal widget with mutable settings for runtime theme changes
        terminalSettings = new MutableSettingsProvider(colors.bg(), colors.fg(), colors.selBg(), colors.selFg());
        widget = new BrokkJediTermWidget(terminalSettings);
        add(widget, BorderLayout.CENTER);

        // Apply theme colors to the widget components immediately
        applyTerminalColors();
        startProcessAsync(cmd);
    }

    private JPanel buildHeader(String shellCommand) {
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Create tab-like panel with shell name and close button
        var tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 0, 1, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(4, 12, 4, 8)));
        tabPanel.setOpaque(true);
        tabPanel.setBackground(UIManager.getColor("Panel.background"));

        var shellName = Path.of(shellCommand).getFileName().toString();
        var label = new JLabel(shellName, Icons.TERMINAL, SwingConstants.LEFT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));

        var closeButton = new MaterialButton("×");
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        closeButton.setPreferredSize(new Dimension(18, 18));
        closeButton.setMargin(new Insets(0, 0, 0, 0));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setToolTipText("Close terminal");
        var closeFg = UIManager.getColor("Button.close.foreground");
        closeButton.setForeground(closeFg != null ? closeFg : Color.GRAY);
        closeButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                closeButton.setForeground(Color.RED);
                closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                var closeFg = UIManager.getColor("Button.close.foreground");
                closeButton.setForeground(closeFg != null ? closeFg : Color.GRAY);
                closeButton.setCursor(Cursor.getDefaultCursor());
            }
        });
        closeButton.addActionListener(e -> {
            var result = console.showConfirmDialog(
                    "This will kill the terminal session.\nTo hide it, click the terminal icon on the right.",
                    "Close Terminal?",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (result == JOptionPane.YES_OPTION) {
                onClose.run();
            }
        });

        tabPanel.add(label);
        tabPanel.add(closeButton);

        // Capture button: capture selected text or entire terminal buffer into workspace
        var captureButton = new MaterialButton();
        captureButton.setIcon(Icons.CONTENT_CAPTURE);
        captureButton.setPreferredSize(new Dimension(60, 24));
        captureButton.setMargin(new Insets(0, 0, 0, 0));
        captureButton.setToolTipText(
                "<html><p width='280'>Capture the terminal's current output into a new text fragment in your workspace context. This action appends to your context and does not replace or update any previous terminal captures.</p></html>");
        captureButton.addActionListener(e -> {
            var w = widget;
            if (w == null) {
                console.systemNotify(
                        "No terminal available to capture", "Terminal Capture", JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (!(console instanceof Chrome c)) {
                // Should not happen in normal usage, but safe guard
                console.systemNotify(
                        "Cannot capture: ContextManager unavailable", "Terminal Capture", JOptionPane.WARNING_MESSAGE);
                return;
            }

            Point selStart = null;
            Point selEnd = null;
            BrokkJediTermPanel displayPanel = null;
            if (w.getTerminalDisplay() instanceof BrokkJediTermPanel display) {
                displayPanel = display;
                var selection = display.getSelection();
                if (selection != null) {
                    var s = selection.getStart();
                    var endPt = selection.getEnd();
                    selStart = new Point(s.x, s.y);
                    selEnd = new Point(endPt.x, endPt.y);
                }
            }

            CompletableFuture<String> future;
            if (selStart != null && selEnd != null && displayPanel != null) {
                final var finalDisplay = displayPanel;
                final var p1 = selStart;
                final var p2 = selEnd;
                future = c.getContextManager()
                        .submitBackgroundTask(
                                "Capturing terminal selection", () -> finalDisplay.getSelectionText(p1, p2));
            } else {
                if (displayPanel == null) {
                    console.systemNotify(
                            "No terminal buffer available to capture", "Terminal Capture", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                final var finalDisplay = displayPanel;
                future = c.getContextManager()
                        .submitBackgroundTask("Capturing terminal buffer", finalDisplay::getFullBufferText);
            }

            future.thenAcceptAsync(this::submitCapturedContent, SwingUtilities::invokeLater)
                    .exceptionally(ex -> {
                        logger.error("Error capturing terminal output", ex);
                        SwingUtilities.invokeLater(() -> console.toolError(
                                "Failed to capture terminal output: " + ex.getMessage(), "Terminal Capture Failed"));
                        return null;
                    });
        });

        panel.add(tabPanel, BorderLayout.WEST);
        panel.add(captureButton, BorderLayout.EAST);
        return panel;
    }

    private void submitCapturedContent(@Nullable String content) {
        if (content == null || content.isBlank()) {
            console.systemNotify(
                    "No terminal content available to capture", "Terminal Capture", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (console instanceof Chrome c) {
            c.getContextManager().submitContextTask(() -> {
                try {
                    c.getContextManager().addPastedTextFragment(trimTrailingFromLines(content));
                    SwingUtilities.invokeLater(() -> {
                        console.showNotification(
                                IConsoleIO.NotificationRole.INFO, "Terminal content captured to workspace");
                    });
                } catch (Exception ex) {
                    logger.error("Error adding terminal content to workspace", ex);
                    SwingUtilities.invokeLater(() -> {
                        console.toolError(
                                "Failed to add terminal content to workspace: " + ex.getMessage(),
                                "Terminal Capture Failed");
                    });
                }
            });
        }
    }

    private void startProcessAsync(String[] cmd) {
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, String> env = new HashMap<>(System.getenv());
                // Keep color support enabled; JediTerm will render ANSI correctly.
                env.putIfAbsent("TERM", "xterm-256color");
                // On macOS, set TERM_PROGRAM to help user configs detect Terminal.app-like environment
                if (Environment.isMacOs()) {
                    env.put("TERM_PROGRAM", "Apple_Terminal");
                }

                String cwd = (initialCwd != null) ? initialCwd.toString() : System.getProperty("user.dir");
                var newProcess = new PtyProcessBuilder(cmd)
                        .setDirectory(cwd)
                        .setEnvironment(env)
                        .start();
                var newConnector = new PtyProcessTtyConnector(newProcess, StandardCharsets.UTF_8);

                // Update fields and UI on EDT
                SwingUtilities.invokeLater(() -> {
                    process = newProcess;
                    connector = newConnector;
                    var w = widget;
                    if (w != null) {
                        w.setTtyConnector(newConnector);
                        w.start();
                        readyFuture.complete(this);

                        // On macOS, ask the shell to print an SGR reset so the terminal receives it on stdout
                        if (Environment.isMacOs()) {
                            try {
                                newConnector.write("printf '\\033[0m\\033[39;49m'; clear\r\n");
                            } catch (Exception e) {
                                logger.debug("Failed to write delayed SGR reset via printf: {}", e.getMessage(), e);
                            }
                        }
                    }
                    // Focus the terminal after startup
                    requestFocusInTerminal();
                });
            } catch (Exception e) {
                logger.error("Error starting terminal process", e);
                SwingUtilities.invokeLater(() -> {
                    try {
                        console.toolError("Error starting terminal: " + e.getMessage(), "Terminal Error");
                    } catch (Exception loggingException) {
                        if (logger.isErrorEnabled()) {
                            logger.error(
                                    "Failed displaying error: logging error - {}.  original error - {}",
                                    e.getMessage(),
                                    loggingException.getMessage());
                        }
                    }
                });
            }
        });
    }

    private boolean isPowerShellAvailable() {
        // "where" is a Windows command to locate files.
        try {
            ProcessBuilder pb = new ProcessBuilder("where", "powershell.exe");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            logger.warn("Could not determine if powershell is available, falling back to cmd.exe.", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private String[] getShellCommand() {
        if (isWindows()) {
            if (isPowerShellAvailable()) {
                return new String[] {"powershell.exe"};
            }
            return new String[] {"cmd.exe"};
        }
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            if (new File("/bin/zsh").exists()) {
                shell = "/bin/zsh";
            } else {
                shell = "/bin/bash";
            }
        }
        return new String[] {shell, "-l"};
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    public void requestFocusInTerminal() {
        var w = widget;
        if (w != null) {
            w.requestFocusInWindow();
        }
    }

    public void pasteText(String text) {
        var c = connector;
        if (c != null && !text.isEmpty()) {
            try {
                c.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                logger.error("Error pasting text into terminal: {}", ex.getMessage(), ex);
            }
        }
    }

    public boolean isReady() {
        return readyFuture.isDone();
    }

    public CompletableFuture<TerminalPanel> whenReady() {
        return readyFuture;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Refresh the entire component tree to apply theme changes
        SwingUtilities.updateComponentTreeUI(this);

        if (widget != null) {
            applyTerminalColors();
        }
    }

    private record ColorPair(Color bg, Color fg, Color selBg, Color selFg) {}

    private ColorPair resolveThemeColors() {
        Color bg = ThemeColors.getUIManagerColorDirect("Editor.background");
        if (bg == null) {
            bg = ThemeColors.getColor(ThemeColors.RSYNTAX_BACKGROUND);
        }

        Color fg = ThemeColors.getUIManagerColorDirect("*.foreground");
        if (fg == null) {
            fg = ThemeColors.getColor(ThemeColors.PLAIN_TEXT_FOREGROUND);
        }

        // Validate contrast for primary colors
        if (!isContrastSufficient(bg, fg)) {
            var pair = pickHighContrastPair(bg);
            bg = pair.bg();
            fg = pair.fg();
        }

        // Try getting selection colors from UIManager first
        Color selBg = ThemeColors.getUIManagerColorDirect("TextField.selectionBackground");
        if (selBg == null) {
            selBg = ThemeColors.getUIManagerColorDirect("*.selectionBackground");
        }

        Color selFg = ThemeColors.getUIManagerColorDirect("TextField.selectionForeground");
        if (selFg == null) {
            selFg = ThemeColors.getUIManagerColorDirect("*.selectionForeground");
        }

        // Fallback if selection colors are missing or have poor contrast with background
        if (selBg == null || !isContrastSufficient(bg, selBg)) {
            selBg = isDark(bg) ? bg.brighter() : bg.darker();
        }
        if (selFg == null || !isContrastSufficient(selBg, selFg)) {
            selFg = isDark(selBg) ? Color.WHITE : Color.BLACK;
        }

        return new ColorPair(bg, fg, selBg, selFg);
    }

    private boolean isContrastSufficient(Color c1, Color c2) {
        return Math.abs(getLuminance(c1) - getLuminance(c2)) > 0.15;
    }

    private ColorPair pickHighContrastPair(Color bg) {
        if (isDark(bg)) {
            return new ColorPair(Color.BLACK, Color.WHITE, Color.GRAY, Color.WHITE);
        } else {
            return new ColorPair(Color.WHITE, Color.BLACK, Color.LIGHT_GRAY, Color.BLACK);
        }
    }

    private boolean isDark(Color c) {
        return getLuminance(c) < 0.5;
    }

    private void applyTerminalColors() {
        var settings = terminalSettings;
        if (settings == null) {
            return;
        }

        var colors = resolveThemeColors();

        // Apply colors through JediTerm's settings system
        settings.setBackground(new TerminalColor(
                colors.bg().getRed(), colors.bg().getGreen(), colors.bg().getBlue()));
        settings.setForeground(new TerminalColor(
                colors.fg().getRed(), colors.fg().getGreen(), colors.fg().getBlue()));
        settings.setSelectionBackground(new TerminalColor(
                colors.selBg().getRed(),
                colors.selBg().getGreen(),
                colors.selBg().getBlue()));
        settings.setSelectionForeground(new TerminalColor(
                colors.selFg().getRed(),
                colors.selFg().getGreen(),
                colors.selFg().getBlue()));

        // Update Swing host components
        var w = widget;
        if (w != null) {
            try {
                var termPanel = w.getTerminalPanel();
                if (termPanel != null) {
                    termPanel.setBackground(colors.bg());
                    termPanel.setForeground(colors.fg());
                    termPanel.setOpaque(true);
                    termPanel.setCursorShape(CursorShape.BLINK_VERTICAL_BAR);
                }
                var display = w.getTerminalDisplay();
                if (display instanceof Component comp) {
                    comp.setBackground(colors.bg());
                    comp.setForeground(colors.fg());
                    if (comp instanceof JComponent jc) {
                        jc.setOpaque(true);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to apply theme colors to terminal components: {}", e.getMessage(), e);
            }
            w.repaint();
            w.revalidate();
        }
    }

    private double getLuminance(Color c) {
        // Simple relative luminance calculation
        return 0.2126 * (c.getRed() / 255.0) + 0.7152 * (c.getGreen() / 255.0) + 0.0722 * (c.getBlue() / 255.0);
    }

    private static String trimTrailingFromLines(String text) {
        return text.lines().map(s -> s.replaceAll("\\s+$", "")).collect(Collectors.joining("\n"));
    }

    public void dispose() {
        try {
            var c = connector;
            if (c != null) {
                try {
                    c.close();
                } catch (Exception e) {
                    logger.debug("Error closing TtyConnector: {}", e.getMessage(), e);
                }
            }
            var p = process;
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception e) {
                    logger.debug("Error destroying PTY process: {}", e.getMessage(), e);
                }
            }
        } finally {
            connector = null;
            process = null;
            var w = widget;
            if (w != null) {
                try {
                    w.close();
                } catch (Exception e) {
                    logger.debug("Error disposing terminal widget: {}", e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        // Do not call dispose() here, to allow the terminal process to survive when this panel is hidden.
    }

    /** Updates the terminal font size to the current project setting. */
    public void updateTerminalFontSize() {
        SwingUtilities.invokeLater(() -> {
            // Trigger repaint to apply the changes
            var w = widget;
            if (w != null) {
                w.updateFontAndResize();
            }
        });
    }
}
