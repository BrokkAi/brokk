package ai.brokk.gui;

import ai.brokk.Brokk;
import ai.brokk.Completions;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.MainProject;
import ai.brokk.Service;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.dialogs.AboutDialog;
import ai.brokk.gui.dialogs.BlitzForgeDialog;
import ai.brokk.gui.dialogs.FeedbackDialog;
import ai.brokk.gui.dialogs.SessionsDialog;
import ai.brokk.gui.dialogs.SettingsDialog;
import ai.brokk.gui.git.GitCommitTab;
import ai.brokk.gui.git.GitIssuesTab;
import ai.brokk.gui.git.GitLogTab;
import ai.brokk.gui.git.GitWorktreeTab;
import ai.brokk.gui.terminal.TerminalPanel;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.issues.IssueProviderType;
import ai.brokk.util.Environment;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.awt.Desktop;
import java.awt.desktop.PreferencesEvent;
import java.awt.desktop.PreferencesHandler;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import org.jetbrains.annotations.Nullable;

public class MenuBar {
    /**
     * Creates and shows a modeless dialog with the given title and content.
     * A new dialog is created on each call (no reuse/tracking).
     *
     * @param chrome the Chrome instance providing the owner frame and theme
     * @param title the dialog title
     * @param content the component to display in the dialog
     * @param onClose optional callback to run when the dialog is closed
     */
    private static void showDialog(Chrome chrome, String title, JComponent content, @Nullable Runnable onClose) {
        Runnable task = () -> {
            // Create new modeless dialog
            JDialog dialog = new JDialog(chrome.getFrame(), title, false);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.getContentPane().add(content);

            // Calculate size with reasonable minimums
            Dimension prefSize = content.getPreferredSize();
            int width = Math.max(prefSize.width, 600);
            int height = Math.max(prefSize.height, 300);
            dialog.setSize(width, height);

            // Center relative to main frame
            JFrame mainFrame = chrome.getFrame();
            int x = mainFrame.getX() + (mainFrame.getWidth() - width) / 2;
            int y = mainFrame.getY() + (mainFrame.getHeight() - height) / 2;
            dialog.setLocation(x, y);

            // Apply application icon
            Chrome.applyIcon(dialog);

            // Apply theme if content supports it
            if (content instanceof ThemeAware themeAware) {
                themeAware.applyTheme(chrome.getTheme());
            }

            // Add window listener for cleanup and callback
            final AtomicBoolean cleanupInvoked = new AtomicBoolean(false);
            dialog.addWindowListener(new WindowAdapter() {
                private void runCleanup() {
                    if (!cleanupInvoked.compareAndSet(false, true)) {
                        return;
                    }

                    // If an explicit onClose handler was provided (e.g. to dispose resources like TerminalPanel),
                    // run it. Otherwise best-effort cleanup for known resource-holding panels.
                    if (onClose != null) {
                        try {
                            onClose.run();
                        } catch (Throwable ignored) {
                        }
                        return;
                    }

                    // Best-effort cleanup for known dialog-backed panels that manage OS resources.
                    // Avoid calling generic dispose/close on arbitrary components to prevent
                    // accidentally closing shared/reused components owned elsewhere.
                    try {
                        if (content instanceof TerminalPanel) {
                            try {
                                ((TerminalPanel) content).dispose();
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void windowClosing(WindowEvent e) {
                    runCleanup();
                }

                @Override
                public void windowClosed(WindowEvent e) {
                    runCleanup();
                }
            });

            // Make visible
            dialog.setVisible(true);
        };

        // Ensure execution on EDT
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * Builds the menu bar
     *
     * @param chrome
     */
    static JMenuBar buildMenuBar(Chrome chrome) {
        var menuBar = new JMenuBar();

        // File menu
        var fileMenu = new JMenu("File");

        var openProjectItem = new JMenuItem("Open Project...");
        openProjectItem.addActionListener(e -> Brokk.promptAndOpenProject(chrome.frame)); // No need to block on EDT
        fileMenu.add(openProjectItem);

        JMenuItem reopenProjectItem;
        String projectName = chrome.getProject().getRoot().getFileName().toString();
        reopenProjectItem = new JMenuItem("Reopen `%s`".formatted(projectName));
        reopenProjectItem.addActionListener(e -> {
            var currentPath = chrome.getProject().getRoot();
            Brokk.reOpenProject(currentPath);
        });
        reopenProjectItem.setEnabled(true);
        fileMenu.add(reopenProjectItem);

        var recentProjectsMenu = new JMenu("Recent Projects");
        fileMenu.add(recentProjectsMenu);
        recentProjectsMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                rebuildRecentProjectsMenu(recentProjectsMenu);
            }

            @Override
            public void menuDeselected(MenuEvent e) {
                // No action needed
            }

            @Override
            public void menuCanceled(MenuEvent e) {
                // No action needed
            }
        });

        var settingsItem = new JMenuItem("Settings...");
        settingsItem.addActionListener(e -> {
            openSettingsDialog(chrome);
        });

        // Use platform conventions on macOS: Preferences live in the application menu.
        // Also ensure Cmd+, opens Settings as a fallback by registering a key binding.
        boolean isMac = Environment.instance.isMacOs();
        // Accelerator uses current binding; action also available via Chrome root pane binding
        settingsItem.setAccelerator(GlobalUiSettings.getKeybinding(
                "global.openSettings",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_COMMA, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx())));

        if (isMac) {
            // Ensure Cmd+, opens settings even if the system does not dispatch the shortcut to the handler.
            var rootPane = chrome.getFrame().getRootPane();
            var im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            var am = rootPane.getActionMap();
            var ks = KeyStroke.getKeyStroke(
                    KeyEvent.VK_COMMA, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
            im.put(ks, "open-settings");
            am.put("open-settings", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    SwingUtilities.invokeLater(() -> openSettingsDialog(chrome));
                }
            });
        } else {
            // Non-macOS: place Settings in File menu as before.
            fileMenu.add(settingsItem);
        }

        // Exit menu item (Cmd/Ctrl+Q)
        var exitItem = new JMenuItem("Exit");
        exitItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_Q, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        exitItem.addActionListener(e -> {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Exiting Brokk...");
            Thread.ofPlatform().start(Brokk::exit);
        });
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        // Edit menu
        var editMenu = new JMenu("Edit");

        JMenuItem undoItem;
        JMenuItem redoItem;

        undoItem = new JMenuItem(chrome.getGlobalUndoAction());
        redoItem = new JMenuItem(chrome.getGlobalRedoAction());

        undoItem.setText("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(undoItem);

        redoItem.setText("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        editMenu.add(redoItem);

        editMenu.addSeparator();

        JMenuItem copyMenuItem;
        JMenuItem pasteMenuItem;

        copyMenuItem = new JMenuItem(chrome.getGlobalCopyAction());
        pasteMenuItem = new JMenuItem(chrome.getGlobalPasteAction());

        copyMenuItem.setText("Copy");
        copyMenuItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(copyMenuItem);

        pasteMenuItem.setText("Paste");
        pasteMenuItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(pasteMenuItem);

        menuBar.add(editMenu);

        // Session menu
        var sessionMenu = new JMenu("Session");

        var newSessionItem = new JMenuItem("New Session");
        newSessionItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        newSessionItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.getContextManager()
                    .createSessionAsync(ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> chrome.getProject().getMainProject().sessionsListChanged());
        }));
        sessionMenu.add(newSessionItem);

