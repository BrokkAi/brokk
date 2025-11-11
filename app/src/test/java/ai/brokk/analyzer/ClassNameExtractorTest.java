package ai.brokk.analyzer;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClassNameExtractor.extractForJava to confirm it produces the simple class name segment.
 */
public class ClassNameExtractorTest {

    @Test
    public void extractForJavaReturnsSimpleClassName() {
        Optional<String> res = ClassNameExtractor.extractForJava("com.example.Chrome.doStuff");
        assertTrue(res.isPresent());
        assertEquals("Chrome", res.get());
    }

    @Test
    public void extractForJavaReturnsEmptyForNonMethodLike() {
        assertTrue(ClassNameExtractor.extractForJava("justAString").isEmpty());
    }

    @Test
    public void extractForJavaHandlesParens() {
        Optional<String> res = ClassNameExtractor.extractForJava("com.example.Chrome.runOnEdt(task)");
        assertTrue(res.isPresent());
        assertEquals("Chrome", res.get());
    }
}
