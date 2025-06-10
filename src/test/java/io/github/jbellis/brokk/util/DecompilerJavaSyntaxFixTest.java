package io.github.jbellis.brokk.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Test harness for decompilation syntax fixes.
 * Tests the automatic correction of malformed Java syntax that can be generated
 * by decompilers like Fernflower, which would otherwise cause parse failures
 * in tools like Joern's JavaSrc2Cpg.
 */
public class DecompilerJavaSyntaxFixTest {

    @TempDir
    Path tempDir;

    private Path testJavaFile;

    @BeforeEach
    void setUp() throws IOException {
        testJavaFile = tempDir.resolve("TestClass.java");
    }

    /**
     * Test case for the FlowConfig.java issue - malformed import with trailing dot and semicolon.
     * This was the root cause of FlowConfig.java being excluded from CPG analysis.
     */
    @Test
    void testMalformedImportWithTrailingDot() throws Exception {
        // Create test case with the exact malformed import from FlowConfig.java
        String malformedJava = """
            package io.joern.joerncli;

            import java.io.Serializable;
            import scala.Option;
            import scala.Product;
            import scala.runtime.BoxesRunTime;
            import scala.runtime.Statics;
            import scala.runtime.ScalaRunTime.;

            public class TestClass implements Product, Serializable {
                private final String field;
            }
            """;

        String expectedFixed = """
            package io.joern.joerncli;

            import java.io.Serializable;
            import scala.Option;
            import scala.Product;
            import scala.runtime.BoxesRunTime;
            import scala.runtime.Statics;
            import scala.runtime.ScalaRunTime;

            public class TestClass implements Product, Serializable {
                private final String field;
            }
            """;

        assertSyntaxFix(malformedJava, expectedFixed, "Malformed import with trailing dot should be fixed");
    }

    /**
     * Test multiple malformed imports in a single file.
     */
    @Test
    void testMultipleMalformedImports() throws Exception {
        String malformedJava = """
            package test.package;

            import java.util.List;
            import scala.runtime.ScalaRunTime.;
            import some.other.Package.;
            import valid.Import;
            import another.Bad.Import.;

            public class TestClass {
                // class content
            }
            """;

        String expectedFixed = """
            package test.package;

            import java.util.List;
            import scala.runtime.ScalaRunTime;
            import some.other.Package;
            import valid.Import;
            import another.Bad.Import;

            public class TestClass {
                // class content
            }
            """;

        assertSyntaxFix(malformedJava, expectedFixed, "Multiple malformed imports should all be fixed");
    }

    /**
     * Test that valid imports are not affected by the fix.
     */
    @Test
    void testValidImportsUnchanged() throws Exception {
        String validJava = """
            package test.package;

            import java.util.List;
            import java.util.Map;
            import scala.runtime.ScalaRunTime;
            import some.package.ValidClass;

            public class TestClass {
                // class content
            }
            """;

        // Should remain exactly the same
        assertSyntaxFix(validJava, validJava, "Valid imports should not be modified");
    }

    /**
     * Test that the fix doesn't break on files without any imports.
     */
    @Test
    void testFileWithoutImports() throws Exception {
        String javaWithoutImports = """
            package test.package;

            public class TestClass {
                private String field;

                public void method() {
                    // some code
                }
            }
            """;

        // Should remain exactly the same
        assertSyntaxFix(javaWithoutImports, javaWithoutImports, "Files without imports should be unchanged");
    }

    /**
     * Test that static imports are not affected by the fix.
     */
    @Test
    void testStaticImportsUnaffected() throws Exception {
        String javaWithStaticImports = """
            import static java.util.Collections.*;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            import scala.runtime.ScalaRunTime.;
            """;

        String expectedFixed = """
            import static java.util.Collections.*;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            import scala.runtime.ScalaRunTime;
            """;

        assertSyntaxFix(javaWithStaticImports, expectedFixed, "Static imports should be preserved, regular malformed imports fixed");
    }

    /**
     * Test edge case where import statement spans multiple lines (shouldn't happen but good to test).
     */
    @Test
    void testSingleLineImportOnly() throws Exception {
        String complexJava = """
            import scala.runtime.ScalaRunTime.;
            // This is not an import: import something.;
            /* Comment with import something.; inside */
            String code = "import fake.Import.;";
            """;

        String expectedFixed = """
            import scala.runtime.ScalaRunTime;
            // This is not an import: import something.;
            /* Comment with import something.; inside */
            String code = "import fake.Import.;";
            """;

        assertSyntaxFix(complexJava, expectedFixed, "Only actual import statements should be fixed, not comments or strings");
    }

    /**
     * Regression test for the specific FlowConfig.java case that caused the original issue.
     */
    @Test
    void testFlowConfigRegressionCase() throws Exception {
        // This is the exact problematic line from FlowConfig.java
        String flowConfigSnippet = """
            package io.joern.joerncli;

            import java.io.Serializable;
            import scala.Option;
            import scala.Product;
            import scala.runtime.BoxesRunTime;
            import scala.runtime.Statics;
            import scala.runtime.ScalaRunTime.;

            public class FlowConfig implements Product, Serializable {
               private final String cpgFileName;
               private final boolean verbose;

               public static FlowConfig apply(String var0, boolean var1) {
                  return FlowConfig$.MODULE$.apply(var0, var1);
               }
            }
            """;

        // Write and process the file
        Files.writeString(testJavaFile, flowConfigSnippet, StandardCharsets.UTF_8);

        // Apply the syntax fix using the package-private method
        Decompiler.fixDecompiledSyntaxIssues(tempDir);

        // Read the result
        String result = Files.readString(testJavaFile, StandardCharsets.UTF_8);

        // Verify the malformed import was fixed
        assertFalse(result.contains("import scala.runtime.ScalaRunTime.;"),
                   "Malformed import should be fixed");
        assertTrue(result.contains("import scala.runtime.ScalaRunTime;"),
                  "Import should be corrected to valid syntax");

        // Verify other imports are unchanged
        assertTrue(result.contains("import java.io.Serializable;"),
                  "Valid imports should be preserved");
        assertTrue(result.contains("import scala.Option;"),
                  "Valid imports should be preserved");
    }

    /**
     * Test helper method that verifies syntax fixes work correctly.
     */
    private void assertSyntaxFix(String malformedJava, String expectedFixed, String message) throws Exception {
        // Write the malformed Java to a file
        Files.writeString(testJavaFile, malformedJava, StandardCharsets.UTF_8);

        // Apply the syntax fix using the package-private method
        Decompiler.fixDecompiledSyntaxIssues(tempDir);

        // Read the result
        String actualResult = Files.readString(testJavaFile, StandardCharsets.UTF_8);

        // Verify the fix worked as expected
        assertEquals(expectedFixed, actualResult, message);
    }
}
