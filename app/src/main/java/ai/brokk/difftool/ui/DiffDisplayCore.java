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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

/**
 * Core logic for managing a list of file comparisons, the current index,
 * and a sliding cache of diff panels.
 */
public class DiffDisplayCore {

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
        if (index < 0 || index >= fileComparisons.size()) return;
        currentIndex = index;
        updateCacheAndDisplay(-1, ReviewParser.DiffSide.NEW);
    }

    public void showFile(ProjectFile file) {
        int index = findIndex(file);
        if (index != -1) {
            showFile(index);
        }
    }

    public void showLocation(ProjectFile file, int lineNumber) {
        showLocation(file, lineNumber, ReviewParser.DiffSide.NEW);
    }

    public void showLocation(ProjectFile file, int lineNumber, ReviewParser.DiffSide side) {
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
        // Evict panels outside the cache radius
        var it = panelCache.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!isWithinCacheWindow(entry.getKey())) {
                entry.getValue().dispose();
                it.remove();
            }
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
        var diffNode = FileComparisonHelper.createDiffNode(
                info.leftSource(), info.rightSource(), contextManager, isMultipleCommitsContext);

        AbstractDiffPanel panel = createPanel(index, diffNode);
        panelCache.put(index, panel);
        if (index == currentIndex) {
            displayPanel(index, panel, targetLine, targetSide);
        }
    }

    private void createAsync(int index, FileComparisonInfo info, int targetLine, ReviewParser.DiffSide targetSide) {
        int generation = updateGeneration.get();
        contextManager.submitBackgroundTask("Computing diff: " + info.getDisplayName(), () -> {
            var diffNode = FileComparisonHelper.createDiffNode(
                    info.leftSource(), info.rightSource(), contextManager, isMultipleCommitsContext);
            diffNode.diff();

            SwingUtilities.invokeLater(() -> {
                // If the generation changed (e.g. file list refreshed), ignore the result
                if (generation != updateGeneration.get()) return;

                // Check if the user has navigated away from the cache window for this panel
                if (!isWithinCacheWindow(index)) {
                    return;
                }

                // If another task already finished for this index, don't overwrite
                if (panelCache.containsKey(index)) {
                    return;
                }

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
        panelCache.values().forEach(AbstractDiffPanel::dispose);
        panelCache.clear();
    }

    public @Nullable AbstractDiffPanel getCachedPanel(int index) {
        return panelCache.get(index);
    }

    public Iterable<AbstractDiffPanel> getCachedPanels() {
        return panelCache.values();
    }

    public Iterable<Integer> getCachedIndices() {
        return panelCache.keySet();
    }
}
