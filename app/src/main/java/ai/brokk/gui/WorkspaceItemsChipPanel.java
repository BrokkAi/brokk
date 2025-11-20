package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.MainProject;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Displays current workspace items as "chips" with a close button to remove them from the workspace.
 * Listens to context changes and updates itself accordingly.
 */
public class WorkspaceItemsChipPanel extends javax.swing.JPanel implements ThemeAware, Scrollable {

    private final Chrome chrome;
    private final ContextManager contextManager;
    private @Nullable Consumer<ContextFragment> onRemoveFragment;

    // Logger for defensive debug logging in catch blocks (avoid empty catches)
    private static final Logger logger = LogManager.getLogger(WorkspaceItemsChipPanel.class);

    static final String READ_ONLY_TIP = "Select latest activity to enable";

    // Cross-hover state: chip lookup by fragment id and external hover callback
    private final Map<String, WorkspaceChip> chipById = new ConcurrentHashMap<>();
    private @Nullable WorkspaceChip.SummaryChip syntheticSummaryChip = null;
    private @Nullable BiConsumer<ContextFragment, Boolean> onHover;
    private Set<ContextFragment> hoveredFragments = Set.of();
    private Set<String> hoveredFragmentIds = Set.of();
    private boolean readOnly = false;

    public WorkspaceItemsChipPanel(Chrome chrome) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        setOpaque(false);
        this.chrome = chrome;
        this.contextManager = chrome.getContextManager();

