package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ComputedSubscription;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.gui.ChipColorUtils.ChipKind;
import ai.brokk.gui.components.MaterialChip;
import ai.brokk.gui.util.Icons;
import ai.brokk.util.GlobalUiSettings;
import ai.brokk.util.Messages;
import ai.brokk.util.ProjectGuideResolver;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.*;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Strongly typed chip component representing one or more ContextFragments.
 * <p>
 * Encapsulates UI, tooltips, theming, hover behavior, and context menus.
 * Wraps {@link MaterialChip} for visual rendering.
 */
public class WorkspaceChip extends JPanel {

    private static final Logger logger = LogManager.getLogger(WorkspaceChip.class);

    private record FragmentMetrics(int loc, int tokens) {}

    private static final ConcurrentMap<ContextFragment, FragmentMetrics> metricsCache = new ConcurrentHashMap<>();

    protected final Chrome chrome;
    protected final ContextManager contextManager;
    protected final Supplier<Boolean> readOnlySupplier;
    protected final @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback;
    protected final @Nullable Consumer<ContextFragment> onRemoveFragment;
    protected final ChipKind kind;

    protected final MaterialChip materialChip;

    // Maximum characters to display on a chip label before truncating.
    private static final int MAX_LABEL_CHARS = 50;

    protected boolean closeEnabled = true;
    private Set<ContextFragment> fragments = Set.of();

    protected WorkspaceChip(
            Chrome chrome,
            ContextManager contextManager,
            Supplier<Boolean> readOnlySupplier,
            @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback,
            @Nullable Consumer<ContextFragment> onRemoveFragment,
            Set<ContextFragment> fragments,
            ChipKind kind) {
        super(new BorderLayout());
        setOpaque(false);
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.readOnlySupplier = readOnlySupplier;
        this.hoverCallback = hoverCallback;
        this.onRemoveFragment = onRemoveFragment;
        this.kind = kind;

        this.materialChip = new MaterialChip("");
        add(materialChip, BorderLayout.CENTER);

        setFragmentsInternal(fragments);
        initUi();
        applyTheme();
        bindComputed();
    }

    private void initUi() {
        ContextFragment fragment = getPrimaryFragment();
        String safeShortDescription = fragment.shortDescription().renderNowOr("Loading...");

        String labelText = (kind == ChipKind.OTHER)
                ? truncateForDisplay(capitalizeFirst(safeShortDescription))
                : truncateForDisplay(safeShortDescription);

        materialChip.setText(labelText);
        refreshLabelAndTooltip();

        materialChip.setCloseVisible(true);
        materialChip.setCloseToolTipText("Remove from Workspace");
        materialChip.setCloseAccessibleName("Remove " + safeShortDescription);
        materialChip.addCloseListener(e -> onCloseClick());
        materialChip.addSeparatorCloseListener(this::onCloseClick);
        materialChip.addChipClickListener(this::onPrimaryClick);

        installInteractionHandlers();
        installHoverListeners();
    }

