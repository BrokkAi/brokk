package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IProject;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FuzzyUsageFinderJavaTest {

    private static final Logger logger = LoggerFactory.getLogger(FuzzyUsageFinderJavaTest.class);

    private static IProject testProject;
    private static TreeSitterAnalyzer analyzer;

    @BeforeAll
    public static void setup() throws IOException {
        testProject = createTestProject("testcode-java");
        analyzer = new JavaAnalyzer(testProject);
        logger.debug(
                "Setting up FuzzyUsageFinder tests with test code from {}",
                testProject.getRoot().toAbsolutePath().normalize());
    }

    @AfterAll
    public static void teardown() {
        try {
            testProject.close();
        } catch (Exception e) {
            logger.error("Exception encountered while closing the test project at the end of testing", e);
        }
    }

    private static IProject createTestProject(String subDir) {
        var testDir = Path.of("./src/test/resources", subDir).toAbsolutePath().normalize();
        assertTrue(Files.exists(testDir), String.format("Test resource dir missing: %s", testDir));
        assertTrue(Files.isDirectory(testDir), String.format("%s is not a directory", testDir));

        return new IProject() {
            @Override
            public Path getRoot() {
                return testDir.toAbsolutePath();
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                var files = testDir.toFile().listFiles();
                if (files == null) {
                    return Collections.emptySet();
                }
                return Arrays.stream(files)
                        .map(file -> new ProjectFile(testDir, testDir.relativize(file.toPath())))
                        .collect(Collectors.toSet());
            }
        };
    }

    private static Set<String> baseNamesFromHits(Set<UsageHit> hits) {
        return hits.stream()
                .map(hit -> hit.file().absPath().getFileName().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static FuzzyUsageFinder newFinder(IProject project) {
        return new FuzzyUsageFinder(project, analyzer, null, null); // No LLM for these tests
    }

    @Test
    public void getUsesMethodExistingTest() {
        var finder = newFinder(testProject);
        var symbol = "A.method2";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = baseNamesFromHits(hits);

        // Expect references to be in B.java and AnonymousUsage.java (may include others; we assert presence)
        assertTrue(files.contains("B.java"), "Expected a usage in B.java; actual: " + files);
        assertTrue(files.contains("AnonymousUsage.java"), "Expected a usage in AnonymousUsage.java; actual: " + files);
    }

    @Test
    public void getUsesNestedClassConstructorTest() {
        var finder = newFinder(testProject);
        var symbol = "A$AInner$AInnerInner";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = baseNamesFromHits(hits);

        assertFalse(files.contains("A.java"), "Declaration should not be counted as usage; actual: " + files);
    }

    @Test
    public void getUsesMethodNonexistentTest() {
        var finder = newFinder(testProject);
        var symbol = "A.noSuchMethod:java.lang.String()";
        var result = finder.findUsages(symbol);

        assertTrue(result instanceof FuzzyResult.Failure, "Expected Failure for " + symbol);
    }

    @Test
    public void getUsesFieldExistingTest() {
        var finder = newFinder(testProject);
        var symbol = "D.field1";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = baseNamesFromHits(hits);

        assertTrue(files.contains("D.java"), "Expected a usage in D.java; actual: " + files);
        assertTrue(files.contains("E.java"), "Expected a usage in E.java; actual: " + files);
    }

    // Mirrors: getUsesFieldNonexistentTest
    @Test
    public void getUsesFieldNonexistentTest() {
        var finder = newFinder(testProject);
        var symbol = "D.notAField";
        var result = finder.findUsages(symbol);

        assertTrue(result instanceof FuzzyResult.Failure, "Expected Failure for " + symbol);
    }

    @Test
    public void getUsesFieldFromUseETest() {
        var finder = newFinder(testProject);
        var symbol = "UseE.e";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = baseNamesFromHits(hits);

        assertTrue(files.contains("UseE.java"), "Expected a usage in UseE.java; actual: " + files);
    }

    @Test
    public void getUsesClassBasicTest() {
        var finder = newFinder(testProject);
        var symbol = "A";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = baseNamesFromHits(hits);

        // Expect references across several files (constructor and method usage)
        assertTrue(files.contains("B.java"), "Expected usage in B.java; actual: " + files);
        assertTrue(files.contains("D.java"), "Expected usage in D.java; actual: " + files);
        assertTrue(files.contains("AnonymousUsage.java"), "Expected usage in AnonymousUsage.java; actual: " + files);
        assertTrue(files.contains("A.java"), "Expected usage in A.java; actual: " + files);
    }

    @Test
    public void getUsesClassNonexistentTest() {
        var finder = newFinder(testProject);
        var symbol = "NoSuchClass";
        var result = finder.findUsages(symbol);

        assertTrue(result instanceof FuzzyResult.Failure, "Expected Failure for " + symbol);
    }

    @Test
    public void getUsesNestedClassTest() {
        var finder = newFinder(testProject);
        var symbol = "A$AInner";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = baseNamesFromHits(hits);

        assertTrue(files.contains("A.java"), "Expected usage in A.java; actual: " + files);
    }

    @Test
    public void getUsesClassWithStaticMembersTest() {
        var finder = newFinder(testProject);
        var symbol = "E";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = baseNamesFromHits(hits);

        assertTrue(files.contains("UseE.java"), "Expected usage in UseE.java; actual: " + files);
    }

    @Test
    public void getUsesClassInheritanceTest() {
        var finder = newFinder(testProject);
        var symbol = "BaseClass";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = baseNamesFromHits(hits);

        // Expect a usage in classes that extend or refer to BaseClass
        assertTrue(files.contains("XExtendsY.java"), "Expected usage in XExtendsY.java; actual: " + files);
        assertTrue(files.contains("MethodReturner.java"), "Expected usage in MethodReturner.java; actual: " + files);
    }

    @Test
    public void getUsesFunctionNoPrefixMatchTest() {
        // Rewritten to use existing resources: ensure that searching for A$AInner does NOT prefix-match AInnerInner.
        var finder = newFinder(testProject);
        var symbol = "A$AInner";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        assertFalse(hits.isEmpty(), "Expected at least one usage for " + symbol);

        // Ensure no matched line contains 'AInnerInner' (i.e., no prefix match)
        for (var hit : hits) {
            var file = hit.file();
            var contentOpt = file.read();
            assertTrue(contentOpt.isPresent(), "Unable to read file content for " + file);
            var lines = contentOpt.get().split("\\R", -1);
            int lineNo = hit.line();
            assertTrue(lineNo >= 1 && lineNo <= lines.length, "Matched line number out of range: " + lineNo);
            var matchedLine = lines[lineNo - 1];

            assertFalse(
                    matchedLine.contains("AInnerInner"),
                    "Should not prefix-match AInnerInner on the matched line; line: " + matchedLine);
        }

        var files = baseNamesFromHits(hits);
        assertTrue(files.contains("A.java"), "Expected usage in A.java; actual: " + files);
    }

    @Test
    public void getUsesFunctionVsFieldAmbiguityTest() {
        // Test that searching for a method foo() does NOT match field assignments to foo.
        // - method: ServiceImpl.foo()
        // - field: E.foo
        // Split into separate call sites: callFoo() invokes s.foo(), assignFoo() assigns e.foo = 1
        var finder = newFinder(testProject);
        var symbol = "ServiceImpl.foo";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        // Focus on hits in UseE.java and ensure they are function invocations, not field accesses.
        var useEHits = hits.stream()
                .filter(h -> h.file().absPath().getFileName().toString().equals("UseE.java"))
                .collect(Collectors.toList());

        assertFalse(useEHits.isEmpty(), "Expected at least one invocation hit in UseE.java");

        // Verify all hits are method calls (line contains foo followed by paren), not field assignments
        for (var hit : useEHits) {
            var file = hit.file();
            var contentOpt = file.read();
            assertTrue(contentOpt.isPresent(), "Unable to read file content for " + file);
            var lines = contentOpt.get().split("\\R", -1);
            int lineNo = hit.line(); // 1-based in UsageHit
            assertTrue(lineNo >= 1 && lineNo <= lines.length, "Matched line number out of range: " + lineNo);
            var matchedLine = lines[lineNo - 1];

            assertTrue(
                    matchedLine.matches(".*\\bfoo\\b\\s*\\(.*"),
                    "Expected only function-call style match on the matched line; line: " + matchedLine);
            assertFalse(
                    matchedLine.matches(".*\\bfoo\\b\\s*=.*"),
                    "Should not match field assignment on the matched line; line: " + matchedLine);
        }

        // Verify that assignFoo() method itself is NOT in the hit list
        var assignFooHits = useEHits.stream()
                .filter(h -> {
                    try {
                        var contentOpt = h.file().read();
                        if (contentOpt.isEmpty()) return false;
                        var lines = contentOpt.get().split("\\R", -1);
                        int lineNo = h.line();
                        if (lineNo < 1 || lineNo > lines.length) return false;
                        return lines[lineNo - 1].contains("assignFoo");
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());

        assertTrue(assignFooHits.isEmpty(), "Should not have hits in assignFoo() method where foo is a field");
    }
}
