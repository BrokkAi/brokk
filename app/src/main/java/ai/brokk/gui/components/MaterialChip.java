package ai.brokk.gui.components;

import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.project.MainProject;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import org.jetbrains.annotations.Nullable;

/**
 * A generic material-styled chip component with rounded corners.
 */
public class MaterialChip extends JPanel {
    protected static final int ARC = 12;

    private final JLabel label = new JLabel();
    private final JPanel iconPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final MaterialButton closeButton = new MaterialButton("");
    private final JPanel separator = new JPanel();

    private float alpha = 1.0f;
    private Color borderColor = Color.GRAY;
    private boolean selected = false;

    private boolean splitPainting = false;
    private @Nullable Color splitColor;

    public MaterialChip(String text) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        label.setText(text);

        iconPanel.setOpaque(false);

        closeButton.setFocusable(false);
        closeButton.setOpaque(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setMargin(new Insets(0, 0, 0, 0));
        closeButton.setPreferredSize(new Dimension(14, 14));
        closeButton.setVisible(false);

        separator.setOpaque(true);
        separator.setVisible(false);

        add(iconPanel);
        add(label);
        add(separator);
        add(closeButton);
    }

    public void setText(String text) {
        label.setText(text);
    }

    public void setChipColors(Color bg, Color fg, Color border) {
        setBackground(bg);
        label.setForeground(fg);
        borderColor = border;
        separator.setBackground(border);
        if (closeButton.isVisible()) {
            closeButton.setIcon(buildCloseIcon(bg));
        }
        repaint();
    }

    public void setAlpha(float alpha) {
        this.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
        repaint();
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        repaint();
    }

    public void setSplitPainting(boolean enabled, @Nullable Color splitColor) {
        this.splitPainting = enabled;
        this.splitColor = splitColor;
        repaint();
    }

    public void setCloseEnabled(boolean enabled) {
        closeButton.setEnabled(enabled);
        closeButton.setVisible(enabled);
        separator.setVisible(enabled);
        if (enabled) {
            updateSeparatorSize();
            closeButton.setIcon(buildCloseIcon(getBackground()));
        }
        revalidate();
    }

    public void setLeadingIcons(java.util.List<Icon> icons) {
        iconPanel.removeAll();
        for (Icon icon : icons) {
            Icon fitted = fitIconToChip(icon);
            JLabel iconLabel = new JLabel(fitted);
            iconLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 2));
            iconPanel.add(iconLabel);
        }
        iconPanel.setVisible(!icons.isEmpty());
        revalidate();
        repaint();
    }

    private Icon fitIconToChip(Icon base) {
        int target = Math.max(12, label.getPreferredSize().height - 4);
        try {
            if (base instanceof ai.brokk.gui.SwingUtil.ThemedIcon themed) {
                return themed.withSize(target);
            }
            int w = Math.max(1, base.getIconWidth());
            int h = Math.max(1, base.getIconHeight());
            BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = buf.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            base.paintIcon(null, g2, 0, 0);
            g2.dispose();
            Image scaled = buf.getScaledInstance(target, target, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Throwable t) {
            return base;
        }
    }

    public void setCloseVisible(boolean visible) {
        setCloseEnabled(visible);
    }

    public void setCloseToolTipText(String text) {
        closeButton.setToolTipText(text);
    }

    public void setCloseAccessibleName(String name) {
        closeButton.getAccessibleContext().setAccessibleName(name);
    }

    public void addCloseListener(ActionListener l) {
        closeButton.addActionListener(l);
    }

    public void addChipClickListener(Runnable onClick) {
        this.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    onClick.run();
                }
            }
        });
    }

    /**
     * Installs logic to trigger the close action when clicking to the right of the separator.
     */
    public void addSeparatorCloseListener(Runnable onClose) {
        this.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && separator.isVisible()) {
                    int clickX = e.getX();
                    int separatorEndX = separator.getX() + separator.getWidth();
                    if (clickX > separatorEndX) {
                        onClose.run();
                    }
                }
            }
        });
    }

    private void updateSeparatorSize() {
        int h = Math.max(label.getPreferredSize().height - 6, 10);
        separator.setPreferredSize(new Dimension(1, h));
    }

    @Override
    public void paint(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            if (alpha < 1.0f) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            }
            super.paint(g2);
        } finally {
            g2.dispose();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();

            if (splitPainting && splitColor != null) {
                int centerX = w / 2;
                int centerY = h / 2;
                int halfDiag = Math.max(w, h);
                int x1 = centerX - halfDiag, y1 = centerY + halfDiag;
                int x2 = centerX + halfDiag, y2 = centerY - halfDiag;

                java.awt.Polygon leftPoly = new java.awt.Polygon();
                leftPoly.addPoint(x1, y1);
                leftPoly.addPoint(x2, y2);
                leftPoly.addPoint(w + halfDiag, -halfDiag);
                leftPoly.addPoint(-halfDiag, -halfDiag);

                java.awt.Polygon rightPoly = new java.awt.Polygon();
                rightPoly.addPoint(x1, y1);
                rightPoly.addPoint(x2, y2);
                rightPoly.addPoint(w + halfDiag, h + halfDiag);
                rightPoly.addPoint(-halfDiag, h + halfDiag);

                g2.setClip(leftPoly);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, w - 1, h - 1, ARC, ARC);

                g2.setClip(rightPoly);
                g2.setColor(splitColor);
                g2.fillRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
                g2.setClip(null);
            } else {
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
            }

            if (selected) {
                g2.setColor(new Color(borderColor.getRed(), borderColor.getGreen(), borderColor.getBlue(), 40));
                g2.fillRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    @Override
    protected void paintBorder(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(borderColor);
            if (selected) {
                g2.setStroke(new BasicStroke(2.0f));
            }
            g2.drawRoundRect(0, 0, w - 1, h - 1, ARC, ARC);

            if (splitPainting && splitColor != null) {
                int centerX = w / 2, centerY = h / 2, halfLen = Math.min(w, h) / 2;
                g2.setColor(splitColor);
                g2.drawLine(centerX - halfLen, centerY + halfLen, centerX + halfLen, centerY - halfLen);
            }
        } finally {
            g2.dispose();
        }
    }

    protected Icon buildCloseIcon(Color chipBackground) {
        int targetW = 10;
        int targetH = 10;
        boolean isHighContrast = GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());

        if (isHighContrast) {
            BufferedImage icon = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = icon.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color iconColor = ai.brokk.difftool.utils.ColorUtil.contrastingText(chipBackground);
                g2.setColor(iconColor);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(2, 2, targetW - 3, targetH - 3);
                g2.drawLine(2, targetH - 3, targetW - 3, 2);
            } finally {
                g2.dispose();
            }
            return new ImageIcon(icon);
        }

        Icon uiIcon = UIManager.getIcon("Brokk.close");
        if (uiIcon == null) uiIcon = ai.brokk.gui.util.Icons.CLOSE;

        BufferedImage buf =
                new BufferedImage(uiIcon.getIconWidth(), uiIcon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = buf.createGraphics();
        uiIcon.paintIcon(null, g2, 0, 0);
        g2.dispose();

        Image scaled = buf.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }
}
