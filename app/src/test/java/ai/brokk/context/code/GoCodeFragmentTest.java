package ai.brokk.context.code;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.ContextFragments.CodeFragment;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GoCodeFragmentTest {

    @TempDir
    Path tempDir;

    @Test
    void testFunctionFiltersUnusedGoImports() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                """
                package main
                
                import (
                    "fmt"
                    "os"
                )
                
                func Hello() {
                    fmt.Println("Hello, World!")
                }
                """, "main.go").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);
            
            var function = analyzer.getDefinitions("main.Hello").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, function);
            String text = fragment.text().join();

            assertTrue(text.contains("import \"fmt\""), "Should include import for used package 'fmt'");
            assertFalse(text.contains("import \"os\""), "Unused import 'os' should be filtered out");
            assertTrue(text.contains("func Hello()"), "Should contain the function declaration");
            assertTrue(text.contains("fmt.Println"), "Should contain the call using fmt");
        }
    }

    @Test
    void testFunctionIncludesMultipleRelevantGoImports() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                """
                package util
                
                import (
                    "bytes"
                    "io"
                    "strings"
                )
                
                func Process(r io.Reader) (*bytes.Buffer, error) {
                    data, err := io.ReadAll(r)
                    if err != nil {
                        return nil, err
                    }
                    return bytes.NewBuffer(data), nil
                }
                """, "util.go").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);
            
            var function = analyzer.getDefinitions("util.Process").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, function);
            String text = fragment.text().join();

            assertTrue(text.contains("\"bytes\""), "Should include import for used package 'bytes'");
            assertTrue(text.contains("\"io\""), "Should include import for used package 'io'");
            assertFalse(text.contains("\"strings\""), "Unused import 'strings' should be filtered out");
            assertTrue(text.contains("bytes.NewBuffer"), "Should contain usage of bytes package");
            assertTrue(text.contains("io.ReadAll"), "Should contain usage of io package");
        }
    }
}
