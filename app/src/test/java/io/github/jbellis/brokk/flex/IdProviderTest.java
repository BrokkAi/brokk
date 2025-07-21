package io.github.jbellis.brokk.flex;

import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.jbellis.brokk.gui.mop.stream.flex.IdProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IdProviderTest {

    @Test
    public void testIdStability() {
        // Set up parser with our IdProvider
        IdProvider idProvider = new IdProvider();
        MutableDataSet options = new MutableDataSet()
            .set(Parser.EXTENSIONS, java.util.Collections.emptyList())
            .set(IdProvider.ID_PROVIDER, idProvider);
        Parser parser = Parser.builder(options).build();
        
        // Parse a simple markdown document with a code block
        String markdown = "# Test\n\n```java\nSystem.out.println(\"Hello\");\n```\n\nMore text.";
        Node doc1 = parser.parse(markdown);
        Node doc2 = parser.parse(markdown);
        
        // Get the first child from each parse
        Node firstChild1 = doc1.getFirstChild();
        Node firstChild2 = doc2.getFirstChild();
        
        // Their IDs should be the same since they're at the same position
        int id1 = idProvider.getId(firstChild1);
        int id2 = idProvider.getId(firstChild2);
        
        assertEquals(id1, id2, "IDs should be stable for the same content at the same position");
    }
    
    @Test
    public void testOffsetBasedIds() {
        // Set up parser with our IdProvider
        IdProvider idProvider = new IdProvider();
        MutableDataSet options = new MutableDataSet()
            .set(Parser.EXTENSIONS, java.util.Collections.emptyList())
            .set(IdProvider.ID_PROVIDER, idProvider);
        Parser parser = Parser.builder(options).build();
        
        // Parse two slightly different documents where the second block is at different positions
        String markdown1 = "# Test\n\n```java\ncode\n```";
        String markdown2 = "# Test\n\nExtra line\n\n```java\ncode\n```";
        
        Node doc1 = parser.parse(markdown1);
        Node doc2 = parser.parse(markdown2);
        
        // Get the second child from each (should be the code block)
        Node secondChild1 = doc1.getFirstChild().getNext();
        Node secondChild2 = doc2.getFirstChild().getNext().getNext();
        
        // Their IDs should be different since they're at different positions
        int id1 = idProvider.getId(secondChild1);
        int id2 = idProvider.getId(secondChild2);
        
        // Not testing for a specific value, just that they're different
        // This isn't a true requirement of the system, but it verifies our ID generation logic
        assertEquals(secondChild1.getStartOffset() != secondChild2.getStartOffset(), 
                   id1 != id2, 
                   "Different positions should yield different IDs");
    }
}
