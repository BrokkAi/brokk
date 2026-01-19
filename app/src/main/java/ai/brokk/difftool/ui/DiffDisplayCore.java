package ai.brokk.difftool.ui;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.difftool.node.JMDiffNode;
import ai.brokk.difftool.performance.PerformanceConstants;
import ai.brokk.difftool.ui.unified.UnifiedDiffDocument;
import ai.brokk.difftool.ui.unified.UnifiedDiffPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.util.ReviewParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Core logic for managing a list of file comparisons, the current index,
 * and a sliding cache of diff panels.
 */
public class DiffDisplayCore {
    private static final Logger logger = LogManager.getLogger(DiffDisplayCore.class);

    private final BrokkDiffPanel mainPanel;
    private final ContextManager contextManager;
    private final GuiTheme theme;
    private List<FileComparisonInfo> fileComparisons;
    private final boolean isMultipleCommitsContext;

    private int currentIndex = 0;
    private final Map<Integer, AbstractDiffPanel> panelCache = new HashMap<>();
    private final AtomicInteger updateGeneration = new AtomicInteger(0);

    public DiffDisplayCore(
            BrokkDiffPanel mainPanel,
            ContextManager contextManager,
            GuiTheme theme,
            List<FileComparisonInfo> fileComparisons,
            boolean isMultipleCommitsContext,
            int initialIndex) {
        this.mainPanel = mainPanel;
        this.contextManager = contextManager;
        this.theme = theme;
        this.isMultipleCommitsContext = isMultipleCommitsContext;
        this.fileComparisons = List.copyOf(fileComparisons);
        this.currentIndex = clampIndex(initialIndex, this.fileComparisons.size());
    }

    public void updateFileComparisons(List<FileComparisonInfo> newFileComparisons) {
        updateFileComparisons(newFileComparisons, currentIndex);
    }

    public void updateFileComparisons(List<FileComparisonInfo> newFileComparisons, int preferredIndex) {
        updateGeneration.incrementAndGet();
        clearCache();
        this.fileComparisons = List.copyOf(newFileComparisons);
        this.currentIndex = clampIndex(preferredIndex, this.fileComparisons.size());
    }

    private static int clampIndex(int index, int size) {
        if (size == 0) return 0;
        return Math.max(0, Math.min(index, size - 1));
    }

