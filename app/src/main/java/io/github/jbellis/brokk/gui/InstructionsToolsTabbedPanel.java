package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.terminal.TaskListPanel;
import io.github.jbellis.brokk.gui.terminal.TerminalPanel;
import io.github.jbellis.brokk.gui.tests.TestRunnerPanel;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.util.GlobalUiSettings;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.Path;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JTabbedPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A single tabbed component that hosts:
 * - Instructions (existing InstructionsPanel instance)
 * - Tasks (TaskListPanel, created lazily)
 * - Terminal (TerminalPanel, created lazily)
 *
 * Replaces the need for a right-hand drawer by centralizing tool switching via tabs.
 */
public final class InstructionsToolsTabbedPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(InstructionsToolsTabbedPanel.class);

    private final Chrome chrome;
    private final InstructionsPanel instructionsPanel;

    private final JTabbedPane tabs;

    private final JPanel tasksPlaceholder = new JPanel(new BorderLayout());
    private final JPanel terminalPlaceholder = new JPanel(new BorderLayout());
    private final JPanel testsPlaceholder = new JPanel(new BorderLayout());

    private @Nullable TaskListPanel taskListPanel;
    private @Nullable TerminalPanel terminalPanel;
    private @Nullable TestRunnerPanel testRunnerPanel;

    // Tab indices (fixed order)
    private static final int TAB_INSTRUCTIONS = 0;
    private static final int TAB_TASKS = 1;
    private static final int TAB_TERMINAL = 2;
    private static final int TAB_TESTS = 3;

    public InstructionsToolsTabbedPanel(Chrome chrome, InstructionsPanel instructionsPanel) {
        super(new BorderLayout());
        assert SwingUtilities.isEventDispatchThread() : "Must construct on EDT";
        this.chrome = chrome;
        this.instructionsPanel = instructionsPanel;

        setBorder(BorderFactory.createEmptyBorder());

        tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);

        // Instructions tab uses the existing instance
        tabs.addTab("Instructions", null, this.instructionsPanel, "Instructions and actions");

        // Add placeholders first so replaceTabComponent can swap content by fixed indices
        tasksPlaceholder.setOpaque(false);
        tabs.addTab("Tasks", Icons.LIST, tasksPlaceholder, "Task list");

        terminalPlaceholder.setOpaque(false);
        tabs.addTab("Terminal", Icons.TERMINAL, terminalPlaceholder, "Integrated terminal");

        testsPlaceholder.setOpaque(false);
        tabs.addTab("Tests", Icons.CHECK, testsPlaceholder, "Test runner output");

        // Eagerly create and install real panels so clicking tabs never shows a blank placeholder
        taskListPanel = new TaskListPanel(chrome);
        replaceTabComponent(TAB_TASKS, taskListPanel, "Tasks", Icons.LIST);
        try {
            taskListPanel.applyTheme(chrome.getTheme());
        } catch (Exception ex) {
            logger.debug("Failed to apply theme to TaskListPanel (eager)", ex);
        }

        createTerminalPanel();

        // Eagerly create Tests panel to avoid placeholder gray background
        testRunnerPanel = new TestRunnerPanel();
        replaceTabComponent(TAB_TESTS, testRunnerPanel, "Tests", Icons.CHECK);
        try {
            testRunnerPanel.applyTheme(chrome.getTheme());
        } catch (Exception ex) {
            logger.debug("Failed to apply theme to TestRunnerPanel (eager)", ex);
        }

        // Restore last selected tab (default to Instructions=0) and persist on change
        int savedIndex = GlobalUiSettings.getLastToolsTabIndex();
        if (savedIndex < 0 || savedIndex >= tabs.getTabCount()) {
            savedIndex = TAB_INSTRUCTIONS;
        }
        tabs.setSelectedIndex(savedIndex);
        tabs.addChangeListener(e -> {
            int idx = tabs.getSelectedIndex();
            GlobalUiSettings.saveLastToolsTabIndex(idx);
            // If the user closed the terminal, selecting the tab should recreate it on demand
            if (idx == TAB_TERMINAL && terminalPanel == null) {
                createTerminalPanel();
            }
            // Safety: recreate tasks panel if ever cleared (should not normally happen)
            if (idx == TAB_TASKS && taskListPanel == null) {
                taskListPanel = new TaskListPanel(chrome);
                replaceTabComponent(TAB_TASKS, taskListPanel, "Tasks", Icons.LIST);
                try {
                    taskListPanel.applyTheme(chrome.getTheme());
                } catch (Exception ex) {
                    logger.debug("Failed to apply theme to TaskListPanel (on-select)", ex);
                }
            }
        });

        add(tabs, BorderLayout.CENTER);
    }

    /** Select the Instructions tab. */
    public void selectInstructionsTab() {
        assert SwingUtilities.isEventDispatchThread() : "Must run on EDT";
        tabs.setSelectedIndex(TAB_INSTRUCTIONS);
        requestFocusFor(instructionsPanel);
    }

    /** Open or focus the Tasks tab, creating it lazily if needed; returns the panel. */
    public TaskListPanel openTaskList() {
        assert SwingUtilities.isEventDispatchThread() : "Must run on EDT";
        if (taskListPanel == null) {
            taskListPanel = new TaskListPanel(chrome);
            replaceTabComponent(TAB_TASKS, taskListPanel, "Tasks", Icons.LIST);
            // Apply current theme to the newly created panel
            try {
                taskListPanel.applyTheme(chrome.getTheme());
            } catch (Exception ex) {
                logger.debug("Failed to apply theme to TaskListPanel", ex);
            }
        }
        tabs.setSelectedIndex(TAB_TASKS);
        return taskListPanel;
    }

    /** Open or focus the Terminal tab, creating it lazily if needed; returns the panel. */
    public TerminalPanel openTerminal() {
        assert SwingUtilities.isEventDispatchThread() : "Must run on EDT";
        if (terminalPanel == null) {
            createTerminalPanel();
        }
        tabs.setSelectedIndex(TAB_TERMINAL);

        var tp = Objects.requireNonNull(terminalPanel, "terminalPanel");
        if (tp.isReady()) {
            tp.requestFocusInTerminal();
        } else {
            tp.whenReady().thenAccept(t ->
                    SwingUtilities.invokeLater(t::requestFocusInTerminal));
        }
        return tp;
    }

    /** Returns the TaskListPanel if created, else null. */
    public @Nullable TaskListPanel getTaskListPanelOrNull() {
        return taskListPanel;
    }

    /** Returns the TestRunnerPanel if created, else null. */
    public @Nullable TestRunnerPanel getTestRunnerPanelOrNull() {
        return testRunnerPanel;
    }

    /** Updates terminal font size if a terminal exists. Safe to call any time. */
    public void updateTerminalFontSize() {
        SwingUtilities.invokeLater(() -> {
            if (terminalPanel != null) {
                terminalPanel.updateTerminalFontSize();
            }
        });
    }

    /** Open the Terminal tab and paste text once the terminal is ready. Runs on the EDT. */
    public void openTerminalAndPasteText(String text) {
        assert SwingUtilities.isEventDispatchThread() : "Must run on EDT";
        var tp = openTerminal();
        tp.whenReady().thenAccept(t ->
                SwingUtilities.invokeLater(() -> t.pasteText(text)));
    }

    /** Apply theme to subpanels that implement ThemeAware. */
    @Override
    public void applyTheme(GuiTheme guiTheme) {
        if (taskListPanel != null) {
            taskListPanel.applyTheme(guiTheme);
        }
        if (terminalPanel != null) {
            terminalPanel.applyTheme(guiTheme);
        }
        if (testRunnerPanel != null) {
            testRunnerPanel.applyTheme(guiTheme);
        }
        if (instructionsPanel instanceof ThemeAware ta) {
            ta.applyTheme(guiTheme);
        }
        revalidate();
        repaint();
    }

    // --- Helpers ---

    private void replaceTabComponent(int index, Component comp, String title, javax.swing.Icon icon) {
        // Keep tooltip if previously set
        String tooltip = tabs.getToolTipTextAt(index);
        tabs.setComponentAt(index, comp);
        tabs.setTitleAt(index, title);
        tabs.setIconAt(index, icon);
        tabs.setToolTipTextAt(index, tooltip);
    }

    private void requestFocusFor(Component c) {
        SwingUtilities.invokeLater(() -> {
            if (c.isShowing()) {
                c.requestFocusInWindow();
            }
        });
    }

    private void createTerminalPanel() {
        try {
            Path cwd = chrome.getProject().getRoot();
            // Show header so capture button is available; onClose disposes and clears the tab content.
            terminalPanel = new TerminalPanel(chrome, this::disposeTerminal, true, cwd);
            replaceTabComponent(TAB_TERMINAL, terminalPanel, "Terminal", Icons.TERMINAL);
            // Apply current theme and font size to the newly created terminal
            try {
                terminalPanel.applyTheme(chrome.getTheme());
            } catch (Exception ex) {
                logger.debug("Failed to apply theme to TerminalPanel", ex);
            }
            try {
                terminalPanel.updateTerminalFontSize();
            } catch (Exception ex) {
                logger.debug("Failed to apply terminal font size setting", ex);
            }
        } catch (Exception ex) {
            logger.warn("Failed to create terminal in tab: {}", ex.getMessage());
        }
    }

    private void disposeTerminal() {
        assert SwingUtilities.isEventDispatchThread() : "Must run on EDT";
        if (terminalPanel != null) {
            try {
                terminalPanel.dispose();
            } catch (Exception ex) {
                logger.debug("Error disposing terminal", ex);
            } finally {
                terminalPanel = null;
            }
        }
        // Restore placeholder so the tab remains valid
        replaceTabComponent(TAB_TERMINAL, terminalPlaceholder, "Terminal", Icons.TERMINAL);
        // Optionally flip back to Instructions to avoid focusing an empty placeholder
        tabs.setSelectedIndex(TAB_INSTRUCTIONS);
    }
}
