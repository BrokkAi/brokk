package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.BuildInfo;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.gui.dialogs.FileSelectionDialog;
import io.github.jbellis.brokk.gui.dialogs.ImportDependencyDialog;
import io.github.jbellis.brokk.gui.dialogs.PreviewImagePanel;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.FeedbackDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

public class MenuBar {
    /**
     * Builds the menu bar
     * @param chrome
     */
    static JMenuBar buildMenuBar(Chrome chrome) {
        // Check if project is available to enable/disable context-related items
        boolean hasProject = chrome.getProject() != null;
        var menuBar = new JMenuBar();

        // File menu
        var fileMenu = new JMenu("File");

        var openProjectItem = new JMenuItem("Open Project...");
        openProjectItem.addActionListener(e -> {
            // Use a directory chooser
            var chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select a Git project directory");
            int result = chooser.showOpenDialog(chrome.frame);
            if (result == JFileChooser.APPROVE_OPTION) {
                var dir = chooser.getSelectedFile().toPath();
                // Opening from menu is a user action, not internal, and has no explicit parent.
                new Brokk.OpenProjectBuilder(dir).open();
            }
        });
        fileMenu.add(openProjectItem);

        JMenuItem reopenProjectItem;
        if (hasProject) {
            // Ensure getFileName() is converted to String for formatting
            String projectName = chrome.getProject().getRoot().getFileName().toString();
            reopenProjectItem = new JMenuItem("Reopen `%s`".formatted(projectName));
            reopenProjectItem.addActionListener(e -> {
                var currentPath = chrome.getProject().getRoot();
                Brokk.reOpenProject(currentPath);
            });
        } else {
            reopenProjectItem = new JMenuItem("Reopen Project");
        }
        reopenProjectItem.setEnabled(hasProject);
        fileMenu.add(reopenProjectItem);

        var recentProjectsMenu = new JMenu("Recent Projects");
        fileMenu.add(recentProjectsMenu);
        rebuildRecentProjectsMenu(recentProjectsMenu);

        var settingsItem = new JMenuItem("Settings...");
        settingsItem.addActionListener(e -> {
            openSettingsDialog(chrome);
        });
        fileMenu.add(settingsItem);

        fileMenu.addSeparator();

        var openDependencyItem = new JMenuItem("Import Dependency...");
        openDependencyItem.setEnabled(hasProject);
        openDependencyItem.addActionListener(e -> {
            // Ensure this action is run on the EDT as it might interact with Swing components immediately
            SwingUtilities.invokeLater(() -> ImportDependencyDialog.show(chrome));
        });
        fileMenu.add(openDependencyItem);

        menuBar.add(fileMenu);

        // Edit menu
        var editMenu = new JMenu("Edit");

        JMenuItem undoItem;
        JMenuItem redoItem;

        if (hasProject) {
            undoItem = new JMenuItem(chrome.getGlobalUndoAction());
            redoItem = new JMenuItem(chrome.getGlobalRedoAction());
        } else {
            // Create disabled menu items if no project (and thus no global actions)
            undoItem = new JMenuItem("Undo");
            undoItem.setEnabled(false);
            redoItem = new JMenuItem("Redo");
            redoItem.setEnabled(false);
        }

        undoItem.setText("Undo"); // Ensure text is set if Action's name is different or null
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(undoItem);

        redoItem.setText("Redo"); // Ensure text is set
        // Standard accelerators for redo
        // Ctrl+Shift+Z or Cmd+Shift+Z
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                       Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        // For Windows/Linux, Ctrl+Y is also common. Adding it as an alternative if the Action itself doesn't set it.
        // However, JMenuItem only supports one accelerator. The global keyboard shortcut in Chrome handles Ctrl+Y.
        editMenu.add(redoItem);

        editMenu.addSeparator();

        JMenuItem copyMenuItem;
        JMenuItem pasteMenuItem;

        if (hasProject) {
            copyMenuItem = new JMenuItem(chrome.getGlobalCopyAction());
            pasteMenuItem = new JMenuItem(chrome.getGlobalPasteAction());
        } else {
            copyMenuItem = new JMenuItem("Copy");
            copyMenuItem.setEnabled(false);
            pasteMenuItem = new JMenuItem("Paste");
            pasteMenuItem.setEnabled(false);
        }
        copyMenuItem.setText("Copy");
        copyMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(copyMenuItem);

        pasteMenuItem.setText("Paste");
        pasteMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editMenu.add(pasteMenuItem);

        menuBar.add(editMenu);

        // Context menu
        var contextMenu = new JMenu("Workspace");

        var refreshItem = new JMenuItem("Refresh Code Intelligence");
        refreshItem.addActionListener(e -> {
            // Fixme ensure the menu item is disabled if no project is open
            assert chrome.getContextManager() != null;
            chrome.contextManager.requestRebuild();
            chrome.systemOutput("Code intelligence will refresh in the background");
        });
        refreshItem.setEnabled(hasProject);
        contextMenu.add(refreshItem);

        contextMenu.addSeparator();

        var editFilesItem = new JMenuItem("Edit Files");
        editFilesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editFilesItem.addActionListener(e -> {
            chrome.getContextPanel().performContextActionAsync(
                    WorkspacePanel.ContextAction.EDIT, List.of());
        });
        editFilesItem.setEnabled(hasProject && chrome.getProject().hasGit());
        contextMenu.add(editFilesItem);

        var readFilesItem = new JMenuItem("Read Files");
        readFilesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        readFilesItem.addActionListener(e -> {
            chrome.getContextPanel().performContextActionAsync(
                    WorkspacePanel.ContextAction.READ, List.of());
        });
        readFilesItem.setEnabled(hasProject);
        contextMenu.add(readFilesItem);
    
    var viewFileItem = new JMenuItem("View File");
    // On Mac, use Cmd+O; on Windows/Linux, use Ctrl+N
    viewFileItem.setAccelerator(KeyStroke.getKeyStroke(
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() == InputEvent.META_DOWN_MASK
            ? KeyEvent.VK_O
            : KeyEvent.VK_N,
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
    viewFileItem.addActionListener(e -> {
            assert chrome.getContextManager() != null;
            assert chrome.getProject() != null;
            var cm = chrome.getContextManager();
            var project = cm.getProject();

            // Use a simplified FileSelectionDialog for viewing
            SwingUtilities.invokeLater(() -> {
                // Autocomplete with all project files, transforming Set<ProjectFile> to List<Path>
                var allFilesFuture = cm.submitBackgroundTask("Fetching project files", () -> {
                    return project.getAllFiles().stream()
                            .map(io.github.jbellis.brokk.analyzer.BrokkFile::absPath)
                            .toList();
                });

                FileSelectionDialog dialog = new FileSelectionDialog(chrome.getFrame(),
                                                                     project,
                                                                     "Select File to View",
                                                                     false,
                                                                     f -> true,
                                                                     allFilesFuture);
                dialog.setVisible(true);

                if (dialog.isConfirmed() && dialog.getSelectedFile() != null) {
                    var selectedBrokkFile = dialog.getSelectedFile();
                    if (selectedBrokkFile instanceof io.github.jbellis.brokk.analyzer.ProjectFile selectedFile) {
                        chrome.previewFile(selectedFile);
                    } else if (!selectedBrokkFile.isText()) {
                        PreviewImagePanel.showInFrame(chrome.getFrame(), cm, selectedBrokkFile, chrome.themeManager);
                    } else {
                        chrome.toolErrorRaw("Cannot view this type of file: " + selectedBrokkFile.getClass().getSimpleName());
                    }
                }
            });
        });
        viewFileItem.setEnabled(hasProject);
        contextMenu.add(viewFileItem);

        contextMenu.addSeparator(); // Add separator before Summarize / Symbol Usage

        var summarizeItem = new JMenuItem("Summarize");
        summarizeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        summarizeItem.addActionListener(e -> {
            chrome.getContextPanel().performContextActionAsync(
                    WorkspacePanel.ContextAction.SUMMARIZE, List.of());
        });
        summarizeItem.setEnabled(hasProject);
        contextMenu.add(summarizeItem);

        var symbolUsageItem = new JMenuItem("Symbol Usage");
            symbolUsageItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_U, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
            symbolUsageItem.addActionListener(e -> {
                chrome.getContextPanel().findSymbolUsageAsync(); // Call via ContextPanel
            });
            symbolUsageItem.setEnabled(hasProject);
            contextMenu.add(symbolUsageItem);

        var callersItem = new JMenuItem("Call graph to function");
            callersItem.addActionListener(e -> {
                chrome.getContextPanel().findMethodCallersAsync(); // Call via ContextPanel
            });
            callersItem.setEnabled(hasProject);
            contextMenu.add(callersItem);

        var calleesItem = new JMenuItem("Call graph from function");
            calleesItem.addActionListener(e -> {
                chrome.getContextPanel().findMethodCalleesAsync(); // Call via ContextPanel
            });
    calleesItem.setEnabled(hasProject);
    contextMenu.add(calleesItem);

    contextMenu.addSeparator(); // Add separator before Drop All

    var dropAllItem = new JMenuItem("Drop All");
    dropAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
    dropAllItem.addActionListener(e -> {
            chrome.getContextPanel().performContextActionAsync(
                    WorkspacePanel.ContextAction.DROP, List.of());
        });
        dropAllItem.setEnabled(hasProject);
        contextMenu.add(dropAllItem);

        menuBar.add(contextMenu);

        // Window menu
        var windowMenu = new JMenu("Window");
        windowMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                windowMenu.removeAll();
                Map<Path, Chrome> openWindows = Brokk.getOpenProjectWindows();
                Path currentProjectPath = null;
                if (chrome.getProject() != null && chrome.getProject().getRoot() != null) {
                    currentProjectPath = chrome.getProject().getRoot().toAbsolutePath().normalize();
                }

                List<JMenuItem> menuItemsList = new ArrayList<>();

                for (Map.Entry<Path, Chrome> entry : openWindows.entrySet()) {
                    Path projectPath = entry.getKey().toAbsolutePath().normalize();
                    Chrome projectChrome = entry.getValue();

                    if (projectChrome.getProject() == null || projectChrome.getProject().getRoot() == null) {
                        continue; // Skip if project or its root is null
                    }

                    String projectName = projectChrome.getProject().getRoot().getFileName().toString();
                    JMenuItem menuItem;

                    if (projectPath.equals(currentProjectPath)) {
                        menuItem = new JCheckBoxMenuItem(projectName, true);
                        menuItem.setEnabled(false); // Current window item is selected but disabled
                    } else {
                        menuItem = new JMenuItem(projectName);
                        menuItem.addActionListener(e2 -> Brokk.focusProjectWindow(projectPath));
                    }
                    menuItemsList.add(menuItem);
                }

                // JMenuItem and JCheckBoxMenuItem both have getText()
                menuItemsList.sort(Comparator.comparing(JMenuItem::getText));

                if (menuItemsList.isEmpty()) {
                    JMenuItem emptyItem = new JMenuItem("(No other windows open)");
                    emptyItem.setEnabled(false);
                    windowMenu.add(emptyItem);
                } else {
                    for (JMenuItem item : menuItemsList) {
                        windowMenu.add(item);
                    }
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
                JOptionPane.showMessageDialog(chrome.getFrame(),
                                              "Please configure a valid Brokk API key in Settings before sending feedback.\n\nError: " + ex.getMessage(),
                                              "Invalid API Key",
                                              JOptionPane.ERROR_MESSAGE);
                return;
            }

            var dialog = new FeedbackDialog(chrome.getFrame(), chrome);
            dialog.setVisible(true);
        });
        helpMenu.add(sendFeedbackItem);

        var aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> {
            ImageIcon icon = null;
            var iconUrl = Brokk.class.getResource(Brokk.ICON_RESOURCE);
            if (iconUrl != null) {
                var originalIcon = new ImageIcon(iconUrl);
                var image = originalIcon.getImage();
                var scaledImage = image.getScaledInstance(128, 128, Image.SCALE_SMOOTH);
                icon = new ImageIcon(scaledImage);
            }
            JOptionPane.showMessageDialog(chrome.getFrame(),
                                          "Brokk Version %s\n\nCopyright (c) 2025 Brokk, Inc.".formatted(BuildInfo.version()),
                                          "About Brokk",
                                          JOptionPane.INFORMATION_MESSAGE,
                                          icon);
        });
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        return menuBar;
    }

    /**
     * Opens the settings dialog
     * @param chrome the Chrome instance
     */
    static void openSettingsDialog(Chrome chrome) {
        var dialog = new SettingsDialog(chrome.frame, chrome);
        dialog.setVisible(true);
    }

    /**
     * Rebuilds the Recent Projects submenu using up to 5 from Project.loadRecentProjects(),
     * sorted by lastOpened descending.
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
            .sorted((a, b) -> Long.compare(b.getValue().lastOpened(), a.getValue().lastOpened()))
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
