package io.github.jbellis.brokk.gui.components;

import com.formdev.flatlaf.icons.FlatCheckBoxIcon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.AbstractButton;

public class SwitchIcon extends FlatCheckBoxIcon {
    private static final int ICON_WIDTH = 28;
    private static final int ICON_HEIGHT = 16;
    private static final int KNOB_WIDTH = ICON_WIDTH / 2 - 2;
    private static final int KNOB_HEIGHT = ICON_HEIGHT - 5;
    private static final int SWITCH_ARC = ICON_HEIGHT;

    public SwitchIcon() {
        super();
    }

    protected void paintBorder(Component c, Graphics2D g2) {
        int arcwh = SWITCH_ARC;
        g2.fillRoundRect(3, 0, ICON_WIDTH - 1, ICON_HEIGHT - 1, arcwh, arcwh);
    }

    protected void paintBackground(Component c, Graphics2D g2) {
        int arcwh = SWITCH_ARC - 1;
        g2.fillRoundRect(4, 1, ICON_WIDTH - 3, ICON_HEIGHT - 3, arcwh, arcwh);
    }

    @Override
    protected void paintCheckmark(Component c, Graphics2D g2) {
        int arcwh = SWITCH_ARC - 1;
        int x = KNOB_WIDTH - 1;
        g2.translate(x, 0);
        g2.fillRoundRect(5, 2, KNOB_WIDTH, KNOB_HEIGHT, arcwh, arcwh);
        g2.translate(-x, 0);
    }

    @Override
    protected void paintFocusBorder(Component c, Graphics2D g2) {
        int fw = Math.round(focusWidth);
        int w = getIconWidth() - 1 + (fw * 2);
        int h = getIconHeight() - 1 + (fw * 2);
        int arcwh = SWITCH_ARC + (fw * 2);
        g2.fillRoundRect(-fw + 3, -fw, w, h, arcwh, arcwh);
    }

    @Override
    protected void paintIcon(Component c, Graphics2D g2) {
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        boolean selected = (c instanceof AbstractButton ab && ab.isSelected());

        // Track geometry (pill)
        int padding = 2; // outer horizontal padding
        int trackX = padding;
        int trackY = 1; // slight vertical centering
        int trackW = ICON_WIDTH - padding * 2;
        int trackH = ICON_HEIGHT - 2;
        int arc = trackH; // perfect pill

        // Draw the pill-shaped track (keep color constant regardless of selection)
        Color trackColor = disabledCheckmarkColor;
        g.setColor(trackColor);
        g.fillRoundRect(trackX, trackY, trackW, trackH, arc, arc);

        // Knob geometry
        int knobInset = 2; // inner spacing from track edge
        int knobH = trackH - knobInset * 2;
        int knobW = (trackW / 2) - knobInset; // fits exactly half the track with inset
        if (knobW < knobH) {
            knobW = knobH; // ensure at least circular
        }
        int knobY = trackY + (trackH - knobH) / 2;
        int knobX = selected
                ? trackX + trackW - knobW - knobInset // right position
                : trackX + knobInset; // left position

        // Draw knob
        g.setColor(Color.WHITE);
        g.fillRoundRect(knobX, knobY, knobW, knobH, knobH, knobH);

        g.dispose();
    }

    @Override
    public int getIconWidth() {
        return ICON_WIDTH;
    }

    @Override
    public int getIconHeight() {
        return ICON_HEIGHT;
    }
}
