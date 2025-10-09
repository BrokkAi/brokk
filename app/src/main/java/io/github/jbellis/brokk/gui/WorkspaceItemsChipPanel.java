package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.dialogs.DropActionDialog;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.util.Messages;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Displays current workspace items as "chips" with a close button to remove them from the workspace. Listens to context
 * changes and updates itself accordingly.
 */
public class WorkspaceItemsChipPanel extends JPanel implements ThemeAware, Scrollable {
    private static final Logger logger = LogManager.getLogger(WorkspaceItemsChipPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private @Nullable Consumer<ContextFragment> onRemoveFragment;

    public WorkspaceItemsChipPanel(Chrome chrome) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        setOpaque(false);
        this.chrome = chrome;
        this.contextManager = chrome.getContextManager();
        setTransferHandler(createFileDropHandler());
    }

    /**
     * Programmatically set the fragments to display as chips. Safe to call from any thread; updates are marshaled to
     * the EDT.
     */
    public void setFragments(List<ContextFragment> fragments) {
        SwingUtilities.invokeLater(() -> updateChips(fragments));
    }

    /**
     * Sets a listener invoked when a chip's remove button is clicked. If not set, the panel will default to removing
     * from the ContextManager.
     */
    public void setOnRemoveFragment(Consumer<ContextFragment> listener) {
        this.onRemoveFragment = listener;
    }

    private void updateChips(List<ContextFragment> fragments) {
        removeAll();

        for (var fragment : fragments) {
            add(createChip(fragment));
        }

        // Re-layout this panel
        revalidate();
        repaint();

        // Also nudge ancestors so containers like BoxLayout recompute heights
        Container p = getParent();
        while (p != null) {
            if (p instanceof JComponent jc) {
                jc.revalidate();
                jc.repaint();
            }
            p = p.getParent();
        }
    }

    private enum ChipKind {
        EDIT,
        SUMMARY,
        OTHER
    }

    // Rounded chip container that paints a rounded background and border
    private static final class RoundedChipPanel extends JPanel {
        private Color borderColor = Color.GRAY;
        private int arc = 12;

        RoundedChipPanel() {
            setOpaque(false);
        }

