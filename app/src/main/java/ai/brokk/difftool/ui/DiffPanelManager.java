package ai.brokk.difftool.ui;

import ai.brokk.ContextManager;
import ai.brokk.difftool.performance.PerformanceConstants;
import ai.brokk.difftool.ui.unified.UnifiedDiffDocument;
import ai.brokk.difftool.ui.unified.UnifiedDiffPanel;
import ai.brokk.util.SlidingWindowCache;
import ai.brokk.gui.theme.GuiTheme;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Manages the lifecycle, caching, and navigation of diff panels.
 */
public class DiffPanelManager implements DiffNavigationTarget {
    private static final Logger logger = LogManager.getLogger(DiffPanelManager.class);

    @Nullable private final BrokkDiffPanel parent;
    private final List<BrokkDiffPanel.FileComparisonInfo> fileComparisons;
    private final ContextManager contextManager;
    private final Consumer<AbstractDiffPanel> displayCallback;
    private final Supplier<GuiTheme> themeSupplier;
    private final SlidingWindowCache<Integer, AbstractDiffPanel> panelCache;

    private int currentFileIndex = 0;

    public DiffPanelManager(
            @Nullable BrokkDiffPanel parent,
            List<BrokkDiffPanel.FileComparisonInfo> fileComparisons,
            ContextManager contextManager,
            Consumer<AbstractDiffPanel> displayCallback,
            Supplier<GuiTheme> themeSupplier) {
        this.parent = parent;
        this.fileComparisons = fileComparisons;
        this.contextManager = contextManager;
        this.displayCallback = displayCallback;
        this.themeSupplier = themeSupplier;
        this.panelCache = new SlidingWindowCache<>(
                PerformanceConstants.MAX_CACHED_DIFF_PANELS,
                PerformanceConstants.DEFAULT_SLIDING_WINDOW);
    }

    @Override
    public void navigateToFile(int fileIndex) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        if (fileIndex < 0 || fileIndex >= fileComparisons.size()) {
            logger.warn("Invalid file index {} (valid range: 0-{})", fileIndex, fileComparisons.size() - 1);
            return;
        }

        currentFileIndex = fileIndex;
        panelCache.updateWindowCenter(fileIndex, fileComparisons.size());

