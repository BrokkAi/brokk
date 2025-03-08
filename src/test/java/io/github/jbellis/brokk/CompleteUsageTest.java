package io.github.jbellis.brokk;

import org.junit.jupiter.api.Test;
import org.msgpack.core.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.jbellis.brokk.Completions.findClassesForMemberAccess;
import static io.github.jbellis.brokk.Completions.getShortClassName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CompleteUsageTest {

    @VisibleForTesting
    static List<String> completeUsage(String input, IAnalyzer analyzer) {
        return Completions.completeClassesAndMembers(input, analyzer);
    }

    // A simple inline "mock" analyzer: no mocking library used.
    private static class MockAnalyzer implements IAnalyzer {
        private final List<CodeUnit> allClasses = Stream.of(
                "a.b.Do",
                "a.b.Do$Re",
                "a.b.Do$Re$Sub",  // nested inside Re
                "x.y.Zz",
                "w.u.Zz",
                "test.CamelClass"
        ).map(CodeUnit::cls).toList();

        private final Map<String, List<CodeUnit>> methodsMap = Map.of(
                "a.b.Do", Stream.of("a.b.Do.foo", "a.b.Do.bar").map(CodeUnit::fn).toList(),
                "a.b.Do$Re", Stream.of("a.b.Do$Re.baz").map(CodeUnit::fn).toList(),
                "a.b.Do$Re$Sub", Stream.of("a.b.Do$Re$Sub.qux").map(CodeUnit::fn).toList(),
                "x.y.Zz", List.of(),
                "w.u.Zz", List.of(),
                "test.CamelClass", Stream.of("test.CamelClass.someMethod").map(CodeUnit::fn).toList()
        );

        @Override
        public List<CodeUnit> getAllClasses() {
            return allClasses;
        }

        @Override
        public List<CodeUnit> getMembersInClass(String fqClass) {
            return methodsMap.getOrDefault(fqClass, List.of());
        }
    }
    
    // Helper to extract values for easy assertion (now just returns the strings)
    private static Set<String> toValues(List<String> candidates) {
        return new HashSet<>(candidates);
    }

    @Test
    public void testGetShortClassName() {
        assertEquals("C", getShortClassName("a.b.C"));
        assertEquals("C", getShortClassName("a.b.C."));
        assertEquals("C", getShortClassName("C"));
        assertEquals("C$D", getShortClassName("a.b.C$D"));
        assertEquals("C$D", getShortClassName("a.b.C$D."));

        // Extra edge cases
        assertEquals("C$D$E", getShortClassName("a.b.C$D$E"));
        assertEquals("C", getShortClassName("C."));
        assertEquals("$D", getShortClassName("$D"));
        assertEquals("C$D", getShortClassName("C$D"));
    }

    @Test
    public void testUnqualifiedSingleLetter() {
        var mock = new MockAnalyzer();

        // Input "d" -> we want it to match "a.b.Do"
        // Because "Do" simple name starts with 'D'
        var completions = completeUsage("d", mock);

        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do", "a.b.Do$Re", "a.b.Do$Re$Sub"), values);
    }

    @Test
    public void testUnqualifiedR() {
        var mock = new MockAnalyzer();
        // Input "r" -> user wants to find "a.b.Do$Re" by partial name "Re"
        var completions = completeUsage("r", mock);
        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do$Re"), values);
    }

    @Test
    public void testQualifiedDo() {
        var mock = new MockAnalyzer();
        var completions = completeUsage("a.b.Do", mock);
        var values = toValues(completions);
        
        assertEquals(
            Set.of("a.b.Do", "a.b.Do$Re", "a.b.Do$Re$Sub"),
            values
        );
    }

    @Test
    public void testNestedClassRe() {
        var mock = new MockAnalyzer();
        var completions = completeUsage("a.b.Do$Re", mock);
        var values = toValues(completions);

        assertEquals(Set.of("a.b.Do$Re", "a.b.Do$Re$Sub"), values);
    }

    @Test
    public void testCamelCaseCompletion() {
        var mock = new MockAnalyzer();
        // Input "CC" -> should match "test.CamelClass" due to camel case matching
        var completions = completeUsage("CC", mock);
        var values = toValues(completions);
        assertEquals(Set.of("test.CamelClass"), values);

        completions = completeUsage("cam", mock);
        values = toValues(completions);
        assertEquals(Set.of("test.CamelClass"), values);
    }

    @Test
    public void testEmptyInput() {
        var mock = new MockAnalyzer();
        // Input "" => propose everything
        var completions = completeUsage("", mock);
        var values = toValues(completions);
        
        assertEquals(
            Set.of("a.b.Do", "a.b.Do$Re", "a.b.Do$Re$Sub", "x.y.Zz", "w.u.Zz", "test.CamelClass"),
            values
        );
    }

    @Test
    public void testSameShortname() {
        var mock = new MockAnalyzer();
        // Input "Zz" -> should match both "x.y.Zz" and "w.u.Zz"
        var matches = Completions.getClassnameMatches("Zz", mock.getAllClasses().stream().map(CodeUnit::reference).toList());
        assertEquals(Set.of("x.y.Zz", "w.u.Zz"), matches);

        var completions = completeUsage("Zz", mock);
        var values = toValues(completions);
        assertEquals(Set.of("x.y.Zz", "w.u.Zz"), values);
    }
    
    @Test
    public void testDeepPackageHierarchyMatches() {
        var classes = List.of(
            "com.example.deep.package.hierarchy.MyClass",
            "com.example.deep.other.package.OtherClass",
            "org.different.very.deep.structure.TestClass",
            "org.different.very.deep.structure.nested.NestedClass"
        );
        
        // Test simple name match
        var matches = Completions.getClassnameMatches("Test", classes);
        assertEquals(Set.of("org.different.very.deep.structure.TestClass"), matches);
        
        // Test camel case match in deep hierarchy
        matches = Completions.getClassnameMatches("NC", classes);
        assertEquals(Set.of("org.different.very.deep.structure.nested.NestedClass"), matches);
        
        // Test exact class name match regardless of package depth
        matches = Completions.getClassnameMatches("MyClass", classes);
        assertEquals(Set.of("com.example.deep.package.hierarchy.MyClass"), matches);
    }

    @Test
    public void testMultipleMatchesInDifferentPackages() {
        var classes = List.of(
            "com.example.util.Handler",
            "com.example.core.Handler",
            "com.example.web.Handler",
            "org.other.Handler"
        );
        
        var matches = Completions.getClassnameMatches("Handler", classes);
        assertEquals(4, matches.size());
        assertTrue(matches.containsAll(classes));
    }

    @Test 
    public void testCamelCaseMatchingInDeepHierarchy() {
        var classes = List.of(
            "com.example.deep.AbstractBaseController",
            "com.example.deeper.BaseController",
            "com.example.deepest.SimpleBaseController",
            "org.other.NotAController"
        );
        
        // Test "ABC" matching only AbstractBaseController
        var matches = Completions.getClassnameMatches("ABC", classes);
        assertEquals(1, matches.size());
        assertTrue(matches.contains("com.example.deep.AbstractBaseController"));
        
        // Test "SBC" matching only SimpleBaseController
        matches = Completions.getClassnameMatches("SBC", classes);
        assertEquals(1, matches.size());
        assertTrue(matches.contains("com.example.deepest.SimpleBaseController"));
    }

    @Test
    public void testClassOnlyCompletions() {
        var mock = new MockAnalyzer();
        
        // Test class-only completions (no dot or $ in input)
        var completions = Completions.completeClassesAndMembers("d", mock);
        var values = toValues(completions);
        assertEquals(Set.of("Do", "Do$Re", "Do$Re$Sub"), values);

        // should match itself
        var matches = Completions.getClassnameMatches("Do", List.of("Do", "Re"));
        assertEquals(Set.of("Do"), new HashSet<>(matches));
    }

    @Test
    public void testShortNameCompletions() {
        var mock = new MockAnalyzer();

        var completions = Completions.completeClassesAndMembers("d", mock);
        // spelling out the classname doesn't change things
        assertEquals(completions, Completions.completeClassesAndMembers("Do", mock));
        assertEquals(Set.of("Do", "Do$Re", "Do$Re$Sub"), toValues(completions));

        completions = Completions.completeClassesAndMembers("Do.", mock);
        assertEquals(Set.of("a.b.Do", "Do.foo", "Do.bar"), toValues(completions));

        completions = Completions.completeClassesAndMembers("Do.", mock);
        assertEquals(Set.of("a.b.Do", "Do.foo", "Do.bar"), toValues(completions));
    }

    //
    // getMatchingFQCNs tests
    //
    private final List<String> allClasses = List.of("a.b.Do", "a.b.Do$Re", "a.b.Do$Re$Sub", "d.Do");

    @Test
    void testEmptyClass() {
        assertTrue(findClassesForMemberAccess("", allClasses).isEmpty());
    }

    @Test
    void testNullInput() {
        assertTrue(findClassesForMemberAccess(null, allClasses).isEmpty());
    }

    @Test
    void testPackageOnly() {
        assertEquals(Set.of(), findClassesForMemberAccess("a", allClasses));
        assertEquals(Set.of(), findClassesForMemberAccess("a.b", allClasses));
    }

    @Test
    void testFullClassName() {
        assertTrue(findClassesForMemberAccess("a.b.Do", allClasses).isEmpty());
    }

    @Test
    void testClassNameWithDot() {
        Set<String> expected = Set.of("a.b.Do", "d.Do");
        assertEquals(expected, findClassesForMemberAccess("Do.", allClasses));
    }

    @Test
    void testFullClassNameWithDot() {
        Set<String> expected = Set.of("a.b.Do");
        assertEquals(expected, findClassesForMemberAccess("a.b.Do.", allClasses));
    }

    @Test
    void testClassWithMemberName() {
        Set<String> expected = Set.of("a.b.Do");
        assertEquals(expected, findClassesForMemberAccess("a.b.Do.foo", allClasses));
    }

    @Test
    void testInnerClassReference() {
        assertEquals(Set.of(), findClassesForMemberAccess("Do$Re", allClasses));
        assertEquals(Set.of("a.b.Do$Re$Sub"), findClassesForMemberAccess("Do$Re$Sub", allClasses));
    }

    @Test
    void testInnerClassWithDot() {
        assertEquals(Set.of("a.b.Do$Re"), findClassesForMemberAccess("Do$Re.", allClasses));
    }

    @Test
    void testNonExistentClass() {
        assertTrue(findClassesForMemberAccess("foo", allClasses).isEmpty());
    }

    @Test
    void testClassNameWithMember() {
        Set<String> expected = Set.of("a.b.Do", "d.Do");
        assertEquals(expected, findClassesForMemberAccess("Do.foo", allClasses));
    }
}
