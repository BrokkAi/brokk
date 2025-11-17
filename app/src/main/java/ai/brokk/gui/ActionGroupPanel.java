package ai.brokk.gui;

import ai.brokk.gui.components.RoundedLineBorder;
import ai.brokk.gui.mop.ThemeColors;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A pill-shaped action toggle panel that hosts the Code/Answer labels and the switch. It supports: - Hover background
 * highlight within rounded bounds using theme color "filter_icon_hover_background" - Clicking anywhere to toggle the
 * bound mode switch (avoids double toggle when clicking the switch itself) - Dynamic accent border via
 * setAccentColor(Color) - Hover persistence when moving across child components
 */
public class ActionGroupPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ActionGroupPanel.class);

    private final JCheckBox modeSwitch;
    private boolean hovering = false;

    private final MouseAdapter hoverListener = new MouseAdapter() {
        @Override
        public void mouseEntered(MouseEvent e) {
            setHovering(true);
        }

        @Override
        public void mouseExited(MouseEvent e) {
            // Only clear hover if we truly left the panel (not just moved over a child)
            var panel = ActionGroupPanel.this;
            var source = (Component) e.getSource();
            var point = SwingUtilities.convertPoint(source, e.getPoint(), panel);
            if (!panel.contains(point)) {
                setHovering(false);
            }
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getSource() != modeSwitch) {
                modeSwitch.doClick();
            }
        }
    };

    public ActionGroupPanel(JLabel codeModeLabel, JCheckBox modeSwitch, JLabel answerModeLabel) {
        super(new FlowLayout(FlowLayout.LEFT, 2, 0));
        this.modeSwitch = modeSwitch;

        // Initial border using UI border color
        Color borderColor = UIManager.getColor("Component.borderColor");
        if (borderColor == null) {
            borderColor = Color.GRAY;
        }
        setBorder(BorderFactory.createCompoundBorder(
                new RoundedLineBorder(borderColor, 1, -1), BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        setOpaque(false);

        // Add listeners to panel and children to preserve hover across children
        addMouseListener(hoverListener);

        add(codeModeLabel);
        add(Box.createHorizontalStrut(2));
        add(modeSwitch);
        add(Box.createHorizontalStrut(2));
        add(answerModeLabel);

        // Keep the grouping box tight; prevent BoxLayout from stretching it
        setMaximumSize(getPreferredSize());
    }

    @Override
    public Component add(Component comp) {
        if (!(comp instanceof Box.Filler)) {
            comp.addMouseListener(hoverListener);
        }
        return super.add(comp);
    }

    private void setHovering(boolean h) {
        if (this.hovering != h) {
            this.hovering = h;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (hovering) {
            boolean isDark = UIManager.getBoolean("laf.dark");
            Color hoverBg = null;
            try {
                hoverBg = ThemeColors.getColor(isDark, ThemeColors.FILTER_ICON_HOVER_BACKGROUND);
            } catch (Exception e) {
                logger.warn("Could not get theme color for hover background", e);
            }

            if (hoverBg != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int arc = getHeight(); // pill shape
                    g2.setColor(hoverBg);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                } finally {
                    g2.dispose();
                }
            }
        }
        super.paintComponent(g);
    }

    /** Update the accent border color applied around the pill. */
    public void setAccentColor(Color accent) {
        setBorder(BorderFactory.createCompoundBorder(
                new RoundedLineBorder(accent, 1, -1), BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        repaint();
    }
}