    private void installInteractionHandlers() {
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) handlePopup(e);
            }
        };
        materialChip.addMouseListener(ma);
    }

    private void handlePopup(MouseEvent e) {
        if (isPanelReadOnly()) {
            chrome.systemNotify(WorkspaceItemsChipPanel.READ_ONLY_TIP, "Workspace", JOptionPane.INFORMATION_MESSAGE);
            e.consume();
            return;
        }
        JPopupMenu menu = createContextMenu();
        if (menu != null) {
            SwingUtilities.invokeLater(() -> menu.show(materialChip, e.getX(), e.getY()));
        }
        e.consume();
    }

    protected void onPrimaryClick() {
        chrome.openFragmentPreview(getPrimaryFragment());
    }

    protected void onCloseClick() {
        if (!closeEnabled) {
            chrome.systemNotify(WorkspaceItemsChipPanel.READ_ONLY_TIP, "Workspace", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!ensureMutatingAllowed()) {
            return;
        }
        dropSingleFragment(getPrimaryFragment());
    }

    protected void installHoverListeners() {
        if (hoverCallback == null) return;

        final int[] hoverCounter = {0};
        MouseAdapter hoverAdapter = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (hoverCounter[0]++ == 0) {
                    notifyHover(true);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverCounter[0] > 0 && --hoverCounter[0] == 0) {
                    notifyHover(false);
                }
            }
        };
        materialChip.addMouseListener(hoverAdapter);
    }

    protected void notifyHover(boolean entered) {
        if (hoverCallback == null) return;
        for (var f : fragments) {
            try {
                hoverCallback.accept(f, entered);
            } catch (Exception ex) {
                logger.trace("onHover callback threw", ex);
            }
        }
    }

    @Override
    public void paint(java.awt.Graphics g) {
        float alpha = 1.0f;
        if (getParent() instanceof WorkspaceItemsChipPanel parentPanel) {
            if (parentPanel.isReadOnlyMode()) {
                alpha = Math.min(alpha, 0.6f);
            }
            var hoveredIds = parentPanel.getHoveredFragmentIds();
            if (!hoveredIds.isEmpty() && !fragments.isEmpty()) {
                boolean isHovered = fragments.stream().map(ContextFragment::id).anyMatch(hoveredIds::contains);
                if (!isHovered) {
                    alpha = Math.min(alpha, 0.5f);
                }
            }
        }
        materialChip.setAlpha(alpha);
        super.paint(g);
    }

    public void setCloseEnabled(boolean enabled) {
        this.closeEnabled = enabled;
        materialChip.setCloseEnabled(enabled);
    }

    public Set<ContextFragment> getFragments() {
        return fragments;
    }

    public void updateFragment(ContextFragment fragment) {
        ComputedSubscription.disposeAll(this);
        setFragmentsInternal(Set.of(fragment));
        refreshLabelAndTooltip();
        bindComputed();
    }

    public void applyTheme() {
        boolean isDarkTheme = UIManager.getBoolean("laf.dark");
        Color bg = ChipColorUtils.getBackgroundColor(kind, isDarkTheme);
        Color fg = ChipColorUtils.getForegroundColor(kind, isDarkTheme);
        Color border = ChipColorUtils.getBorderColor(kind, isDarkTheme);

        ContextFragment fragment = getPrimaryFragment();
        if (fragment instanceof ContextFragments.StringFragment sf) {
            if (SpecialTextType.TASK_LIST.description().equals(sf.description().renderNowOrNull())) {
                bg = ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.CHIP_TASKLIST_BACKGROUND);
                fg = ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.CHIP_TASKLIST_FOREGROUND);
                border = ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.CHIP_TASKLIST_BORDER);
            }
        }

        materialChip.setChipColors(bg, fg, border);
    }

    public void updateReadOnlyIcon() {
        ContextFragment fragment = getPrimaryFragment();
        boolean showRo = fragment.getType().isEditable() && isFragmentReadOnly(fragment);

        var ctx = contextManager.selectedContext();
        boolean showPinned = ctx != null && ctx.isPinned(fragment);

        List<Icon> icons = new ArrayList<>();
        if (showPinned) icons.add(Icons.PUSH_PIN);
        if (showRo) icons.add(Icons.EDIT_OFF);

        materialChip.setLeadingIcons(icons);
    }

    protected void setFragmentsInternal(Set<ContextFragment> fragments) {
        assert !fragments.isEmpty();
        this.fragments = fragments;
    }

    protected ContextFragment getPrimaryFragment() {
        return fragments.iterator().next();
    }

    private boolean isFragmentReadOnly(ContextFragment fragment) {
        var ctx = contextManager.selectedContext();
        return ctx != null && ctx.isMarkedReadonly(fragment);
    }

    private boolean isPanelReadOnly() {
        return readOnlySupplier.get();
    }

    private boolean isOnLatestContext() {
        return Objects.equals(contextManager.selectedContext(), contextManager.liveContext());
    }

    protected boolean ensureMutatingAllowed() {
        if (isPanelReadOnly() || !isOnLatestContext()) {
            chrome.systemNotify(WorkspaceItemsChipPanel.READ_ONLY_TIP, "Workspace", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    protected void dropSingleFragment(ContextFragment fragment) {
        contextManager.submitContextTask(() -> {
            try {
                metricsCache.remove(fragment);
                if (fragment.getType() == ContextFragment.FragmentType.HISTORY) {
                    contextManager.clearHistoryOnly();
                } else if (onRemoveFragment == null) {
                    contextManager.dropWithHistorySemantics(List.of(fragment));
                } else {
                    onRemoveFragment.accept(fragment);
                }
            } catch (Exception ex) {
                logger.error("Failed to drop fragment {}", fragment, ex);
            }
        });
    }

    protected void bindComputed() {
        ComputedSubscription.bind(getPrimaryFragment(), this, this::refreshLabelAndTooltip);
    }

    protected void refreshLabelAndTooltip() {
        ContextFragment fragment = getPrimaryFragment();
        SwingUtilities.invokeLater(() -> {
            updateTextAndTooltip(fragment);
            applyTheme();
            updateReadOnlyIcon();
        });
    }

    protected void updateTextAndTooltip(ContextFragment fragment) {
        String sd = fragment.shortDescription().renderNowOr("Loading...");
        String newLabelText =
                (kind == ChipKind.OTHER) ? truncateForDisplay(capitalizeFirst(sd)) : truncateForDisplay(sd);

        if (!Objects.equals(materialChip.getAccessibleContext().getAccessibleName(), newLabelText)) {
            materialChip.setText(newLabelText);
            materialChip.setCloseAccessibleName("Remove " + sd);
        }

        String newTooltip = buildDefaultTooltip(fragment);
        if (!Objects.equals(materialChip.getToolTipText(), newTooltip)) {
            materialChip.setToolTipText(newTooltip);
        }

        String description = fragment.description().renderNowOr("");
        var ac = materialChip.getAccessibleContext();
        if (ac != null && !Objects.equals(ac.getAccessibleDescription(), description)) {
            ac.setAccessibleDescription(description);
        }
    }

    protected void safeAddSeparator(JPopupMenu menu) {
        int count = menu.getComponentCount();
        if (count > 0 && !(menu.getComponent(count - 1) instanceof javax.swing.JSeparator)) {
            menu.addSeparator();
        }
    }

    private static boolean isDropAction(Object actionOrItem) {
        try {
            if (actionOrItem instanceof JMenuItem mi) return "Drop".equals(mi.getText());
            if (actionOrItem instanceof Action a) return "Drop".equals(a.getValue(Action.NAME));
        } catch (Exception ex) {
            logger.debug("Error inspecting action/menu item for 'Drop'", ex);
        }
        return false;
    }

    protected @Nullable JPopupMenu createContextMenu() {
        ContextFragment fragment = getPrimaryFragment();
        JPopupMenu menu = new JPopupMenu();

        if (GlobalUiSettings.isAdvancedMode()) {
            boolean onLatest = isOnLatestContext();
            var ctx = contextManager.selectedContext();
            boolean isPinned = ctx != null && ctx.isPinned(fragment);
            JMenuItem togglePin = new JMenuItem(isPinned ? "Unpin" : "Pin", Icons.PUSH_PIN);
            togglePin.setEnabled(onLatest && !isPanelReadOnly());
            togglePin.addActionListener(e -> {
                if (!ensureMutatingAllowed()) return;
                contextManager.pushContext(curr -> curr.withPinned(fragment, !curr.isPinned(fragment)));
            });
            menu.add(togglePin);

            if (fragment.getType().isEditable()) {
                String labelText = isFragmentReadOnly(fragment) ? "Unset Read-Only" : "Set Read-Only";
                JMenuItem toggleRo = new JMenuItem(labelText, Icons.EDIT_OFF);
                toggleRo.setEnabled(onLatest && !isPanelReadOnly());
                toggleRo.addActionListener(e -> {
                    if (!ensureMutatingAllowed()) return;
                    contextManager.pushContext(curr -> curr.setReadonly(fragment, !curr.isMarkedReadonly(fragment)));
                });
                menu.add(toggleRo);
            }
            menu.addSeparator();
        }

        var scenario = new ContextActionsHandler.SingleFragment(fragment);
        var actions = scenario.getActions(chrome.getContextActionsHandler());
        boolean separatorPending = false;
        for (var action : actions) {
            if (action == null) {
                separatorPending = true;
                continue;
            }
            if (isDropAction(action)) continue;
            if (separatorPending) {
                safeAddSeparator(menu);
                separatorPending = false;
            }
            menu.add(action);
        }

        JMenuItem dropOther = new JMenuItem("Drop Others");
        var selected = contextManager.selectedContext();
        List<ContextFragment> toDrop = (selected == null)
                ? List.of()
                : selected.getAllFragmentsInDisplayOrder().stream()
                        .filter(f -> !fragments.contains(f))
                        .filter(f -> f.getType() != ContextFragment.FragmentType.HISTORY)
                        .toList();

        dropOther.setEnabled(!toDrop.isEmpty());
        dropOther.addActionListener(e -> {
            if (ensureMutatingAllowed())
                contextManager.submitContextTask(() -> contextManager.dropWithHistorySemantics(toDrop));
        });

        safeAddSeparator(menu);
        menu.add(dropOther);
        chrome.getThemeManager().registerPopupMenu(menu);
        return menu;
    }

    private static String wrapTooltipHtml(String innerHtml, int maxWidthPx) {
        return "<html><body style='width: " + maxWidthPx + "px'>" + innerHtml + "</body></html>";
    }

    private static String formatCount(int count) {
        return (count < 1000) ? String.format("%,d", count) : String.format("%.1fk", count / 1000.0);
    }

    private static String buildMetricsHtml(ContextFragment fragment) {
        if (fragment.isText() || fragment.getType().isOutput()) {
            FragmentMetrics metrics = getOrComputeMetrics(fragment);
            return String.format(
                    "<div>%s LOC • ~%s tokens</div><br/>", formatCount(metrics.loc()), formatCount(metrics.tokens()));
        }
        return "";
    }

    private static FragmentMetrics getOrComputeMetrics(ContextFragment fragment) {
        return metricsCache.computeIfAbsent(fragment, f -> {
            String text = f.text().renderNowOr("");
            int loc = text.split("\\r?\\n", -1).length;
            int tokens = Messages.getApproximateTokens(text);
            return new FragmentMetrics(loc, tokens);
        });
    }

    private static String buildDefaultTooltip(ContextFragment fragment) {
        String d = fragment.description().renderNowOr("");
        String descriptionHtml = StringEscapeUtils.escapeHtml4(d).replace("\n", "<br/>");
        StringBuilder body = new StringBuilder();
        String metrics = buildMetricsHtml(fragment);
        if (!metrics.isEmpty()) body.append(metrics);
        body.append(descriptionHtml);
        body.append("<br/><br/><i>Click to preview contents</i>");
        return wrapTooltipHtml(body.toString(), 420);
    }

    private static String capitalizeFirst(String s) {
        if (s.isEmpty()) return s;
        int first = s.codePointAt(0);
        return new StringBuilder(s.length())
                .appendCodePoint(Character.toUpperCase(first))
                .append(s.substring(Character.charCount(first)))
                .toString();
    }

    private static String truncateForDisplay(String text) {
        if (text.length() <= MAX_LABEL_CHARS) return text;
        return text.substring(0, Math.max(0, MAX_LABEL_CHARS - 3)) + "...";
    }

    public static final class SummaryChip extends WorkspaceChip {
        private enum ValidityState {
            ALL_VALID,
            MIXED,
            ALL_INVALID
        }

        private List<ContextFragment> summaryFragments;
        private int invalidSummaryCount = 0;
        private ValidityState validityState = ValidityState.ALL_VALID;

        @SuppressWarnings("NullAway.Init")
        public SummaryChip(
                Chrome chrome,
                ContextManager contextManager,
                Supplier<Boolean> readOnlySupplier,
                @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback,
                @Nullable Consumer<ContextFragment> onRemoveFragment,
                List<ContextFragment> summaries) {
            super(
                    chrome,
                    contextManager,
                    readOnlySupplier,
                    hoverCallback,
                    onRemoveFragment,
                    new LinkedHashSet<>(summaries),
                    ChipKind.SUMMARY);
            this.summaryFragments = List.copyOf(summaries);
            ComputedSubscription.disposeAll(this);
            bindComputed();
            refreshLabelAndTooltip();
        }

        @Override
        protected void setFragmentsInternal(Set<ContextFragment> fragments) {
            super.setFragmentsInternal(fragments);
            if (summaryFragments != null) {
                this.summaryFragments = List.copyOf(fragments);
            }
            updateValidityState();
        }

        @Override
        protected ContextFragment getPrimaryFragment() {
            return summaryFragments.getFirst();
        }

        public void updateSummaries(List<ContextFragment> newSummaries) {
            var oldFragments = new ArrayList<>(this.summaryFragments);
            ComputedSubscription.disposeAll(this);
            for (var f : oldFragments) if (!newSummaries.contains(f)) metricsCache.remove(f);
            this.summaryFragments = new ArrayList<>(newSummaries);
            super.setFragmentsInternal(new LinkedHashSet<>(newSummaries));
            updateValidityState();
            bindComputed();
            refreshLabelAndTooltip();
        }

        private void updateValidityState() {
            this.invalidSummaryCount =
                    (int) summaryFragments.stream().filter(f -> !f.isValid()).count();
            if (invalidSummaryCount == 0) {
                validityState = ValidityState.ALL_VALID;
            } else if (invalidSummaryCount == summaryFragments.size()) {
                validityState = ValidityState.ALL_INVALID;
            } else {
                validityState = ValidityState.MIXED;
            }

            if (validityState == ValidityState.MIXED) {
                materialChip.setSplitPainting(
                        true,
                        ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.CHIP_INVALID_BACKGROUND));
            } else {
                materialChip.setSplitPainting(false, null);
            }
        }

        @Override
        public void applyTheme() {
            if (validityState == ValidityState.ALL_INVALID && !summaryFragments.isEmpty()) {
                Color bg = ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.CHIP_INVALID_BACKGROUND);
                Color fg = ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.CHIP_INVALID_FOREGROUND);
                Color border = ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.CHIP_INVALID_BORDER);
                materialChip.setChipColors(bg, fg, border);
            } else {
                super.applyTheme();
            }
        }

        @Override
        protected void bindComputed() {
            for (var f : summaryFragments) ComputedSubscription.bind(f, this, this::refreshLabelAndTooltip);
        }

        @Override
        protected void installHoverListeners() {
            if (hoverCallback == null) return;
            final int[] hoverCounter = {0};
            MouseAdapter hoverAdapter = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (hoverCounter[0]++ == 0) {
                        for (var f : summaryFragments) hoverCallback.accept(f, true);
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (hoverCounter[0] > 0 && --hoverCounter[0] == 0) {
                        for (var f : summaryFragments) hoverCallback.accept(f, false);
                    }
                }
            };
            materialChip.addMouseListener(hoverAdapter);
        }

        @Override
        protected void updateTextAndTooltip(ContextFragment fragment) {
            materialChip.setText("Summaries (" + summaryFragments.size() + ")");
            materialChip.setToolTipText(computeAggregateSummaryTooltip());
            var ac = materialChip.getAccessibleContext();
            if (ac != null) ac.setAccessibleDescription("All summaries combined");
        }

        private String computeAggregateSummaryTooltip() {
            var allFiles = summaryFragments.stream()
                    .flatMap(f -> f.files().renderNowOr(Set.of()).stream())
                    .map(ProjectFile::toString)
                    .distinct()
                    .sorted()
                    .toList();
            StringBuilder body = new StringBuilder();
            int totalLoc = 0, totalTokens = 0;
            for (var summary : summaryFragments) {
                FragmentMetrics metrics = getOrComputeMetrics(summary);
                totalLoc += metrics.loc();
                totalTokens += metrics.tokens();
            }
            body.append("<div>")
                    .append(formatCount(totalLoc))
                    .append(" LOC • ~")
                    .append(formatCount(totalTokens))
                    .append(" tokens</div><br/>");
            body.append(
                    "<div><b>Summaries</b></div><hr style='border:0;border-top:1px solid #ccc;margin:4px 0 6px 0;'/>");
            if (allFiles.isEmpty()) body.append("[No summaries with valid filenames]");
            else {
                body.append("<ul style='margin:0;padding-left:16px'>");
                for (var f : allFiles)
                    body.append("<li>").append(StringEscapeUtils.escapeHtml4(f)).append("</li>");
                body.append("</ul>");
            }
            body.append("<br/><i>Click to preview all contents</i>");
            return wrapTooltipHtml(body.toString(), 420);
        }

        @Override
        protected void onPrimaryClick() {
            var syntaxStyle = summaryFragments.stream()
                    .map(f -> f.syntaxStyle().renderNowOrNull())
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                    .entrySet()
                    .stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(SyntaxConstants.SYNTAX_STYLE_NONE);
            List<ContextFragments.SummaryFragment> fragmentsToProcess = summaryFragments.stream()
                    .filter(f -> f instanceof ContextFragments.SummaryFragment)
                    .map(f -> (ContextFragments.SummaryFragment) f)
                    .toList();
            contextManager
                    .submitBackgroundTask(
                            "Aggregating summaries",
                            () -> ContextFragments.SummaryFragment.combinedText(fragmentsToProcess))
                    .thenAccept(combinedText -> SwingUtilities.invokeLater(() -> {
                        var syntheticFragment = new ContextFragments.StringFragment(
                                chrome.getContextManager(), combinedText, "Summaries", syntaxStyle);
                        chrome.openFragmentPreview(syntheticFragment);
                    }));
        }

        @Override
        protected void onCloseClick() {
            if (!ensureMutatingAllowed()) return;
            contextManager.submitContextTask(() -> {
                for (var f : summaryFragments) metricsCache.remove(f);
                contextManager.dropWithHistorySemantics(summaryFragments);
            });
        }

        @Override
        protected JPopupMenu createContextMenu() {
            JPopupMenu menu = new JPopupMenu();
            var scenario = new ContextActionsHandler.MultiFragment(summaryFragments);
            var actions = scenario.getActions(chrome.getContextActionsHandler());

            boolean separatorPending = false;
            for (var action : actions) {
                if (action == null) {
                    separatorPending = true;
                    continue;
                }
                String actionName = (String) action.getValue(Action.NAME);
                if ("Summarize all References".equals(actionName) || isDropAction(action)) continue;
                if (separatorPending) {
                    safeAddSeparator(menu);
                    separatorPending = false;
                }
                menu.add(action);
            }

            if (invalidSummaryCount > 0) {
                safeAddSeparator(menu);
                var invalidSummaries =
                        summaryFragments.stream().filter(f -> !f.isValid()).toList();
                JMenuItem dropInvalidItem = new JMenuItem(
                        invalidSummaryCount == 1
                                ? "Drop Invalid Summary"
                                : "Drop Invalid Summaries (" + invalidSummaryCount + ")");
                dropInvalidItem.addActionListener(e -> {
                    if (ensureMutatingAllowed()) {
                        invalidSummaries.forEach(metricsCache::remove);
                        contextManager.submitContextTask(
                                () -> contextManager.dropWithHistorySemantics(invalidSummaries));
                    }
                });
                menu.add(dropInvalidItem);
            }

            safeAddSeparator(menu);
            summaryFragments.forEach(f -> {
                JMenuItem item = new JMenuItem("Drop: " + f.shortDescription().renderNowOr("[loading]"));
                item.addActionListener(e -> dropSingleFragment(f));
                menu.add(item);
            });

            chrome.getThemeManager().registerPopupMenu(menu);
            return menu;
        }
    }

    public static final class StyleGuideChip extends WorkspaceChip {
        private static final String LABEL_TEXT = "AGENTS.md";
        private static final String ACCESSIBLE_DESC =
                "Project style guide (AGENTS.md). Informational; cannot be removed.";

        public StyleGuideChip(
                Chrome chrome,
                ContextManager contextManager,
                Supplier<Boolean> readOnlySupplier,
                @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback,
                @Nullable Consumer<ContextFragment> onRemoveFragment,
                ContextFragment fragment) {
            super(
                    chrome,
                    contextManager,
                    readOnlySupplier,
                    hoverCallback,
                    onRemoveFragment,
                    Set.of(fragment),
                    ChipKind.OTHER);
            setCloseEnabled(false);
            materialChip.setCloseVisible(false);
            materialChip.setText(LABEL_TEXT);
            materialChip.setToolTipText(buildStyleGuideTooltip());
            materialChip.setCloseToolTipText("Informational; cannot be removed");
            var ac = materialChip.getAccessibleContext();
            if (ac != null) {
                ac.setAccessibleName(LABEL_TEXT);
                ac.setAccessibleDescription(ACCESSIBLE_DESC);
            }
        }

        private String buildStyleGuideTooltip() {
            return wrapTooltipHtml(
                    "<b>AGENTS.md</b> — informational Style Guide<br/>It is always applied automatically to prompts.<br/><br/><i>This chip cannot be removed.</i>",
                    420);
        }

        @Override
        public void applyTheme() {
            Color bg = ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.NOTIF_INFO_BG);
            Color fg = ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.NOTIF_INFO_FG);
            Color border = ai.brokk.gui.mop.ThemeColors.getColor(ai.brokk.gui.mop.ThemeColors.NOTIF_INFO_BORDER);
            materialChip.setChipColors(bg, fg, border);
        }

        @Override
        protected void onCloseClick() {
            chrome.systemNotify(
                    "AGENTS.md is informational and cannot be removed.", "Workspace", JOptionPane.INFORMATION_MESSAGE);
        }

        @Override
        protected void updateTextAndTooltip(ContextFragment fragment) {
            materialChip.setText(LABEL_TEXT);
            materialChip.setToolTipText(buildStyleGuideTooltip());
            var ac = materialChip.getAccessibleContext();
            if (ac != null) ac.setAccessibleDescription(ACCESSIBLE_DESC);
        }

        @Override
        protected JPopupMenu createContextMenu() {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem showContentsItem = new JMenuItem("Show Contents");
            showContentsItem.addActionListener(e -> onPrimaryClick());
            menu.add(showContentsItem);

            var scenario = new ContextActionsHandler.SingleFragment(getPrimaryFragment());
            var actions = scenario.getActions(chrome.getContextActionsHandler());
            boolean separatorPending = false;
            for (var action : actions) {
                if (action == null) {
                    separatorPending = true;
                    continue;
                }
                if (isDropAction(action)) continue;
                String name = (String) action.getValue(Action.NAME);
                if ("Show Contents".equals(name) || "Drop".equals(name) || "Drop Others".equals(name)) continue;
                if (separatorPending) {
                    safeAddSeparator(menu);
                    separatorPending = false;
                }
                menu.add(action);
            }
            chrome.getThemeManager().registerPopupMenu(menu);
            return menu;
        }

        @Override
        protected void onPrimaryClick() {
            var selected = contextManager.selectedContext();
            List<ProjectFile> candidateFiles = (selected == null)
                    ? List.of()
                    : selected.getAllFragmentsInDisplayOrder().stream()
                            .filter(f -> !(f instanceof ContextFragments.SummaryFragment))
                            .filter(f -> f.getType() != ContextFragment.FragmentType.SKELETON)
                            .flatMap(f -> f.files().renderNowOr(Set.of()).stream())
                            .distinct()
                            .toList();
            contextManager
                    .submitBackgroundTask(
                            "Compute AGENTS.md",
                            () -> ProjectGuideResolver.resolve(candidateFiles, contextManager.getProject()))
                    .thenAccept(content -> SwingUtilities.invokeLater(() -> {
                        var syntheticFragment = new ContextFragments.StringFragment(
                                chrome.getContextManager(),
                                content,
                                "AGENTS.md",
                                SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
                        chrome.openFragmentPreview(syntheticFragment);
                    }));
        }
    }
}
