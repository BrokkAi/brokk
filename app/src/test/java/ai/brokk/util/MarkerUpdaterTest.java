package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkerUpdaterTest {

    private final MarkerUpdater updater = new MarkerUpdater("TEST");

    @Test
    void testUpdateEmptyFile() {
        String result = updater.update("", "New Content");
        String expected = "<!-- BROKK TEST BEGIN -->\nNew Content\n<!-- BROKK TEST END -->";
        assertEquals(expected, result);
    }

    @Test
    void testInsertIntoExistingText() {
        String source = "# Header\nSome user text.";
        String result = updater.update(source, "New Content");
        
        assertTrue(result.startsWith("# Header\nSome user text.\n\n"));
        assertTrue(result.contains("<!-- BROKK TEST BEGIN -->\nNew Content\n<!-- BROKK TEST END -->"));
    }

    @Test
    void testReplaceExistingSection() {
        String source = "# Header\n\n<!-- BROKK TEST BEGIN -->\nOld Content\n<!-- BROKK TEST END -->\n\nFooter";
        String result = updater.update(source, "New Content");
        
        String expected = "# Header\n\n<!-- BROKK TEST BEGIN -->\nNew Content\n<!-- BROKK TEST END -->\n\nFooter";
        assertEquals(expected, result);
    }

    @Test
    void testPreserveSurroundingText() {
        String source = "Top\n\n<!-- BROKK TEST BEGIN -->\nInside\n<!-- BROKK TEST END -->\n\nBottom";
        String result = updater.update(source, "Modified");
        
        assertTrue(result.startsWith("Top\n\n"));
        assertTrue(result.endsWith("\n\nBottom"));
        assertTrue(result.contains("Modified"));
    }
}
