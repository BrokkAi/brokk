package io.github.jbellis.brokk.gui.menu;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.RunTestsService;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** Builder for creating consistent context menus for files and symbols */
public class ContextMenuBuilder {
    private static final Logger logger = LogManager.getLogger(ContextMenuBuilder.class);

    private final JPopupMenu menu;
    private final MenuContext context;

    private ContextMenuBuilder(MenuContext context) {
        this.context = context;
        this.menu = new JPopupMenu();
        context.chrome().getTheme().registerPopupMenu(menu);
    }

    /** Creates a context menu for symbols */
    public static ContextMenuBuilder forSymbol(
            String symbolName,
            boolean symbolExists,
            @Nullable String fqn,
            Chrome chrome,
            ContextManager contextManager) {
        var context = new SymbolMenuContext(symbolName, symbolExists, fqn, chrome, contextManager);
        var builder = new ContextMenuBuilder(context);
        builder.buildSymbolMenu();
        return builder;
    }

    /** Creates a context menu for files */
    public static ContextMenuBuilder forFiles(List<ProjectFile> files, Chrome chrome, ContextManager contextManager) {
        var context = new FileMenuContext(files, chrome, contextManager);
        var builder = new ContextMenuBuilder(context);
        builder.buildFileMenu();
        return builder;
    }

    /** Shows the menu at the specified coordinates */
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
        var headerItem = new JMenuItem("Class: " + symbolContext.symbolName());
        headerItem.setEnabled(false);
        menu.add(headerItem);
        menu.addSeparator();

        boolean analyzerReady =
                symbolContext.contextManager().getAnalyzerWrapper().isReady();

        if (symbolContext.symbolExists()) {
            // Check if we have multiple FQNs (comma-separated)
            var fqn = symbolContext.fqn();
            if (fqn != null && fqn.contains(",")) {
                // Multiple matches - create submenus
                var fqns = Splitter.on(',').split(fqn);
                var uniqueFqns = new HashSet<String>();

                for (String currentFqn : fqns) {
                    var trimmedFqn = currentFqn.trim();
                    // Skip duplicates
                    if (uniqueFqns.add(trimmedFqn)) {
                        var submenu = new JMenu(trimmedFqn);

                        // Create a context for this specific FQN
                        var specificContext = new SymbolMenuContext(
                                symbolContext.symbolName(),
                                true,
                                trimmedFqn,
                                symbolContext.chrome(),
                                symbolContext.contextManager());

                        // Add symbol actions to this submenu
                        addSymbolActions(submenu, specificContext, analyzerReady);

                        menu.add(submenu);
                    }
                }
            } else {
                // Single match - add symbol actions directly to main menu
                addSymbolActions(menu, symbolContext, analyzerReady);
            }

            menu.addSeparator();
        }

