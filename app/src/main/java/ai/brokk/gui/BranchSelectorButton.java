package ai.brokk.gui;

import ai.brokk.IConsoleIO;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import ai.brokk.gui.components.SplitButton;
import ai.brokk.gui.dialogs.CreatePullRequestDialog;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A specialized SplitButton for displaying and managing Git branches.
 * Provides a dropdown menu for switching branches and creating new branches.
 */
public class BranchSelectorButton extends SplitButton {
    private static final Logger logger = LogManager.getLogger(BranchSelectorButton.class);

    private final Chrome chrome;

    public BranchSelectorButton(Chrome chrome) {
        super("No Git");
        this.chrome = chrome;

        setUnifiedHover(true);
        setToolTipText("Current Git branch — click to create/select branches");
        setFocusable(true);

        setMenuSupplier(this::buildBranchMenu);

        addActionListener(ev -> SwingUtilities.invokeLater(() -> {
            try {
                var menu = buildBranchMenu();
                try {
                    chrome.themeManager.registerPopupMenu(menu);
                } catch (Exception e) {
                    logger.debug("Error registering popup menu", e);
                }
                menu.show(this, 0, getHeight());
            } catch (Exception ex) {
                logger.error("Error showing branch dropdown", ex);
            }
        }));

        initializeCurrentBranch();
    }

    private void initializeCurrentBranch() {
        var project = chrome.getProject();
        try {
            if (project.hasGit()) {
                IGitRepo repo = project.getRepo();
                String cur = repo.getCurrentBranch();
                if (!cur.isBlank()) {
                    setText(cur);
                    setEnabled(true);
                } else {
                    setText("Unknown");
                    setEnabled(true);
                }
            } else {
                setText("No Git");
                setEnabled(false);
            }
        } catch (Exception ex) {
            logger.error("Error initializing branch button", ex);
            setText("No Git");
            setEnabled(false);
        }
    }