    public List<FileComparisonInfo> getFileComparisons() {
        return fileComparisons;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void showFile(int index) {
        assert SwingUtilities.isEventDispatchThread() : "showFile must be called on EDT";
        if (index < 0 || index >= fileComparisons.size()) return;
        currentIndex = index;
        updateCacheAndDisplay(-1, ReviewParser.DiffSide.NEW);
    }

    public void showFile(ProjectFile file) {
        assert SwingUtilities.isEventDispatchThread() : "showFile must be called on EDT";
        int index = findIndex(file);
        if (index != -1) {
            showFile(index);
        }
    }

    public void showLocation(ProjectFile file, int lineNumber) {
        showLocation(file, lineNumber, ReviewParser.DiffSide.NEW);
    }

    public void showLocation(ProjectFile file, int lineNumber, ReviewParser.DiffSide side) {
        assert SwingUtilities.isEventDispatchThread() : "showLocation must be called on EDT";
        int index = findIndex(file);
        if (index != -1) {
            currentIndex = index;
            updateCacheAndDisplay(lineNumber, side);
        }
    }

    private int findIndex(ProjectFile file) {
        for (int i = 0; i < fileComparisons.size(); i++) {
            var info = fileComparisons.get(i);
            // Direct comparison via ProjectFile record is preferred
            if (file.equals(info.file())) return i;

            // Fallback for cases where source metadata might match but record doesn't
            if (matches(info.leftSource(), file) || matches(info.rightSource(), file)) return i;
        }
        return -1;
    }

    private boolean matches(BufferSource source, ProjectFile file) {
        if (source instanceof BufferSource.FileSource fs) return fs.file().equals(file);
        String name = source.filename();
        if (name == null) return false;

        try {
            Path p = Path.of(name);
            return p.equals(file.absPath()) || p.equals(file.getRelPath());
        } catch (Exception e) {
            return name.replace('\\', '/').endsWith(file.getRelPath().toString().replace('\\', '/'));
        }
    }

    private void updateCacheAndDisplay(int targetLine, ReviewParser.DiffSide targetSide) {
        assert SwingUtilities.isEventDispatchThread() : "updateCacheAndDisplay must be called on EDT";
        // Evict panels outside the cache radius
        var it = panelCache.entrySet().iterator();
        List<Integer> retainedIndices = new ArrayList<>();

        while (it.hasNext()) {
            var entry = it.next();
            int index = entry.getKey();
            AbstractDiffPanel panel = entry.getValue();

            if (!isWithinCacheWindow(index)) {
                if (panel.hasUnsavedChanges()) {
                    retainedIndices.add(index);
                } else {
                    panel.dispose();
                    it.remove();
                }
            }
        }

        if (!retainedIndices.isEmpty()) {
            logger.warn(
                    "Memory usage increased: retaining {} edited files outside sliding window", retainedIndices.size());
        }

        // Ensure current is loading/loaded
        ensurePanel(currentIndex, targetLine, targetSide);

        // Background preload adjacent panels within the radius
        int radius = PerformanceConstants.DIFF_PANEL_CACHE_RADIUS;
        for (int i = 1; i <= radius; i++) {
            ensurePanel(currentIndex - i, -1, ReviewParser.DiffSide.NEW);
            ensurePanel(currentIndex + i, -1, ReviewParser.DiffSide.NEW);
        }
    }

    private boolean isWithinCacheWindow(int index) {
        int radius = PerformanceConstants.DIFF_PANEL_CACHE_RADIUS;
        return index >= currentIndex - radius && index <= currentIndex + radius;
    }

    private void ensurePanel(int index, int targetLine, ReviewParser.DiffSide targetSide) {
        assert SwingUtilities.isEventDispatchThread() : "ensurePanel must be called on EDT";
        if (index < 0 || index >= fileComparisons.size()) return;
        if (panelCache.containsKey(index)) {
            if (index == currentIndex) {
                displayPanel(index, panelCache.get(index), targetLine, targetSide);
            }
            return;
        }

        var info = fileComparisons.get(index);
        long maxSize =
                Math.max(info.leftSource().sizeInBytes(), info.rightSource().sizeInBytes());

        if (maxSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES) {
            createAsync(index, info, targetLine, targetSide);
        } else {
            createSync(index, info, targetLine, targetSide);
        }
    }

    private void createSync(int index, FileComparisonInfo info, int targetLine, ReviewParser.DiffSide targetSide) {
        assert SwingUtilities.isEventDispatchThread() : "createSync must be called on EDT";
        // Note: createDiffNode is @Blocking but for small files (checked via size thresholds in ensurePanel)
        // the overhead is minimal and worth the immediate UI response.
        var diffNode = FileComparisonHelper.createDiffNode(
                info.leftSource(), info.rightSource(), contextManager, isMultipleCommitsContext);

        AbstractDiffPanel panel = createPanel(index, diffNode);
        panelCache.put(index, panel);
        if (index == currentIndex) {
            displayPanel(index, panel, targetLine, targetSide);
        }
    }

    private void createAsync(
            int index, FileComparisonInfo expectedInfo, int targetLine, ReviewParser.DiffSide targetSide) {
        int generation = updateGeneration.get();
        contextManager.submitBackgroundTask("Computing diff: " + expectedInfo.getDisplayName(), () -> {
            // Expensive I/O and CPU work (diffing) happens here on a virtual thread
            var diffNode = FileComparisonHelper.createDiffNode(
                    expectedInfo.leftSource(), expectedInfo.rightSource(), contextManager, isMultipleCommitsContext);
            diffNode.diff();

            SwingUtilities.invokeLater(() -> {
                // RACE CONDITION GUARDS:
                // 1. Generation check: if the entire file list was refreshed, this result is stale.
                if (generation != updateGeneration.get()) return;

                // 2. Index bounds check: ensure index is still valid for the current list.
                if (index < 0 || index >= fileComparisons.size()) return;

                // 3. Identity check: Ensure the file at this index is still the one we computed.
                // This handles cases where the user navigated away and back quickly.
                if (!fileComparisons.get(index).equals(expectedInfo)) return;

                // 4. Cache window check: if user scrolled far away, don't waste memory caching this.
                if (!isWithinCacheWindow(index)) return;

                // 5. Concurrent task check: if another creation task finished first, don't overwrite.
                if (panelCache.containsKey(index)) return;

                AbstractDiffPanel panel = createPanel(index, diffNode);
                panelCache.put(index, panel);

                // Only display if the user is still waiting for this specific file
                if (index == currentIndex) {
                    displayPanel(index, panel, targetLine, targetSide);
                }
            });
            return null;
        });
    }

    protected void displayPanel(int index, AbstractDiffPanel panel, int targetLine, ReviewParser.DiffSide targetSide) {
        mainPanel.displayAndRefreshPanel(index, panel, targetLine, targetSide);
    }

    protected AbstractDiffPanel createPanel(int index, JMDiffNode diffNode) {
        AbstractDiffPanel panel;
        if (mainPanel.isUnifiedView()) {
            var up = new UnifiedDiffPanel(mainPanel, theme, diffNode);
            up.setContextMode(
                    mainPanel.getGlobalShowAllLinesInUnified()
                            ? UnifiedDiffDocument.ContextMode.FULL_CONTEXT
                            : UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);
            panel = up;
        } else {
            var bp = new BufferDiffPanel(mainPanel, theme);
            bp.setDiffNode(diffNode);
            panel = bp;
        }
        panel.setAssociatedFileIndex(index);
        panel.applyTheme(theme);
        return panel;
    }

    public void clearCache() {
        assert SwingUtilities.isEventDispatchThread() : "clearCache must be called on EDT";
        panelCache.values().forEach(AbstractDiffPanel::dispose);
        panelCache.clear();
    }

    public @Nullable AbstractDiffPanel getCachedPanel(int index) {
        return panelCache.get(index);
    }

    public Collection<AbstractDiffPanel> getCachedPanels() {
        return panelCache.values();
    }

    public Iterable<Integer> getCachedIndices() {
        return panelCache.keySet();
    }
}
