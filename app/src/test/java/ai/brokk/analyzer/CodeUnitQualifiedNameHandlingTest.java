package ai.brokk.analyzer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests verifying CodeUnit behavior when the provided short name is a fully-qualified token versus when package and
 * short name are provided separately.
 *
 * Additionally contains a disabled placeholder demonstrating how one might capture and assert the WARN-level
 * diagnostic produced by TreeSitterAnalyzer when it detects a likely fully-qualified simple name. The placeholder
 * is intentionally disabled to avoid adding logging-test dependencies (e.g., Logback test appender) to the project.
 */
public class CodeUnitQualifiedNameHandlingTest {

    @Test
    public void qualifiedShortNameIsPreservedInCodeUnit() {
        ProjectFile source = new ProjectFile(Paths.get(".").toAbsolutePath().normalize(), "src/main/java/com/example/MyClass.java");
        CodeUnit cu = CodeUnit.cls(source, "", "com.example.MyClass");

        // The supplied shortName is itself a qualified token; CodeUnit should preserve it as the short name
        assertEquals("com.example.MyClass", cu.shortName());
        // fqName should match the preserved qualified token as provided
        assertEquals("com.example.MyClass", cu.fqName());
    }

    @Test
    public void fqNameConstructedFromPackageAndShortName() {
        ProjectFile source = new ProjectFile(Paths.get(".").toAbsolutePath().normalize(), "src/main/java/com/example/MyClass.java");
        CodeUnit cu = CodeUnit.cls(source, "com.example", "MyClass");

        // When package and shortName are provided separately, shortName remains the simple name
        assertEquals("MyClass", cu.shortName());
        // fqName should be composed of package + '.' + shortName
        assertEquals("com.example.MyClass", cu.fqName());
    }

    @Test
    public void nestedLikeShortNameIsPreservedAndPrefixedByPackage() {
        ProjectFile source = new ProjectFile(Paths.get(".").toAbsolutePath().normalize(), "src/main/java/com/example/Outer.java");
        // Simulate a shortName that already looks like an inner-class token "Outer$Inner"
        CodeUnit cu = CodeUnit.cls(source, "com.example", "Outer$Inner");

        // The supplied shortName contains a nested/inner-class marker and should be preserved as-is
        assertEquals("Outer$Inner", cu.shortName());
        // fqName should be composed of package + '.' + shortName, preserving the '$' marker
        assertEquals("com.example.Outer$Inner", cu.fqName());

        // This test locks in behavior for nested-like tokens provided as short names: they are not split
        // into package vs. simple name by CodeUnit construction and are preserved verbatim.
    }

    /**
     * Disabled placeholder illustrating how to capture the WARN diagnostic emitted by
     * TreeSitterAnalyzer.analyzeFileContent(...) when a likely-qualified simple name is detected.
     *
     * Notes:
     * - This test is intentionally disabled to avoid introducing a runtime dependency on a logging-test
     *   library (e.g., Logback's ListAppender) in the project's test classpath.
     * - To enable and use this pattern:
     *   1. Add a test-scoped dependency on a Logback implementation (ch.qos.logback:logback-classic)
     *      so you can attach a ListAppender to the logger used by TreeSitterAnalyzer.
     *   2. Attach the appender to the logger for TreeSitterAnalyzer.class, trigger the code path that logs
     *      the warning (e.g., call analyzeFileContent via a thin wrapper or exercise analyzeFile with crafted bytes),
     *      then assert that the appender captured at least one WARN event whose message contains
     *      "Detected likely fully-qualified simpleName".
     *
     * Example pseudocode (do not uncomment here — kept as documentation only):
     *
     * // ch.qos.logback.classic.Logger tsLogger =
     * //     (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TreeSitterAnalyzer.class);
     * // var listAppender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
     * // listAppender.start();
     * // tsLogger.addAppender(listAppender);
     * //
     * // // Trigger analyzeFileContent or equivalent
     * //
     * // boolean found = listAppender.list.stream()
     * //     .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
     * //         && e.getFormattedMessage().contains("Detected likely fully-qualified simpleName"));
     * // assertTrue(found);
     * //
     * // tsLogger.detachAppender(listAppender);
     */
    @Disabled("Placeholder. Enable and add logback test deps to assert analyzer warnings.")
    @Test
    public void captureWarnDiagnostic_placeholder() {
        // Intentionally empty: guidance is provided in the method Javadoc above.
    }
}