    private JPopupMenu buildBranchMenu() {
        var menu = new JPopupMenu();
        var project = chrome.getProject();
        var cm = chrome.getContextManager();

        if (project.hasGit()) {
            // Top-level actions should always appear first when a Git repo is present
            JMenuItem create = new JMenuItem("Create New Branch...");
            create.addActionListener(ev -> {
                SwingUtilities.invokeLater(() -> {
                    String name = JOptionPane.showInputDialog(chrome.getFrame(), "New branch name:");
                    if (name == null || name.isBlank()) return;
                    final String proposed = name.strip();
                    cm.submitExclusiveAction(() -> {
                        try {
                            IGitRepo r = project.getRepo();
                            String sanitized = r.sanitizeBranchName(proposed);
                            String source = r.getCurrentBranch();
                            try {
                                if (r instanceof GitRepo gitRepo) {
                                    try {
                                        gitRepo.createAndCheckoutBranch(sanitized, source);
                                    } catch (NoSuchMethodError | UnsupportedOperationException nsme) {
                                        gitRepo.createBranch(sanitized, source);
                                        gitRepo.checkout(sanitized);
                                    }
                                } else {
                                    throw new UnsupportedOperationException(
                                            "Repository implementation does not support branch creation");
                                }
                            } catch (NoSuchMethodError | UnsupportedOperationException nsme) {
                                throw nsme;
                            }
                            SwingUtilities.invokeLater(() -> {
                                try {
                                    refreshBranch(sanitized);
                                } catch (Exception ex) {
                                    logger.debug("Error updating branch UI after branch creation", ex);
                                }
                                chrome.showNotification(
                                        IConsoleIO.NotificationRole.INFO, "Created and checked out: " + sanitized);
                            });
                        } catch (Exception ex) {
                            logger.error("Error creating branch", ex);
                            SwingUtilities.invokeLater(
                                    () -> chrome.toolError("Error creating branch: " + ex.getMessage()));
                        }
                    });
                });
            });
            menu.add(create);

            JMenuItem refresh = new JMenuItem("Refresh Branches");
            refresh.addActionListener(ev -> {
                SwingUtilities.invokeLater(
                        () -> chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Branches refreshed"));
            });
            menu.add(refresh);

            JMenuItem createPr = new JMenuItem("Create Pull Request...");
            createPr.addActionListener(ev -> {
                SwingUtilities.invokeLater(() -> {
                    try {
                        IGitRepo r = project.getRepo();
                        String branch = r.getCurrentBranch();
                        if (branch.isBlank()) {
                            chrome.toolError("Cannot create PR: No branch is currently selected.");
                            return;
                        }
                        if (GitWorkflow.isSyntheticBranchName(branch)) {
                            chrome.toolError(
                                    "Select a local branch before creating a PR. Synthetic views are not supported.");
                            return;
                        }
                        boolean isRemote = false;
                        if (r instanceof GitRepo gitRepo) {
                            isRemote = gitRepo.isRemoteBranch(branch) && !gitRepo.isLocalBranch(branch);
                        }
                        if (isRemote) {
                            chrome.toolError(
                                    "Select a local branch before creating a PR. Remote branches are not supported.");
                            return;
                        }
                        CreatePullRequestDialog.show(chrome.getFrame(), chrome, cm, branch);
                    } catch (Exception ex) {
                        logger.error("Error opening Create Pull Request dialog", ex);
                        chrome.toolError("Failed to open Create Pull Request dialog: " + ex.getMessage());
                    }
                });
            });
            menu.add(createPr);

            // Separator before the branch list
            menu.addSeparator();

            // Build the branch list; if something fails, show an error while keeping top actions
            try {
                IGitRepo repo = project.getRepo();
                List<String> localBranches;
                if (repo instanceof GitRepo gitRepo) {
                    localBranches = gitRepo.listLocalBranches();
                } else {
                    localBranches = List.of();
                }
                String current = repo.getCurrentBranch();

                if (!current.isBlank()) {
                    JMenuItem header = new JMenuItem("Current: " + current);
                    header.setEnabled(false);
                    menu.add(header);
                }

                // Create a scrollable list of branches, so the popup can remain below the button
                DefaultListModel<String> model = new DefaultListModel<>();
                for (String b : localBranches) {
                    model.addElement(b);
                }
                JList<String> list = new JList<>(model);
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.setVisibleRowCount(-1); // let the scrollpane determine visible rows
                list.setFocusable(true);

                // Single-click to checkout, like JMenuItem behavior
                list.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        int idx = list.locationToIndex(e.getPoint());
                        if (idx >= 0) {
                            String b = model.getElementAt(idx);
                            cm.submitExclusiveAction(() -> {
                                try {
                                    IGitRepo r = project.getRepo();
                                    r.checkout(b);
                                    SwingUtilities.invokeLater(() -> {
                                        try {
                                            var currentBranch = r.getCurrentBranch();
                                            var displayBranch = currentBranch.isBlank() ? b : currentBranch;
                                            refreshBranch(displayBranch);
                                        } catch (Exception ex) {
                                            logger.debug("Error updating branch UI after checkout", ex);
                                            refreshBranch(b);
                                        }
                                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Checked out: " + b);
                                    });
                                } catch (Exception ex) {
                                    logger.error("Error checking out branch {}", b, ex);
                                    SwingUtilities.invokeLater(
                                            () -> chrome.toolError("Error checking out branch: " + ex.getMessage()));
                                } finally {
                                    SwingUtilities.invokeLater(() -> menu.setVisible(false));
                                }
                            });
                        }
                    }
                });

                // Enter key triggers checkout for keyboard users
                list.addKeyListener(new KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent e) {
                        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                            int idx = list.getSelectedIndex();
                            if (idx >= 0) {
                                String b = model.getElementAt(idx);
                                cm.submitExclusiveAction(() -> {
                                    try {
                                        IGitRepo r = project.getRepo();
                                        r.checkout(b);
                                        SwingUtilities.invokeLater(() -> {
                                            try {
                                                var currentBranch = r.getCurrentBranch();
                                                var displayBranch = currentBranch.isBlank() ? b : currentBranch;
                                                refreshBranch(displayBranch);
                                            } catch (Exception ex) {
                                                logger.debug("Error updating branch UI after checkout", ex);
                                                refreshBranch(b);
                                            }
                                            chrome.showNotification(
                                                    IConsoleIO.NotificationRole.INFO, "Checked out: " + b);
                                        });
                                    } catch (Exception ex) {
                                        logger.error("Error checking out branch {}", b, ex);
                                        SwingUtilities.invokeLater(() ->
                                                chrome.toolError("Error checking out branch: " + ex.getMessage()));
                                    } finally {
                                        SwingUtilities.invokeLater(() -> menu.setVisible(false));
                                    }
                                });
                            }
                        }
                    }
                });

                JScrollPane scrollPane = new JScrollPane(list);
                scrollPane.setBorder(BorderFactory.createEmptyBorder());
                scrollPane.getVerticalScrollBar().setUnitIncrement(16);

                // Compute available space below the button and cap the scroll area height
                int availableBelow = getAvailableSpaceBelow();

                // Height used by items added so far (top actions, separator, and optional header)
                int usedHeight = 0;
                for (Component c : menu.getComponents()) {
                    Dimension d = c.getPreferredSize();
                    if (d != null) usedHeight += d.height;
                }

                // Provide some max height, but ensure we don't exceed available space below
                int maxListHeight = Math.max(120, availableBelow - usedHeight - 8);
                if (maxListHeight < 60) {
                    // If extremely constrained, still show a tiny scroll area instead of forcing a flip
                    maxListHeight = Math.max(availableBelow - usedHeight - 4, 16);
                }
                // Set a reasonable width; layout will expand if needed
                int prefWidth = Math.max(
                        260,
                        list.getPreferredSize().width
                                + scrollPane.getVerticalScrollBar().getPreferredSize().width
                                + 16);
                if (maxListHeight > 0) {
                    scrollPane.setPreferredSize(new Dimension(prefWidth, maxListHeight));
                } else {
                    // Fallback: minimal height to keep menu below; may show only top actions
                    scrollPane.setPreferredSize(new Dimension(prefWidth, 1));
                }

                menu.add(scrollPane);
            } catch (Exception ex) {
                logger.error("Error building branch menu", ex);
                JMenuItem err = new JMenuItem("Error loading branches");
                err.setEnabled(false);
                menu.add(err);
            }
        } else {
            JMenuItem noRepo = new JMenuItem("No Git repository");
            noRepo.setEnabled(false);
            menu.add(noRepo);
        }

        return menu;
    }

    public void refreshBranch(String branchName) {
        Runnable task = () -> {
            if (!chrome.getProject().hasGit()) {
                return;
            }
            setText(branchName);
            chrome.getProjectFilesPanel().updateBorderTitle();

            chrome.updateLogTab();
            chrome.selectCurrentBranchInLogTab();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    // Compute the vertical space available below this button within the current screen,
    // accounting for taskbar/dock insets, to keep the popup below the button.
    private int getAvailableSpaceBelow() {
        try {
            Point screenLoc = getLocationOnScreen();
            GraphicsConfiguration gc = getGraphicsConfiguration();
            Rectangle screenBounds = (gc != null)
                    ? gc.getBounds()
                    : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            Insets insets = (gc != null) ? Toolkit.getDefaultToolkit().getScreenInsets(gc) : new Insets(0, 0, 0, 0);
            int bottomEdge = screenBounds.y + screenBounds.height - insets.bottom;
            int buttonBottom = screenLoc.y + getHeight();
            return Math.max(0, bottomEdge - buttonBottom);
        } catch (IllegalComponentStateException e) {
            // If not yet showing, fall back to a reasonable default
            return 400;
        }
    }
}
