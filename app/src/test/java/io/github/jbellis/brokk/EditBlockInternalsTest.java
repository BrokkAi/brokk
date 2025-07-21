package io.github.jbellis.brokk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the individual static methods of {@link EditBlock} to pinpoint
 * where behavior diverges from expectations in multi-line replacements
 * with leading blank lines and indentation.
 */
class EditBlockInternalsTest {
    @Test
    void testCountLeadingBlankLines() {
        // 1. No blank lines at start
        String[] lines1 = {
                "line1",
                "line2",
                ""
        };
        assertEquals(0, EditBlock.countLeadingBlankLines(lines1));

        // 2. Some blank lines at start
        String[] lines2 = {
                "",
                "  ",
                "lineA",
                "lineB"
        };
        // Two blank lines at start
        assertEquals(2, EditBlock.countLeadingBlankLines(lines2));
    }

    @Test
    void testCountLeadingSpaces() {
        // Simple checks
        assertEquals("", EditBlock.getLeadingWhitespace("line1\n"));
        assertEquals("    ", EditBlock.getLeadingWhitespace("    line2\n"));
        assertEquals("  ", EditBlock.getLeadingWhitespace("  lineX \n"));
    }

    @Test
    void testMatchesIgnoringWhitespace() {
        String[] whole = {
                "    line1\n",
                "        line2\n"
        };
        String[] part = {
                "line1\n",
                "    line2\n"
        };
        // We expect a match ignoring leading spaces
        assertTrue(EditBlock.matchesIgnoringWhitespace(whole, 0, part));

        // If we shift by 1, out of range => false
        assertFalse(EditBlock.matchesIgnoringWhitespace(whole, 1, part));
    }

    @Test
    void testPerfectReplace() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String[] whole = { "A\n", "B\n", "C\n" };
        String[] part  = { "B\n" };
        String[] repl  = { "B-REPLACED\n" };

        String result = EditBlock.perfectReplace(whole, part, repl);
        assertNotNull(result);