        // Add right-click listener for blank space
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleBlankSpaceRightClick(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleBlankSpaceRightClick(e);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                // Clear hover state when mouse leaves the entire panel
                applyGlobalStyling(Set.of());
            }
        });
    }

    private void handleBlankSpaceRightClick(MouseEvent e) {
        if (readOnly) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, READ_ONLY_TIP);
            return;
        }
        // Check if click is on blank space (not within any chip component)
        java.awt.Component clickTarget = getComponentAt(e.getPoint());
        if (clickTarget != null && clickTarget != WorkspaceItemsChipPanel.this) {
            // Click is within a chip component, ignore
            return;
        }

        // Use NoSelection scenario to get standard blank-space actions
        var scenario = new WorkspacePanel.NoSelection();
        var actions = scenario.getActions(chrome.getContextPanel());

        // Show popup menu using PopupBuilder
        WorkspacePanel.PopupBuilder.create(chrome).add(actions).show(this, e.getX(), e.getY());
    }

    /**
     * Programmatically set the fragments to display as chips. Safe to call from any thread; updates are marshaled to
     * the EDT.
     */
    public void setFragments(List<ContextFragment> fragments) {
        SwingUtilities.invokeLater(() -> updateChips(fragments));
    }

    /**
     * Sets the fragments from a Context (historical or live). Safe to call from any thread.
     */
    public void setFragmentsForContext(Context context) {
        List<ContextFragment> frags = context.getAllFragmentsInDisplayOrder();
        SwingUtilities.invokeLater(() -> updateChips(frags));
    }

    /**
     * Enable or disable interactive behavior and visuals for read-only mode. Runs on the EDT.
     */
    public void setReadOnly(boolean readOnly) {
        SwingUtilities.invokeLater(() -> {
            this.readOnly = readOnly;
            if (readOnly) {
                this.hoveredFragments = Set.of();
                this.hoveredFragmentIds = Set.of();
            }
            for (var component : getComponents()) {
                if (component instanceof WorkspaceChip chip) {
                    chip.setCloseEnabled(!readOnly);
                }
            }
            revalidate();
            repaint();
        });
    }

    /**
     * Sets a listener invoked when a chip's remove button is clicked. If not set, the panel will default to removing
     * from the ContextManager.
     */
    public void setOnRemoveFragment(Consumer<ContextFragment> listener) {
        this.onRemoveFragment = listener;
    }

    /**
     * Set a callback invoked when the mouse enters/leaves a chip for a fragment.
     * Pass null to clear.
     */
    public void setOnHover(@Nullable BiConsumer<ContextFragment, Boolean> listener) {
        this.onHover = listener;
    }

    public void applyGlobalStyling(Set<ContextFragment> targets) {
        this.hoveredFragments = readOnly ? Set.of() : targets;
        this.hoveredFragmentIds = Set.copyOf(this.hoveredFragments.stream()
                .map(ContextFragment::id)
                .collect(java.util.stream.Collectors.toSet()));
        for (var component : getComponents()) {
            if (component instanceof JComponent jc) {
                jc.repaint();
            }
        }
    }

    // Overload to support existing callers that pass a Collection
    public void applyGlobalStyling(Collection<ContextFragment> targets) {
        applyGlobalStyling(Set.copyOf(targets));
    }

    /**
     * Highlight or clear highlight for a collection of fragments' chips.
     * Safe to call from any thread; will marshal to the EDT.
     */
    public void highlightFragments(Collection<ContextFragment> fragments, boolean highlight) {
        if (readOnly) {
            applyGlobalStyling(Set.of());
            return;
        }
        if (highlight) {
            applyGlobalStyling(Set.copyOf(fragments));
        } else {
            applyGlobalStyling(Set.of());
        }
    }

    private void updateChips(List<ContextFragment> fragments) {
        logger.debug(
                "updateChips (incremental) called with {} fragments (forceToolEmulation={} readOnly={})",
                fragments.size(),
                MainProject.getForceToolEmulation(),
                readOnly);

        var visibleFragments = fragments.stream()
                .filter(f -> MainProject.getForceToolEmulation() || hasRenderableContent(f))
                .toList();

        var summaries = visibleFragments.stream()
                .filter(f -> classify(f) == WorkspaceChip.ChipKind.SUMMARY)
                .toList();
        var nonsummaries = visibleFragments.stream()
                .filter(f -> classify(f) != WorkspaceChip.ChipKind.SUMMARY)
                .toList();

        logger.debug(
                "updateChips: {} visible ({} summaries, {} others) out of {}",
                visibleFragments.size(),
                summaries.size(),
                nonsummaries.size(),
                fragments.size());

        Map<String, ContextFragment> newOthersById = new LinkedHashMap<>();
        for (var f : nonsummaries) {
            newOthersById.put(f.id(), f);
        }

        // 1) Remove chips that are no longer present
        var toRemove = chipById.keySet().stream()
                .filter(oldId -> !newOthersById.containsKey(oldId))
                .toList();
        for (var id : toRemove) {
            var chip = chipById.remove(id);
            if (chip != null) {
                remove(chip);
            }
        }

        // 2) Add chips that are new
        for (var entry : newOthersById.entrySet()) {
            var id = entry.getKey();
            var frag = entry.getValue();
            if (!chipById.containsKey(id)) {
                var chip = createChip(frag);
                if (chip != null) {
                    add(chip);
                    chipById.put(id, chip);
                }
            }
        }

        // 3) Reorder chips to match 'others' order
        int z = 0;
        for (var f : nonsummaries) {
            var chip = chipById.get(f.id());
            if (chip != null) {
                try {
                    setComponentZOrder(chip, z++);
                } catch (Exception ex) {
                    logger.debug("Failed to set component z-order for fragment: {}", f.id(), ex);
                }
            }
        }

        // 3b) Rebind existing chips to updated fragment instances
        for (var f : nonsummaries) {
            var chip = chipById.get(f.id());
            if (chip != null) {
                chip.updateFragment(f);
            }
        }

        // 4) Synthetic "Summaries" chip incremental management
        boolean anyRenderableSummary =
                summaries.stream().anyMatch(f -> MainProject.getForceToolEmulation() || hasRenderableContent(f));

        if (!anyRenderableSummary) {
            if (syntheticSummaryChip != null) {
                remove(syntheticSummaryChip);
                syntheticSummaryChip = null;
            }
        } else {
            var renderableSummaries = summaries.stream()
                    .filter(f -> MainProject.getForceToolEmulation() || hasRenderableContent(f))
                    .toList();
            if (renderableSummaries.isEmpty()) {
                logger.debug("No renderable summaries for synthetic chip; skipping.");
            } else if (syntheticSummaryChip == null) {
                syntheticSummaryChip = createSyntheticSummaryChip(renderableSummaries);
                if (syntheticSummaryChip != null) {
                    add(syntheticSummaryChip);
                }
            } else {
                syntheticSummaryChip.updateSummaries(renderableSummaries);
            }

            if (syntheticSummaryChip != null) {
                try {
                    setComponentZOrder(syntheticSummaryChip, getComponentCount() - 1);
                } catch (Exception ex) {
                    logger.debug("Failed to set component z-order for synthetic summary chip", ex);
                }
            }
        }

        revalidate();
        repaint();

        java.awt.Container p = getParent();
        while (p != null) {
            if (p instanceof JComponent jc) {
                jc.revalidate();
                jc.repaint();
            }
            p = p.getParent();
        }
    }

    private WorkspaceChip.ChipKind classify(ContextFragment fragment) {
        if (fragment.getType().isEditable()) {
            return WorkspaceChip.ChipKind.EDIT;
        }
        if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
            return WorkspaceChip.ChipKind.SUMMARY;
        }
        if (fragment.getType() == ContextFragment.FragmentType.HISTORY) {
            return WorkspaceChip.ChipKind.HISTORY;
        }
        return WorkspaceChip.ChipKind.OTHER;
    }

    /**
     * Conservative predicate deciding whether a fragment has visible/renderable content.
     * <p>
     * Rules:
     * - Immediately return true for ComputedFragment or any dynamic fragment.
     * - Always keep output fragments (history / outputs).
     * - For static non-computed text fragments: require non-blank text.
     * - For static non-text fragments: require at least an image, at least one file, or a non-empty description.
     * <p>
     * Any exception during evaluation causes the method to return true (fail-safe: show the fragment).
     */
    private boolean hasRenderableContent(ContextFragment f) {
        try {
            if (f instanceof ContextFragment.ComputedFragment) {
                return true;
            }
            if (f.getType().isOutput()) {
                return true;
            }
            if (f.isText()) {
                String txt = f.text();
                return !txt.trim().isEmpty();
            } else {
                boolean hasImage = f instanceof ContextFragment.ImageFragment;
                Set<ProjectFile> files = f.files();
                String desc = f.description();
                return hasImage || !files.isEmpty() || !desc.trim().isEmpty();
            }
        } catch (Exception ex) {
            logger.debug("hasRenderableContent threw for fragment {}", f, ex);
            return true;
        }
    }

    private @Nullable WorkspaceChip createChip(ContextFragment fragment) {
        if (!MainProject.getForceToolEmulation() && !hasRenderableContent(fragment)) {
            logger.debug("Skipping creation of chip for fragment (no renderable content): {}", fragment);
            return null;
        }
        var kind = classify(fragment);
        var chip = new WorkspaceChip(chrome, contextManager, () -> readOnly, onHover, onRemoveFragment, fragment, kind);
        chip.setBorder(new EmptyBorder(0, 0, 0, 0));
        return chip;
    }

    private @Nullable WorkspaceChip.SummaryChip createSyntheticSummaryChip(List<ContextFragment> summaries) {
        if (summaries.isEmpty()) return null;

        var renderableSummaries = summaries.stream()
                .filter(f -> MainProject.getForceToolEmulation() || hasRenderableContent(f))
                .toList();
        if (renderableSummaries.isEmpty()) {
            logger.debug("No renderable summaries for synthetic chip; skipping creation.");
            return null;
        }

        var chip = new WorkspaceChip.SummaryChip(
                chrome, contextManager, () -> readOnly, onHover, onRemoveFragment, renderableSummaries);
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return chip;
    }

    // Scrollable support and width-tracking preferred size for proper wrapping inside JScrollPane

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        int rowH = Math.max(24, getFontMetrics(getFont()).getHeight() + 8);
        return Math.max(12, rowH / 2);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        int rowH = Math.max(24, getFontMetrics(getFont()).getHeight() + 8);
        return Math.max(rowH, visibleRect.height - rowH);
    }

    @Override
    public Dimension getPreferredSize() {
        int width;
        var parent = getParent();
        if (parent instanceof JViewport vp) {
            width = vp.getWidth();
        } else {
            width = getWidth();
        }

        if (width <= 0) {
            return super.getPreferredSize();
        }

        Insets in = getInsets();
        int contentWidth = width - (in == null ? 0 : in.left + in.right);
        if (contentWidth <= 0) {
            return super.getPreferredSize();
        }

        int hgap = 6;
        int vgap = 4;
        if (getLayout() instanceof FlowLayout fl) {
            hgap = fl.getHgap();
            vgap = fl.getVgap();
        }

        int lineWidth = 0;
        int rows = 1;
        for (var comp : getComponents()) {
            if (!comp.isVisible()) continue;
            int w = comp.getPreferredSize().width;
            int next = (lineWidth == 0 ? w : lineWidth + hgap + w);
            if (next <= contentWidth) {
                lineWidth = next;
            } else {
                rows++;
                lineWidth = w;
            }
        }

        int rowH = Math.max(24, getFontMetrics(getFont()).getHeight() + 8);
        int height = (rows * rowH) + (rows > 1 ? (rows - 1) * vgap : 0);
        return new Dimension(width, Math.max(height, rowH));
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        applyTheme(guiTheme, false);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        SwingUtilities.invokeLater(() -> {
            restyleAllChips();
            SwingUtilities.invokeLater(this::restyleAllChips);
        });
    }

    private void restyleAllChips() {
        for (var component : getComponents()) {
            if (component instanceof WorkspaceChip chip) {
                chip.applyTheme();
                chip.updateReadOnlyIcon();
            }
        }
    }

    // Package-private accessors for WorkspaceChip paint logic

    boolean isReadOnlyMode() {
        return readOnly;
    }

    Set<ContextFragment> getHoveredFragments() {
        return hoveredFragments;
    }

    Set<String> getHoveredFragmentIds() {
        return hoveredFragmentIds;
    }
}
