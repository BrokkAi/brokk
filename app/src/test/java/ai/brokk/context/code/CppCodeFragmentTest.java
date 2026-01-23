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

public class CppCodeFragmentTest {

    @TempDir
    Path tempDir;

    @Test
    void testFunctionFiltersUnusedCppIncludes() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                #ifndef HELPER_H
                #define HELPER_H
                void helperFunction() {}
                #endif
                """, "helper.h")
                .addFileContents(
                """
                #ifndef UNUSED_H
                #define UNUSED_H
                void unusedFunction() {}
                #endif
                """, "unused.h");

        try (var project = builder.addFileContents(
                """
                #include "helper.h"
                #include "unused.h"

                void mainFunction() {
                    helperFunction();
                }
                """, "main.cpp").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);
            
            var function = analyzer.getDefinitions("mainFunction").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, function);
            String text = fragment.text().join();

            assertTrue(text.contains("#include \"helper.h\""), "Should include #include for used header 'helper.h'");
            assertFalse(text.contains("#include \"unused.h\""), "Unused #include 'unused.h' should be filtered out");
            assertTrue(text.contains("void mainFunction()"), "Should contain the function declaration");
            assertTrue(text.contains("helperFunction()"), "Should contain the call to the helper function");
        }
    }

    @Test
    void testClassIncludesMultipleRelevantCppIncludes() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
                class TypeA {};
                """, "type_a.h")
                .addFileContents(
                """
                class TypeB {};
                """, "type_b.h")
                .addFileContents(
                """
                class TypeC {};
                """, "type_c.h");

        try (var project = builder.addFileContents(
                """
                #include "type_a.h"
                #include "type_b.h"
                #include "type_c.h"

                class MyClass {
                public:
                    TypeA a;
                    void process(TypeB b);
                };
                """, "my_class.h").build()) {

            var analyzer = createTreeSitterAnalyzer(project);
            var contextManager = new TestContextManager(tempDir, new TestConsoleIO(), analyzer);
            
            var cls = analyzer.getDefinitions("MyClass").stream().findFirst().orElseThrow();
            var fragment = new CodeFragment(contextManager, cls);
            String text = fragment.text().join();

            assertTrue(text.contains("#include \"type_a.h\""), "Should include #include for used type A");
            assertTrue(text.contains("#include \"type_b.h\""), "Should include #include for used type B");
            assertFalse(text.contains("#include \"type_c.h\""), "Unused #include for type C should be filtered out");
            assertTrue(text.contains("TypeA a;"), "Should contain field using type A");
            assertTrue(text.contains("process(TypeB b)"), "Should contain method using type B");
        }
    }
}
