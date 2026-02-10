package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import org.junit.jupiter.api.Test;

class BorderUtilsTest {

    @Test
    void testAddFocusBorderUsesRoundedCorners() {
        JTextArea area = new JTextArea();
        BorderUtils.addFocusBorder(area, area);

        // Verify initial state (unfocused)
        assertRoundedLineBorder(area.getBorder(), "Initial unfocused border should be rounded");

        // Simulate focus gained
        simulateFocusEvent(area, true);
        assertRoundedLineBorder(area.getBorder(), "Focused border should be rounded");

        // Simulate focus lost
        simulateFocusEvent(area, false);
        assertRoundedLineBorder(area.getBorder(), "Restored unfocused border should be rounded");
    }

    private void assertRoundedLineBorder(javax.swing.border.Border border, String message) {
        LineBorder lineBorder;
        if (border instanceof CompoundBorder compound) {
            lineBorder = (LineBorder) compound.getOutsideBorder();
        } else {
            lineBorder = (LineBorder) border;
        }

        assertNotNull(lineBorder, message + ": Border is not a LineBorder");
        assertTrue(lineBorder.getRoundedCorners(), message + ": Rounded corners not set to true");
    }

    private void simulateFocusEvent(JComponent component, boolean gained) {
        FocusEvent event = new FocusEvent(component, gained ? FocusEvent.FOCUS_GAINED : FocusEvent.FOCUS_LOST);
        for (FocusListener listener : component.getFocusListeners()) {
            if (gained) {
                listener.focusGained(event);
            } else {
                listener.focusLost(event);
            }
        }
    }
}