        var newSessionCopyWorkspaceItem = new JMenuItem("New + Copy Context");
        newSessionCopyWorkspaceItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        newSessionCopyWorkspaceItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.getContextManager()
                    .createSessionFromContextAsync(
                            chrome.getContextManager().topContext(), ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> chrome.getProject().getMainProject().sessionsListChanged());
        }));
        sessionMenu.add(newSessionCopyWorkspaceItem);

        var renameSessionItem = new JMenuItem("Rename Session");
        renameSessionItem.addActionListener(e -> {
            SessionsDialog.renameCurrentSession(chrome.frame, chrome, chrome.getContextManager());
        });
        sessionMenu.add(renameSessionItem);

        var deleteSessionItem = new JMenuItem("Delete Session");
        deleteSessionItem.addActionListener(e -> {
            SessionsDialog.deleteCurrentSession(chrome.frame, chrome, chrome.getContextManager());
        });
        sessionMenu.add(deleteSessionItem);

        sessionMenu.addSeparator();

        var manageSessionsItem = new JMenuItem("Manage Sessions...");
        manageSessionsItem.addActionListener(e -> {
            var dialog = new SessionsDialog(chrome, chrome.getContextManager());
            dialog.setVisible(true);
        });
        sessionMenu.add(manageSessionsItem);

        menuBar.add(sessionMenu);

        // Context menu
        var contextMenu = new JMenu("Context");

        var refreshItem = new JMenuItem("Refresh Code Intelligence");
        refreshItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.contextManager.requestRebuild();
            chrome.showNotification(
                    IConsoleIO.NotificationRole.INFO, "Code intelligence will refresh in the background");
        }));
        refreshItem.setEnabled(true);
        contextMenu.add(refreshItem);

        contextMenu.addSeparator();

        var attachContextItem = new JMenuItem("Attach ...");
        attachContextItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_E, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        attachContextItem.addActionListener(e -> {
            chrome.getContextPanel().attachContextViaDialog();
        });
        attachContextItem.setEnabled(true);
        contextMenu.add(attachContextItem);

        var summarizeContextItem = new JMenuItem("Summarize ...");
        summarizeContextItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_I, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        summarizeContextItem.addActionListener(e -> {
            chrome.getContextPanel().attachContextViaDialog(true);
        });
        contextMenu.add(summarizeContextItem);

        // Keep enabled state in sync with analyzer readiness
        contextMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                summarizeContextItem.setEnabled(
                        chrome.getContextManager().getAnalyzerWrapper().isReady());
            }

            @Override
            public void menuDeselected(MenuEvent e) {
                // No action needed
            }

            @Override
            public void menuCanceled(MenuEvent e) {
                // No action needed
            }
        });

        var attachExternalItem = new JMenuItem("Attach Non-Project Files...");
        attachExternalItem.addActionListener(e -> {
            var cm = chrome.getContextManager();
            var project = cm.getProject();
            SwingUtilities.invokeLater(() -> {
                var fileChooser = new JFileChooser();
                fileChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                fileChooser.setMultiSelectionEnabled(true);
                fileChooser.setDialogTitle("Attach Non-Project Files");

                var returnValue = fileChooser.showOpenDialog(chrome.getFrame());

                if (returnValue == JFileChooser.APPROVE_OPTION) {
                    var selectedFiles = fileChooser.getSelectedFiles();
                    if (selectedFiles.length == 0) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No files or folders selected.");
                        return;
                    }

                    cm.submitContextTask(() -> {
                        Set<Path> pathsToAttach = new HashSet<>();
                        for (File file : selectedFiles) {
                            Path startPath = file.toPath();
                            if (Files.isRegularFile(startPath)) {
                                pathsToAttach.add(startPath);
                            } else if (Files.isDirectory(startPath)) {
                                try (Stream<Path> walk = Files.walk(startPath, FileVisitOption.FOLLOW_LINKS)) {
                                    walk.filter(Files::isRegularFile).forEach(pathsToAttach::add);
                                } catch (IOException ex) {
                                    chrome.toolError("Error reading directory " + startPath + ": " + ex.getMessage());
                                }
                            }
                        }

                        if (pathsToAttach.isEmpty()) {
                            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No files found to attach.");
                            return;
                        }

                        var projectRoot = project.getRoot();
                        List<ContextFragment.PathFragment> fragments = new ArrayList<>();
                        for (Path p : pathsToAttach) {
                            BrokkFile bf = Completions.maybeExternalFile(
                                    projectRoot, p.toAbsolutePath().normalize().toString());
                            var pathFrag = ContextFragment.toPathFragment(bf, cm);
                            fragments.add(pathFrag);
                        }
                        cm.addPathFragments(fragments);
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO, "Attached " + fragments.size() + " files.");
                    });
                } else {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "File attachment cancelled.");
                }
            });
        });
        attachExternalItem.setEnabled(true);
        contextMenu.add(attachExternalItem);

        contextMenu.addSeparator();

        var compressTaskHistoryItem = new JMenuItem("Compress Task History");
        compressTaskHistoryItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_R, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        compressTaskHistoryItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.getContextManager().submitContextTask(() -> {
                chrome.getContextManager().compressHistoryAsync();
            });
        }));
        compressTaskHistoryItem.setEnabled(true);
        contextMenu.add(compressTaskHistoryItem);

        // Clear Task History (Cmd/Ctrl+P)
        var clearTaskHistoryItem = new JMenuItem("Clear Task History");
        clearTaskHistoryItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        clearTaskHistoryItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.getContextManager()
                    .submitContextTask(() -> chrome.getContextManager().clearHistory());
        }));
        clearTaskHistoryItem.setEnabled(true);
        contextMenu.add(clearTaskHistoryItem);

        contextMenu.addSeparator();

        var dropAllItem = new JMenuItem("Drop All");
        dropAllItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        dropAllItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.getContextManager().submitContextTask(() -> {
                chrome.getContextPanel().performContextActionAsync(WorkspacePanel.ContextAction.DROP, List.of());
            });
        }));
        dropAllItem.setEnabled(true);
        contextMenu.add(dropAllItem);

        // Store reference in WorkspacePanel for dynamic state updates
        chrome.getContextPanel().setDropAllMenuItem(dropAllItem);

        menuBar.add(contextMenu);

        // Tools menu
        var toolsMenu = new JMenu("Tools");
        toolsMenu.setEnabled(true);

        var upgradeAgentItem = new JMenuItem("BlitzForge...");
        upgradeAgentItem.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                var dialog = new BlitzForgeDialog(chrome.getFrame(), chrome);
                dialog.setVisible(true);
            });
        });
        upgradeAgentItem.setEnabled(true);
        toolsMenu.add(upgradeAgentItem);

        // Issues
        var issuesItem = new JMenuItem("Issues");
        issuesItem.addActionListener(e -> {
            var content = new GitIssuesTab(chrome, chrome.getContextManager());
            showDialog(chrome, "Issues", content, null);
        });
        toolsMenu.add(issuesItem);

        // Terminal (create a fresh terminal panel with proper cleanup)
        var terminalItem = new JMenuItem("Terminal");
        terminalItem.addActionListener(e -> {
            var terminalPanel = new TerminalPanel(
                    chrome, () -> {}, true, chrome.getProject().getRoot());
            showDialog(chrome, "Terminal", terminalPanel, terminalPanel::dispose);
        });
        toolsMenu.add(terminalItem);

        // Git submenu
        var gitMenu = new JMenu("Git");

        // Pull Requests
        var prsItem = new JMenuItem("Pull Requests");
        prsItem.addActionListener(e -> {
            var existing = chrome.getPullRequestsPanel();
            if (existing != null) {
                showDialog(chrome, "Pull Requests", existing, null);
            } else {
                var fallback = new JPanel(new BorderLayout());
                fallback.add(new JLabel("Pull Requests not available for this project."), BorderLayout.CENTER);
                showDialog(chrome, "Pull Requests", fallback, null);
            }
        });
        gitMenu.add(prsItem);

        // Log (Git Log)
        var logItem = new JMenuItem("Log");
        logItem.addActionListener(e -> {
            var gitLogTab = new GitLogTab(chrome, chrome.getContextManager());
            gitLogTab.setPreferredSize(new Dimension(1000, 700));
            showDialog(chrome, "Log", gitLogTab, null);
        });
        gitMenu.add(logItem);

        // Changes (Git Commit tab)
        var commitItem = new JMenuItem("Changes");
        commitItem.addActionListener(e -> {
            var content = new GitCommitTab(chrome, chrome.getContextManager());
            // Start an initial refresh so the dialog is populated with repository status
            content.updateCommitPanel();
            showDialog(chrome, "Changes", content, null);
        });
        gitMenu.add(commitItem);

        // Worktrees
        var worktreesItem = new JMenuItem("Worktrees");
        worktreesItem.addActionListener(e -> {
            var content = new GitWorktreeTab(chrome, chrome.getContextManager());
            showDialog(chrome, "Worktrees", content, null);
        });
        gitMenu.add(worktreesItem);

        toolsMenu.add(gitMenu);

        // Open Output in New Window
        var openOutputItem = new JMenuItem("Open Output in New Window");
        openOutputItem.addActionListener(e -> SwingUtilities.invokeLater(() -> {
            chrome.getHistoryOutputPanel().openOutputInNewWindow();
        }));
        toolsMenu.add(openOutputItem);

        // Let Chrome manage BlitzForge item’s enabled state during long-running actions
        chrome.setBlitzForgeMenuItem(upgradeAgentItem);
        if (toolsMenu.getItemCount() > 0) { // Should always be true since BlitzForge is present.
            menuBar.add(toolsMenu);
        }

        // Window menu
        var windowMenu = new JMenu("Window");
        windowMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                windowMenu.removeAll();

                // Add IntelliJ-style sidebar panel switching shortcuts
                var projectFilesItem = new JMenuItem("Project Files");
                projectFilesItem.setAccelerator(KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_1));
                projectFilesItem.addActionListener(actionEvent -> {
                    chrome.getLeftTabbedPanel().setSelectedIndex(0);
                });
                windowMenu.add(projectFilesItem);

                var testsItem = new JMenuItem("Tests");
                testsItem.setAccelerator(KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_2));
                testsItem.addActionListener(actionEvent -> {
                    var idx = chrome.getLeftTabbedPanel().indexOfComponent(chrome.getTestRunnerPanel());
                    if (idx != -1) chrome.getLeftTabbedPanel().setSelectedIndex(idx);
                });
                windowMenu.add(testsItem);

                if (chrome.getProject().hasGit()) {
                    var changesItem = new JMenuItem("Changes");
                    changesItem.setAccelerator(KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_3));
                    changesItem.addActionListener(actionEvent -> {
                        var idx = chrome.getLeftTabbedPanel().indexOfComponent(chrome.getGitCommitTab());
                        if (idx != -1) chrome.getLeftTabbedPanel().setSelectedIndex(idx);
                    });
                    windowMenu.add(changesItem);

                    var logItem = new JMenuItem("Log");
                    logItem.setAccelerator(KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_4));
                    logItem.addActionListener(actionEvent -> {
                        var gitLogTab = chrome.getGitLogTab();
                        if (gitLogTab != null) {
                            var idx = chrome.getLeftTabbedPanel().indexOfComponent(gitLogTab);
                            if (idx != -1) chrome.getLeftTabbedPanel().setSelectedIndex(idx);
                        }
                    });
                    windowMenu.add(logItem);

                    var worktreesItem = new JMenuItem("Worktrees");
                    worktreesItem.setAccelerator(KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_5));
                    worktreesItem.addActionListener(actionEvent -> {
                        var gitWorktreeTab = chrome.getGitWorktreeTab();
                        if (gitWorktreeTab != null) {
                            var idx = chrome.getLeftTabbedPanel().indexOfComponent(gitWorktreeTab);
                            if (idx != -1) chrome.getLeftTabbedPanel().setSelectedIndex(idx);
                        }
                    });
                    windowMenu.add(worktreesItem);
                }

                if (chrome.getProject().isGitHubRepo() && chrome.getProject().hasGit()) {
                    var pullRequestsItem = new JMenuItem("Pull Requests");
                    pullRequestsItem.setAccelerator(KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_6));
                    pullRequestsItem.addActionListener(actionEvent -> {
                        var idx = chrome.getLeftTabbedPanel().indexOfComponent(chrome.getPullRequestsPanel());
                        if (idx != -1) chrome.getLeftTabbedPanel().setSelectedIndex(idx);
                    });
                    windowMenu.add(pullRequestsItem);
                }

                if (chrome.getProject().getIssuesProvider().type() != IssueProviderType.NONE
                        && chrome.getProject().hasGit()) {
                    var issuesItem = new JMenuItem("Issues");
                    issuesItem.setAccelerator(KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_7));
                    issuesItem.addActionListener(actionEvent -> {
                        var idx = chrome.getLeftTabbedPanel().indexOfComponent(chrome.getIssuesPanel());
                        if (idx != -1) chrome.getLeftTabbedPanel().setSelectedIndex(idx);
                    });
                    windowMenu.add(issuesItem);
                }

                windowMenu.addSeparator();

                Window currentChromeWindow = chrome.getFrame();
                List<JMenuItem> menuItemsList = new ArrayList<>();

                for (Window window : Window.getWindows()) {
                    if (!window.isVisible()) {
                        continue;
                    }

                    // We are interested in Frames and non-modal Dialogs
                    if (!(window instanceof Frame || window instanceof Dialog)) {
                        continue;
                    }

                    if (window instanceof JDialog dialog && dialog.isModal()) {
                        continue;
                    }

                    String title = null;
                    if (window instanceof Frame frame) {
                        title = frame.getTitle();
                    } else {
                        title = ((Dialog) window).getTitle();
                    }

                    if (title == null || title.trim().isEmpty()) {
                        continue;
                    }

                    JMenuItem menuItem;
                    if (window == currentChromeWindow) {
                        menuItem = new JCheckBoxMenuItem(title, true);
                        menuItem.setEnabled(false); // Current window item is selected but disabled
                    } else {
                        menuItem = new JMenuItem(title);
                        final Window windowToFocus = window; // final variable for lambda
                        menuItem.addActionListener(actionEvent -> {
                            if (windowToFocus instanceof Frame frame) {
                                frame.setState(Frame.NORMAL);
                            }
                            windowToFocus.toFront();
                            windowToFocus.requestFocus();
                        });
                    }
                    menuItemsList.add(menuItem);
                }

                menuItemsList.sort(Comparator.comparing(JMenuItem::getText));
                for (JMenuItem item : menuItemsList) {
                    windowMenu.add(item);
                }
            }

            @Override
            public void menuDeselected(MenuEvent e) {
                // No action needed
            }

            @Override
            public void menuCanceled(MenuEvent e) {
                // No action needed
            }
        });
        menuBar.add(windowMenu);

        // Help menu
        var helpMenu = new JMenu("Help");

        var sendFeedbackItem = new JMenuItem("Send Feedback...");
        sendFeedbackItem.addActionListener(e -> {
            try {
                Service.validateKey(MainProject.getBrokkKey());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(
                        chrome.getFrame(),
                        "Please configure a valid Brokk API key in Settings before sending feedback.\n\nError: "
                                + ex.getMessage(),
                        "Invalid API Key",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            var dialog = new FeedbackDialog(chrome.getFrame(), chrome);
            dialog.setVisible(true);
        });
        helpMenu.add(sendFeedbackItem);

        var joinDiscordItem = new JMenuItem("Join Discord");
        joinDiscordItem.addActionListener(e -> {
            Environment.openInBrowser("https://discord.gg/QjhQDK8kAj", chrome.getFrame());
        });
        helpMenu.add(joinDiscordItem);

        var aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> AboutDialog.showAboutDialog(chrome.getFrame()));
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);

        return menuBar;
    }

    private static void runWithRefocus(Chrome chrome, Runnable action) {
        action.run();
        SwingUtilities.invokeLater(chrome::focusInput);
    }

    /**
     * Opens the settings dialog
     *
     * @param chrome the Chrome instance
     */
    static void openSettingsDialog(Chrome chrome) {
        var dialog = new SettingsDialog(chrome.frame, chrome);
        dialog.setVisible(true);
    }

    /**
     * Sets up the global macOS preferences handler that works across all Chrome windows. This should be called once
     * during application startup.
     */
    public static void setupGlobalMacOSPreferencesHandler() {
        if (!Environment.instance.isMacOs()) {
            return;
        }

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().setPreferencesHandler(new PreferencesHandler() {
                    @Override
                    public void handlePreferences(PreferencesEvent e) {
                        SwingUtilities.invokeLater(() -> {
                            // Find the focused Chrome window, or fallback to any active window
                            var targetChrome = Brokk.getActiveWindow();
                            if (targetChrome != null) {
                                openSettingsDialog(targetChrome);
                            }
                        });
                    }
                });
            }
        } catch (Throwable t) {
            // If global handler setup fails, individual windows will fall back to their own handlers
        }
    }

    /**
     * Rebuilds the Recent Projects submenu using up to 5 from Project.loadRecentProjects(), sorted by lastOpened
     * descending.
     */
    private static void rebuildRecentProjectsMenu(JMenu recentMenu) {
        recentMenu.removeAll();

        var map = MainProject.loadRecentProjects();
        if (map.isEmpty()) {
            var emptyItem = new JMenuItem("(No Recent Projects)");
            emptyItem.setEnabled(false);
            recentMenu.add(emptyItem);
            return;
        }

        var sorted = map.entrySet().stream()
                .sorted((a, b) ->
                        Long.compare(b.getValue().lastOpened(), a.getValue().lastOpened()))
                .limit(5)
                .toList();

        for (var entry : sorted) {
            var projectPath = entry.getKey();
            var pathString = projectPath.toString();
            var item = new JMenuItem(pathString);
            item.addActionListener(e -> {
                if (Brokk.isProjectOpen(projectPath)) {
                    Brokk.focusProjectWindow(projectPath);
                } else {
                    // Reopening from recent menu is a user action, not internal, no explicit parent.
                    new Brokk.OpenProjectBuilder(projectPath).open();
                }
            });
            recentMenu.add(item);
        }
    }
}
