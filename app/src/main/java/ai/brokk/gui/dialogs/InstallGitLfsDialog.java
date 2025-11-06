package ai.brokk.gui.dialogs;

import ai.brokk.gui.Chrome;
import ai.brokk.git.GitRepo;
import ai.brokk.util.Environment;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Modal dialog shown when a worktree creation fails due to missing git-lfs. It provides:
 *
 *  - Clear explanation that the system git could not find the git-lfs binary.
 *  - Action buttons:
 *      * Install git-lfs (opens installer page and shows a one-line install command for the current platform
 *        with a Copy and an optional Open Terminal button)
 *      * Open Git settings (delegates to SettingsDialog)
 *      * Retry (returns Result.RETRY) and Cancel (returns Result.CANCEL)
 *  - Expandable "More details" section that contains the original command output and captured provenance:
 *      git --version, git lfs version (if any), git executable path, attempted command and working directory.
 */
public class InstallGitLfsDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(InstallGitLfsDialog.class);

    public enum Result {
        RETRY,
        CANCEL
    }

    private Result result = Result.CANCEL;

    private final Chrome chrome;
    private final GitRepo.GitLfsMissingException exception;

    private InstallGitLfsDialog(Chrome chrome, GitRepo.GitLfsMissingException exception) {
        super(chrome.getFrame(), "Install Git LFS", true);
        this.chrome = chrome;
        this.exception = exception;
        initUi();
        pack();
        setResizable(false);
        setLocationRelativeTo(chrome.getFrame());
    }

    /**
     * Show the dialog and return the user's choice.
     *
     * @param chrome host chrome
     * @param ex the GitLfsMissingException with diagnostics to display
     * @return Result.RETRY if user chose Retry, otherwise Result.CANCEL
     */
    public static Result showDialog(Chrome chrome, GitRepo.GitLfsMissingException ex) {
        var dlg = new InstallGitLfsDialog(chrome, ex);

        // Show on EDT and block until closed
        if (SwingUtilities.isEventDispatchThread()) {
            dlg.setVisible(true);
        } else {
            try {
                SwingUtilities.invokeAndWait(() -> dlg.setVisible(true));
            } catch (Exception e) {
                logger.warn("Failed to show InstallGitLfsDialog on EDT", e);
                dlg.setVisible(true);
            }
        }
        return dlg.result;
    }

    private void initUi() {
        var content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Primary message
        JLabel title = new JLabel(
                "<html><b>Worktree creation failed because the system git could not find the git-lfs binary.</b></html>");
        content.add(title, BorderLayout.NORTH);

        // Center panel with install actions and command snippet
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

        // Install row
        JPanel installRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton installBtn = new JButton("Install git-lfs");
        installBtn.setToolTipText("Open git-lfs installer page in your browser and copy the install command.");
        installRow.add(installBtn);

        // Copy and Open Terminal will operate on the platform-specific command
        JButton copyBtn = new JButton("Copy command");
        installRow.add(copyBtn);

        JButton openTerminalBtn = new JButton("Open Terminal");
        installRow.add(openTerminalBtn);

        center.add(installRow);

        // One-line command display (non-editable)
        JTextArea commandArea = new JTextArea(getPlatformInstallCommand());
        commandArea.setEditable(false);
        commandArea.setLineWrap(true);
        commandArea.setWrapStyleWord(true);
        commandArea.setBackground(getBackground());
        commandArea.setBorder(BorderFactory.createTitledBorder("One-line install command for your platform"));
        commandArea.setRows(3);
        commandArea.setMaximumSize(new Dimension(600, 80));
        center.add(commandArea);

        content.add(center, BorderLayout.CENTER);

        // Buttons at bottom: Open Git settings, Retry, Cancel
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton openSettingsBtn = new JButton("Open Git settings");
        JButton retryBtn = new JButton("Retry");
        JButton cancelBtn = new JButton("Cancel");

        bottom.add(openSettingsBtn);
        bottom.add(retryBtn);
        bottom.add(cancelBtn);

        content.add(bottom, BorderLayout.SOUTH);

        // More details expandable section
        String details = buildDetailsText();
        JTextArea detailsArea = new JTextArea(details);
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(false);
        JScrollPane detailsScroll = new JScrollPane(detailsArea);
        detailsScroll.setPreferredSize(new Dimension(700, 240));
        detailsScroll.setVisible(false);

        JButton toggleDetailsBtn = new JButton("More details...");
        toggleDetailsBtn.addActionListener(e -> {
            boolean visible = !detailsScroll.isVisible();
            detailsScroll.setVisible(visible);
            toggleDetailsBtn.setText(visible ? "Hide details" : "More details...");
            pack(); // resize dialog to accommodate details
        });

        JPanel detailsPanel = new JPanel(new BorderLayout());
        detailsPanel.add(toggleDetailsBtn, BorderLayout.NORTH);
        detailsPanel.add(detailsScroll, BorderLayout.CENTER);

        content.add(detailsPanel, BorderLayout.AFTER_LAST_LINE);

        setContentPane(content);

        // Action listeners
        installBtn.addActionListener(e -> {
            openInstallerAndCopy(commandArea.getText());
        });

        copyBtn.addActionListener(e -> {
            copyToClipboard(commandArea.getText(), "Install command copied to clipboard");
        });

        openTerminalBtn.addActionListener(e -> {
            openTerminalAtWorkingDir(exception.getWorkingDir(), this);
        });

        openSettingsBtn.addActionListener(e -> {
            // Open Git/GitHub settings tab
            SettingsDialog.showSettingsDialog(chrome, SettingsDialog.GITHUB_SETTINGS_TAB_NAME);
        });

        retryBtn.addActionListener((ActionEvent e) -> {
            this.result = Result.RETRY;
            dispose();
        });

        cancelBtn.addActionListener((ActionEvent e) -> {
            this.result = Result.CANCEL;
            dispose();
        });
    }

    private void openInstallerAndCopy(String cmd) {
        // Open the most relevant installer page for the user's platform and copy the command for convenience.
        String url = getPlatformInstallerUrl();
        try {
            Environment.openInBrowser(url, chrome.getFrame());
        } catch (Exception ex) {
            // Environment.openInBrowser already shows a dialog on failure; still log
            logger.warn("Failed to open browser for git-lfs installer", ex);
        }
        copyToClipboard(cmd, "Install command copied to clipboard");
    }

    private void copyToClipboard(String text, String successMessage) {
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
            JOptionPane.showMessageDialog(this, successMessage, "Copied", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            logger.warn("Failed to copy to clipboard", e);
            JOptionPane.showMessageDialog(this, "Failed to copy to clipboard: " + e.getMessage(), "Copy failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Returns a one-line (or short multi-line) install guidance string suitable for display/copying.
     * We prefer to present multiple common options when appropriate (e.g. Chocolatey vs Scoop on Windows,
     * apt vs dnf on Linux) so the user can pick the one matching their system.
     */
    private String getPlatformInstallCommand() {
        if (Environment.isMacOs()) {
            return "brew install git-lfs && git lfs install";
        } else if (Environment.isWindows()) {
            return String.join("\n",
                    "Recommended (Chocolatey):",
                    "  choco install git-lfs && git lfs install",
                    "",
                    "Alternative (Scoop):",
                    "  scoop install git-lfs && git lfs install",
                    "",
                    "Or download the installer from: https://github.com/git-lfs/git-lfs/releases");
        } else if (Environment.isLinux()) {
            // Provide common distro package manager commands for convenience
            return String.join("\n",
                    "Debian / Ubuntu (apt):",
                    "  curl -s https://packagecloud.io/install/repositories/github/git-lfs/script.deb.sh | sudo bash && sudo apt-get update && sudo apt-get install git-lfs && git lfs install",
                    "",
                    "Fedora / RHEL (dnf):",
                    "  sudo dnf install git-lfs && git lfs install",
                    "",
                    "Generic (release binaries / installers):",
                    "  See https://github.com/git-lfs/git-lfs/releases for prebuilt packages and installers");
        } else {
            // Generic fallback
            return "See https://git-lfs.github.com/ or https://github.com/git-lfs/git-lfs/releases for installation instructions";
        }
    }

    /**
     * Returns the most relevant installer/landing page URL for the current platform. This is used when the user
     * clicks 'Install git-lfs' so we open a targeted page in the browser.
     */
    private String getPlatformInstallerUrl() {
        if (Environment.isWindows()) {
            // Central project page / releases for Windows users (also links installers)
            return "https://git-lfs.github.com/";
        } else if (Environment.isMacOs()) {
            // Homebrew formula page is useful for macOS users
            return "https://formulae.brew.sh/formula/git-lfs";
        } else if (Environment.isLinux()) {
            // PackageCloud installer script docs are useful for many distros
            return "https://packagecloud.io/github/git-lfs/install";
        } else {
            // Generic fallback
            return "https://git-lfs.github.com/";
        }
    }

    /**
     * Create a preview of the given output: first maxLines lines, truncated to maxChars if needed.
     * This mirrors the preview logic used in GitRepoWorktrees so UI and logs can be correlated.
     */
    private static String previewOutput(@Nullable String output, int maxLines, int maxChars) {
        if (output == null || output.isEmpty()) return "<no output>";
        var lines = java.util.Arrays.asList(output.split("\\R"));
        boolean moreLines = lines.size() > maxLines;
        String joined = String.join("\n", lines.subList(0, Math.min(lines.size(), maxLines)));
        if (joined.length() > maxChars) {
            return joined.substring(0, maxChars) + "\n...(truncated)";
        } else if (moreLines) {
            return joined + "\n...(truncated)";
        } else {
            return joined;
        }
    }

    private String buildDetailsText() {
        StringBuilder sb = new StringBuilder();

        // Use the same preview parameters as the logger
        final int PREVIEW_MAX_LINES = 50;
        final int PREVIEW_MAX_CHARS = 4000;

        String output = exception.getOutput();
        String outputPreview = previewOutput(output, PREVIEW_MAX_LINES, PREVIEW_MAX_CHARS);

        // Diagnostic summary (concise) — intended to match the structured WARN log for correlation.
        sb.append("Diagnostic summary (first ").append(PREVIEW_MAX_LINES).append(" lines):\n");
        sb.append("Git version: ")
                .append(exception.getGitVersion() == null || exception.getGitVersion().isEmpty()
                        ? "<unknown>"
                        : exception.getGitVersion())
                .append("\n");
        sb.append("Git LFS version: ")
                .append(exception.getLfsVersion() == null || exception.getLfsVersion().isEmpty()
                        ? "<not available>"
                        : exception.getLfsVersion())
                .append("\n");
        sb.append("Git executable path: ")
                .append(exception.getGitPath() == null || exception.getGitPath().isEmpty()
                        ? "<unknown>"
                        : exception.getGitPath())
                .append("\n");
        sb.append("Attempted command: ").append(exception.getCommand()).append("\n");
        sb.append("Repository top-level: ").append(exception.getWorkingDir()).append("\n\n");

        sb.append("Output preview:\n");
        sb.append(outputPreview).append("\n\n");

        // Full output and provenance follow (keep existing detail)
        sb.append("Error output from git command (full):\n");
        sb.append(output == null ? "<no output>" : output).append("\n\n");

        sb.append("Provenance:\n");
        sb.append("Attempted command: ").append(exception.getCommand()).append("\n");
        sb.append("Working directory: ").append(exception.getWorkingDir()).append("\n");
        sb.append("Git version: ")
                .append(exception.getGitVersion() == null || exception.getGitVersion().isEmpty()
                        ? "<unknown>"
                        : exception.getGitVersion())
                .append("\n");
        String lfs = exception.getLfsVersion();
        sb.append("Git LFS version: ").append(lfs == null || lfs.isEmpty() ? "<not available>" : lfs).append("\n");
        sb.append("Git executable path: ")
                .append(exception.getGitPath() == null || exception.getGitPath().isEmpty()
                        ? "<unknown>"
                        : exception.getGitPath())
                .append("\n");

        return sb.toString();
    }

    /**
     * Attempts to open a system terminal at the specified working directory.
     * If this fails, an informative message dialog is shown.
     */
    private void openTerminalAtWorkingDir(Path wd, Component parent) {
        if (wd == null) {
            JOptionPane.showMessageDialog(parent, "Working directory is unknown. Cannot open terminal.", "Open Terminal",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            if (Environment.isWindows()) {
                // Use start to create a new console window and run cmd in that directory
                String cmd = "cmd.exe";
                String arg = String.format("/K cd /d \"%s\"", wd.toAbsolutePath().toString());
                new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", arg).start();
            } else if (Environment.isMacOs()) {
                // macOS: open Terminal app in directory
                new ProcessBuilder("open", "-a", "Terminal", wd.toAbsolutePath().toString()).start();
            } else if (Environment.isLinux()) {
                // Try common terminal emulators in order
                String dir = wd.toAbsolutePath().toString();
                String[][] tryCmds = new String[][] {
                        {"x-terminal-emulator", "-e", "bash", "-c", "cd '" + dir + "'; exec bash"},
                        {"gnome-terminal", "--", "bash", "-c", "cd '" + dir + "'; exec bash"},
                        {"konsole", "-e", "bash", "-c", "cd '" + dir + "'; exec bash"},
                        {"xterm", "-e", "bash", "-c", "cd '" + dir + "'; exec bash"},
                };
                boolean started = false;
                for (var tc : tryCmds) {
                    try {
                        new ProcessBuilder(tc).start();
                        started = true;
                        break;
                    } catch (IOException ignored) {
                    }
                }
                if (!started) {
                    // fallback: open file manager instead
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(wd.toFile());
                    } else {
                        throw new IOException("No terminal emulator could be started");
                    }
                }
            } else {
                throw new UnsupportedOperationException("Unsupported OS for opening terminal");
            }
        } catch (Exception e) {
            logger.warn("Failed to open terminal at {}", wd, e);
            JOptionPane.showMessageDialog(parent,
                    "Unable to open a terminal automatically. You can run the following command manually:\n\n"
                            + getPlatformInstallCommand(),
                    "Open Terminal failed",
                    JOptionPane.WARNING_MESSAGE);
        }
    }
}
