package ai.brokk.errorprone;

import com.google.errorprone.CompilationTestHelper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for EmptyFileChecker.
 */
public class EmptyFileCheckerTest {

    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(EmptyFileChecker.class, getClass())
            .setArgs(List.of("--release", "21"));

    @Test
    public void flagsEmptyFile() {
        // Error Prone tests often need at least a comment to avoid parser errors
        // before the checker runs, but we want to test a truly empty-looking file.
        helper.addSourceLines("Test.java", "/* BUG: Diagnostic contains: BrokkEmptyFile */")
                .doTest();
    }

    @Test
    public void flagsWhitespaceFile() {
        helper.addSourceLines("Test.java", "/* BUG: Diagnostic contains: BrokkEmptyFile */ ", "   ", "\t")
                .doTest();
    }

    @Test
    public void doesNotFlagFileWithContent() {
        helper.addSourceLines("Test.java", "package test;", "public class Test {}")
                .doTest();
    }

    @Test
    public void skipsPackageInfo() {
        helper.addSourceLines("package-info.java", " ").doTest();
    }
}