        // Copy Symbol Name (FQN if available)
        var copyItem = new JMenuItem("Copy Class Name");
        copyItem.addActionListener(e -> copySymbolName(symbolContext));
        menu.add(copyItem);
    }

    /**
     * Helper method to add symbol actions (Open in Preview, Open in Project Tree, Read File, Edit File, Summarize File)
     * to a container
     */
    private void addSymbolActions(Container parent, SymbolMenuContext context, boolean analyzerReady) {
        // Open in Preview
        var openInPreviewItem = new JMenuItem("Open in Preview");
        openInPreviewItem.setEnabled(analyzerReady);
        openInPreviewItem.addActionListener(e -> openInPreview(context));
        parent.add(openInPreviewItem);

        // Open in Project Tree
        var openInTreeItem = new JMenuItem("Open in Project Tree");
        openInTreeItem.setEnabled(analyzerReady);
        openInTreeItem.addActionListener(e -> openInProjectTree(context));
        parent.add(openInTreeItem);

        parent.add(new JPopupMenu.Separator());

        // Read File
        var readFileItem = new JMenuItem("Read File");
        readFileItem.addActionListener(e -> readFiles(context));
        parent.add(readFileItem);

        // Edit File
        var editFileItem = new JMenuItem("Edit File");
        editFileItem.setEnabled(analyzerReady);
        editFileItem.addActionListener(e -> editFiles(context));
        parent.add(editFileItem);

        // Summarize File
        var summarizeFileItem = new JMenuItem("Summarize File");
        summarizeFileItem.setEnabled(analyzerReady);
        summarizeFileItem.addActionListener(e -> summarizeFiles(context));
        parent.add(summarizeFileItem);
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

        boolean allFilesTracked = fileContext
                .contextManager()
                .getProject()
                .getRepo()
                .getTrackedFiles()
                .containsAll(files);

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
        boolean analyzerReady =
                fileContext.contextManager().getAnalyzerWrapper().isReady();
        summarizeItem.setEnabled(analyzerReady);
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
    /** Helper to find a symbol definition using exact FQN lookup with fallback search */
    private Optional<CodeUnit> findSymbolDefinition(SymbolMenuContext context) {
        if (!context.contextManager().getAnalyzerWrapper().isReady()) {
            context.chrome()
                    .systemNotify(
                            AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                            AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                            JOptionPane.INFORMATION_MESSAGE);
            return Optional.empty();
        }

        var symbolName = context.symbolName();
        var fqn = context.fqn() != null ? context.fqn() : symbolName;
        var analyzer = context.contextManager().getAnalyzerUninterrupted();

        try {
            // First try exact FQN match
            var definition = analyzer.getDefinition(fqn);
            if (definition.isPresent()) {
                return definition;
            }
        } catch (Exception e) {
            logger.warn("Error during exact FQN lookup for '{}': {}", fqn, e.getMessage());
            context.chrome().toolError("Failed to find definition: " + e.getMessage(), "Symbol Lookup Error");
            return Optional.empty();
        }

        // Fallback: search for candidates with the symbol name
        List<CodeUnit> candidates;
        try {
            candidates = analyzer.searchDefinitions(symbolName);
            if (candidates.isEmpty()) {
                context.chrome()
                        .systemNotify(
                                "Definition not found for: " + symbolName,
                                "Symbol Lookup",
                                JOptionPane.WARNING_MESSAGE);
                return Optional.empty();
            }
        } catch (Exception e) {
            logger.warn("Error during fallback search for '{}': {}", symbolName, e.getMessage());
            context.chrome().toolError("Search failed: " + e.getMessage(), "Symbol Lookup Error");
            return Optional.empty();
        }

        // If multiple candidates, show popup and return first
        if (candidates.size() > 1) {
            var matchList = candidates.stream()
                    .map(candidate -> String.format("• %s in %s", candidate.shortName(), candidate.source()))
                    .collect(Collectors.joining("\n"));

            var message = String.format(
                    "Found %d definitions for '%s'. Using the first one:\n\n%s",
                    candidates.size(), symbolName, matchList);

            context.chrome().systemNotify(message, "Multiple Definitions Found", JOptionPane.INFORMATION_MESSAGE);
        }

        return Optional.of(candidates.getFirst());
    }

    private void openInPreview(SymbolMenuContext context) {
        logger.info("Open in preview for symbol: {}", context.symbolName());

        var symbolName = context.symbolName();
        context.contextManager().submitContextTask("Open in preview for " + symbolName, () -> {
            var definition = findSymbolDefinition(context);
            definition.ifPresent(codeUnit -> openPreview(codeUnit, context));
        });
    }

    private void openInProjectTree(SymbolMenuContext context) {
        logger.info("Open in project tree for symbol: {}", context.symbolName());

        var symbolName = context.symbolName();
        context.contextManager().submitContextTask("Open in project tree for " + symbolName, () -> {
            var definition = findSymbolDefinition(context);
            if (definition.isPresent()) {
                var projectFile = definition.get().source();
                SwingUtilities.invokeLater(() -> {
                    context.chrome().getProjectFilesPanel().showFileInTree(projectFile);
                });
            }
        });
    }

    private void copySymbolName(SymbolMenuContext context) {
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        // Use FQN if available, otherwise fall back to simple name
        var nameToClipboard = context.fqn() != null ? context.fqn() : context.symbolName();
        var selection = new StringSelection(nameToClipboard);
        clipboard.setContents(selection, null);
        logger.debug("Copied symbol name to clipboard: {}", nameToClipboard);
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
            context.chrome()
                    .systemNotify(
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
            var testProjectFiles =
                    context.files().stream().filter(ContextManager::isTestFile).collect(Collectors.toSet());

            if (!testProjectFiles.isEmpty()) {
                RunTestsService.runTests(context.chrome(), context.contextManager(), testProjectFiles);
            } else {
                context.chrome().toolError("No test files were selected to run");
            }
        });
    }

    private void openPreview(CodeUnit symbol, SymbolMenuContext context) {
        logger.debug("Opening symbol in preview: {} in file: {}", symbol.fqName(), symbol.source());

        context.contextManager().submitContextTask("Open preview for " + symbol.fqName(), () -> {
            SwingUtilities.invokeLater(() -> {
                // Use Chrome's centralized preview system
                context.chrome().previewFile(symbol.source());
            });
        });
    }

    // Symbol-based file operations (overloaded versions)
    private void editFiles(SymbolMenuContext context) {
        context.contextManager().submitContextTask("Edit file", () -> {
            var definition = findSymbolDefinition(context);
            if (definition.isPresent()) {
                var file = definition.get().source();

                // Check if file is tracked by git
                var trackedFiles =
                        context.contextManager().getProject().getRepo().getTrackedFiles();
                if (!trackedFiles.contains(file)) {
                    SwingUtilities.invokeLater(() -> {
                        context.chrome().toolError("Cannot edit file: not tracked by git", "Edit File Error");
                    });
                    return;
                }

                context.contextManager().editFiles(List.of(file));
            }
        });
    }

    private void readFiles(SymbolMenuContext context) {
        context.contextManager().submitContextTask("Read file", () -> {
            var definition = findSymbolDefinition(context);
            if (definition.isPresent()) {
                var file = definition.get().source();
                context.contextManager().addReadOnlyFiles(List.of(file));
            }
        });
    }

    private void summarizeFiles(SymbolMenuContext context) {
        if (!context.contextManager().getAnalyzerWrapper().isReady()) {
            context.chrome()
                    .systemNotify(
                            AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                            AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                            JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        context.contextManager().submitContextTask("Summarize file", () -> {
            var definition = findSymbolDefinition(context);
            if (definition.isPresent()) {
                var file = definition.get().source();
                context.contextManager().addSummaries(Set.of(file), Collections.emptySet());
            }
        });
    }
}
