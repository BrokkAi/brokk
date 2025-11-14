package ai.brokk.gui.menu;

import ai.brokk.AnalyzerWrapper;
import ai.brokk.ContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceCodeProvider;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.util.SourceCaptureUtil;
import ai.brokk.util.FileManagerUtil;
import com.google.common.base.Splitter;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Builder for creating consistent context menus for files and symbols. */
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

    /** Creates a context menu for file path matches (MOP disambiguation) */
    public static ContextMenuBuilder forFilePathMatches(
            List<ProjectFile> files, Chrome chrome, ContextManager contextManager) {
        var context = new FileMenuContext(files, chrome, contextManager);
        var builder = new ContextMenuBuilder(context);
        builder.buildFilePathMenu();
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

        // Capture Class Source (only when available, like PreviewTextPanel)
        if (analyzerReady) {
            var fqn = context.fqn() != null ? context.fqn() : context.symbolName();
            var analyzer = context.contextManager().getAnalyzerWrapper().getNonBlocking();
            if (analyzer != null
                    && analyzer.getDefinition(fqn).isPresent()
                    && analyzer.as(SourceCodeProvider.class).isPresent()) {
                var captureSourceItem = new JMenuItem("Capture Class Source");
                captureSourceItem.addActionListener(e -> captureClassSource(context));
                parent.add(captureSourceItem);
            }
        }
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

        // Summarize
        var summarizeItem = new JMenuItem(files.size() == 1 ? "Summarize" : "Summarize All");
        boolean analyzerReady =
                fileContext.contextManager().getAnalyzerWrapper().isReady();
        summarizeItem.setEnabled(analyzerReady);
        summarizeItem.addActionListener(e -> summarizeFiles(fileContext));
        menu.add(summarizeItem);

        var openInItem = new JMenuItem(FileManagerUtil.fileManagerActionLabel());
        openInItem.setToolTipText(FileManagerUtil.fileManagerActionTooltip());
        Path target;
        if (files.size() == 1) {
            target = files.getFirst().absPath();
        } else {
            var first = files.getFirst().absPath();
            var parent = first.getParent();
            target = parent != null ? parent : first;
        }
        openInItem.setEnabled(Files.exists(target));
        openInItem.addActionListener(e -> openInFileManager(fileContext));
        menu.add(openInItem);

        // Run Tests (only show if all files are test files)
        boolean hasTestFiles = files.stream().allMatch(ContextManager::isTestFile);
        if (hasTestFiles) {
            menu.addSeparator();
            var runTestsItem = new JMenuItem("Run Tests");
            runTestsItem.addActionListener(e -> runTests(fileContext));
            menu.add(runTestsItem);
        }
    }

    private void buildFilePathMenu() {
        if (!(context instanceof FileMenuContext fileContext)) {
            return;
        }

        var files = fileContext.files();
        if (files.isEmpty()) {
            return;
        }

        if (files.size() == 1) {
            // Single file - add actions directly to main menu
            var file = files.getFirst();
            var singleFileContext =
                    new FileMenuContext(List.of(file), fileContext.chrome(), fileContext.contextManager());

            addFileActions(menu, singleFileContext);
        } else {
            // Multiple files - create submenus for each file (no bulk actions for MOP disambiguation)
            for (var file : files) {
                var submenu = new JMenu(file.toString());

                // Create a context for this specific file
                var singleFileContext =
                        new FileMenuContext(List.of(file), fileContext.chrome(), fileContext.contextManager());

                // Add file actions to this submenu
                addFileActions(submenu, singleFileContext);

                menu.add(submenu);
            }
        }
    }

    private JMenuItem createHistoryMenuItem(FileMenuContext context) {
        var file = context.files().getFirst();
        boolean hasGit = context.contextManager().getProject().hasGit();
        var historyItem = new JMenuItem("Show History");
        historyItem.addActionListener(e -> {
            final var chrome = context.chrome();
            chrome.addFileHistoryTab(file);
        });
        historyItem.setEnabled(hasGit);
        if (!hasGit) {
            historyItem.setToolTipText("Git not available for this project.");
        }
        return historyItem;
    }

    /**
     * Helper method to add file actions (Show History, Edit, Read, Summarize, Run Tests) to a container for single-file
     * contexts
     */
    private void addFileActions(Container parent, FileMenuContext singleFileContext) {
        assert singleFileContext.files().size() == 1 : "addFileActions expects single file context";

        var file = singleFileContext.files().getFirst();
        boolean hasGit = singleFileContext.contextManager().getProject().hasGit();
        boolean isTracked = singleFileContext
                .contextManager()
                .getProject()
                .getRepo()
                .getTrackedFiles()
                .contains(file);
        boolean analyzerReady =
                singleFileContext.contextManager().getAnalyzerWrapper().isReady();
        boolean isTestFile = ContextManager.isTestFile(file);

        // Show History
        var historyItem = new JMenuItem("Show History");
        historyItem.addActionListener(e -> {
            final var chrome = singleFileContext.chrome();
            chrome.addFileHistoryTab(file);
        });
        historyItem.setEnabled(hasGit);
        if (!hasGit) {
            historyItem.setToolTipText("Git not available for this project.");
        }
        parent.add(historyItem);

        parent.add(new JPopupMenu.Separator());

        // Edit
        var editItem = new JMenuItem("Edit");
        editItem.addActionListener(e -> editFiles(singleFileContext));
        editItem.setEnabled(isTracked);
        if (!isTracked) {
            editItem.setToolTipText("File not tracked by git");
        }
        parent.add(editItem);

        // Summarize
        var summarizeItem = new JMenuItem("Summarize");
        summarizeItem.setEnabled(analyzerReady);
        summarizeItem.addActionListener(e -> summarizeFiles(singleFileContext));
        parent.add(summarizeItem);

        var openInItem = new JMenuItem(FileManagerUtil.fileManagerActionLabel());
        openInItem.setToolTipText(FileManagerUtil.fileManagerActionTooltip());
        var singleTarget = file.absPath();
        openInItem.setEnabled(Files.exists(singleTarget));
        openInItem.addActionListener(e -> openInFileManager(singleFileContext));
        parent.add(openInItem);

        // Run Tests (only if this file is a test file)
        if (isTestFile) {
            parent.add(new JPopupMenu.Separator());
            var runTestItem = new JMenuItem("Run Test");
            runTestItem.addActionListener(e -> runTests(singleFileContext));
            parent.add(runTestItem);
        }
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
        Set<CodeUnit> candidates;
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

        return Optional.of(candidates.iterator().next());
    }

    private void openInPreview(SymbolMenuContext context) {
        logger.info("Open in preview for symbol: {}", context.symbolName());

        var symbolName = context.symbolName();
        context.contextManager().submitBackgroundTask("Open in preview for " + symbolName, () -> {
            var definition = findSymbolDefinition(context);
            definition.ifPresent(codeUnit -> openPreview(codeUnit, context));
        });
    }

    private void openInProjectTree(SymbolMenuContext context) {
        logger.info("Open in project tree for symbol: {}", context.symbolName());

        var symbolName = context.symbolName();
        context.contextManager().submitBackgroundTask("Open in project tree for " + symbolName, () -> {
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

    private void editFiles(FileMenuContext context) {
        context.contextManager().submitContextTask(() -> {
            context.contextManager().addFiles(context.files());
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
        context.contextManager().submitContextTask(() -> {
            context.contextManager().addSummaries(new HashSet<>(context.files()), Collections.emptySet());
        });
    }

    private void runTests(FileMenuContext context) {
        context.contextManager().submitLlmAction(() -> {
            var testProjectFiles =
                    context.files().stream().filter(ContextManager::isTestFile).collect(Collectors.toSet());

            if (testProjectFiles.isEmpty()) {
                context.chrome().toolError("No test files were selected to run");
            } else {
                try {
                    context.chrome().runTests(testProjectFiles);
                } catch (InterruptedException e) {
                    logger.debug("Tests interrupted", e);
                }
            }
        });
    }

    private void openPreview(CodeUnit symbol, SymbolMenuContext context) {
        logger.debug("Opening symbol in preview: {} in file: {}", symbol.fqName(), symbol.source());

        context.contextManager().submitBackgroundTask("Open preview for " + symbol.fqName(), () -> {
            SwingUtilities.invokeLater(() -> {
                // Try to get the symbol's source range to position the preview
                var analyzer = context.contextManager().getAnalyzerWrapper().getNonBlocking();
                var startLine = -1; // Default: no positioning

                if (analyzer != null) {
                    try {
                        // Try direct TreeSitterAnalyzer cast first
                        var tsAnalyzer = analyzer.as(TreeSitterAnalyzer.class);

                        // If direct cast fails, try to get the delegate from MultiAnalyzer
                        if (tsAnalyzer.isEmpty() && analyzer instanceof MultiAnalyzer multiAnalyzer) {
                            var delegates = multiAnalyzer.getDelegates();

                            // Get the appropriate delegate based on file extension
                            var fileExtension = com.google.common.io.Files.getFileExtension(
                                    symbol.source().absPath().toString());
                            var language = Languages.fromExtension(fileExtension);

                            var delegate = delegates.get(language);
                            if (delegate != null) {
                                tsAnalyzer = delegate.as(TreeSitterAnalyzer.class);
                            }
                        }

                        startLine = tsAnalyzer
                                .map(tsa -> tsa.getStartLineForCodeUnit(symbol))
                                .orElse(-1);

                        if (startLine >= 0) {
                            logger.debug("Positioning preview at line {} for symbol {}", startLine, symbol.fqName());
                        }
                    } catch (Exception e) {
                        logger.debug("Could not get start line for symbol {}: {}", symbol.fqName(), e.getMessage());
                        // Fall back to default positioning
                    }
                }

                // Use Chrome's positioned preview system
                context.chrome().previewFile(symbol.source(), startLine);
            });
        });
    }

    // Symbol-based file operations (overloaded versions)
    private void editFiles(SymbolMenuContext context) {
        context.contextManager().submitContextTask(() -> {
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

                context.contextManager().addFiles(List.of(file));
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
        context.contextManager().submitContextTask(() -> {
            var definition = findSymbolDefinition(context);
            if (definition.isPresent()) {
                var file = definition.get().source();
                context.contextManager().addSummaries(Set.of(file), Collections.emptySet());
            }
        });
    }

    /**
     * Capture class source for the given symbol context.
     *
     * <p>Uses shared SourceCaptureUtil to ensure consistent behavior with PreviewTextPanel.
     */
    private void captureClassSource(SymbolMenuContext context) {
        if (!context.contextManager().getAnalyzerWrapper().isReady()) {
            context.chrome()
                    .systemNotify(
                            AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                            AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                            JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        var fqn = context.fqn() != null ? context.fqn() : context.symbolName();
        context.contextManager().submitBackgroundTask("Capture Source Code", () -> {
            var definition = findSymbolDefinition(context);
            if (definition.isPresent()) {
                SourceCaptureUtil.captureSourceForCodeUnit(definition.get(), context.contextManager());
            } else {
                SwingUtilities.invokeLater(() -> {
                    context.chrome()
                            .systemNotify(
                                    "No source definition found for: " + fqn,
                                    "Capture Class Source",
                                    JOptionPane.WARNING_MESSAGE);
                });
            }
        });
    }

    private void openInFileManager(FileMenuContext context) {
        var files = context.files();
        if (files.isEmpty()) {
            return;
        }

        Path target;
        if (files.size() == 1) {
            target = files.getFirst().absPath();
        } else {
            var first = files.getFirst().absPath();
            var parent = first.getParent();
            target = parent != null ? parent : first;
        }

        var taskLabel = FileManagerUtil.fileManagerActionLabel();
        context.contextManager().submitBackgroundTask(taskLabel, () -> {
            try {
                FileManagerUtil.revealPath(target);
            } catch (IOException | UnsupportedOperationException ex) {
                logger.warn("Failed to open file manager for {}: {}", target, ex.getMessage());
                SwingUtilities.invokeLater(() -> context.chrome()
                        .toolError("Failed to open file manager: " + ex.getMessage(), "Open in File Manager"));
            }
        });
    }
}
