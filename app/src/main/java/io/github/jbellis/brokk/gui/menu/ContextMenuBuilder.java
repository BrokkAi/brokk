package io.github.jbellis.brokk.gui.menu;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.RunTestsService;
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
            String symbolName, boolean symbolExists, Chrome chrome, ContextManager contextManager) {
        var context = new SymbolMenuContext(symbolName, symbolExists, chrome, contextManager);
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
        var headerItem = new JMenuItem("Symbol: " + symbolContext.symbolName());
        headerItem.setEnabled(false);
        menu.add(headerItem);
        menu.addSeparator();

        boolean analyzerReady =
                symbolContext.contextManager().getAnalyzerWrapper().isReady();

        if (symbolContext.symbolExists()) {
            // Go to Definition
            var goToDefItem = new JMenuItem("Go to Definition");
            goToDefItem.setEnabled(analyzerReady);
            goToDefItem.addActionListener(e -> goToDefinition(symbolContext));
            menu.add(goToDefItem);

            // Find References
            var findRefsItem = new JMenuItem("Find References");
            findRefsItem.setEnabled(analyzerReady);
            findRefsItem.addActionListener(e -> findReferences(symbolContext));
            menu.add(findRefsItem);

            menu.addSeparator();
        }

        // Copy Symbol Name
        var copyItem = new JMenuItem("Copy Symbol Name");
        copyItem.addActionListener(e -> copySymbolName(symbolContext));
        menu.add(copyItem);
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
    private void goToDefinition(SymbolMenuContext context) {
        logger.info("Go to definition for symbol: {}", context.symbolName());

        if (!context.contextManager().getAnalyzerWrapper().isReady()) {
            context.chrome()
                    .systemNotify(
                            AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                            AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                            JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        var symbolName = context.symbolName();
        context.contextManager().submitContextTask("Go to definition for " + symbolName, () -> {
            var analyzer = context.contextManager().getAnalyzerUninterrupted();

            // First try exact FQN match
            var definition = analyzer.getDefinition(symbolName);
            if (definition.isPresent()) {
                navigateToSymbol(definition.get(), context);
                return;
            }

            // Fallback: search for candidates with the symbol name
            var candidates = analyzer.searchDefinitions(symbolName);
            if (candidates.isEmpty()) {
                SwingUtilities.invokeLater(() -> context.chrome()
                        .systemNotify(
                                "Definition not found for: " + symbolName,
                                "Go to Definition",
                                JOptionPane.WARNING_MESSAGE));
                return;
            }

            if (candidates.size() == 1) {
                navigateToSymbol(candidates.get(0), context);
            } else {
                // Multiple matches - show disambiguation dialog on EDT
                SwingUtilities.invokeLater(() -> {
                    var selected = showSymbolDisambiguationDialog(candidates, symbolName, "Go to Definition");
                    if (selected != null) {
                        // Navigate back in background thread
                        context.contextManager().submitContextTask("Navigate to " + selected.fqName(), () -> {
                            navigateToSymbol(selected, context);
                        });
                    }
                });
            }
        });
    }

    private void findReferences(SymbolMenuContext context) {
        logger.info("Find references for symbol: {}", context.symbolName());

        if (!context.contextManager().getAnalyzerWrapper().isReady()) {
            context.chrome()
                    .systemNotify(
                            AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                            AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                            JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        var symbolName = context.symbolName();
        context.contextManager().submitContextTask("Find references for " + symbolName, () -> {
            var analyzer = context.contextManager().getAnalyzerUninterrupted();

            // First try exact FQN match for uses
            var uses = analyzer.getUses(symbolName);
            if (!uses.isEmpty()) {
                addUsagesToContext(uses, symbolName, context);
                SwingUtilities.invokeLater(() -> context.chrome()
                        .systemNotify(
                                String.format("Found %d references for %s", uses.size(), symbolName),
                                "Find References",
                                JOptionPane.INFORMATION_MESSAGE));
                return;
            }

            // If no direct uses found, try to find the symbol first, then get its uses
            var definition = analyzer.getDefinition(symbolName);
            if (definition.isPresent()) {
                var symbolFqName = definition.get().fqName();
                var definitionUses = analyzer.getUses(symbolFqName);
                if (!definitionUses.isEmpty()) {
                    addUsagesToContext(definitionUses, symbolFqName, context);
                    SwingUtilities.invokeLater(() -> context.chrome()
                            .systemNotify(
                                    String.format("Found %d references for %s", definitionUses.size(), symbolFqName),
                                    "Find References",
                                    JOptionPane.INFORMATION_MESSAGE));
                    return;
                }
            }

            // Fallback: search for symbol candidates and let user choose
            var candidates = analyzer.searchDefinitions(symbolName);
            if (candidates.isEmpty()) {
                SwingUtilities.invokeLater(() -> context.chrome()
                        .systemNotify(
                                "No references found for: " + symbolName,
                                "Find References",
                                JOptionPane.WARNING_MESSAGE));
                return;
            }

            if (candidates.size() == 1) {
                var symbolFqName = candidates.get(0).fqName();
                var candidateUses = analyzer.getUses(symbolFqName);
                if (!candidateUses.isEmpty()) {
                    addUsagesToContext(candidateUses, symbolFqName, context);
                    SwingUtilities.invokeLater(() -> context.chrome()
                            .systemNotify(
                                    String.format("Found %d references for %s", candidateUses.size(), symbolFqName),
                                    "Find References",
                                    JOptionPane.INFORMATION_MESSAGE));
                } else {
                    SwingUtilities.invokeLater(() -> context.chrome()
                            .systemNotify(
                                    "No references found for: " + symbolFqName,
                                    "Find References",
                                    JOptionPane.WARNING_MESSAGE));
                }
            } else {
                // Multiple matches - show disambiguation dialog on EDT
                SwingUtilities.invokeLater(() -> {
                    var selected = showSymbolDisambiguationDialog(candidates, symbolName, "Find References");
                    if (selected != null) {
                        // Continue processing in background thread
                        context.contextManager().submitContextTask("Find references for " + selected.fqName(), () -> {
                            var symbolFqName = selected.fqName();
                            var selectedUses = analyzer.getUses(symbolFqName);
                            if (!selectedUses.isEmpty()) {
                                addUsagesToContext(selectedUses, symbolFqName, context);
                                SwingUtilities.invokeLater(() -> context.chrome()
                                        .systemNotify(
                                                String.format(
                                                        "Found %d references for %s",
                                                        selectedUses.size(), symbolFqName),
                                                "Find References",
                                                JOptionPane.INFORMATION_MESSAGE));
                            } else {
                                SwingUtilities.invokeLater(() -> context.chrome()
                                        .systemNotify(
                                                "No references found for: " + symbolFqName,
                                                "Find References",
                                                JOptionPane.WARNING_MESSAGE));
                            }
                        });
                    }
                });
            }
        });
    }

    private void copySymbolName(SymbolMenuContext context) {
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        var selection = new StringSelection(context.symbolName());
        clipboard.setContents(selection, null);
        logger.debug("Copied symbol name to clipboard: {}", context.symbolName());
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

    private void navigateToSymbol(CodeUnit symbol, SymbolMenuContext context) {
        logger.debug("Navigating to symbol: {} in file: {}", symbol.fqName(), symbol.source());

        context.contextManager().submitContextTask("Navigate to " + symbol.fqName(), () -> {
            context.contextManager().editFiles(List.of(symbol.source()));
        });
    }

    private void addUsagesToContext(List<CodeUnit> uses, String symbolName, SymbolMenuContext context) {
        logger.debug("Adding {} usages of {} to context", uses.size(), symbolName);

        var usageFiles = uses.stream().map(CodeUnit::source).distinct().toList();

        context.contextManager().submitContextTask("Add references for " + symbolName, () -> {
            context.contextManager().addReadOnlyFiles(usageFiles);
        });
    }

    private @Nullable CodeUnit showSymbolDisambiguationDialog(
            List<CodeUnit> candidates, String symbolName, String title) {
        if (candidates.isEmpty()) {
            return null;
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        var options = candidates.stream()
                .map(cu -> String.format("%s (%s)", cu.fqName(), cu.source().toString()))
                .toArray(String[]::new);

        int choice = JOptionPane.showOptionDialog(
                null,
                "Multiple " + symbolName + " symbols found. Select one:",
                title,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        return choice >= 0 ? candidates.get(choice) : null;
    }
}