        void setBorderColor(Color c) {
            this.borderColor = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = getBackground();
                if (bg == null) {
                    bg = getParent() != null ? getParent().getBackground() : Color.LIGHT_GRAY;
                }
                int w = getWidth();
                int h = getHeight();
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(borderColor);
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            } finally {
                g2.dispose();
            }
        }
    }

    private ChipKind classify(ContextFragment fragment) {
        Context ctx = contextManager.selectedContext();
        // EDIT: fragments that are in the editable file stream of the currently selected context
        if (ctx != null) {
            boolean isEdit = ctx.fileFragments().anyMatch(f -> f == fragment);
            if (isEdit) {
                return ChipKind.EDIT;
            }
        }
        // SUMMARY: fragments produced by summarize action are Skeletons
        if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
            return ChipKind.SUMMARY;
        }
        // OTHER: everything else
        return ChipKind.OTHER;
    }

    private static boolean isDarkColor(Color c) {
        // Relative luminance per ITU-R BT.709
        double r = c.getRed() / 255.0;
        double g = c.getGreen() / 255.0;
        double b = c.getBlue() / 255.0;
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return lum < 0.5;
    }

    private static Color contrastingText(Color bg) {
        return isDarkColor(bg) ? Color.WHITE : Color.BLACK;
    }

    // Lighten a color by blending it towards white by the given fraction (0..1)
    private static Color lighten(Color c, float fraction) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        int r = c.getRed() + Math.round((255 - c.getRed()) * fraction);
        int g = c.getGreen() + Math.round((255 - c.getGreen()) * fraction);
        int b = c.getBlue() + Math.round((255 - c.getBlue()) * fraction);
        return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b), c.getAlpha());
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
        // Track viewport width to compute wrapped height
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

    // Tooltip helpers

    private static String wrapTooltipHtml(String innerHtml, int maxWidthPx) {
        // Use Swing's HTML renderer with a width style to enable wrapping.
        // The BasicHTML engine respects width on body in practice.
        return "<html><body style='width: " + maxWidthPx + "px'>" + innerHtml + "</body></html>";
    }

    private static String buildSummaryLabel(ContextFragment fragment) {
        int n = (int)
                fragment.files().stream().map(f -> f.toString()).distinct().count();
        return "Summary" + (n > 0 ? " (" + n + ")" : "");
    }

    /**
     * Builds an HTML snippet showing approximate size metrics (LOC and tokens) for the fragment. Returns an empty
     * string if metrics are not applicable (e.g., non-text/image fragments).
     */
    private static String buildMetricsHtml(ContextFragment fragment) {
        try {
            // Only compute for text-like fragments; non-text (e.g., images) do not have meaningful text metrics
            if (fragment.isText() || fragment.getType().isOutput()) {
                String text = fragment.text();
                int loc = text.split("\\r?\\n", -1).length;
                int tokens = Messages.getApproximateTokens(text);
                return "<div><b>Size:</b> " + String.format("%,d", loc) + " LOC \u2022 ~" + String.format("%,d", tokens)
                        + " tokens</div><br/>";
            }
        } catch (Exception ignored) {
            // Best effort; if anything goes wrong, just return no metrics
        }
        return "";
    }

    private static String buildSummaryTooltip(ContextFragment fragment) {
        var files = fragment.files().stream()
                .map(f -> f.toString())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        StringBuilder body = new StringBuilder();

        // Prepend metrics (LOC + tokens) if available
        String metrics = buildMetricsHtml(fragment);
        if (!metrics.isEmpty()) {
            body.append(metrics);
        }

        // Header and divider
        body.append("<div><b>Summaries</b></div>");
        body.append("<hr style='border:0;border-top:1px solid #ccc;margin:4px 0 6px 0;'/>");

        if (files.isEmpty()) {
            // Fallback: if no files are available, show any description as a last resort
            String d;
            try {
                d = fragment.description();
            } catch (Exception e) {
                d = fragment.shortDescription();
            }
            if (d == null) d = "";
            body.append(StringEscapeUtils.escapeHtml4(d));
        } else {
            body.append("<ul style='margin:0;padding-left:16px'>");
            for (var f : files) {
                body.append("<li>").append(StringEscapeUtils.escapeHtml4(f)).append("</li>");
            }
            body.append("</ul>");
        }

        body.append("<br/><i>Click to preview contents</i>");
        return wrapTooltipHtml(body.toString(), 420);
    }

    private static String buildDefaultTooltip(ContextFragment fragment) {
        String d;
        try {
            d = fragment.description();
        } catch (Exception e) {
            d = fragment.shortDescription();
        }
        if (d == null) d = "";

        // Preserve existing newlines as line breaks for readability
        String descriptionHtml = StringEscapeUtils.escapeHtml4(d)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br/>");

        StringBuilder body = new StringBuilder();

        // Prepend metrics (LOC + tokens) if available
        String metrics = buildMetricsHtml(fragment);
        if (!metrics.isEmpty()) {
            body.append(metrics);
        }

        body.append(descriptionHtml);
        body.append("<br/><br/><i>Click to preview contents</i>");

        return wrapTooltipHtml(body.toString(), 420);
    }

    // Capitalize only the first character of the given string; leaves the rest unchanged.
    private static String capitalizeFirst(String s) {
        if (s.isEmpty()) {
            return s;
        }
        int first = s.codePointAt(0);
        int upper = Character.toUpperCase(first);
        if (upper == first) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        sb.appendCodePoint(upper);
        sb.append(s.substring(Character.charCount(first)));
        return sb.toString();
    }

    private void styleChip(JPanel chip, JLabel label, boolean isDark, @Nullable ContextFragment fragment) {
        ChipKind kind = fragment == null ? ChipKind.OTHER : classify(fragment);

        Color bg;
        Color fg;
        Color border;

        switch (kind) {
            case EDIT -> {
                // Use accent color for EDIT chips; fall back to linkColor, then to a reasonable theme color
                bg = UIManager.getColor("Component.accentColor");
                if (bg == null) {
                    bg = UIManager.getColor("Component.linkColor");
                }
                if (bg == null) {
                    // Robust fallback if theme key is missing
                    bg = ThemeColors.getColor(isDark, "git_badge_background");
                }
                // In light mode, make the accent background lighter for a softer look
                if (!isDark) {
                    bg = lighten(bg, 0.7f); // blend 70% towards white
                }
                fg = contrastingText(bg);
                border = UIManager.getColor("Component.borderColor");
                if (border == null) {
                    border = Color.GRAY;
                }
            }
            case SUMMARY -> {
                bg = ThemeColors.getColor(isDark, "notif_cost_bg");
                fg = ThemeColors.getColor(isDark, "notif_cost_fg");
                border = ThemeColors.getColor(isDark, "notif_cost_border");
            }
            default -> {
                // Info/Warning colors for everything else
                bg = ThemeColors.getColor(isDark, "notif_info_bg");
                fg = ThemeColors.getColor(isDark, "notif_info_fg");
                border = ThemeColors.getColor(isDark, "notif_info_border");
            }
        }

        chip.setBackground(bg);
        label.setForeground(fg);

        // Rounded look: padding as inner border, rounded border painted by RoundedChipPanel
        var inner = new EmptyBorder(2, 8, 2, 6);
        if (chip instanceof RoundedChipPanel rc) {
            rc.setBorderColor(border);
            chip.setBorder(inner);
        } else {
            // Fallback for non-rounded panel (shouldn't happen with current creation)
            var outer = new MatteBorder(1, 1, 1, 1, border);
            chip.setBorder(new CompoundBorder(outer, inner));
        }

        // Style the divider (vertical line) between label and close button
        for (var child : chip.getComponents()) {
            if (child instanceof JPanel p && Boolean.TRUE.equals(p.getClientProperty("brokk.chip.separator"))) {
                p.setBackground(border);
                var pref = p.getPreferredSize();
                int h = Math.max(label.getPreferredSize().height - 6, 10);
                p.setPreferredSize(new Dimension(pref.width, h));
                p.revalidate();
                p.repaint();
            }
        }
    }

    private Icon buildCloseIcon() {
        // Always fetch the current UI icon to respect the active theme
        var uiIcon = UIManager.getIcon("Brokk.close");
        if (uiIcon == null) {
            uiIcon = Icons.CLOSE;
        }
        int targetW = 10;
        int targetH = 10;

        Icon source = uiIcon;
        Image scaled;
        if (source instanceof ImageIcon ii) {
            scaled = ii.getImage().getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
        } else {
            int w = Math.max(1, source.getIconWidth());
            int h = Math.max(1, source.getIconHeight());
            BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = buf.createGraphics();
            try {
                source.paintIcon(null, g2, 0, 0);
            } finally {
                g2.dispose();
            }
            scaled = buf.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
        }

        if (scaled == null) {
            // Robust fallback: draw a simple X
            BufferedImage fallback = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = fallback.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.GRAY);
                g2.drawLine(1, 1, targetW - 2, targetH - 2);
                g2.drawLine(1, targetH - 2, targetW - 2, 1);
            } finally {
                g2.dispose();
            }
            return new ImageIcon(fallback);
        }

        return new ImageIcon(scaled);
    }

    private Component createChip(ContextFragment fragment) {
        var chip = new RoundedChipPanel();
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(false);

        // Use a compact label for SUMMARY chips; otherwise use the fragment's shortDescription
        ChipKind kindForLabel = classify(fragment);
        String labelText;
        if (kindForLabel == ChipKind.SUMMARY) {
            labelText = buildSummaryLabel(fragment);
        } else if (kindForLabel == ChipKind.OTHER) {
            labelText = capitalizeFirst(fragment.shortDescription());
        } else {
            labelText = fragment.shortDescription();
        }
        var label = new JLabel(labelText);

        // Improve discoverability and accessibility with wrapped HTML tooltips
        try {
            if (kindForLabel == ChipKind.SUMMARY) {
                label.setToolTipText(buildSummaryTooltip(fragment));
                // Accessible description: use the full (non-HTML) description
                label.getAccessibleContext().setAccessibleDescription(fragment.description());
            } else {
                label.setToolTipText(buildDefaultTooltip(fragment));
                label.getAccessibleContext().setAccessibleDescription(fragment.description());
            }
        } catch (Exception ignored) {
            // Defensive: avoid issues if any accessor fails
        }

        // Make label clickable to open preview
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    chrome.openFragmentPreview(fragment);
                }
            }
        });

        // MaterialButton does not provide a constructor that accepts an Icon on this classpath.
        // Construct with an empty label and set the icon explicitly.
        var close = new MaterialButton("");
        close.setIcon(buildCloseIcon());
        close.setFocusable(false);
        // keep the icon-only styling but keep hit area reasonable
        close.setOpaque(false);
        close.setContentAreaFilled(false);
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setMargin(new Insets(0, 0, 0, 0));
        close.setPreferredSize(new Dimension(14, 14));
        close.setToolTipText("Remove from Workspace");
        try {
            close.getAccessibleContext().setAccessibleName("Remove " + fragment.shortDescription());
        } catch (Exception ignored) {
            // best-effort accessibility improvements
        }
        close.addActionListener(e -> {
            // Guard against interfering with an ongoing LLM task
            if (contextManager.isLlmTaskInProgress()) {
                return;
            }

            // Perform the removal via the ContextManager task queue to avoid
            // listener reentrancy and ensure proper processing of the drop.
            chrome.getContextManager().submitContextTask(() -> {
                if (onRemoveFragment != null) {
                    onRemoveFragment.accept(fragment);
                } else {
                    contextManager.drop(Collections.singletonList(fragment));
                }
            });
        });

        chip.add(label);

        // Add a slim vertical divider between label and close button
        var sep = new JPanel();
        sep.putClientProperty("brokk.chip.separator", true);
        sep.setOpaque(true);
        sep.setPreferredSize(new Dimension(1, Math.max(label.getPreferredSize().height - 6, 10)));
        sep.setMinimumSize(new Dimension(1, 10));
        sep.setMaximumSize(new Dimension(1, Integer.MAX_VALUE));
        chip.add(sep);

        chip.add(close);

        // Keep a handle to the fragment and close button so theme changes can restyle accurately
        chip.putClientProperty("brokk.fragment", fragment);
        chip.putClientProperty("brokk.chip.closeButton", close);
        styleChip(chip, label, chrome.getTheme().isDarkTheme(), fragment);

        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // Open preview on left-click anywhere on the chip (excluding close button which handles its own events)
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    chrome.openFragmentPreview(fragment);
                }
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    ContextMenuUtils.showContextFragmentMenu(chip, e.getX(), e.getY(), fragment, chrome);
                }
            }
        });

        return chip;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        applyTheme(guiTheme, false);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        SwingUtilities.invokeLater(() -> {
            boolean isDark = guiTheme.isDarkTheme();
            restyleAllChips(isDark);
            // Defer a second pass to catch any late UIManager icon changes after LAF/theme switch
            SwingUtilities.invokeLater(() -> restyleAllChips(isDark));
        });
    }

    private void restyleAllChips(boolean isDark) {
        for (var component : getComponents()) {
            if (component instanceof JPanel chip) {
                JLabel label = null;
                for (var child : chip.getComponents()) {
                    if (child instanceof JLabel jLabel) {
                        label = jLabel;
                        break;
                    }
                }
                if (label != null) {
                    var fragObj = chip.getClientProperty("brokk.fragment");
                    ContextFragment fragment = (fragObj instanceof ContextFragment f) ? f : null;
                    styleChip(chip, label, isDark, fragment);
                }
                var closeObj = chip.getClientProperty("brokk.chip.closeButton");
                if (closeObj instanceof MaterialButton b) {
                    b.setIcon(buildCloseIcon());
                    b.revalidate();
                    b.repaint();
                }
            }
        }
    }

    private boolean isAnalyzerReady() {
        if (!contextManager.getAnalyzerWrapper().isReady()) {
            chrome.systemNotify(
                    AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                    AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    private TransferHandler createFileDropHandler() {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }

                if (contextManager.isLlmTaskInProgress()) {
                    chrome.systemNotify(
                            "Cannot add to workspace while an action is running.",
                            "Workspace",
                            JOptionPane.INFORMATION_MESSAGE);
                    return false;
                }

                try {
                    @SuppressWarnings("unchecked")
                    List<File> files =
                            (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) {
                        return false;
                    }

                    var projectRoot = contextManager
                            .getProject()
                            .getRoot()
                            .toAbsolutePath()
                            .normalize();
                    // Map to ProjectFile inside this project; ignore anything outside
                    var projectFiles = files.stream()
                            .map(File::toPath)
                            .map(Path::toAbsolutePath)
                            .map(Path::normalize)
                            .filter(p -> {
                                boolean inside = p.startsWith(projectRoot);
                                if (!inside) {
                                    logger.debug("Ignoring dropped file outside project: {}", p);
                                }
                                return inside;
                            })
                            .map(p -> projectRoot.relativize(p))
                            .map(rel -> new ProjectFile(projectRoot, rel))
                            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

                    if (projectFiles.isEmpty()) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No project files found in drop");
                        return false;
                    }

                    // Ask the user what to do
                    var analyzedExts = contextManager.getProject().getAnalyzerLanguages().stream()
                            .flatMap(lang -> lang.getExtensions().stream())
                            .collect(Collectors.toSet());
                    boolean canSummarize = projectFiles.stream().anyMatch(pf -> analyzedExts.contains(pf.extension()));
                    java.awt.Point pointer = null;
                    try {
                        var pi = java.awt.MouseInfo.getPointerInfo();
                        if (pi != null) {
                            pointer = pi.getLocation();
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                    var selection = DropActionDialog.show(chrome.getFrame(), canSummarize, pointer);
                    if (selection == null) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Drop canceled");
                        return false;
                    }
                    switch (selection) {
                        case EDIT -> {
                            // Only allow editing tracked files; others are silently ignored by editFiles
                            contextManager.submitContextTask(() -> {
                                contextManager.addFiles(projectFiles);
                            });
                        }
                        case SUMMARIZE -> {
                            if (!isAnalyzerReady()) {
                                return false;
                            }
                            contextManager.submitContextTask(() -> {
                                contextManager.addSummaries(
                                        new java.util.HashSet<>(projectFiles), Collections.emptySet());
                            });
                        }
                        default -> {
                            logger.warn("Unexpected drop selection: {}", selection);
                            return false;
                        }
                    }

                    return true;
                } catch (Exception ex) {
                    logger.error("Error importing dropped files into workspace", ex);
                    chrome.toolError("Failed to import dropped files: " + ex.getMessage());
                    return false;
                }
            }
        };
    }
}
