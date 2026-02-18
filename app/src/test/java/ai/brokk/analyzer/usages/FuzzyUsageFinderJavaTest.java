package ai.brokk.analyzer.usages;

import static ai.brokk.testutil.FuzzyUsageFinderTestUtil.fileNamesFromHits;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.*;
import ai.brokk.project.IProject;
import ai.brokk.testutil.FuzzyUsageFinderTestUtil;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
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
        var testDir = Path.of("./src/test/resources", "testcode-java")
                .toAbsolutePath()
                .normalize();
        testProject = new TestProject(testDir);
        analyzer = new JavaAnalyzer(testProject);
        logger.debug(
                "Setting up UsageFinder tests with test code from {}",
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

    @Test
    public void getUsesMethodExistingTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "A.method2";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        // Expect references to be in B.java and AnonymousUsage.java (may include others; we assert presence)
        assertTrue(files.contains("B.java"), "Expected a usage in B.java; actual: " + files);
        assertTrue(files.contains("AnonymousUsage.java"), "Expected a usage in AnonymousUsage.java; actual: " + files);
    }

    @Test
    public void getUsesNestedClassConstructorTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "A$AInner$AInnerInner";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        assertFalse(files.contains("A.java"), "Declaration should not be counted as usage; actual: " + files);
    }

    @Test
    public void getUsesMethodNonexistentTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "A.noSuchMethod:java.lang.String()";
        var result = finder.findUsages(symbol);

        assertTrue(result instanceof FuzzyResult.Failure, "Expected Failure for " + symbol);
    }

    @Test
    public void getUsesFieldExistingTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "D.field1";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        assertTrue(files.contains("D.java"), "Expected a usage in D.java; actual: " + files);
        assertTrue(files.contains("E.java"), "Expected a usage in E.java; actual: " + files);
    }

    @Test
    public void getUsesFieldNonexistentTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "D.notAField";
        var result = finder.findUsages(symbol);

        assertTrue(result instanceof FuzzyResult.Failure, "Expected Failure for " + symbol);
    }

    @Test
    public void getUsesFieldFromUseETest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "UseE.e";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        assertTrue(files.contains("UseE.java"), "Expected a usage in UseE.java; actual: " + files);
    }

    @Test
    public void getUsesClassBasicTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "A";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        // Expect references across several files (constructor and method usage)
        assertTrue(files.contains("B.java"), "Expected usage in B.java; actual: " + files);
        assertTrue(files.contains("D.java"), "Expected usage in D.java; actual: " + files);
        assertTrue(files.contains("AnonymousUsage.java"), "Expected usage in AnonymousUsage.java; actual: " + files);
        assertTrue(files.contains("A.java"), "Expected usage in A.java; actual: " + files);
    }

    @Test
    public void getUsesClassNonexistentTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "NoSuchClass";
        var result = finder.findUsages(symbol);

        assertTrue(result instanceof FuzzyResult.Failure, "Expected Failure for " + symbol);
    }

    @Test
    public void getUsesNestedClassTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "A$AInner";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        assertTrue(files.contains("A.java"), "Expected usage in A.java; actual: " + files);
    }

    @Test
    public void getUsesClassWithStaticMembersTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "E";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        assertTrue(files.contains("UseE.java"), "Expected usage in UseE.java; actual: " + files);
    }

    @Test
    public void getUsesClassInheritanceTest() throws InterruptedException {
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "BaseClass";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        // Expect a usage in classes that extend or refer to BaseClass
        assertTrue(files.contains("XExtendsY.java"), "Expected usage in XExtendsY.java; actual: " + files);
        assertTrue(files.contains("MethodReturner.java"), "Expected usage in MethodReturner.java; actual: " + files);
    }

    @Test
    public void getUsesFunctionNoPrefixMatchTest() throws InterruptedException {
        // Ensure that searching for A$AInner does NOT prefix-match A$AInner$AInnerInner
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "A$AInner";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        assertFalse(hits.isEmpty(), "Expected at least one usage for " + symbol);

        var files = fileNamesFromHits(hits);
        assertTrue(files.contains("A.java"), "Expected usage in A.java; actual: " + files);

        // Verify that all hits are for A$AInner, not for any prefix-matched longer class names
        for (var hit : hits) {
            var enclosing = hit.enclosing();
            assertNotEquals(
                    "A$AInner$AInnerInner",
                    enclosing.fqName(),
                    "Should not have matched the nested class A$AInner$AInnerInner");
        }
    }

    @Test
    public void getUsesFunctionVsFieldAmbiguityTest() throws Exception {
        // Test that searching for a method foo() correctly identifies usages within the right enclosing methods
        // and does NOT match field usages like E.foo.
        String serviceImpl =
                """
                public class ServiceImpl {
                    public void foo() {
                        System.out.println("foo");
                        class Local {
                            void bar() { foo(); } // call from local class for detection
                        }
                    }

                    public void callFoo() {
                        foo(); // direct call
                    }
                }
                """;

        try (IProject inlineProject =
                InlineTestProjectCreator.code(serviceImpl, "ServiceImpl.java").build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);
            var symbol = "ServiceImpl.foo";
            var either = finder.findUsages(symbol).toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages().stream()
                    .map(uh -> uh.enclosing().identifier())
                    .collect(Collectors.toSet());

            // We expect one hit in callFoo, and one hit from the local class bar() method
            assertTrue(hits.contains("callFoo"), "Expected usage in callFoo");
            assertTrue(hits.contains("bar"), "Expected usage in local class method bar(), but got: " + hits);
        }
    }

    @Test
    public void getUsesMethodReferenceTest() throws InterruptedException {
        // Test that method references (e.g., this::transform) are correctly identified
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "MethodReferenceUsage.transform";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages().stream()
                .map(uh -> uh.enclosing().identifier())
                .collect(Collectors.toSet());
        assertEquals(Set.of("demonstrateCall", "demonstrateInstanceReference", "demonstrateReferenceParameter"), hits);
    }

    @Test
    public void getUsesOverloadedMethodsAggregationTest() throws InterruptedException {
        // Test that findUsages aggregates usages from all overloaded methods
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "Overloads.process";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        // Should find usages in OverloadsUser.java which calls multiple process() overloads
        assertTrue(files.contains("OverloadsUser.java"), "Expected usage in OverloadsUser.java; actual: " + files);

        // Should have at least one hit from OverloadsUser
        assertFalse(hits.isEmpty(), "Expected at least one hit for process() calls");
    }

    @Test
    public void getUsesClassComprehensivePatternsTest() throws InterruptedException {
        // Test that all class usage patterns are detected:
        // - Constructor calls (new BaseClass())
        // - Inheritance (extends BaseClass)
        // - Variable declarations (BaseClass field)
        // - Parameters (BaseClass param)
        // - Return types (BaseClass method())
        // - Generics (List<BaseClass>)
        // - Casts ((BaseClass) obj)
        // - Static access (BaseClass.staticMethod())
        var finder = FuzzyUsageFinderTestUtil.createForTest(testProject, analyzer);
        var symbol = "BaseClass";
        var either = finder.findUsages(symbol).toEither();

        if (either.hasErrorMessage()) {
            fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
        }

        var hits = either.getUsages();
        var files = fileNamesFromHits(hits);

        // ClassUsagePatterns.java should contain all pattern types
        assertTrue(
                files.contains("ClassUsagePatterns.java"),
                "Expected comprehensive usage patterns in ClassUsagePatterns.java; actual: " + files);

        // Verify we found multiple usages (at least 5 different pattern types)
        var classUsageHits = hits.stream()
                .filter(h -> h.file().absPath().getFileName().toString().equals("ClassUsagePatterns.java"))
                .toList();
        assertTrue(
                classUsageHits.size() >= 5,
                "Expected at least 5 different usage patterns, found: " + classUsageHits.size());
    }

    @Test
    public void filterByConfidenceFiltersLowConfidenceHits() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        var file = new ProjectFile(root, Path.of("A.java"));

        var enclosingIncluded = new CodeUnit(file, CodeUnitType.CLASS, "", "A1", null);
        var enclosingExcluded = new CodeUnit(file, CodeUnitType.CLASS, "", "A2", null);
        var enclosingHigh = new CodeUnit(file, CodeUnitType.CLASS, "", "A3", null);

        UsageHit hitIncluded = new UsageHit(file, 1, 0, 1, enclosingIncluded, 0.1, "");
        UsageHit hitExcluded = new UsageHit(file, 1, 10, 11, enclosingExcluded, 0.099, "");
        UsageHit hitHigh = new UsageHit(file, 2, 20, 21, enclosingHigh, 1.0, "");

        var allHitsByOverload = new HashMap<CodeUnit, Set<UsageHit>>();
        allHitsByOverload.put(enclosingIncluded, new HashSet<>(Set.of(hitIncluded, hitExcluded)));
        allHitsByOverload.put(enclosingHigh, new HashSet<>(Set.of(hitHigh)));

        var filtered = LlmUsageAnalyzer.filterByConfidence(allHitsByOverload);

        assertEquals(2, filtered.size());
        assertEquals(Set.of(hitIncluded), filtered.get(enclosingIncluded));
        assertEquals(Set.of(hitHigh), filtered.get(enclosingHigh));
    }

    @Test
    public void filterByConfidenceExcludesEntriesWithAllLowConfidenceHits() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        var file = new ProjectFile(root, Path.of("A.java"));

        var enclosingLow = new CodeUnit(file, CodeUnitType.CLASS, "", "LowConf", null);
        var enclosingHigh = new CodeUnit(file, CodeUnitType.CLASS, "", "HighConf", null);

        // All hits for this overload are below 0.1
        UsageHit hitLow1 = new UsageHit(file, 1, 0, 1, enclosingLow, 0.09, "");
        UsageHit hitLow2 = new UsageHit(file, 2, 10, 11, enclosingLow, 0.05, "");

        // This overload has at least one good hit
        UsageHit hitHigh = new UsageHit(file, 3, 20, 21, enclosingHigh, 0.5, "");

        var allHitsByOverload = new HashMap<CodeUnit, Set<UsageHit>>();
        allHitsByOverload.put(enclosingLow, new HashSet<>(Set.of(hitLow1, hitLow2)));
        allHitsByOverload.put(enclosingHigh, new HashSet<>(Set.of(hitHigh)));

        var filtered = LlmUsageAnalyzer.filterByConfidence(allHitsByOverload);

        // LowConf should be absent because all its hits were filtered out
        assertEquals(1, filtered.size());
        assertFalse(filtered.containsKey(enclosingLow), "Overload with only low confidence hits should be removed");
        assertTrue(filtered.containsKey(enclosingHigh));
    }

    @Test
    public void testMultipleHitsInSameMethodAreKeptSeparate() throws Exception {
        String fooContent = "public class Foo { public void process() {} }";
        String callerContent =
                """
                public class MultipleHitsInSameMethod {
                    public void caller(Foo foo) {
                        // Two calls to the SAME method in the same enclosing method
                        foo.process();
                        foo.process();
                    }
                }
                """;

        try (IProject inlineProject = InlineTestProjectCreator.code(fooContent, "Foo.java")
                .addFileContents(callerContent, "MultipleHitsInSameMethod.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            // Search for Foo.process - the test file has two calls to foo.process() in the same method
            var symbol = "Foo.process";
            var either = finder.findUsages(symbol).toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            // Filter specifically for the hits in the test file within the 'caller' method
            var fileHits = hits.stream()
                    .filter(h -> h.file().getFileName().equals("MultipleHitsInSameMethod.java"))
                    .filter(h -> h.enclosing().identifier().equals("caller")
                            || h.enclosing().shortName().equals("caller"))
                    .sorted(Comparator.comparingInt(UsageHit::startOffset))
                    .toList();

            // If the filter didn't find hits by method name, check what enclosing units we actually got
            if (fileHits.isEmpty()) {
                // Fall back to just filtering by file and checking we have multiple hits with different offsets
                fileHits = hits.stream()
                        .filter(h -> h.file().getFileName().equals("MultipleHitsInSameMethod.java"))
                        .sorted(Comparator.comparingInt(UsageHit::startOffset))
                        .toList();
            }

            // We expect 2 hits for the two foo.process() calls in the caller method
            // These should NOT be collapsed even though they share the same enclosing CodeUnit
            assertEquals(
                    2,
                    fileHits.size(),
                    "Should have found 2 distinct hits for foo.process() calls in 'caller' method; found enclosing units: "
                            + fileHits.stream()
                                    .map(h -> h.enclosing().toString())
                                    .toList());
            assertTrue(
                    fileHits.get(0).startOffset() < fileHits.get(1).startOffset(),
                    "Hits should be at different offsets");
        }
    }

    @Test
    public void testAlternativesComputationForNestedClasses() throws Exception {
        String outer1 = "public class Outer1 { public static class Inner { public void doWork() {} } }";
        String outer2 = "public class Outer2 { public static class Inner { public void doWork() {} } }";
        String user =
                """
                public class User {
                    public void use() {
                        Outer1.Inner inner = new Outer1.Inner();
                        inner.doWork();
                    }
                }
                """;

        try (IProject inlineProject = InlineTestProjectCreator.code(outer1, "Outer1.java")
                .addFileContents(outer2, "Outer2.java")
                .addFileContents(user, "User.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            // Search for usages of Outer1$Inner.
            // Under the current bug, identifier will be "Inner", but matchingCodeUnits filter uses
            // cu.shortName().equals("Inner"). For Outer1.Inner, shortName is "Outer1.Inner" (or "Outer1$Inner"),
            // so it won't match "Inner", resulting in isUnique = false but with 0 alternatives,
            // or incorrect Ambiguous/Success classification.
            var result = finder.findUsages("Outer1$Inner");
            var either = result.toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure: " + either.getErrorMessage());
            }

            // With the fix, the finder correctly identifies both Inner classes as alternatives.
            // The public findUsages(String) API always returns Success after aggregation/filtering.
            // We verify success and that hits were found for the usage in User.java.
            assertTrue(
                    result instanceof FuzzyResult.Success,
                    "Expected Success result, got: " + result.getClass().getSimpleName());

            var hits = either.getUsages();
            assertFalse(hits.isEmpty(), "Expected at least one usage hit for Outer1.Inner");

            var files = hits.stream().map(h -> h.file().getFileName()).collect(Collectors.toSet());
            assertTrue(files.contains("User.java"), "Expected usage in User.java; actual: " + files);
        }
    }

    @Test
    public void testFindUsagesSimpleNonNestedClass() throws Exception {
        // Test that findUsages works correctly for simple non-nested classes
        // This ensures the lastSep >= 0 branch in CodeUnit.identifier() doesn't regress
        // when lastSep is -1 (no '.' or '$' in the shortName)
        String fooContent = "public class Foo { public void doSomething() {} }";
        String barContent =
                """
                public class Bar {
                    private Foo foo;
                    public void useFoo() {
                        foo = new Foo();
                        foo.doSomething();
                    }
                }
                """;

        try (IProject inlineProject = InlineTestProjectCreator.code(fooContent, "Foo.java")
                .addFileContents(barContent, "Bar.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            // Search for usages of the simple class Foo (no nesting, so identifier() returns "Foo")
            var result = finder.findUsages("Foo");
            var either = result.toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure: " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            assertFalse(hits.isEmpty(), "Expected at least one usage hit for Foo");

            var files = hits.stream().map(h -> h.file().getFileName()).collect(Collectors.toSet());
            assertTrue(files.contains("Bar.java"), "Expected usage in Bar.java; actual: " + files);

            // Verify we found usages in Bar's useFoo method
            var barHits = hits.stream()
                    .filter(h -> h.file().getFileName().equals("Bar.java"))
                    .toList();
            assertFalse(barHits.isEmpty(), "Expected hits in Bar.java");
        }
    }

    @Test
    public void testDeeplyNestedClassUsages() throws Exception {
        String nestedSource =
                """
                public class Outer {
                    public static class Middle {
                        public static class Inner {
                            public void targetMethod() {}
                        }
                    }
                }
                """;
        String otherSource = "public class Other { public static class DecoyInner { public void otherMethod() {} } }";
        String userSource =
                """
                public class User {
                    public void use() {
                        Outer.Middle.Inner a = new Outer.Middle.Inner();
                        Other.DecoyInner b = new Other.DecoyInner();
                        a.targetMethod();
                    }
                }
                """;

        try (IProject inlineProject = InlineTestProjectCreator.code(nestedSource, "Outer.java")
                .addFileContents(otherSource, "Other.java")
                .addFileContents(userSource, "User.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            // Search for the deeply nested class
            var result = finder.findUsages("Outer$Middle$Inner");
            var either = result.toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure: " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            // We expect usages in User.java for Outer.Middle.Inner
            // The decoy class Other.DecoyInner has a different simple name, so it won't be matched
            var userHits = hits.stream()
                    .filter(h -> h.file().getFileName().equals("User.java"))
                    .toList();

            // Verify we found at least one hit for the deeply nested class
            assertFalse(userHits.isEmpty(), "Expected hits for Outer.Middle.Inner in User.java");

            // The key verification: hits should be for Inner, not DecoyInner
            // Since DecoyInner has a different identifier, it won't appear in our results
            for (var hit : userHits) {
                // The enclosing method should be 'use' from User class
                assertEquals("use", hit.enclosing().identifier(), "Expected hit to be within the 'use' method");

                // Verify the snippet contains the target class name
                assertTrue(hit.snippet().contains("Outer.Middle.Inner"), "Snippet should contain Outer.Middle.Inner");
            }
        }
    }

    @Test
    public void testUsageHitEqualityBasedOnPosition() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        var file = new ProjectFile(root, Path.of("A.java"));
        var enclosing = new CodeUnit(file, CodeUnitType.FUNCTION, "p", "m", null);

        // Two hits in the same enclosing method but at different offsets
        UsageHit hit1 = new UsageHit(file, 10, 100, 110, enclosing, 1.0, "snippet1");
        UsageHit hit2 = new UsageHit(file, 12, 200, 210, enclosing, 1.0, "snippet2");

        assertNotEquals(hit1, hit2, "Hits at different offsets should not be equal even if enclosing is same");
        assertNotEquals(hit1.hashCode(), hit2.hashCode(), "Hash codes should differ for different offsets");

        Set<UsageHit> hitSet = new HashSet<>();
        hitSet.add(hit1);
        hitSet.add(hit2);
        assertEquals(2, hitSet.size(), "Set should preserve both hits within the same enclosing unit");
    }

    @Test
    public void testPolymorphicMatchesIncludeNonOverridingSubclasses() throws Exception {
        String animalContent =
                """
            public class Animal {
                public void speak() {
                    System.out.println("Animal speaks");
                }
            }
            """;
        String dogContent =
                """
            public class Dog extends Animal {
                // Does NOT override speak() - inherits from Animal
            }
            """;
        String catContent =
                """
            public class Cat extends Animal {
                @Override
                public void speak() {
                    System.out.println("Meow");
                }
            }
            """;
        String callerContent =
                """
            public class Caller {
                public void callDogSpeak(Dog dog) {
                    dog.speak(); // This is actually Animal.speak via inheritance
                }
                public void callCatSpeak(Cat cat) {
                    cat.speak(); // This is Cat.speak (override)
                }
            }
            """;

        try (IProject inlineProject = InlineTestProjectCreator.code(animalContent, "Animal.java")
                .addFileContents(dogContent, "Dog.java")
                .addFileContents(catContent, "Cat.java")
                .addFileContents(callerContent, "Caller.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            var symbol = "Animal.speak";
            var either = finder.findUsages(symbol).toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            var enclosingMethods =
                    hits.stream().map(h -> h.enclosing().identifier()).collect(Collectors.toSet());

            // Should find usage in callDogSpeak because Dog inherits speak() without overriding
            // Dog should be detected as a polymorphic match
            assertTrue(
                    enclosingMethods.contains("callDogSpeak"),
                    "Expected to find usage in callDogSpeak (Dog inherits speak without overriding); actual: "
                            + enclosingMethods);
        }
    }

    @Test
    public void testOverloadedMethodDoesNotBlockPolymorphicMatch() throws Exception {
        String animalContent =
                """
            public class Animal {
                public void speak() {
                    System.out.println("Animal speaks");
                }
            }
            """;
        String dogContent =
                """
            public class Dog extends Animal {
                // Has an OVERLOAD with different signature, but does NOT override speak()
                public void speak(String message) {
                    System.out.println("Dog says: " + message);
                }
            }
            """;
        String callerContent =
                """
            public class Caller {
                public void callDogSpeak(Dog dog) {
                    dog.speak(); // This calls Animal.speak() via inheritance (not the overload)
                }
            }
            """;

        try (IProject inlineProject = InlineTestProjectCreator.code(animalContent, "Animal.java")
                .addFileContents(dogContent, "Dog.java")
                .addFileContents(callerContent, "Caller.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            var symbol = "Animal.speak";
            var either = finder.findUsages(symbol).toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            var enclosingMethods =
                    hits.stream().map(h -> h.enclosing().identifier()).collect(Collectors.toSet());

            // Dog.speak(String) is an OVERLOAD, not an override of Animal.speak()
            // So Dog should still be a polymorphic match for Animal.speak()
            // and calls to dog.speak() should be found as usages
            assertTrue(
                    enclosingMethods.contains("callDogSpeak"),
                    "Expected to find usage in callDogSpeak (Dog has overload speak(String) but inherits speak()); actual: "
                            + enclosingMethods);
        }
    }

    @Test
    public void testPolymorphicMatchesIncludeTransitiveNonOverridingSubclasses() throws Exception {
        String animalContent =
                """
            public class Animal {
                public void speak() {
                    System.out.println("Animal speaks");
                }
            }
            """;
        String mammalContent =
                """
            public class Mammal extends Animal {
                // Inherits speak
            }
            """;
        String dogContent =
                """
            public class Dog extends Mammal {
                // Inherits speak via Mammal
            }
            """;
        String callerContent =
                """
            public class Caller {
                public void callMammalSpeak(Mammal m) {
                    m.speak();
                }
                public void callDogSpeak(Dog d) {
                    d.speak();
                }
            }
            """;

        try (IProject inlineProject = InlineTestProjectCreator.code(animalContent, "Animal.java")
                .addFileContents(mammalContent, "Mammal.java")
                .addFileContents(dogContent, "Dog.java")
                .addFileContents(callerContent, "Caller.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            var symbol = "Animal.speak";
            var either = finder.findUsages(symbol).toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            var enclosingMethods =
                    hits.stream().map(h -> h.enclosing().identifier()).collect(Collectors.toSet());

            assertTrue(
                    enclosingMethods.contains("callMammalSpeak"),
                    "Expected to find usage in callMammalSpeak; actual: " + enclosingMethods);
            assertTrue(
                    enclosingMethods.contains("callDogSpeak"),
                    "Expected to find usage in callDogSpeak (transitive inheritance); actual: " + enclosingMethods);
        }
    }

    @Test
    public void testFieldUsageExcludesConstructorParameter() throws Exception {
        String channelHolderSource =
                """
                public class ChannelHolder {
                    public String channel;
                }
                """;

        String falsePositiveSource =
                """
                public class FalsePositive {
                    protected FalsePositive(String channel, int bufferSize) {
                        this(channel, bufferSize, 0);
                    }

                    protected FalsePositive(String channel, int bufferSize, int offset) {
                        // usage of parameter 'channel'
                        System.out.println(channel);
                    }
                }
                """;

        String truePositiveSource =
                """
                public class TruePositive {
                    public void access(ChannelHolder holder) {
                        // Actual usage of the field
                        System.out.println(holder.channel);
                    }
                }
                """;

        try (IProject inlineProject = InlineTestProjectCreator.code(channelHolderSource, "ChannelHolder.java")
                .addFileContents(falsePositiveSource, "FalsePositive.java")
                .addFileContents(truePositiveSource, "TruePositive.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            var symbol = "ChannelHolder.channel";
            var either = finder.findUsages(symbol).toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            var files = fileNamesFromHits(hits);

            // Verify FalsePositive.java is NOT included (parameter usage)
            assertFalse(
                    files.contains("FalsePositive.java"),
                    "Should NOT find usage in FalsePositive.java (it's a constructor parameter)");

            // Verify TruePositive.java IS included (actual field access)
            assertTrue(
                    files.contains("TruePositive.java"),
                    "Should find usage in TruePositive.java (actual field access)");

            // Additionally verify we have exactly one hit (the true positive)
            assertEquals(1, hits.size(), "Expected exactly one usage hit");
        }
    }

    @Test
    public void testImplicitConstructorUsageDetection() throws Exception {
        String fooContent = "public class Foo { public void bar() {} }";
        String callerContent =
                """
                public class Caller {
                    public void use() {
                        Foo foo = new Foo();
                        foo.bar();
                    }
                }
                """;

        try (IProject inlineProject = InlineTestProjectCreator.code(fooContent, "Foo.java")
                .addFileContents(callerContent, "Caller.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            // Search for the implicit constructor. JavaAnalyzer synthesizes Foo.Foo for Foo.
            var symbol = "Foo.Foo";
            var result = finder.findUsages(symbol);
            var either = result.toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            var files = fileNamesFromHits(hits);

            // Verify Caller.java is included because it calls 'new Foo()'
            assertTrue(
                    files.contains("Caller.java"),
                    "Should find usage of implicit constructor in Caller.java; found in: " + files);

            // Verify the hit is actually the constructor call
            var hit = hits.stream()
                    .filter(h -> h.file().getFileName().equals("Caller.java"))
                    .findFirst()
                    .orElseThrow();

            assertTrue(hit.snippet().contains("new Foo()"), "Snippet should contain the constructor call");
        }
    }

    @Test
    public void testRecordComponentUsageSearchExcludesSelfReference() throws Exception {
        String recordSource =
                """
                package ai.brokk;
                import org.jetbrains.annotations.Nullable;
                public record TaskEntry(
                        int sequence,
                        @Nullable String log,
                        @Nullable String summary) {
                }
                """;

        String consumerSource =
                """
                package ai.brokk;
                public class TaskConsumer {
                    public void printLog(TaskEntry entry) {
                        // This is a usage of the 'log' component accessor
                        System.out.println(entry.log());
                    }
                }
                """;

        try (IProject inlineProject = InlineTestProjectCreator.code(recordSource, "ai/brokk/TaskEntry.java")
                .addFileContents(consumerSource, "ai/brokk/TaskConsumer.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);
            var finder = FuzzyUsageFinderTestUtil.createForTest(inlineProject, inlineAnalyzer);

            // Search for usages of the record component 'log'
            var symbol = "ai.brokk.TaskEntry.log";
            var either = finder.findUsages(symbol).toEither();

            if (either.hasErrorMessage()) {
                fail("Got failure for " + symbol + " -> " + either.getErrorMessage());
            }

            var hits = either.getUsages();
            assertFalse(hits.isEmpty(), "Expected at least one usage hit for record component 'log'");

            var files = hits.stream().map(h -> h.file().getFileName()).collect(Collectors.toSet());

            // Acceptance: At least one hit comes from the consumer file
            assertTrue(files.contains("TaskConsumer.java"), "Expected usage in TaskConsumer.java; actual: " + files);

            // Acceptance: No hit comes from the record declaration file (TaskEntry.java)
            // This is the core of the regression check for Issue #2604
            assertFalse(
                    files.contains("TaskEntry.java"),
                    "Record component declaration in TaskEntry.java should NOT be detected as a usage; actual: "
                            + files);

            // Verify the snippet in consumer is actually the call
            var consumerHit = hits.stream()
                    .filter(h -> h.file().getFileName().equals("TaskConsumer.java"))
                    .findFirst()
                    .get();
            assertTrue(
                    consumerHit.snippet().contains("entry.log()"),
                    "Snippet should contain the accessor call 'entry.log()'");
        }
    }

    @Test
    public void testFallbackProviderGraphNotEmpty() throws InterruptedException {
        Path root = Path.of(".").toAbsolutePath().normalize();
        ProjectFile file = new ProjectFile(root, "Target.java");
        CodeUnit target = CodeUnit.cls(file, "p", "Target");

        TestAnalyzer analyzer = new TestAnalyzer();
        analyzer.addDeclaration(target);

        RecordingProvider graph = new RecordingProvider(Set.of(file));
        RecordingProvider text = new RecordingProvider(Set.of());

        CandidateFileProvider fallback = UsageFinder.createFallbackProvider(graph, text);
        Set<ProjectFile> result = fallback.findCandidates(target, analyzer);

        assertEquals(Set.of(file), result);
        assertEquals(1, graph.callCount);
        assertEquals(0, text.callCount);
    }

    @Test
    public void testFallbackProviderGraphEmptyAnalyzerNotEmpty() throws InterruptedException {
        Path root = Path.of(".").toAbsolutePath().normalize();
        ProjectFile file = new ProjectFile(root, "Target.java");
        CodeUnit target = CodeUnit.cls(file, "p", "Target");

        TestAnalyzer analyzer = new TestAnalyzer();
        analyzer.addDeclaration(target);

        RecordingProvider graph = new RecordingProvider(Set.of());
        RecordingProvider text = new RecordingProvider(Set.of(file));

        CandidateFileProvider fallback = UsageFinder.createFallbackProvider(graph, text);
        Set<ProjectFile> result = fallback.findCandidates(target, analyzer);

        assertEquals(Set.of(file), result);
        assertEquals(1, graph.callCount);
        assertEquals(1, text.callCount);
    }

    @Test
    public void testFallbackProviderGraphEmptyAnalyzerEmpty() throws InterruptedException {
        Path root = Path.of(".").toAbsolutePath().normalize();
        ProjectFile file = new ProjectFile(root, "Target.java");
        CodeUnit target = CodeUnit.cls(file, "p", "Target");

        TestAnalyzer analyzer = new TestAnalyzer(); // Empty

        RecordingProvider graph = new RecordingProvider(Set.of());
        RecordingProvider text = new RecordingProvider(Set.of(file));

        CandidateFileProvider fallback = UsageFinder.createFallbackProvider(graph, text);
        Set<ProjectFile> result = fallback.findCandidates(target, analyzer);

        assertTrue(result.isEmpty());
        assertEquals(1, graph.callCount);
        assertEquals(0, text.callCount);
    }

    private static class RecordingProvider implements CandidateFileProvider {
        private final Set<ProjectFile> result;
        int callCount = 0;

        RecordingProvider(Set<ProjectFile> result) {
            this.result = result;
        }

        @Override
        public Set<ProjectFile> findCandidates(CodeUnit target, IAnalyzer analyzer) {
            callCount++;
            return result;
        }
    }

    @Test
    public void testImportGraphCandidateProviderWithPolymorphism() throws Exception {
        String baseSource = "package animals; public class Base { public void speak() {} }";
        String dogSource = "package animals; public class Dog extends Base {}";
        String callerSource =
                """
                package zoo;
                import animals.Dog;
                public class Caller {
                    public void callDog(Dog dog) {
                        dog.speak();
                    }
                }
                """;

        try (IProject inlineProject = InlineTestProjectCreator.code(baseSource, "animals/Base.java")
                .addFileContents(dogSource, "animals/Dog.java")
                .addFileContents(callerSource, "zoo/Caller.java")
                .build()) {
            JavaAnalyzer inlineAnalyzer = new JavaAnalyzer(inlineProject);

            // Get the base method CodeUnit
            var definitions = inlineAnalyzer.getDefinitions("animals.Base.speak");
            assertFalse(definitions.isEmpty(), "Base.speak definition should be found");
            CodeUnit target = definitions.getFirst();

            // Use the provider to find candidates
            ImportGraphCandidateProvider provider = new ImportGraphCandidateProvider();
            Set<ProjectFile> candidates = provider.findCandidates(target, inlineAnalyzer);

            Set<String> fileNames =
                    candidates.stream().map(ProjectFile::getFileName).collect(Collectors.toSet());

            // Assert that candidates include the defining file and the file calling via subclass
            assertTrue(fileNames.contains("Base.java"), "Should include Base.java (source); found: " + fileNames);
            assertTrue(
                    fileNames.contains("Caller.java"),
                    "Should include Caller.java (polymorphic call); found: " + fileNames);
        }
    }
}
