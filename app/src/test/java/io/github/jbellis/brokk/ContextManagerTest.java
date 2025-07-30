package io.github.jbellis.brokk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ContextManager#TEST_FILE_PATTERN}.
 */
class ContextManagerTest
{
    @Test
    void shouldMatchTestFilenames()
    {
        var positives = List.of(
                // match in path
                "src/test/java/MyClass.java",
                "src/tests/io/github/Main.kt",

                // match in file
                "TestX.java",
                "TestsX.java",
                "XTest.java",
                "XTests.java",
                "CamelTestCase.java",
                "CamelTestsCase.java",
                // with a path
                "src/foo/bar/TestX.java",
                "src/foo/bar/TestsX.java",
                "src/foo/bar/XTest.java",
                "src/foo/bar/XTests.java",
                "src/foo/bar/CamelTestCase.java",
                "src/foo/bar/CamelTestsCase.java",

                // underscore style
                "test_x.py",
                "tests_x.py",
                "x_test.py",
                "x_tests.py",
                "under_test_score.py",
                "under_tests_score.py",
                // with a path
                "src/foo/bar/test_x.py",
                "src/foo/bar/tests_x.py",
                "src/foo/bar/x_test.py",
                "src/foo/bar/x_tests.py",
                "src/foo/bar/under_test_score.py",
                "src/foo/bar/under_tests_score.py"
        );

        var pattern = ContextManager.TEST_FILE_PATTERN;
        var mismatches = new java.util.ArrayList<String>();

        positives.forEach(path -> {
            if (!pattern.matcher(path).matches()) {
                mismatches.add(path);
            }
        });

        assertTrue(mismatches.isEmpty(),
                   "Expected to match but didn't: " + mismatches);
    }

    @Test
    void shouldNotMatchNonTestFilenames()
    {
        var negatives = List.of(
                "testing/Bar.java",
                "src/production/java/MyClass.java",
                "contest/file.java",
                "testament/Foo.java",
                "src/main/java/Testament.java",
                "src/main/java/Contest.java"
        );

        var pattern = ContextManager.TEST_FILE_PATTERN;
        var unexpectedMatches = new java.util.ArrayList<String>();

        negatives.forEach(path -> {
            if (pattern.matcher(path).matches()) {
                unexpectedMatches.add(path);
            }
        });

        assertTrue(unexpectedMatches.isEmpty(),
                   "Unexpectedly matched: " + unexpectedMatches);
    }
}
