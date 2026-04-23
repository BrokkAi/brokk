package ai.brokk.analyzer.usages;

import static ai.brokk.testutil.UsageFinderTestUtil.fileNamesFromHits;
import static ai.brokk.testutil.UsageFinderTestUtil.newFinder;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.brokk.analyzer.CSharpAnalyzer;
import ai.brokk.analyzer.CppAnalyzer;
import ai.brokk.analyzer.GoAnalyzer;
import ai.brokk.analyzer.JavascriptAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.PhpAnalyzer;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.analyzer.TypescriptAnalyzer;
import ai.brokk.testutil.TestProject;
import org.junit.jupiter.api.Test;

/**
 * App-owned integration tests that validate {@code UsageFinder} behavior against language analyzers.
 *
 * <p>These intentionally live in {@code :app} because {@code UsageFinder} and {@code IProject} are app-owned.
 */
public final class UsageFinderComprehensivePatternsTest {

    @Test
    void csharp_getUsesClassComprehensivePatterns() throws InterruptedException {
        try (var project = TestProject.createTestProject("testcode-cs", Languages.C_SHARP)) {
            var analyzer = new CSharpAnalyzer(project);

            var finder = newFinder(project, analyzer);
            var symbol = "BaseClass";
            var either = finder.findUsages(symbol).toEither();

            assumeFalse(either.hasErrorMessage(), "C# analyzer unavailable");

            var hits = either.getUsages();
            var files = fileNamesFromHits(hits);
            assertTrue(
                    files.contains("ClassUsagePatterns.cs"),
                    "Expected comprehensive usage patterns in ClassUsagePatterns.cs; actual: " + files);
            assertTrue(
                    hits.stream()
                                    .filter(h -> h.file()
                                            .absPath()
                                            .getFileName()
                                            .toString()
                                            .equals("ClassUsagePatterns.cs"))
                                    .toList()
                                    .size()
                            >= 2,
                    "Expected at least 2 different usage patterns");
        }
    }

    @Test
    void cpp_getUsesClassComprehensivePatterns() throws InterruptedException {
        try (var project = TestProject.createTestProject("testcode-cpp", Languages.C_CPP)) {
            var analyzer = new CppAnalyzer(project).update();

            var finder = newFinder(project, analyzer);
            var symbol = "BaseClass";
            var either = finder.findUsages(symbol).toEither();

            assumeFalse(either.hasErrorMessage(), "C++ analyzer unavailable");

            var hits = either.getUsages();
            var files = fileNamesFromHits(hits);
            assertTrue(
                    files.contains("class_usage_patterns.cpp"),
                    "Expected comprehensive usage patterns in class_usage_patterns.cpp; actual: " + files);
            assertTrue(
                    hits.stream()
                                    .filter(h -> h.file()
                                            .absPath()
                                            .getFileName()
                                            .toString()
                                            .equals("class_usage_patterns.cpp"))
                                    .toList()
                                    .size()
                            >= 2,
                    "Expected at least 2 different usage patterns");
        }
    }

    @Test
    void go_getUsesClassComprehensivePatterns() throws InterruptedException {
        try (var project = TestProject.createTestProject("testcode-go", Languages.GO)) {
            var analyzer = new GoAnalyzer(project).update();

            var finder = newFinder(project, analyzer);
            var symbol = "main.BaseStruct";
            var either = finder.findUsages(symbol).toEither();

            assumeFalse(either.hasErrorMessage(), "Go analyzer unavailable");

            var hits = either.getUsages();
            var files = fileNamesFromHits(hits);
            assertTrue(
                    files.contains("class_usage_patterns.go"),
                    "Expected comprehensive usage patterns in class_usage_patterns.go; actual: " + files);
            assertTrue(
                    hits.stream()
                                    .filter(h -> h.file()
                                            .absPath()
                                            .getFileName()
                                            .toString()
                                            .equals("class_usage_patterns.go"))
                                    .toList()
                                    .size()
                            >= 2,
                    "Expected at least 2 different usage patterns");
        }
    }

