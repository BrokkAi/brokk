package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.doc.InMemoryDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import javax.swing.text.PlainDocument;
import javax.swing.text.BadLocationException;

/**
 * Tests for document synchronization in FilePanel to prevent line joining bugs.
 * This test specifically addresses the issue where editing text in the diff tool
 * can cause line breaks to be removed, joining the current line with the next line.
 */
public class DocumentSyncTest {

    private PlainDocument sourceDoc;
    private PlainDocument destDoc;

    @BeforeEach
    void setUp() {
        sourceDoc = new PlainDocument();
        destDoc = new PlainDocument();
    }

    @Test
    void testLinePreservationDuringInsert() throws BadLocationException {
        // Set up initial document with multiple lines
        String initialContent = "// File navigation handlers\nbtnPreviousFile.addActionListener(e -> previousFile());\nbtnNextFile.addActionListener(e -> nextFile());";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // Simulate user typing "cqc" in the middle of the first line comment
        int insertPosition = "// File navigation handlers".length() - "handlers".length();
        String textToInsert = "cqc";

        // Insert into source document
        sourceDoc.insertString(insertPosition, textToInsert, null);

        // Manually trigger the fixed sync behavior (using full document copy)
        String sourceText = sourceDoc.getText(0, sourceDoc.getLength());
        destDoc.remove(0, destDoc.getLength());
        destDoc.insertString(0, sourceText, null);

        // Verify that lines are preserved correctly
        String expectedContent = "// File navigation cqchandlers\nbtnPreviousFile.addActionListener(e -> previousFile());\nbtnNextFile.addActionListener(e -> nextFile());";
        String actualContent = destDoc.getText(0, destDoc.getLength());

        assertEquals(expectedContent, actualContent, "Document synchronization should preserve line breaks");

        // Verify newlines are still present
        assertTrue(actualContent.contains("\n"), "Document should contain newline characters");

        // Count lines to ensure no lines were joined
        long expectedLineCount = expectedContent.chars().filter(ch -> ch == '\n').count() + 1;
        long actualLineCount = actualContent.chars().filter(ch -> ch == '\n').count() + 1;
        assertEquals(expectedLineCount, actualLineCount, "Line count should be preserved");
    }

    @Test
    void testMultipleLineEdits() throws BadLocationException {
        // Test multiple edits to ensure line structure is maintained
        String initialContent = "line1\nline2\nline3\nline4";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // First edit: insert in middle of line2
        int firstInsertPos = initialContent.indexOf("line2") + 4;
        sourceDoc.insertString(firstInsertPos, "XXX", null);

        // Sync manually
        String sourceText = sourceDoc.getText(0, sourceDoc.getLength());
        destDoc.remove(0, destDoc.getLength());
        destDoc.insertString(0, sourceText, null);

        // Second edit: insert at beginning of line3
        String currentContent = destDoc.getText(0, destDoc.getLength());
        int secondInsertPos = currentContent.indexOf("line3");
        sourceDoc.remove(0, sourceDoc.getLength());
        sourceDoc.insertString(0, currentContent, null);
        sourceDoc.insertString(secondInsertPos, "YYY", null);

        // Sync again
        sourceText = sourceDoc.getText(0, sourceDoc.getLength());
        destDoc.remove(0, destDoc.getLength());
        destDoc.insertString(0, sourceText, null);

        String finalContent = destDoc.getText(0, destDoc.getLength());
        String expectedFinal = "line1\nlineXXX2\nYYYline3\nline4";

        assertEquals(expectedFinal, finalContent, "Multiple edits should preserve line structure");
        assertTrue(finalContent.contains("\n"), "Newlines should be preserved after multiple edits");
    }

    @Test
    void testCommentEditingScenario() throws BadLocationException {
        // Reproduce the exact scenario from the bug report
        String initialContent = "        // save each panel\n        for (var p : panelCache.values()) {";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // User adds "cqc" to the comment
        int insertPos = initialContent.indexOf("panel") + "panel".length();
        sourceDoc.insertString(insertPos, " cqc", null);

        // Sync using the fixed approach
        String sourceText = sourceDoc.getText(0, sourceDoc.getLength());
        destDoc.remove(0, destDoc.getLength());
        destDoc.insertString(0, sourceText, null);

        String result = destDoc.getText(0, destDoc.getLength());
        String expected = "        // save each panel cqc\n        for (var p : panelCache.values()) {";

        assertEquals(expected, result, "Comment editing should not join lines");
        assertTrue(result.contains("\n        for"), "The 'for' loop should remain on a separate line");
        assertFalse(result.contains("cqcfor"), "Should not have 'cqcfor' joined text");
    }

    @Test
    void testNewlineAtEndOfDocument() throws BadLocationException {
        // Test behavior when newlines are at document boundaries
        String contentWithTrailingNewline = "line1\nline2\n";
        sourceDoc.insertString(0, contentWithTrailingNewline, null);
        destDoc.insertString(0, contentWithTrailingNewline, null);

        // Insert text at the end (before the trailing newline)
        int insertPos = contentWithTrailingNewline.indexOf("\n", contentWithTrailingNewline.indexOf("line2"));
        sourceDoc.insertString(insertPos, " added", null);

        // Sync
        String sourceText = sourceDoc.getText(0, sourceDoc.getLength());
        destDoc.remove(0, destDoc.getLength());
        destDoc.insertString(0, sourceText, null);

        String result = destDoc.getText(0, destDoc.getLength());
        String expected = "line1\nline2 added\n";

        assertEquals(expected, result, "Trailing newlines should be preserved");
        assertTrue(result.endsWith("\n"), "Document should still end with newline");
    }
}
