package ai.brokk.gui.terminal;

import ai.brokk.IConsoleIO;
import ai.brokk.MainProject;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Settings provider that allows runtime modification of terminal colors. */
class MutableSettingsProvider extends DefaultSettingsProvider {
    private TerminalColor bg = TerminalColor.BLACK;
    private TerminalColor fg = TerminalColor.WHITE;

    // Selection colors: defaults chosen to be visible on dark background
    private TerminalColor selBg = new TerminalColor(60, 100, 170);
    private TerminalColor selFg = TerminalColor.WHITE;

    @Override
    public @NotNull TerminalColor getDefaultBackground() {
        return bg;
    }

    @Override
    public @NotNull TerminalColor getDefaultForeground() {
        return fg;
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

        // Create the terminal widget with mutable settings for runtime theme changes
        terminalSettings = new MutableSettingsProvider();
        widget = new BrokkJediTermWidget(terminalSettings);
        add(widget, BorderLayout.CENTER);

        // Apply initial theme to terminal based on current UI theme
        boolean dark = false;
        if (console instanceof Chrome c) {
            dark = c.getTheme().isDarkTheme();
        }
        applyTerminalColors(dark);
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
        captureButton.addActionListener(e -> SwingUtilities.invokeLater(() -> {
            try {
                var w = widget;
                if (w == null) {
                    console.systemNotify(
                            "No terminal available to capture", "Terminal Capture", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                var buffer = w.getTerminalTextBuffer();
                if (buffer == null) {
                    console.systemNotify(
                            "No terminal buffer available to capture", "Terminal Capture", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                var lines = new ArrayList<String>();
                for (int i = 0; i < buffer.getHeight(); i++) {
                    var line = buffer.getLine(i);
                    lines.add(line.getText());
                }

                String content =
                        lines.stream().map(s -> s.replaceAll("\\s+$", "")).collect(Collectors.joining("\n"));

                if (content.isBlank()) {
                    console.systemNotify(
                            "No terminal content available to capture",
                            "Terminal Capture",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }

                if (console instanceof Chrome c) {
                    c.getContextManager().submitContextTask(() -> {
                        try {
                            c.getContextManager().addPastedTextFragment(content);
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
            } catch (Exception ex) {
                logger.error("Error capturing terminal output", ex);
                console.toolError("Failed to capture terminal output: " + ex.getMessage(), "Terminal Capture Failed");
            }
        }));

        panel.add(tabPanel, BorderLayout.WEST);
        panel.add(captureButton, BorderLayout.EAST);
        return panel;
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
                            } catch (Exception ignore2) {
                                logger.debug("Failed to write delayed SGR reset via printf", ignore2);
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
                logger.debug("Error pasting text into terminal", ex);
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
        
        boolean dark = guiTheme.isDarkTheme();
        if (widget != null) {
            applyTerminalColors(dark);
        }
    }

    private void applyTerminalColors(boolean dark) {
        var settings = terminalSettings;
        if (settings == null) {
            return;
        }

        // Define terminal colors based on theme
        TerminalColor bg = dark ? new TerminalColor(30, 30, 30) : new TerminalColor(255, 255, 255);
        TerminalColor fg = dark ? new TerminalColor(221, 221, 221) : new TerminalColor(0, 0, 0);
        // Selection colors: explicit background/foreground to ensure visibility
        TerminalColor selBg = dark ? new TerminalColor(60, 100, 170) : new TerminalColor(173, 214, 255);
        TerminalColor selFg = dark ? new TerminalColor(255, 255, 255) : new TerminalColor(0, 0, 0);

        // Apply colors through JediTerm's settings system
        settings.setBackground(bg);
        settings.setForeground(fg);
        settings.setSelectionBackground(selBg);
        settings.setSelectionForeground(selFg);

        // Trigger repaint to apply the changes
        var w = widget;
        if (w != null) {
            // needed to force the color update
            w.getTerminalPanel().setCursorShape(CursorShape.BLINK_VERTICAL_BAR);
            w.repaint();
        }
    }

    public void dispose() {
        try {
            var c = connector;
            if (c != null) {
                try {
                    c.close();
                } catch (Exception e) {
                    logger.debug("Error closing TtyConnector", e);
                }
            }
            var p = process;
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception e) {
                    logger.debug("Error destroying PTY process", e);
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
                    logger.debug("Error disposing terminal widget", e);
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
