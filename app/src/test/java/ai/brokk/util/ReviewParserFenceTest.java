package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewParserFenceTest {

    @Test
    void testFindClosingFenceTerminationWithPathologicalInput() {
        // Construct a large input where every "closing" fence is immediately followed by something
        // that triggers a 'continue' in findClosingFence.
        StringBuilder sb = new StringBuilder();
        sb.append("```java\n");
        sb.append("File.java @10\n");
        for (int i = 0; i < 2000; i++) {
            sb.append("```\n");
            sb.append("AnotherFile.java @20\n"); // Trigger continue via fileLinePattern
        }

        // This should terminate quickly due to maxLookahead
        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(sb.toString());

        // If it terminated correctly, the first segment will be text because the
        // first code block never found a valid closing fence within the lookahead.
        assertFalse(segments.isEmpty());
        assertTrue(segments.get(0) instanceof ReviewParser.TextSegment);
    }

    @Test
    void testFindClosingFenceWithBareFilenameContinue() {
        String input =
                """
                ```java
                Source.java @1
                content
                ```
                BareFile.java
                more content
                ```
                """;
        // The first ``` is followed by a line that looks like a bare filename.
        // findClosingFence should 'continue' and find the second one.
        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(input);

        // findClosingFence skips the first ``` because BareFile.java is on the next line.
        // It should eventually return -1 or close at the final fence.
        // In this specific input, it should close at the last fence.
        assertTrue(segments.stream().anyMatch(s -> s instanceof ReviewParser.ExcerptSegment));
        ReviewParser.ExcerptSegment excerpt = (ReviewParser.ExcerptSegment) segments.stream()
                .filter(s -> s instanceof ReviewParser.ExcerptSegment)
                .findFirst()
                .orElseThrow();

        assertTrue(excerpt.content().contains("BareFile.java"));
    }
}
