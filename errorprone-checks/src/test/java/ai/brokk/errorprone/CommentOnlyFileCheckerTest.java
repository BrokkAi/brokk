package ai.brokk.errorprone;

import com.google.errorprone.CompilationTestHelper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for CommentOnlyFileChecker. */
public class CommentOnlyFileCheckerTest {

    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(
                    CommentOnlyFileChecker.class, getClass())
            .setArgs(List.of("--release", "21"));

    @Test
    public void flagsEmptyFile() {
        helper.addSourceLines("Test.java", "\n\n\n").doTest();
    }

    @Test
    public void flagsWhitespaceFile() {
        helper.addSourceLines("Test.java", "/* BUG: Diagnostic contains: BrokkEmptyFile */", "   ", "\t")
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