        loadFileOnDemand(currentFileIndex, false);
        preloadAdjacentFiles(currentFileIndex);
    }

    @Override
    public void navigateToLocation(int fileIndex, int lineNumber) {
        navigateToFile(fileIndex);
        // Location targeting implementation details (like scrolling) would follow here
        // or be handled by the displayed panel once loaded.
    }

    @Override
    public int getCurrentFileIndex() {
        return currentFileIndex;
    }

    @Override
    public int getFileComparisonCount() {
        return fileComparisons.size();
    }

    public void loadFileOnDemand(int fileIndex, boolean skipLoadingUI) {
        if (fileIndex < 0 || fileIndex >= fileComparisons.size()) return;

        AbstractDiffPanel cachedPanel = panelCache.get(fileIndex);
        if (cachedPanel != null) {
            displayCachedFile(fileIndex, cachedPanel);
            return;
        }

        if (!panelCache.tryReserve(fileIndex)) {
            // Check if it finished loading while we tried to reserve
            AbstractDiffPanel nowCached = panelCache.get(fileIndex);
            if (nowCached != null) {
                displayCachedFile(fileIndex, nowCached);
            } else if (!skipLoadingUI && parent != null) {
                parent.showLoadingForFile();
            }
            return;
        }

        if (!skipLoadingUI && parent != null) {
            parent.showLoadingForFile();
        }

        BrokkDiffPanel.FileComparisonInfo compInfo = fileComparisons.get(fileIndex);
        
        GuiTheme theme = themeSupplier.get();
        boolean isMultipleCommits = parent != null && parent.isMultipleCommitsContext();

        BrokkDiffPanel.createDiffPanel(
                compInfo.leftSource,
                compInfo.rightSource,
                parent,
                theme,
                contextManager,
                isMultipleCommits,
                fileIndex);
    }

    public void cachePanel(int fileIndex, AbstractDiffPanel panel) {
        // Ensure we don't cache panels from the wrong view mode
        boolean isUnified = parent != null ? parent.isUnifiedView() : false;
        if (panel instanceof UnifiedDiffPanel != isUnified) {
            return;
        }

        panel.setAssociatedFileIndex(fileIndex);
        panel.resetAutoScrollFlag();

        // Apply global font settings
        if (parent != null && parent.getCurrentFontIndex() >= 0) {
            panel.applyEditorFontSize(BrokkDiffPanel.FONT_SIZES.get(parent.getCurrentFontIndex()));
        }

        if (panelCache.isInWindow(fileIndex)) {
            panelCache.putReserved(fileIndex, panel);
        } else {
            panelCache.removeReserved(fileIndex);
            panel.dispose();
        }
    }

    public void displayCachedFile(int fileIndex, AbstractDiffPanel cachedPanel) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // If the cached panel mode doesn't match current view mode, discard and reload
        boolean isUnified = parent != null ? parent.isUnifiedView() : false;
        if (cachedPanel instanceof UnifiedDiffPanel != isUnified) {
            cachedPanel.dispose();
            panelCache.clear();
            panelCache.updateWindowCenter(currentFileIndex, fileComparisons.size());
            loadFileOnDemand(fileIndex, false);
            return;
        }

        displayCallback.accept(cachedPanel);
    }

    private void preloadAdjacentFiles(int currentIndex) {
        contextManager.getBackgroundTasks().execute(() -> {
            preloadIfNeeded(currentIndex - 1);
            preloadIfNeeded(currentIndex + 1);
        });
    }

    private void preloadIfNeeded(int index) {
        if (index >= 0 && index < fileComparisons.size() 
                && panelCache.get(index) == null 
                && panelCache.isInWindow(index)) {
            preloadFile(index);
        }
    }

    private void preloadFile(int fileIndex) {
        try {
            var compInfo = fileComparisons.get(fileIndex);
            if (!FileComparisonHelper.isValidForPreload(compInfo.leftSource, compInfo.rightSource)) {
                return;
            }

            boolean isMultipleCommits = parent != null && parent.isMultipleCommitsContext();
            var result = FileComparisonHelper.createFileLoadingResult(
                    compInfo.leftSource, compInfo.rightSource, contextManager, isMultipleCommits);

            if (result.isSuccess() && result.getDiffNode() != null) {
                result.getDiffNode().diff();
                
                SwingUtilities.invokeLater(() -> {
                    // Check if still in window and not loaded by now
                    if (panelCache.get(fileIndex) == null && panelCache.isInWindow(fileIndex)) {
                        AbstractDiffPanel panel;
                        GuiTheme theme = themeSupplier.get();
                        if (parent != null && parent.isUnifiedView()) {
                            panel = new UnifiedDiffPanel(parent, theme, result.getDiffNode());
                            var targetMode = parent.getGlobalShowAllLinesInUnified()
                                    ? UnifiedDiffDocument.ContextMode.FULL_CONTEXT
                                    : UnifiedDiffDocument.ContextMode.STANDARD_3_LINES;
                            ((UnifiedDiffPanel) panel).setContextMode(targetMode);
                        } else {
                            panel = new BufferDiffPanel(parent, theme);
                            panel.setDiffNode(result.getDiffNode());
                        }
                        panel.applyTheme(theme);
                        cachePanel(fileIndex, panel);
                    }
                });
            }
        } catch (Exception e) {
            logger.warn("Failed to preload file {}: {}", fileIndex, e.getMessage());
        }
    }

    public Iterable<AbstractDiffPanel> getCachedPanels() {
        return panelCache.nonNullValues();
    }

    public Set<Integer> getCachedKeys() {
        return panelCache.getCachedKeys();
    }

    @Nullable
    public AbstractDiffPanel getPanel(int index) {
        return panelCache.get(index);
    }

    public void removeReserved(int index) {
        panelCache.removeReserved(index);
    }

    public void clearCache() {
        panelCache.clear();
    }
}
