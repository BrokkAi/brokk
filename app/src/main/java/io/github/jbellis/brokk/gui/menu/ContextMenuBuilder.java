package io.github.jbellis.brokk.gui.menu;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.RunTestsService;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Builder for creating consistent context menus for files and symbols
 */
public class ContextMenuBuilder {
    private static final Logger logger = LogManager.getLogger(ContextMenuBuilder.class);

    private final JPopupMenu menu;
    private final MenuContext context;

    private ContextMenuBuilder(MenuContext context) {
        this.context = context;
        this.menu = new JPopupMenu();
        context.chrome().getTheme().registerPopupMenu(menu);
    }

    /**
     * Creates a context menu for symbols
     */
    public static ContextMenuBuilder forSymbol(String symbolName, boolean symbolExists, Chrome chrome, ContextManager contextManager) {
        var context = new SymbolMenuContext(symbolName, symbolExists, chrome, contextManager);
        var builder = new ContextMenuBuilder(context);
        builder.buildSymbolMenu();
        return builder;
    }

    /**
     * Creates a context menu for files
     */
    public static ContextMenuBuilder forFiles(List<ProjectFile> files, Chrome chrome, ContextManager contextManager) {
        var context = new FileMenuContext(files, chrome, contextManager);
        var builder = new ContextMenuBuilder(context);
        builder.buildFileMenu();
        return builder;
    }

    /**
     * Shows the menu at the specified coordinates
     */
    public void show(Component component, int x, int y) {
        if (menu.getComponentCount() > 0) {
            menu.show(component, x, y);
        }
    }

    private void buildSymbolMenu() {
        if (!(context instanceof SymbolMenuContext symbolContext)) {
            return;
        }

        // Header item (disabled)
        var headerItem = new JMenuItem("Symbol: " + symbolContext.symbolName());
        headerItem.setEnabled(false);
        menu.add(headerItem);
        menu.addSeparator();

        if (symbolContext.symbolExists()) {
            // Go to Definition
            var goToDefItem = new JMenuItem("Go to Definition");
            goToDefItem.addActionListener(e -> goToDefinition(symbolContext));
            menu.add(goToDefItem);

            // Find References
            var findRefsItem = new JMenuItem("Find References");
            findRefsItem.addActionListener(e -> findReferences(symbolContext));
            menu.add(findRefsItem);

            menu.addSeparator();
        }

        // Copy Symbol Name
        var copyItem = new JMenuItem("Copy Symbol Name");
        copyItem.addActionListener(e -> copySymbolName(symbolContext));
        menu.add(copyItem);

        // Search for Symbol
        var searchItem = new JMenuItem("Search for Symbol");
        searchItem.addActionListener(e -> searchForSymbol(symbolContext));
        menu.add(searchItem);
    }

    private void buildFileMenu() {
        if (!(context instanceof FileMenuContext fileContext)) {
            return;
        }

        var files = fileContext.files();
        if (files.isEmpty()) {
            return;
        }

        // Show History (single file only)
        if (files.size() == 1) {
            var historyItem = createHistoryMenuItem(fileContext);
            menu.add(historyItem);
            menu.addSeparator();
        }

        boolean allFilesTracked = fileContext.contextManager().getProject().getRepo().getTrackedFiles().containsAll(files);

        // Edit
        var editItem = new JMenuItem(files.size() == 1 ? "Edit" : "Edit All");
        editItem.addActionListener(e -> editFiles(fileContext));
        editItem.setEnabled(allFilesTracked);
        menu.add(editItem);

        // Read
        var readItem = new JMenuItem(files.size() == 1 ? "Read" : "Read All");
        readItem.addActionListener(e -> readFiles(fileContext));
        menu.add(readItem);

        // Summarize
        var summarizeItem = new JMenuItem(files.size() == 1 ? "Summarize" : "Summarize All");
        summarizeItem.addActionListener(e -> summarizeFiles(fileContext));
        menu.add(summarizeItem);

        menu.addSeparator();

        // Run Tests
        var runTestsItem = new JMenuItem("Run Tests");
        boolean hasTestFiles = files.stream().allMatch(ContextManager::isTestFile);
        runTestsItem.setEnabled(hasTestFiles);
        if (!hasTestFiles) {
            runTestsItem.setToolTipText("Non-test files in selection");
        }
        runTestsItem.addActionListener(e -> runTests(fileContext));
        menu.add(runTestsItem);
    }

    // Symbol actions
    private void goToDefinition(SymbolMenuContext context) {
        logger.info("Go to definition for symbol: {}", context.symbolName());
        // TODO: Implement symbol definition lookup
        context.chrome().systemNotify("Go to definition not yet implemented", "Symbol Action", JOptionPane.INFORMATION_MESSAGE);
    }

    private void findReferences(SymbolMenuContext context) {
        logger.info("Find references for symbol: {}", context.symbolName());
        // TODO: Implement symbol reference search
        context.chrome().systemNotify("Find references not yet implemented", "Symbol Action", JOptionPane.INFORMATION_MESSAGE);
    }

    private void copySymbolName(SymbolMenuContext context) {
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        var selection = new StringSelection(context.symbolName());
        clipboard.setContents(selection, null);
        logger.debug("Copied symbol name to clipboard: {}", context.symbolName());
    }

    private void searchForSymbol(SymbolMenuContext context) {
        logger.info("Search for symbol: {}", context.symbolName());
        // TODO: Integrate with search functionality
        context.chrome().systemNotify("Search for symbol not yet implemented", "Symbol Action", JOptionPane.INFORMATION_MESSAGE);
    }

    // File actions
    private JMenuItem createHistoryMenuItem(FileMenuContext context) {
        var file = context.files().getFirst();
        boolean hasGit = context.contextManager().getProject().hasGit();
        var historyItem = new JMenuItem("Show History");
        historyItem.addActionListener(e -> {
            if (context.chrome().getGitPanel() != null) {
                context.chrome().getGitPanel().addFileHistoryTab(file);
            } else {
                logger.warn("GitPanel is null, cannot show history for {}", file);
            }
        });
        historyItem.setEnabled(hasGit);
        if (!hasGit) {
            historyItem.setToolTipText("Git not available for this project.");
        }
        return historyItem;
    }

    private void editFiles(FileMenuContext context) {
        context.contextManager().submitContextTask("Edit files", () -> {
            context.contextManager().editFiles(context.files());
        });
    }

    private void readFiles(FileMenuContext context) {
        context.contextManager().submitContextTask("Read files", () -> {
            context.contextManager().addReadOnlyFiles(context.files());
        });
    }

    private void summarizeFiles(FileMenuContext context) {
        if (!context.contextManager().getAnalyzerWrapper().isReady()) {
            context.chrome().systemNotify(
                AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        context.contextManager().submitContextTask("Summarize files", () -> {
            context.contextManager().addSummaries(new HashSet<>(context.files()), Collections.emptySet());
        });
    }

    private void runTests(FileMenuContext context) {
        context.contextManager().submitContextTask("Run selected tests", () -> {
            var testProjectFiles = context.files().stream()
                .filter(ContextManager::isTestFile)
                .collect(Collectors.toSet());

            if (!testProjectFiles.isEmpty()) {
                RunTestsService.runTests(context.chrome(), context.contextManager(), testProjectFiles);
            } else {
                context.chrome().toolError("No test files were selected to run");
            }
        });
    }
}