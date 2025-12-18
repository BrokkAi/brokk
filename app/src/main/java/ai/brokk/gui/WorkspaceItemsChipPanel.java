package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.search.ScrollingUtils;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.project.MainProject;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
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
    private @Nullable WorkspaceChip.StyleGuideChip styleGuideChip = null;
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
        this.hoveredFragmentIds = Set.copyOf(
                this.hoveredFragments.stream().map(ContextFragment::id).collect(java.util.stream.Collectors.toSet()));
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
        updateChips(fragments, false);
    }

    private void updateChips(List<ContextFragment> fragments, boolean fromBackground) {
        if (!fromBackground && SwingUtilities.isEventDispatchThread()) {
            var fragmentsCopy = List.copyOf(fragments);
            contextManager.submitBackgroundTask(
                    "WorkspaceItemsChipPanel.updateChips", () -> updateChips(fragmentsCopy, true));
            return;
        }

        logger.debug(
                "updateChips (incremental) called with {} fragments (forceToolEmulation={} readOnly={})",
                fragments.size(),
                MainProject.getForceToolEmulation(),
                readOnly);

        var summaries = fragments.stream()
                .filter(f -> f.getType() == ContextFragment.FragmentType.SKELETON)
                .toList();
        var nonSummaryFragments = fragments.stream()
                .filter(f -> f.getType() != ContextFragment.FragmentType.SKELETON)
                .toList();

        // Pre-compute classifications off-EDT
        var classifiedNonSummaries =
                nonSummaryFragments.stream().map(ChipColorUtils::classify).toList();

        logger.debug(
                "updateChips: {} visible ({} summaries, {} others) out of {}",
                fragments.size(),
                summaries.size(),
                nonSummaryFragments.size(),
                fragments.size());

        Map<String, ContextFragment> newOthersById = new LinkedHashMap<>();
        for (var f : nonSummaryFragments) {
            newOthersById.put(f.id(), f);
        }

        var orderIds = nonSummaryFragments.stream().map(ContextFragment::id).toList();

        SwingUtilities.invokeLater(() -> {
            // Snapshot current ids to avoid races and inconsistent views during add/remove planning.
            var currentIds = new HashSet<>(chipById.keySet());

            var toRemoveIds = currentIds.stream()
                    .filter(oldId -> !newOthersById.containsKey(oldId))
                    .toList();

            for (var id : toRemoveIds) {
                var chip = chipById.remove(id);
                if (chip != null) {
                    remove(chip);
                }
            }

            var toAddFrags = nonSummaryFragments.stream()
                    .filter(f -> !currentIds.contains(f.id()))
                    .toList();

            for (var frag : toAddFrags) {
                var classified = classifiedNonSummaries.stream()
                        .filter(cf -> cf.fragment().equals(frag))
                        .findFirst()
                        .orElse(new ChipColorUtils.ClassifiedFragment(frag, ChipColorUtils.ChipKind.OTHER));
                var chip = createChip(frag, classified.kind());
                add(chip);
                chipById.put(frag.id(), chip);
            }

            int z = 0;
            for (var id : orderIds) {
                var chip = chipById.get(id);
                if (chip != null) {
                    setComponentZOrder(chip, z++);
                }
            }

            for (var f : nonSummaryFragments) {
                var chip = chipById.get(f.id());
                if (chip != null) {
                    chip.updateFragment(f);
                }
            }

            if (summaries.isEmpty()) {
                if (syntheticSummaryChip != null) {
                    remove(syntheticSummaryChip);
                    syntheticSummaryChip = null;
                }
            } else if (syntheticSummaryChip == null) {
                syntheticSummaryChip = createSyntheticSummaryChip(summaries);
                add(syntheticSummaryChip);
            } else {
                syntheticSummaryChip.updateSummaries(summaries);
            }

            if (syntheticSummaryChip != null) {
                setComponentZOrder(syntheticSummaryChip, getComponentCount() - 1);
            }

            ensureStyleGuideChip(true);

            revalidate();
            repaint();

            Container p = getParent();
            while (p != null) {
                if (p instanceof JComponent jc) {
                    jc.revalidate();
                    jc.repaint();
                }
                p = p.getParent();
            }
        });
    }

    private WorkspaceChip createChip(ContextFragment fragment, ChipColorUtils.ChipKind kind) {
        var chip = new WorkspaceChip(
                chrome, contextManager, () -> readOnly, onHover, onRemoveFragment, Set.of(fragment), kind);
        chip.setBorder(new EmptyBorder(0, 0, 0, 0));
        return chip;
    }

    private WorkspaceChip.SummaryChip createSyntheticSummaryChip(List<ContextFragment> summaries) {
        var chip = new WorkspaceChip.SummaryChip(
                chrome, contextManager, () -> readOnly, onHover, onRemoveFragment, summaries);
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return chip;
    }

    /**
     * Ensures the pinned Style Guide chip is inserted (first) or removed.
     * This chip is UI-only and is not tracked in chipById nor included in cross-hover.
     */
    private void ensureStyleGuideChip(boolean present) {
        if (!present) {
            if (styleGuideChip != null) {
                remove(styleGuideChip);
                styleGuideChip = null;
            }
            return;
        }

        boolean needCreate = styleGuideChip == null;
        if (needCreate) {
            // Minimal, static fragment used only to anchor the chip in menus/preview.
            // Not tied to workspace hover/selection or computed updates.
            ContextFragment fragment =
                    new ContextFragment.StringFragment(contextManager, "", "AGENTS.md", SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
            styleGuideChip = new WorkspaceChip.StyleGuideChip(
                    chrome, contextManager, () -> readOnly, null, onRemoveFragment, fragment);
            styleGuideChip.setBorder(new EmptyBorder(0, 0, 0, 0));
            styleGuideChip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            add(styleGuideChip);
        } else if (styleGuideChip.getParent() != this) {
            add(styleGuideChip);
        }

        // Pin at the first position.
        setComponentZOrder(styleGuideChip, 0);
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

    /**
     * Scroll the chip corresponding to the given fragment into view within the parent scroll pane, if present.
     * <p>
     * This is intended to be called from hover handlers (e.g. TokenUsageBar) so that when a fragment segment
     * is highlighted in the token usage bar, the associated workspace chip is made visible to the user.
     * <p>
     * Safe to call from any thread; the scroll operation is marshaled onto the EDT.
     */
    public void scrollFragmentIntoView(@Nullable ContextFragment fragment) {
        if (fragment == null) {
            return;
        }
        if (readOnly) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            WorkspaceChip targetChip = chipById.get(fragment.id());
            if (targetChip == null && syntheticSummaryChip != null) {
                // If this fragment is a summary that is represented by the synthetic "Summaries" chip,
                // scroll that synthetic chip into view instead.
                if (syntheticSummaryChip.getFragments().stream()
                        .anyMatch(f -> fragment.id().equals(f.id()))) {
                    targetChip = syntheticSummaryChip;
                }
            }
            if (targetChip == null) {
                return;
            }
            ScrollingUtils.scrollToComponent(targetChip);
        });
    }
}
