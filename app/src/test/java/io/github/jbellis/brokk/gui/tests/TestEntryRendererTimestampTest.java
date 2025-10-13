package io.github.jbellis.brokk.gui.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import org.junit.jupiter.api.Test;

/**
 * Tests for TestRunnerPanel's list cell rendering of timestamps.
 *
 * <p>Uses reflection to instantiate the private nested TestEntryRenderer and to set fixed timestamps on TestEntry.
 */
public class TestEntryRendererTimestampTest {

    private static Object newRenderer() throws Exception {
        Class<?> rendererClass = Class.forName("io.github.jbellis.brokk.gui.tests.TestRunnerPanel$TestEntryRenderer");
        Constructor<?> ctor = rendererClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static void setInstantField(Object target, String fieldName, Instant value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void completedTimestampPreferredAndRendered() throws Exception {
        // Arrange: create entry with both startedAt and completedAt
        var entry = new TestEntry("example/path/MyTest.java", "MyTest");
        // Set fixed instants
        Instant started = Instant.parse("2024-03-10T12:34:56Z");
        Instant completed = Instant.parse("2024-03-10T12:56:34Z");

        // Reflectively set private timestamp fields
        setInstantField(entry, "startedAt", started);
        setInstantField(entry, "completedAt", completed);

        // Instantiate the private renderer via reflection
        var renderer = (DefaultListCellRenderer) newRenderer();

        // Act: render the cell
        JList<?> list = new JList<>();
        var comp = renderer.getListCellRendererComponent(list, entry, 0, false, false);
        assertTrue(comp instanceof JLabel, "Renderer must return a JLabel");
        var label = (JLabel) comp;

        // Assert: label text contains display name and HH:mm:ss of COMPLETED, tooltip is ISO instant of COMPLETED
        String expectedTime = DateTimeFormatter.ofPattern("HH:mm:ss").format(completed.atZone(ZoneId.systemDefault()));

        assertTrue(label.getText().contains("MyTest"), "Label text should include display name");
        assertTrue(label.getText().contains(expectedTime), "Label should include completed time in HH:mm:ss");
        String expectedTooltip = DateTimeFormatter.ISO_INSTANT.format(completed);
        assertEquals(expectedTooltip, label.getToolTipText(), "Tooltip should be ISO-8601 completed timestamp");
    }

    @Test
    void startedTimestampRenderedWhenNoCompleted() throws Exception {
        // Arrange: entry with only startedAt
        var entry = new TestEntry("example/path/OtherTest.java", "OtherTest");
        Instant started = Instant.parse("2024-05-01T08:09:10Z");
        setInstantField(entry, "startedAt", started);

        var renderer = (DefaultListCellRenderer) newRenderer();

        // Act
        JList<?> list = new JList<>();
        var comp = renderer.getListCellRendererComponent(list, entry, 0, false, false);
        assertTrue(comp instanceof JLabel, "Renderer must return a JLabel");
        var label = (JLabel) comp;

        // Assert: label contains started HH:mm:ss and tooltip is started ISO instant
        String expectedTime = DateTimeFormatter.ofPattern("HH:mm:ss").format(started.atZone(ZoneId.systemDefault()));
        assertTrue(label.getText().contains("OtherTest"), "Label text should include display name");
        assertTrue(label.getText().contains(expectedTime), "Label should include started time in HH:mm:ss");
        String expectedTooltip = DateTimeFormatter.ISO_INSTANT.format(started);
        assertEquals(expectedTooltip, label.getToolTipText(), "Tooltip should be ISO-8601 started timestamp");
    }
}
