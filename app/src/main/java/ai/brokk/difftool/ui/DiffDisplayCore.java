package ai.brokk.difftool.ui;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.difftool.node.JMDiffNode;
import ai.brokk.difftool.performance.PerformanceConstants;
import ai.brokk.difftool.ui.unified.UnifiedDiffDocument;
import ai.brokk.difftool.ui.unified.UnifiedDiffPanel;
import ai.brokk.gui.theme.GuiTheme;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final List<FileComparisonInfo> fileComparisons;
    private final boolean isMultipleCommitsContext;

    private int currentIndex = 0;
    private final Map<Integer, AbstractDiffPanel> panelCache = new HashMap<>();

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
        this.fileComparisons = List.copyOf(fileComparisons);
        this.isMultipleCommitsContext = isMultipleCommitsContext;
        this.currentIndex = initialIndex;
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
        updateCacheAndDisplay();
    }

    public void showFile(ProjectFile file) {
        int index = findIndex(file);
        if (index != -1) {
            showFile(index);
        }
    }

    public void showLocation(ProjectFile file, int lineNumber) {
        int index = findIndex(file);
        if (index != -1) {
            currentIndex = index;
            updateCacheAndDisplay();
            // Scroll logic is handled by the panel display callback in BrokkDiffPanel
            // or explicitly called after display.
        }
    }

    private int findIndex(ProjectFile file) {
        for (int i = 0; i < fileComparisons.size(); i++) {
            var info = fileComparisons.get(i);
            if (file.equals(info.file())) return i;
            if (matches(info.leftSource(), file) || matches(info.rightSource(), file)) return i;
        }
        return -1;
    }

    private boolean matches(BufferSource source, ProjectFile file) {
        if (source instanceof BufferSource.FileSource fs) return fs.file().equals(file);
        String name = source.filename();
        return name != null && name.replace('\\', '/').endsWith(file.getRelPath().toString().replace('\\', '/'));
    }

    private void updateCacheAndDisplay() {
        // Simple cache of {prev, current, next}
        List<Integer> keep = List.of(currentIndex - 1, currentIndex, currentIndex + 1);
        
        // Evict
        var it = panelCache.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!keep.contains(entry.getKey())) {
                entry.getValue().dispose();
                it.remove();
            }
        }

        // Ensure current is loading/loaded
        ensurePanel(currentIndex);
        
        // Background preload adjacent
        if (currentIndex > 0) ensurePanel(currentIndex - 1);
        if (currentIndex < fileComparisons.size() - 1) ensurePanel(currentIndex + 1);
    }

    private void ensurePanel(int index) {
        if (index < 0 || index >= fileComparisons.size()) return;
        if (panelCache.containsKey(index)) {
            if (index == currentIndex) {
                mainPanel.displayAndRefreshPanel(index, panelCache.get(index));
            }
            return;
        }

        var info = fileComparisons.get(index);
        long maxSize = Math.max(info.leftSource().sizeInBytes(), info.rightSource().sizeInBytes());
        
        if (maxSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES) {
            createAsync(index, info);
        } else {
            createSync(index, info);
        }
    }

    private void createSync(int index, FileComparisonInfo info) {
        var diffNode = FileComparisonHelper.createDiffNode(
                info.leftSource(), info.rightSource(), contextManager, isMultipleCommitsContext);
        
        AbstractDiffPanel panel = createPanel(index, diffNode);
        panelCache.put(index, panel);
        if (index == currentIndex) {
            mainPanel.displayAndRefreshPanel(index, panel);
        }
    }

    private void createAsync(int index, FileComparisonInfo info) {
        contextManager.submitBackgroundTask("Computing diff: " + info.getDisplayName(), () -> {
            var diffNode = FileComparisonHelper.createDiffNode(
                    info.leftSource(), info.rightSource(), contextManager, isMultipleCommitsContext);
            diffNode.diff();
            
            SwingUtilities.invokeLater(() -> {
                AbstractDiffPanel panel = createPanel(index, diffNode);
                panelCache.put(index, panel);
                if (index == currentIndex) {
                    mainPanel.displayAndRefreshPanel(index, panel);
                }
            });
            return null;
        });
    }

    private AbstractDiffPanel createPanel(int index, JMDiffNode diffNode) {
        AbstractDiffPanel panel;
        if (mainPanel.isUnifiedView()) {
            var up = new UnifiedDiffPanel(mainPanel, theme, diffNode);
            up.setContextMode(mainPanel.getGlobalShowAllLinesInUnified() 
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
