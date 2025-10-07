package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.util.Messages;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Cursor;
import java.awt.Insets;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.ImageIcon;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import org.jetbrains.annotations.Nullable;

/**
 * Displays current workspace items as "chips" with a close button to remove them from the workspace.
 * Listens to context changes and updates itself accordingly.
 */
public class WorkspaceItemsChipPanel extends JPanel implements ThemeAware {

    private final Chrome chrome;
    private final ContextManager contextManager;
    private @Nullable Consumer<ContextFragment> onRemoveFragment;

    public WorkspaceItemsChipPanel(Chrome chrome) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        setOpaque(false);
        this.chrome = chrome;
        this.contextManager = chrome.getContextManager();
    }

    /**
     * Programmatically set the fragments to display as chips. Safe to call from any thread; updates are
     * marshaled to the EDT.
     */
    public void setFragments(List<ContextFragment> fragments) {
        SwingUtilities.invokeLater(() -> updateChips(fragments));
    }

    /**
     * Sets a listener invoked when a chip's remove button is clicked. If not set, the panel will
     * default to removing from the ContextManager.
     */
    public void setOnRemoveFragment(Consumer<ContextFragment> listener) {
        this.onRemoveFragment = listener;
    }


    private void updateChips(List<ContextFragment> fragments) {
        removeAll();

        for (var fragment : fragments) {
            add(createChip(fragment));
        }

        revalidate();
        repaint();
    }

    private enum ChipKind { EDIT, SUMMARY, OTHER }

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

    // Tooltip helpers
    private static String htmlEscape(String s) {
        String out = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        // Normalize whitespace a bit for readability
        out = out.replace("\t", "    ");
        return out;
    }

    private static String wrapTooltipHtml(String innerHtml, int maxWidthPx) {
        // Use Swing's HTML renderer with a width style to enable wrapping.
        // The BasicHTML engine respects width on body in practice.
        return "<html><body style='width: " + maxWidthPx + "px'>" + innerHtml + "</body></html>";
    }

    private static String[] summaryLinesFrom(ContextFragment fragment) {
        String desc;
        try {
            desc = fragment.description();
        } catch (Exception e) {
            desc = fragment.shortDescription();
        }
        if (desc == null) desc = "";
        // Split on CRLF/CR/LF
        String[] raw = desc.split("\\R");
        // Trim lines but keep empty filtering decisions for counting and display
        for (int i = 0; i < raw.length; i++) {
            raw[i] = raw[i].trim();
        }
        return raw;
    }


    private static String buildSummaryLabel(ContextFragment fragment) {
        int n = (int) fragment.files().stream()
                .map(f -> f.toString())
                .distinct()
                .count();
        return "Summary" + (n > 0 ? " (" + n + ")" : "");
    }

    /**
     * Builds an HTML snippet showing approximate size metrics (LOC and tokens) for the fragment.
     * Returns an empty string if metrics are not applicable (e.g., non-text/image fragments).
     */
    private static String buildMetricsHtml(ContextFragment fragment) {
        try {
            // Only compute for text-like fragments; non-text (e.g., images) do not have meaningful text metrics
            if (fragment.isText() || fragment.getType().isOutput()) {
                String text = fragment.text();
                int loc = text.split("\\r?\\n", -1).length;
                int tokens = Messages.getApproximateTokens(text);
                return "<div><b>Size:</b> " + String.format("%,d", loc)
                        + " LOC \u2022 ~" + String.format("%,d", tokens) + " tokens</div><br/>";
            }
        } catch (Exception ignored) {
            // Best effort; if anything goes wrong, just return no metrics
        }
        return "";
    }

    private static String buildSummaryTooltip(ContextFragment fragment) {
        String[] lines = summaryLinesFrom(fragment);
        StringBuilder body = new StringBuilder();

        // Prepend metrics (LOC + tokens) if available
        String metrics = buildMetricsHtml(fragment);
        if (!metrics.isEmpty()) {
            body.append(metrics);
        }

        boolean first = true;
        for (String s : lines) {
            if (s.isBlank()) continue;
            if (!first) body.append("<br/>");
            body.append(htmlEscape(s));
            first = false;
        }
        if (first) { // no non-blank lines were appended
            // Fallback to any available description
            String d;
            try {
                d = fragment.description();
            } catch (Exception e) {
                d = fragment.shortDescription();
            }
            if (d == null) d = "";
            body.append(htmlEscape(d));
        }

        // Add preview hint
        if (body.length() > 0) {
            body.append("<br/><br/><i>Click to preview contents</i>");
        } else {
            body.append("<i>Click to preview contents</i>");
        }
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
        String descriptionHtml = htmlEscape(d).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br/>");

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

    private void styleChip(JPanel chip, JLabel label, boolean isDark, @Nullable ContextFragment fragment) {
        ChipKind kind = fragment == null ? ChipKind.OTHER : classify(fragment);

        Color bg;
        Color fg;
        Color border;

        switch (kind) {
            case EDIT -> {
                // Use linkColor as requested
                bg = javax.swing.UIManager.getColor("Component.linkColor");
                if (bg == null) {
                    // Robust fallback if theme key is missing
                    bg = ThemeColors.getColor(isDark, "git_badge_background");
                }
                fg = contrastingText(bg);
                border = javax.swing.UIManager.getColor("Component.borderColor");
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

        var outer = new MatteBorder(1, 1, 1, 1, border);
        var inner = new EmptyBorder(2, 8, 2, 6);
        chip.setBorder(new CompoundBorder(outer, inner));
    }

    private Component createChip(ContextFragment fragment) {
        var chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(true);

        // Use a compact label for SUMMARY chips; otherwise use the fragment's shortDescription
        ChipKind kindForLabel = classify(fragment);
        String labelText = (kindForLabel == ChipKind.SUMMARY)
                ? buildSummaryLabel(fragment)
                : fragment.shortDescription();
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

        var originalIcon = Icons.CLOSE;

        Image image;
        if (originalIcon instanceof ImageIcon ii) {
            // If it's already an ImageIcon, scale its image directly
            image = ii.getImage().getScaledInstance(10, 10, Image.SCALE_SMOOTH);
        } else {
            // Otherwise paint the Icon into a BufferedImage and scale that.
            int w = originalIcon.getIconWidth();
            int h = originalIcon.getIconHeight();
            if (w <= 0) w = 16;
            if (h <= 0) h = 16;
            BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = buf.createGraphics();
            try {
                originalIcon.paintIcon(null, g2, 0, 0);
            } finally {
                g2.dispose();
            }
            image = buf.getScaledInstance(10, 10, Image.SCALE_SMOOTH);
        }

        // MaterialButton does not provide a constructor that accepts an Icon on this classpath.
        // Construct with an empty label and set the icon explicitly.
        var close = new MaterialButton("");
        close.setIcon(new ImageIcon(image));
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
        chip.add(close);

        // Keep a handle to the fragment so theme changes can restyle accurately
        chip.putClientProperty("brokk.fragment", fragment);
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
                }
            }
        });
    }
}
