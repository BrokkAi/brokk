package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void normalizeCompletion_ensuresSingleLineAndNonBlank() {
        assertEquals("foo", PreviewTextPanel.normalizeCompletion("foo\nbar"));
        assertEquals("foo", PreviewTextPanel.normalizeCompletion("foo\r\nbar"));
        assertEquals("foo", PreviewTextPanel.normalizeCompletion("foo\rbar"));
        assertNull(PreviewTextPanel.normalizeCompletion("\nbar"));
        assertNull(PreviewTextPanel.normalizeCompletion("   "));
        assertEquals("  foo", PreviewTextPanel.normalizeCompletion("  foo\n"));
    }

    @Test
    void normalizeCompletion_truncatesAtFirstNewline() {
        assertEquals("first line", PreviewTextPanel.normalizeCompletion("first line\nsecond line"));
        assertEquals("only line", PreviewTextPanel.normalizeCompletion("only line"));
        assertNull(PreviewTextPanel.normalizeCompletion("\n"));
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

    @Test
    void buildPreviewAutocompleteRequest_worksWithDocument() throws Exception {
        var doc = new javax.swing.text.PlainDocument();
        String content = "public class Foo {\n    public void bar() {\n        \n    }\n}";
        doc.insertString(0, content, null);
        int caretPosition = content.indexOf("\n        \n") + 9;

        var request = PreviewTextPanel.buildPreviewAutocompleteRequest(doc, caretPosition, null);

        assertNotNull(request);
        assertTrue(request.prefix().endsWith("public void bar() {\n        "));
        assertTrue(request.suffix().startsWith("\n    }\n}"));
    }

    @Test
    void buildPreviewAutocompleteRequest_suppressesLoadingContentInDocument() throws Exception {
        var doc = new javax.swing.text.PlainDocument();
        doc.insertString(0, "Loading...", null);
        assertNull(PreviewTextPanel.buildPreviewAutocompleteRequest(doc, 5, null));
    }
}