    @Test
    void javascript_getUsesClassComprehensivePatterns() throws InterruptedException {
        try (var project = TestProject.createTestProject("testcode-js", Languages.JAVASCRIPT)) {
            var analyzer = new JavascriptAnalyzer(project);
            analyzer.update();

            var finder = newFinder(project, analyzer);
            var symbol = "BaseClass";
            var either = finder.findUsages(symbol).toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            var files = fileNamesFromHits(hits);
            assertTrue(
                    files.contains("ClassUsagePatterns.js"),
                    "Expected comprehensive usage patterns in ClassUsagePatterns.js; actual: " + files);
            assertTrue(
                    hits.stream()
                                    .filter(h -> h.file()
                                            .absPath()
                                            .getFileName()
                                            .toString()
                                            .equals("ClassUsagePatterns.js"))
                                    .toList()
                                    .size()
                            >= 2,
                    "Expected at least 2 different usage patterns");
        }
    }

    @Test
    void php_getUsesClassComprehensivePatterns() throws InterruptedException {
        try (var project = TestProject.createTestProject("testcode-php", Languages.PHP)) {
            var analyzer = new PhpAnalyzer(project);
            analyzer.update();

            var finder = newFinder(project, analyzer);
            var symbol = "BaseClass";
            var either = finder.findUsages(symbol).toEither();

            assumeFalse(either.hasErrorMessage(), "PHP analyzer unavailable");

            var hits = either.getUsages();
            var files = fileNamesFromHits(hits);
            assertTrue(
                    files.contains("ClassUsagePatterns.php"),
                    "Expected comprehensive usage patterns in ClassUsagePatterns.php; actual: " + files);
            assertTrue(
                    hits.stream()
                                    .filter(h -> h.file()
                                            .absPath()
                                            .getFileName()
                                            .toString()
                                            .equals("ClassUsagePatterns.php"))
                                    .toList()
                                    .size()
                            >= 2,
                    "Expected at least 2 different usage patterns");
        }
    }

    @Test
    void python_getUsesClassComprehensivePatterns() throws InterruptedException {
        try (var project = TestProject.createTestProject("testcode-py", Languages.PYTHON)) {
            var analyzer = new PythonAnalyzer(project);
            analyzer.update();

            var finder = newFinder(project, analyzer);
            var symbol = "class_usage_patterns.BaseClass";
            var either = finder.findUsages(symbol).toEither();

            assumeFalse(either.hasErrorMessage(), "Python analyzer unavailable");

            var hits = either.getUsages();
            var files = fileNamesFromHits(hits);
            assertTrue(
                    files.contains("class_usage_patterns.py"),
                    "Expected comprehensive usage patterns in class_usage_patterns.py; actual: " + files);
            assertTrue(
                    hits.stream()
                                    .filter(h -> h.file()
                                            .absPath()
                                            .getFileName()
                                            .toString()
                                            .equals("class_usage_patterns.py"))
                                    .toList()
                                    .size()
                            >= 2,
                    "Expected at least 2 different usage patterns");
        }
    }

    @Test
    void typescript_getUsesClassComprehensivePatterns() throws InterruptedException {
        try (var project = TestProject.createTestProject("testcode-ts", Languages.TYPESCRIPT)) {
            var analyzer = new TypescriptAnalyzer(project);
            analyzer.update();

            var finder = newFinder(project, analyzer);
            var symbol = "BaseClass";
            var either = finder.findUsages(symbol).toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            var files = fileNamesFromHits(hits);
            assertTrue(
                    files.contains("ClassUsagePatterns.ts"),
                    "Expected comprehensive usage patterns in ClassUsagePatterns.ts; actual: " + files);
            assertTrue(
                    hits.stream()
                                    .filter(h -> h.file()
                                            .absPath()
                                            .getFileName()
                                            .toString()
                                            .equals("ClassUsagePatterns.ts"))
                                    .toList()
                                    .size()
                            >= 5,
                    "Expected at least 5 different usage patterns");
        }
    }
}
