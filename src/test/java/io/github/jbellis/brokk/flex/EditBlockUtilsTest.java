package io.github.jbellis.brokk.flex;

import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.jbellis.brokk.gui.mop.stream.flex.BrokkMarkdownExtension;
import com.vladsch.flexmark.parser.Parser;
import org.junit.jupiter.api.Test;
import java.util.List;

import java.util.Set;

import static io.github.jbellis.brokk.gui.mop.stream.flex.EditBlockUtils.*;
import static org.junit.jupiter.api.Assertions.*;

public class EditBlockUtilsTest {

    @Test
    void testStripQuotedWrapping() {
        // Test with filename removal
        assertEquals("content\n", 
                     stripQuotedWrapping("foo.py\ncontent", "foo.py"));
        
        // Test with fence removal
        assertEquals("content\n", 
                     stripQuotedWrapping("```\ncontent\n```", null));
        
        // Test with empty input
        assertEquals("", stripQuotedWrapping("", null));
    }
    
    @Test
    void testStripFilename() {
        // Test basic filename extraction
        assertEquals("foo.py", stripFilename("foo.py"));
        
        // Test with decorations
        assertEquals("foo.py", stripFilename("# foo.py"));
        assertEquals("foo.py", stripFilename("foo.py:"));
        assertEquals("foo.py", stripFilename("`foo.py`"));
        assertEquals("foo.py", stripFilename("*foo.py*"));
        
        // Test fence line
        assertNull(stripFilename("```"));
        
        // Test ellipsis
        assertNull(stripFilename("..."));
        
        // Test blank input
        assertNull(stripFilename("  "));
    }
    
    @Test
    void testFindFileNameNearby() {
        String[] lines = {
            "Some text",
            "```",
            "foo.py",
            "<<<<<<< SEARCH",
            "content"
        };
        
        // Test with typical context (fence + filename before marker)
        assertEquals("foo.py", findFileNameNearby(lines, 3, Set.of(), null));
        
        // Test with fallback when no filename found
        assertEquals("default.py", findFileNameNearby(
            new String[] {"<<<<<<< SEARCH"}, 0, Set.of(), "default.py"));
    }
    
    @Test
    void testPatternMatching() {
        // Test HEAD pattern
        assertTrue(HEAD.matcher("<<<<<<< SEARCH").matches());
        assertTrue(HEAD.matcher("<<<<<<< SEARCH foo.py").matches());
        assertTrue(HEAD.matcher(" <<<<<<< SEARCH").matches());
        assertFalse(HEAD.matcher("<<<<< WRONG").matches());
        
        // Test DIVIDER pattern
        assertTrue(DIVIDER.matcher("=======").matches());
        assertTrue(DIVIDER.matcher(" =======").matches());
        assertFalse(DIVIDER.matcher("== not enough =").matches());
        
        // Test UPDATED pattern
        assertTrue(UPDATED.matcher(">>>>>>> REPLACE").matches());
        assertTrue(UPDATED.matcher(">>>>>>> REPLACE foo.py").matches());
        assertTrue(UPDATED.matcher(" >>>>>>> REPLACE").matches());
        assertFalse(UPDATED.matcher(">>>> WRONG").matches());
        
        // Test OPENING_FENCE pattern
        assertTrue(OPENING_FENCE.matcher("```").matches());
        assertTrue(OPENING_FENCE.matcher(" ```").matches());
        assertTrue(OPENING_FENCE.matcher("```java").matches(), "Should match fence with language");
        assertTrue(OPENING_FENCE.matcher("```file.txt").matches(), "Should match fence with filename");
        assertTrue(OPENING_FENCE.matcher("``` java").matches(), "Should match fence with space before language");
        assertFalse(OPENING_FENCE.matcher("``").matches());
        
        // Test extracting token from OPENING_FENCE
        var matcher = OPENING_FENCE.matcher("```java");
        assertTrue(matcher.matches());
        assertEquals("java", matcher.group(1), "Should extract language token");
        
        matcher = OPENING_FENCE.matcher("```file.txt");
        assertTrue(matcher.matches());
        assertEquals("file.txt", matcher.group(1), "Should extract filename token");
        
        matcher = OPENING_FENCE.matcher("```");
        assertTrue(matcher.matches());
        assertNull(matcher.group(1), "Should have null token for plain fence");
    }
    
    @Test
    void testLooksLikePath() {
        // Test path detection
        assertTrue(looksLikePath("file.txt"), "File with extension should look like path");
        assertTrue(looksLikePath("src/main/file.txt"), "Path with slashes should look like path");
        assertTrue(looksLikePath("C:/Windows/file.txt"), "Windows path with forward slashes should look like path");
        assertTrue(looksLikePath("C:\\Windows\\file.txt"), "Windows path with backslashes should look like path");
        
        // Test non-path strings
        assertFalse(looksLikePath("java"), "Language should not look like path");
        assertFalse(looksLikePath("cpp"), "Language should not look like path");
        assertFalse(looksLikePath(""), "Empty string should not look like path");
    }
    
    @Test
    void testParserRecognition() {
        // Test with Parser directly to ensure recognition of all syntax variants
        MutableDataSet options = new MutableDataSet()
                .set(Parser.EXTENSIONS, List.of(BrokkMarkdownExtension.create()));
        
        Parser parser = Parser.builder(options).build();
        
        // 1. Test normal syntax
        var doc1 = parser.parse("""
                file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                """);
        assertHasEditBlock(doc1, "Normal syntax not recognized");
        
        // 2. Test conflict syntax 
        var doc2 = parser.parse("""
                <<<<<<< SEARCH file1.txt
                one
                ======= file1.txt
                two
                >>>>>>> REPLACE file1.txt
                """);
        assertHasEditBlock(doc2, "Conflict syntax not recognized");
        
        // 3. Test fenced normal syntax
        var doc3 = parser.parse("""
                ```
                file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```
                """);
        assertHasEditBlock(doc3, "Fenced normal syntax not recognized");
        
        // 4. Test fenced with language
        var doc4 = parser.parse("""
                ```java
                file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```
                """);
        assertHasEditBlock(doc4, "Fenced with language syntax not recognized");
        
        // 5. Test fenced with filename in fence line
        var doc5 = parser.parse("""
                ```file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```
                """);
        assertHasEditBlock(doc5, "Fenced with filename syntax not recognized");
    }
    
    private void assertHasEditBlock(com.vladsch.flexmark.util.ast.Document doc, String message) {
        boolean hasEditBlock = doc.getChildOfType(io.github.jbellis.brokk.gui.mop.stream.flex.EditBlockNode.class) != null;
        assertTrue(hasEditBlock, message);
    }
}
