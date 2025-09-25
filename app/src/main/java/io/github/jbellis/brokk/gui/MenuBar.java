package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.dialogs.AboutDialog;
import io.github.jbellis.brokk.gui.dialogs.BlitzForgeDialog;
import io.github.jbellis.brokk.gui.dialogs.FeedbackDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.util.Environment;
import java.awt.*;
import java.awt.Desktop;
import java.awt.desktop.PreferencesHandler;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

public class MenuBar {
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
        settingsItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_COMMA, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));

        if (isMac) {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().setPreferencesHandler(new PreferencesHandler() {
                        @Override
                        public void handlePreferences(java.awt.desktop.PreferencesEvent e) {
                            SwingUtilities.invokeLater(() -> openSettingsDialog(chrome));
                        }
                    });
                }
            } catch (Throwable t) {
                // Best-effort; if registering the Preferences handler fails, fall back to putting the menu
                // entry into the File menu so Settings remains reachable.
                fileMenu.add(settingsItem);
            }

            // Ensure Cmd+, opens settings even if the system does not dispatch the shortcut to the handler.
            var rootPane = chrome.getFrame().getRootPane();
            var im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            var am = rootPane.getActionMap();
            var ks = KeyStroke.getKeyStroke(
                    KeyEvent.VK_COMMA, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
            im.put(ks, "open-settings");
            am.put("open-settings", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
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
            chrome.systemOutput("Exiting Brokk...");
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

        // Context menu
        var contextMenu = new JMenu("Workspace");

        var refreshItem = new JMenuItem("Refresh Code Intelligence");
        refreshItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.contextManager.requestRebuild();
            chrome.systemOutput("Code intelligence will refresh in the background");
        }));
        refreshItem.setEnabled(true);
        contextMenu.add(refreshItem);

        contextMenu.addSeparator();

        var attachContextItem = new JMenuItem("Attach Context...");
        attachContextItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_E, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        attachContextItem.addActionListener(e -> {
            chrome.getContextPanel().attachContextViaDialog();
        });
        attachContextItem.setEnabled(true);
        contextMenu.add(attachContextItem);

        var summarizeContextItem = new JMenuItem("Summarize Context...");
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
                        chrome.systemOutput("No files or folders selected.");
                        return;
                    }

                    cm.submitContextTask("Attach Non-Project Files", () -> {
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
                            chrome.systemOutput("No files found to attach.");
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
                        chrome.systemOutput("Attached " + fragments.size() + " files.");
                    });
                } else {
                    chrome.systemOutput("File attachment cancelled.");
                }
            });
        });
        attachExternalItem.setEnabled(true);
        contextMenu.add(attachExternalItem);

        contextMenu.addSeparator();

        var callersItem = new JMenuItem("Call graph to function");
        callersItem.addActionListener(e -> {
            chrome.getContextPanel().findMethodCallersAsync(); // Call via ContextPanel
        });
        callersItem.setEnabled(true);
        contextMenu.add(callersItem);

        var calleesItem = new JMenuItem("Call graph from function");
        calleesItem.addActionListener(e -> {
            chrome.getContextPanel().findMethodCalleesAsync(); // Call via ContextPanel
        });
        calleesItem.setEnabled(true);
        contextMenu.add(calleesItem);

        contextMenu.addSeparator();

        var newSessionItem = new JMenuItem("New Session");
        newSessionItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        newSessionItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.getContextManager()
                    .createSessionAsync(ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> chrome.getProject().getMainProject().sessionsListChanged());
        }));
        contextMenu.add(newSessionItem);

        var newSessionCopyWorkspaceItem = new JMenuItem("New + Copy Workspace");
        newSessionCopyWorkspaceItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        newSessionCopyWorkspaceItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.getContextManager()
                    .createSessionFromContextAsync(
                            chrome.getContextManager().topContext(), ContextManager.DEFAULT_SESSION_NAME)
                    .thenRun(() -> chrome.getProject().getMainProject().sessionsListChanged());
        }));
        contextMenu.add(newSessionCopyWorkspaceItem);

        contextMenu.addSeparator();

        // Clear Task History (Cmd/Ctrl+P)
        var clearTaskHistoryItem = new JMenuItem("Clear Task History");
        clearTaskHistoryItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        clearTaskHistoryItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.getContextManager().submitContextTask("Clear Task History", () -> chrome.getContextManager()
                    .clearHistory());
        }));
        clearTaskHistoryItem.setEnabled(true);
        contextMenu.add(clearTaskHistoryItem);

        var dropAllItem = new JMenuItem("Drop All");
        dropAllItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        dropAllItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            chrome.getContextManager().submitContextTask("Drop All", () -> {
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

        var scanProjectItem = new JMenuItem("Scan Project");
        scanProjectItem.addActionListener(e -> runWithRefocus(chrome, () -> {
            // Delegate to InstructionsPanel's scan flow which handles model selection, validation,
            // and submission to ContextManager.
            chrome.getInstructionsPanel().runScanProjectCommand();
        }));
        scanProjectItem.setEnabled(true);
        toolsMenu.add(scanProjectItem);

        var upgradeAgentItem = new JMenuItem("BlitzForge...");
        upgradeAgentItem.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                var dialog = new BlitzForgeDialog(chrome.getFrame(), chrome);
                dialog.setVisible(true);
            });
        });
        upgradeAgentItem.setEnabled(true);
        toolsMenu.add(upgradeAgentItem);

        // Let Chrome manage this item’s enabled state during long-running actions
        chrome.setBlitzForgeMenuItem(upgradeAgentItem);
        if (toolsMenu.getItemCount() > 0) {
            menuBar.add(toolsMenu);
        }

        // Window menu
        var windowMenu = new JMenu("Window");
        windowMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                windowMenu.removeAll();

                // Add IntelliJ-style sidebar panel switching shortcuts
                // Determine the modifier based on platform (Cmd on Mac, Alt on Windows/Linux)
                int modifier = System.getProperty("os.name")
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains("mac")
                        ? KeyEvent.META_DOWN_MASK
                        : KeyEvent.ALT_DOWN_MASK;

                var projectFilesItem = new JMenuItem("Project Files");
                projectFilesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1, modifier));
                projectFilesItem.addActionListener(actionEvent -> {
                    chrome.getLeftTabbedPanel().setSelectedIndex(0);
                });
                windowMenu.add(projectFilesItem);

                if (chrome.getProject().hasGit()) {
                    var gitItem = new JMenuItem("Commit");
                    gitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_2, modifier));
                    gitItem.addActionListener(actionEvent -> {
                        var idx = chrome.getLeftTabbedPanel().indexOfComponent(chrome.getGitCommitTab());
                        if (idx != -1) chrome.getLeftTabbedPanel().setSelectedIndex(idx);
                    });
                    windowMenu.add(gitItem);
                }

                if (chrome.getProject().isGitHubRepo() && chrome.getProject().hasGit()) {
                    var pullRequestsItem = new JMenuItem("Pull Requests");
                    pullRequestsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_3, modifier));
                    pullRequestsItem.addActionListener(actionEvent -> {
                        var idx = chrome.getLeftTabbedPanel().indexOfComponent(chrome.getPullRequestsPanel());
                        if (idx != -1) chrome.getLeftTabbedPanel().setSelectedIndex(idx);
                    });
                    windowMenu.add(pullRequestsItem);
                }

                if (chrome.getProject().getIssuesProvider().type()
                                != io.github.jbellis.brokk.issues.IssueProviderType.NONE
                        && chrome.getProject().hasGit()) {
                    var issuesItem = new JMenuItem("Issues");
                    issuesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_4, modifier));
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
            io.github.jbellis.brokk.util.Environment.openInBrowser("https://discord.gg/QjhQDK8kAj", chrome.getFrame());
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
