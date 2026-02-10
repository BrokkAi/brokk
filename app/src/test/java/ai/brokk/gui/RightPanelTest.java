package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.gui.RightPanel.UndockTarget;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class RightPanelTest {

    @Test
    void testUndockTargetMapping() {
        JPanel build = new JPanel();
        JPanel verticalBuild = new JPanel();
        JPanel review = new JPanel();
        JPanel preview = new JPanel();
        JPanel terminal = new JPanel();
        JPanel unknown = new JPanel();

        assertEquals(
                UndockTarget.NONE, RightPanel.getUndockTarget(build, review, preview, terminal, build, verticalBuild));
        assertEquals(
                UndockTarget.NONE,
                RightPanel.getUndockTarget(verticalBuild, review, preview, terminal, build, verticalBuild));

        assertEquals(
                UndockTarget.REVIEW,
                RightPanel.getUndockTarget(review, review, preview, terminal, build, verticalBuild));
        assertEquals(
                UndockTarget.PREVIEW,
                RightPanel.getUndockTarget(preview, review, preview, terminal, build, verticalBuild));
        assertEquals(
                UndockTarget.TERMINAL,
                RightPanel.getUndockTarget(terminal, review, preview, terminal, build, verticalBuild));

        assertEquals(
                UndockTarget.NONE,
                RightPanel.getUndockTarget(unknown, review, preview, terminal, build, verticalBuild));
    }

    @Test
    void testUndockTargetWithNullVerticalLayout() {
        JPanel build = new JPanel();
        JPanel review = new JPanel();

        // Ensure it doesn't NPE if verticalActivityCombinedPanel is null
        assertEquals(
                UndockTarget.NONE, RightPanel.getUndockTarget(build, review, new JPanel(), new JPanel(), build, null));
        assertEquals(
                UndockTarget.REVIEW,
                RightPanel.getUndockTarget(review, review, new JPanel(), new JPanel(), build, null));
    }
}