        // Expect "A\n" + "B-REPLACED\n" + "C\n"
        assertEquals("A\nB-REPLACED\nC\n", result);
    }

    @Test
    void testReplaceIgnoringWhitespace_includingBlankLine() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        // This is closer to the scenario that breaks in your test:
        // There's an extra blank line in 'search' that doesn't appear in the original.
        String[] whole = {
                "line1\n",
                "    line2\n",
                "    line3\n"
        };
        String[] part = {
                "\n",        // blank line
                "  line2\n"  // partial indentation
        };
        String[] replace = {
                "\n",          // blank line
                "  replaced_line2\n"
        };

        String attempt = EditBlock.replaceIgnoringWhitespace(whole, part, replace);

        // We'll see if it aligns or if we get an extra blank line in between
        // (which might be the bug).
        assertNotNull(attempt);

        // If we want "line2" to become "replaced_line2" with the same indentation as line2 originally had:
        // We expect:
        // line1
        //     replaced_line2
        //     line3
        String expected = """
                line1
                    replaced_line2
                    line3
                """;
        assertEquals(expected, attempt);
    }

    @Test
    void testReplaceMostSimilarChunk() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String whole = """
                line1
                    line2
                    line3
                """;
        String part = "\n  line2\n";
        String replace = "\n  replaced_line2\n";

        String result = EditBlock.replaceMostSimilarChunk(whole, part, replace);

        // We'll see if an extra blank line got inserted.
        // We'll check the final lines carefully.
        String expected = """
                line1
                    replaced_line2
                    line3
                """;
        assertEquals(expected, result);
    }

    @Test
    void testDoReplaceWithBlankLineAndIndent() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        // "doReplace" is a higher-level method that calls stripQuotedWrapping + replaceMostSimilarChunk, etc.
        String original = """
                line1
                    line2
                    line3
                """;
        String before = "\n  line2\n";
        String after = "\n  replaced_line2\n";

        String result = EditBlock.replaceMostSimilarChunk(original, before, after);

        String expected = """
                line1
                    replaced_line2
                    line3
                """;
        assertEquals(expected, result);
    }

    @Test
    void testReplaceSimpleExact() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String original = "This is a sample text.\nAnother line\nYet another line.\n";
        String search = "Another line\n";
        String replace = "Changed line\n";

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        String expected = "This is a sample text.\nChanged line\nYet another line.\n";
        assertEquals(expected, updated);
    }

    @Test
    void testReplaceIgnoringWhitespace() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String original = """
                line1
                    line2
                    line3
                """.stripIndent();
        String search = """
                line2
                    line3
                """;
        String replace = """
                new_line2
                    new_line3
                """;
        String expected = """
                line1
                    new_line2
                    new_line3
                """.stripIndent();

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        assertEquals(expected, updated);
    }

    @Test
    void testDeletionIgnoringWhitespace() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String original = """
                One
                  Two
                """.stripIndent();

        String updated = EditBlock.replaceMostSimilarChunk(original, "Two\n", "");
        assertEquals("One\n", updated);
    }

    @Test
    void testAmbiguousMatch() {
        String original = """
                line1
                line2
                line1
                line3
                """;
        String search = "line1\n";
        String replace = "new_line\n";

        assertThrows(EditBlock.AmbiguousMatchException.class, () -> EditBlock.replaceMostSimilarChunk(original, search, replace));
    }

    @Test
    void testNoMatch() {
        String original = """
                line1
                line2
                line1
                line3
                """;
        String search = "line4\n";
        String replace = "new_line\n";

        assertThrows(EditBlock.NoMatchException.class, () -> EditBlock.replaceMostSimilarChunk(original, search, replace));
    }

    @Test
    void testEmptySearchReplacesContent() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        // If beforeText is empty, replace the entire content
        String original = "one\ntwo\n";
        String search = "";
        String replace = "new content\n";
        // Expected behavior: original content is replaced entirely
        String expected = "new content\n";

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        assertEquals(expected, updated);
    }

    /**
     * LLM likes to start blocks without the leading whitespace sometimes
     */
    @Test
    void testReplacePartWithMissingLeadingWhitespace() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String original = """
                line1
                    line2
                    line3
                line4
                """.stripIndent();

        // We'll omit some leading whitespace in the beforeText block
        String search = """
                line2
                    line3
                """.stripIndent();
        String replace = """
                NEW_line2
                    NEW_line3
                """.stripIndent();

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);

        String expected = """
                line1
                    NEW_line2
                    NEW_line3
                line4
                """.stripIndent();

        assertEquals(expected, updated);
    }

    /**
     * Test blank line with missing leading whitespace in beforeText.
     * (Similar to python test_replace_part_with_missing_leading_whitespace_including_blank_line)
     */
    @Test
    void testReplaceIgnoringWhitespaceIncludingBlankLine() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String original = """
                line1
                    line2
                    line3
                """.stripIndent();
        // Insert a blank line in the beforeText, plus incomplete indentation
        String search = """
                
                  line2
                """;
        String replace = """
                
                  replaced_line2
                """;

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);

        // The beforeText block basically tries to match line2 ignoring some whitespace and skipping a blank line
        // We expect line2 -> replaced_line2, with same leading indentation as original (4 spaces).
        String expected = """
                line1
                    replaced_line2
                    line3
                """.stripIndent();

        assertEquals(expected, updated);
    }

    @Test
    void testReplaceIgnoringTrailingWhitespace() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String original = """
                line1
                    line2  
                    line3
                """.stripIndent();
        // Insert a blank line in the beforeText, plus incomplete indentation
        String search = """
                  line2
                """;
        String replace = """
                  replaced_line2
                """;

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        String expected = """
                line1
                    replaced_line2
                    line3
                """.stripIndent();
        assertEquals(expected, updated);
    }

    @Test
    void testReplaceIgnoringInternalWhitespace() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String original = """
                line1
                    a   b 
                    line3
                """.stripIndent();
        // Insert a blank line in the beforeText, plus incomplete indentation
        String search = """
                  a b
                """;
        String replace = """
                  replaced_line2
                """;

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        String expected = """
                line1
                    replaced_line2
                    line3
                """.stripIndent();
        assertEquals(expected, updated);
    }

    /**
     * Tests that if beforeText block lines are already in the filename, but user tries the same "afterText",
     * we do not break anything. We can't confirm the "already replaced" scenario fully
     * but we can ensure no weird edge crash.
     */
    @Test
    void testApplyFuzzySearchReplaceIfReplaceAlreadyPresent() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String original = """
                line1
                line2
                line3
                """;
        // Suppose the "beforeText" is line2 => line2
        // but "afterText" is line2 => line2 (the same text).
        // The code should see a perfect match and effectively do no change but not crash.
        String search = "line2\n";
        String replace = "line2\n";

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        // We expect no change
        assertEquals(original, updated);
    }

    @Test
    void testEmptySearchOnEmptyFile() throws EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        String original = "";
        String search = "";  // empty
        String replace = "initial content\n";

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        assertEquals("initial content\n", updated);
    }

    @Test
    void testAmbiguousMatchRejected() {
        String original = """
                line1
                line2
                line1
                line3
                """;

        assertThrows(EditBlock.AmbiguousMatchException.class, () -> EditBlock.replaceMostSimilarChunk(original, "line1\n", "replaced\n"));
      }
  
      @Test
      void testExtractCodeFromTripleBackticks() {
          // Case 1: Language specifier present, code starts on next line
          String input1 = """
                  ```java
                  System.out.println("Hello");
                  ```
                  """;
          // Expect content starting *after* the first newline
          assertEquals("System.out.println(\"Hello\");\n", EditBlock.extractCodeFromTripleBackticks(input1));
  
          // Case 2: No language specifier, code starts on next line
          String input2 = """
                  ```
                  Just code here.
                  ```
                  """;
          assertEquals("Just code here.\n", EditBlock.extractCodeFromTripleBackticks(input2));
  
          // Case 3: Empty content block (only newline between backticks)
          String input3 = """
                  ```
  
                  ```
                  """;
           // The newline between the opening and closing backticks is captured
          assertEquals("\n", EditBlock.extractCodeFromTripleBackticks(input3));
  
          // Case 4: Content on the same line as opening backticks (should not match pattern)
          String input4 = "```System.out.println(\"Inline\");```";
          // No match because content isn't on the line *after* the opening ```
          assertEquals("", EditBlock.extractCodeFromTripleBackticks(input4));
  
          // Case 5: No backticks
          assertEquals("", EditBlock.extractCodeFromTripleBackticks("Just plain text."));
  
          // Case 6: Only opening backticks with language specifier and newline
          assertEquals("", EditBlock.extractCodeFromTripleBackticks("```java\nSome code"));
  
          // Case 7: Only closing backticks
          assertEquals("", EditBlock.extractCodeFromTripleBackticks("Some code\n```"));
  
          // Case 8: Empty string input
          assertEquals("", EditBlock.extractCodeFromTripleBackticks(""));
  
           // Case 9: Backticks with only language specifier and newline, nothing else
          String input9 = "```python\n```";
           // Empty content captured after the newline
          assertEquals("", EditBlock.extractCodeFromTripleBackticks(input9));
  
          // Case 10: Backticks with language specifier, but no newline after it (invalid)
          String input10 = "```python```";
          assertEquals("", EditBlock.extractCodeFromTripleBackticks(input10));
      }

    @Test
    void testIsDeletion() {
        // Case 1: Original non-blank, updated blank (is a deletion)
        assertTrue(EditBlock.isDeletion("some content", ""));
        assertTrue(EditBlock.isDeletion("some content", "   \n   ")); // whitespace only is blank

        // Case 2: Original blank, updated blank (not a deletion of actual content)
        assertFalse(EditBlock.isDeletion("", ""));
        assertFalse(EditBlock.isDeletion("  \n  ", "")); // original was already blank
        assertFalse(EditBlock.isDeletion("", "   \n ")); // original was blank, updated is blank

        // Case 3: Original blank, updated non-blank (creation, not deletion)
        assertFalse(EditBlock.isDeletion("", "new content"));
        assertFalse(EditBlock.isDeletion("  \n  ", "new content")); // original was blank

        // Case 4: Original non-blank, updated non-blank (modification, not deletion)
        assertFalse(EditBlock.isDeletion("some content", "other content"));
        assertFalse(EditBlock.isDeletion("line1\nline2", "line1\nline3"));

        // Case 5: Original non-blank, updated is effectively blank (only newlines/spaces)
        assertTrue(EditBlock.isDeletion("content", "\n"));
        assertTrue(EditBlock.isDeletion("content", " \t \n "));

        // Case 6: Original has only whitespace, updated is blank (not a deletion of non-blank content)
        assertFalse(EditBlock.isDeletion("\n   \n", ""));
        assertFalse(EditBlock.isDeletion("   ", " ")); // original was already blank
    }
}
