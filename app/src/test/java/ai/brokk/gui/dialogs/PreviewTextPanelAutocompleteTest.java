package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewTextPanelAutocompleteTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldEnablePreviewAutocomplete_requiresEditableLiveFile() {
        var file = new ProjectFile(tempDir, "src/Main.java");

        assertTrue(PreviewTextPanel.shouldEnablePreviewAutocomplete(file, true));
        assertFalse(PreviewTextPanel.shouldEnablePreviewAutocomplete(file, false));
        assertFalse(PreviewTextPanel.shouldEnablePreviewAutocomplete(null, true));
    }

    @Test
    void buildPreviewAutocompleteRequest_rejectsSelectionsAndVeryShortPrefixes() {
        assertNull(PreviewTextPanel.buildPreviewAutocompleteRequest("a", 1, null));
        assertNull(PreviewTextPanel.buildPreviewAutocompleteRequest("ab", 2, "b"));
        assertNull(PreviewTextPanel.buildPreviewAutocompleteRequest("Loading...", 10, null));
    }

    @Test
    void buildPreviewAutocompleteRequest_boundsPrefixAndSuffixAroundCaret() {
        String text = "0123456789".repeat(900);
        int caretPosition = 4500;

        var request = PreviewTextPanel.buildPreviewAutocompleteRequest(text, caretPosition, null);

        assertNotNull(request);
        assertTrue(request.prefix().length() <= 4000);
        assertTrue(request.suffix().length() <= 4000);
        assertTrue(request.prefix().endsWith(text.substring(caretPosition - 25, caretPosition)));
        assertTrue(request.suffix().startsWith(text.substring(caretPosition, caretPosition + 25)));
    }
}
