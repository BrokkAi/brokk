package io.github.jbellis.brokk.gui.terminal;

import com.jediterm.pty.PtyProcessTtyConnector;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Settings provider that allows runtime modification of terminal colors. */
class MutableSettingsProvider extends DefaultSettingsProvider {
    private TerminalColor bg = TerminalColor.BLACK;
    private TerminalColor fg = TerminalColor.WHITE;

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

    public void setBackground(TerminalColor c) {
        bg = c;
    }

    public void setForeground(TerminalColor c) {
        fg = c;
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
        try {
            startProcess(cmd);
        } catch (Exception e) {
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
        }
    }

    private JPanel buildHeader(String shellCommand) {
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        var shellName = Path.of(shellCommand).getFileName().toString();
        var label = new JLabel(shellName, Icons.TERMINAL, SwingConstants.LEFT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

        var closeButton = new JButton("×");
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        closeButton.setPreferredSize(new Dimension(24, 24));
        closeButton.setMargin(new Insets(0, 0, 0, 0));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setToolTipText("Close terminal");
        closeButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                closeButton.setForeground(Color.RED);
                closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                closeButton.setForeground(null);
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

        var tabHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabHeader.setOpaque(false);
        tabHeader.add(label);
        tabHeader.add(closeButton);

        panel.add(tabHeader, BorderLayout.WEST);
        return panel;
    }

    private void startProcess(String[] cmd) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        // Keep color support enabled; JediTerm will render ANSI correctly.
        env.putIfAbsent("TERM", "xterm-256color");

        String cwd = (initialCwd != null) ? initialCwd.toString() : System.getProperty("user.dir");
        process =
                new PtyProcessBuilder(cmd).setDirectory(cwd).setEnvironment(env).start();

        var p = process;

        connector = new PtyProcessTtyConnector(p, StandardCharsets.UTF_8);
        var w = widget;
        if (w != null) {
            w.setTtyConnector(connector);
            w.start();
        }

        // Focus the terminal after startup
        SwingUtilities.invokeLater(this::requestFocusInTerminal);
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
            if (new java.io.File("/bin/zsh").exists()) {
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

    @Override
    public void applyTheme(GuiTheme guiTheme) {
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

        // Apply colors through JediTerm's settings system
        settings.setBackground(bg);
        settings.setForeground(fg);

        // Trigger repaint to apply the changes
        var w = widget;
        if (w != null) {
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
