package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class IAnalyzerTest {

    @Test
    public void testGetIdentifierAtReturnsLongestRange() throws Exception {
        try (var testProject = InlineTestProjectCreator.code(
                """
                public class X {
                    public void m() {
                        A a = new A();
                        a.b().c();
                    }
                }
                """,
                "X.java").build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);
            var maybeFile = AnalyzerUtil.getFileFor(analyzer, "X");
            assertTrue(maybeFile.isPresent(), "Should locate X.java in project");
            var file = maybeFile.get();

            var srcOpt = file.read();
            assertTrue(srcOpt.isPresent(), "Should be able to read X.java");
            String src = srcOpt.get();

            int idx = src.indexOf("a.b().c");
            assertTrue(idx >= 0, "Test source must contain 'a.b().c'");

            // Choose byte offset located at the 'c' character (UTF-8 bytes)
            int charPos = idx + "a.b().".length(); // position of 'c' within the string
            int byteOffset = src.substring(0, charPos).getBytes(StandardCharsets.UTF_8).length;

            var maybeIdent = analyzer.getIdentifierAt(file, byteOffset);
            assertTrue(maybeIdent.isPresent(), "Expected an identifier at the offset");
            assertEquals("a.b().c", maybeIdent.get(), "Should return the longest identifier covering the offset");
        }
    }
}
