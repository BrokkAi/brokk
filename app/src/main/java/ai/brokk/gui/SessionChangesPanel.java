package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.context.Context;
import ai.brokk.context.FrozenFragment;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.ContentDiffUtils;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SessionChangesPanel — encapsulates the "Changes" tab UI and aggregation logic.
 *
 * Responsibilities:
 *  - Aggregate per-context diffs into a single per-file earliest->latest diff (background).
 *  - Render an aggregated BrokkDiffPanel built from BufferSource.StringSource comparisons.
 *  - Manage lifecycle (dispose old BrokkDiffPanel) and apply theme updates.
 *
 * This class is a focused extraction of the Changes-tab logic previously located inside
 * HistoryOutputPanel.
 */
public class SessionChangesPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SessionChangesPanel.class);

    private final ContextManager contextManager;
    private final GuiTheme chrome;

    // Placeholder panel that receives the aggregated UI
    private final JPanel changesTabPlaceholder = new JPanel(new BorderLayout());

    // Currently displayed aggregated diff panel (if any)
    private BrokkDiffPanel aggregatedChangesPanel;

    // Last aggregated changes result
    private CumulativeChanges lastCumulativeChanges;

    public SessionChangesPanel(GuiTheme chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        add(changesTabPlaceholder, BorderLayout.CENTER);
    }

    /**
     * Per-file aggregated change from earliest->latest captured contents.
     */
    private static record PerFileChange(String displayFile, String earliestOld, String latestNew) {}

    /**
     * Aggregated result for the entire session.
     */
    public static record CumulativeChanges(
            int filesChanged, int totalAdded, int totalDeleted, List<PerFileChange> perFileChanges) {}

    /**
     * Refresh the cumulative changes asynchronously.
     * Returns a CompletableFuture that completes with the CumulativeChanges result.
     */
    public CompletableFuture<CumulativeChanges> refreshCumulativeChangesAsync() {
        // Submit background aggregation via ContextManager so the app's executor is used.
        return contextManager.submitBackgroundTask("Aggregating session changes", (Callable<CumulativeChanges>) () -> {
            try {
                var contexts = contextManager.getContextHistoryList();
                if (contexts == null || contexts.isEmpty()) {
                    return new CumulativeChanges(0, 0, 0, List.of());
                }

                var diffService = contextManager.getContextHistory().getDiffService();

                // Map representative FrozenFragment -> PerFileChange builder data
                // We identify fragments by hasSameSource; keep insertion order (oldest->newest contexts).
                final List<FrozenFragment> seenFragments = new ArrayList<>();
                final Map<FrozenFragment, String> earliestOldByFragment = new IdentityHashMap<>();
                final Map<FrozenFragment, String> latestNewByFragment = new IdentityHashMap<>();

                for (Context ctx : contexts) {
                    List<Context.DiffEntry> entries;
                    try {
                        entries = diffService.diff(ctx).join();
                    } catch (Throwable t) {
                        logger.warn("Failed to compute diffs for context {}: {}", ctx, t.toString());
                        continue;
                    }
                    if (entries == null) continue;
                    for (Context.DiffEntry e : entries) {
                        var frag = e.fragment();
                        // find equivalent seen fragment (by hasSameSource) if any
                        FrozenFragment rep = null;
                        for (FrozenFragment f : seenFragments) {
                            if (frag.hasSameSource(f)) {
                                rep = f;
                                break;
                            }
                        }
                        if (rep == null) {
                            // new representative
                            rep = frag;
                            seenFragments.add(rep);
                        }
                        // earliestOld: only set when first seen for this rep
                        earliestOldByFragment.computeIfAbsent(rep, k -> {
                            var text = safeFragmentText(e);
                            return text == null ? "" : text;
                        });
                        // latestNew: always overwrite to keep the newest seen
                        latestNewByFragment.put(rep, Objects.requireNonNullElse(e.newContent(), ""));
                    }
                }

                // Build PerFileChange list
                var perFileChanges = new ArrayList<PerFileChange>();
                int totalAdded = 0;
                int totalDeleted = 0;
                for (FrozenFragment rep : seenFragments) {
                    String earliestOld = earliestOldByFragment.getOrDefault(rep, "");
                    String latestNew = latestNewByFragment.getOrDefault(rep, "");
                    // Determine display file: try to use files() or fallback to shortDescription()
                    String displayFile = rep.shortDescription();
                    try {
                        var files = rep.files();
                        if (files != null && !files.isEmpty()) {
                            // best-effort: use first file's toString() (ProjectFile API unknown here)
                            displayFile = files.iterator().next().toString();
                        }
                    } catch (Throwable ignored) {
                    }

                    var counts = computeNetLineCounts(earliestOld, latestNew);
                    totalAdded += counts[0];
                    totalDeleted += counts[1];
                    perFileChanges.add(new PerFileChange(displayFile, earliestOld, latestNew));
                }

                // Sort by display file for stable ordering
                perFileChanges.sort(
                        Comparator.comparing(PerFileChange::displayFile, Comparator.nullsFirst(String::compareTo)));

                var result = new CumulativeChanges(
                        perFileChanges.size(), totalAdded, totalDeleted, List.copyOf(perFileChanges));
                // Schedule UI update on EDT
                SwingUtilities.invokeLater(() -> {
                    try {
                        updateChangesTabContent(result);
                    } catch (Throwable t) {
                        logger.error("Error updating Changes tab content", t);
                    }
                });
                this.lastCumulativeChanges = result;
                return result;
            } catch (Throwable t) {
                logger.error("Unhandled error while aggregating session changes", t);
                var empty = new CumulativeChanges(0, 0, 0, List.of());
                SwingUtilities.invokeLater(() -> updateChangesTabContent(empty));
                return empty;
            }
        });
    }

    /**
     * Update the visible Changes tab content on the EDT using the aggregated result.
     */
    private void updateChangesTabContent(CumulativeChanges cumulativeChanges) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateChangesTabContent(cumulativeChanges));
            return;
        }

        // Clear any old UI
        try {
            if (aggregatedChangesPanel != null) {
                try {
                    aggregatedChangesPanel.dispose();
                } catch (Throwable t) {
                    logger.warn("Error disposing previous aggregatedChangesPanel", t);
                }
                aggregatedChangesPanel = null;
            }
        } finally {
            changesTabPlaceholder.removeAll();
        }

        if (cumulativeChanges == null || cumulativeChanges.filesChanged() == 0) {
            var label = new JLabel("No changes in this session.", SwingConstants.CENTER);
            label.setBorder(new EmptyBorder(24, 24, 24, 24));
            changesTabPlaceholder.add(label, BorderLayout.CENTER);
        } else {
            try {
                var wrapper = buildAggregatedChangesPanel(cumulativeChanges);
                changesTabPlaceholder.add(wrapper, BorderLayout.CENTER);
            } catch (Throwable t) {
                logger.error("Failed to build aggregated changes panel", t);
                var err = new JLabel("Failed to build changes view.", SwingConstants.CENTER);
                err.setBorder(new EmptyBorder(24, 24, 24, 24));
                changesTabPlaceholder.add(err, BorderLayout.CENTER);
            }
        }

        changesTabPlaceholder.revalidate();
        changesTabPlaceholder.repaint();
    }

    /**
     * Build an aggregated BrokkDiffPanel wrapper for the provided cumulative result.
     */
    private JPanel buildAggregatedChangesPanel(CumulativeChanges cumulativeChanges) {
        var wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new CompoundBorder(new LineBorder(Color.LIGHT_GRAY), new EmptyBorder(8, 8, 8, 8)));

        // Attempt to derive a root title from the project if possible
        String rootTitle = "";
        try {
            var proj = contextManager.getProject();
            if (proj != null) {
                rootTitle = proj.toString();
            }
        } catch (Throwable ignored) {
        }

        var builder = new BrokkDiffPanel.Builder(chrome, contextManager)
                .setMultipleCommitsContext(true)
                .setRootTitle(rootTitle)
                .setInitialFileIndex(0)
                .setForceFileTree(true);

        for (PerFileChange p : cumulativeChanges.perFileChanges()) {
            var left = new BufferSource.StringSource(
                    Objects.requireNonNullElse(p.earliestOld(), ""), p.displayFile(), p.displayFile(), null);
            var right = new BufferSource.StringSource(
                    Objects.requireNonNullElse(p.latestNew(), ""), p.displayFile(), p.displayFile(), null);
            builder.addComparison(left, right);
        }

        // Force unified view for aggregated panel to make side-by-side less noisy
        try {
            if (!GlobalUiSettings.isDiffUnifiedView()) {
                GlobalUiSettings.saveDiffUnifiedView(true);
            }
        } catch (Throwable ignored) {
        }

        var panel = builder.build();
        this.aggregatedChangesPanel = panel;
        // Apply theme immediately so it matches surrounding UI
        try {
            panel.applyTheme(chrome);
        } catch (Throwable t) {
            logger.warn("Failed to apply theme to aggregated diff panel", t);
        }

        wrapper.add(panel, BorderLayout.CENTER);
        return wrapper;
    }

    /**
     * Compute net line counts between two versions using ContentDiffUtils.
     * Returns int[]{added, deleted}.
     */
    private static int[] computeNetLineCounts(String earliestOld, String latestNew) {
        try {
            var res = ContentDiffUtils.computeDiffResult(
                    Objects.requireNonNullElse(earliestOld, ""),
                    Objects.requireNonNullElse(latestNew, ""),
                    "old",
                    "new");
            return new int[] {res.added(), res.deleted()};
        } catch (Throwable t) {
            return new int[] {0, 0};
        }
    }

    /**
     * Safely extract fragment text from a DiffEntry like HistoryOutputPanel.safeFragmentText did.
     */
    private static String safeFragmentText(Context.DiffEntry entry) {
        if (entry == null) return "";
        try {
            var frag = entry.fragment();
            if (frag != null) {
                try {
                    var text = frag.text();
                    if (text != null) return text;
                } catch (Throwable ignore) {
                    // fall through to fallback fields
                }
            }
        } catch (Throwable ignored) {
        }
        return Objects.requireNonNullElse(entry.oldContent(), Objects.requireNonNullElse(entry.newContent(), ""));
    }

    /**
     * Dispose resources (notably any embedded BrokkDiffPanel).
     */
    public void dispose() {
        if (aggregatedChangesPanel != null) {
            try {
                aggregatedChangesPanel.dispose();
            } catch (Throwable t) {
                logger.warn("Error disposing aggregatedChangesPanel", t);
            } finally {
                aggregatedChangesPanel = null;
            }
        }
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        if (aggregatedChangesPanel != null) {
            try {
                aggregatedChangesPanel.applyTheme(guiTheme);
            } catch (Throwable t) {
                logger.warn("Failed to apply theme to aggregatedChangesPanel", t);
            }
        }
    }
}
