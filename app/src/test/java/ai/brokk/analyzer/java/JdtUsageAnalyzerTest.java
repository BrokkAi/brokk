package ai.brokk.analyzer.java;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class JdtUsageAnalyzerTest {

    @Test
    public void testBasicUsages() throws Exception {
        String targetSource =
                """
                package com.example;
                public class Target {
                    public String field;
                    public Target() {}
                    public void method() {}
                }
                """;

        String consumerSource =
                """
                package com.consumer;
                import com.example.Target;
                public class Consumer {
                    private Target t; // Type reference
                    public void use() {
                        t = new Target(); // Constructor
                        t.field = "foo";  // Field store
                        System.out.println(t.field); // Field load
                        t.method(); // Method call
                    }
                }
                """;

        try (IProject project = InlineTestProjectCreator.code(targetSource, "com/example/Target.java")
                .addFileContents(consumerSource, "com/consumer/Consumer.java")
                .build()) {

            ProjectFile targetFile = project.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().contains("Target.java"))
                    .findFirst()
                    .orElseThrow();

            Set<ProjectFile> candidates = project.getAllFiles();

            // 1. Verify Method Usages
            CodeUnit methodTarget = new CodeUnit(targetFile, CodeUnitType.FUNCTION, "com.example", "Target.method");
            Set<UsageHit> methodHits = JdtUsageAnalyzer.findUsages(methodTarget, candidates, project);
            assertEquals(1, methodHits.size(), "Should find 1 method usage");
            assertTrue(methodHits.iterator().next().snippet().contains("t.method()"));

            // 2. Verify Field Usages (Loads and Stores)
            CodeUnit fieldTarget = new CodeUnit(targetFile, CodeUnitType.FIELD, "com.example", "Target.field");
            Set<UsageHit> fieldHits = JdtUsageAnalyzer.findUsages(fieldTarget, candidates, project);
            assertEquals(2, fieldHits.size(), "Should find 2 field usages (load + store)");

            // 3. Verify Constructor Usages
            CodeUnit constructorTarget =
                    new CodeUnit(targetFile, CodeUnitType.FUNCTION, "com.example", "Target.Target");
            Set<UsageHit> constructorHits = JdtUsageAnalyzer.findUsages(constructorTarget, candidates, project);
            assertEquals(1, constructorHits.size(), "Should find 1 constructor usage");
            assertTrue(constructorHits.iterator().next().snippet().contains("new Target()"));

            // 4. Verify Type References (Explicit)
            CodeUnit classTarget = new CodeUnit(targetFile, CodeUnitType.CLASS, "com.example", "Target");
            Set<UsageHit> classHits = JdtUsageAnalyzer.findUsages(classTarget, candidates, project);
            // Hits expected in Consumer.java:
            // - private Target t;
            // - new Target(); (SimpleName resolution)
            assertTrue(
                    classHits.stream().anyMatch(h -> h.snippet().contains("private Target t")),
                    "Should find type reference in field declaration");
            assertTrue(
                    classHits.stream().anyMatch(h -> h.snippet().contains("new Target()")),
                    "Should find type reference in constructor call");
        }
    }

    @Test
    public void testLambdaAndAnonymousAttribution() throws Exception {
        String source =
                """
                package com.example;
                public class Target {
                    public void run() {}
                }
                """;
        String consumer =
                """
                package com.example;
                public class Consumer {
                    public void test() {
                        Runnable r = () -> {
                            new Target().run();
                        };
                        r.run();
                    }
                }
                """;

        try (IProject project = InlineTestProjectCreator.code(source, "com/example/Target.java")
                .addFileContents(consumer, "com/example/Consumer.java")
                .build()) {

            ProjectFile targetFile = project.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().contains("Target.java"))
                    .findFirst()
                    .orElseThrow();

            CodeUnit targetUnit = new CodeUnit(targetFile, CodeUnitType.FUNCTION, "com.example", "Target.run");
            Set<UsageHit> hits = JdtUsageAnalyzer.findUsages(targetUnit, project.getAllFiles(), project);

            assertEquals(1, hits.size());
            CodeUnit enclosing = hits.iterator().next().enclosing();
            // JDT resolution for enclosing might vary, but it should be within 'test'
            assertTrue(enclosing.fqName().contains("test"), "Enclosing unit should be attributed to 'test' method");
        }
    }

    @Test
    public void testUsageOutsideMethodOrType() throws Exception {
        // Test that usages in field initializers (outside a specific method) still work
        // or are handled gracefully according to the new logic.
        String source = "package com.example; public class Target { public static int VAL = 1; }";
        String consumer =
                """
                package com.example;
                public class FieldConsumer {
                    private int external = Target.VAL;
                }
                """;

        try (IProject project = InlineTestProjectCreator.code(source, "com/example/Target.java")
                .addFileContents(consumer, "com/example/FieldConsumer.java")
                .build()) {

            ProjectFile targetFile = project.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().contains("Target.java"))
                    .findFirst()
                    .orElseThrow();

            CodeUnit targetUnit = new CodeUnit(targetFile, CodeUnitType.FIELD, "com.example", "Target.VAL");
            Set<UsageHit> hits = JdtUsageAnalyzer.findUsages(targetUnit, project.getAllFiles(), project);

            // The usage is inside FieldConsumer (TypeDeclaration), so it should have an enclosing context.
            assertFalse(hits.isEmpty(), "Should find usage in field initializer");
            assertEquals(
                    "com.example.FieldConsumer",
                    hits.iterator().next().enclosing().fqName());
        }
    }

    @Test
    public void testAnonymousClassUsage() throws Exception {
        String source = "package com.example; public class Base { public void doWork() {} }";
        String consumer =
                """
                package com.example;
                public class AnonConsumer {
                    public void execute() {
                        Base b = new Base() {
                            @Override
                            public void doWork() {
                                super.doWork();
                            }
                        };
                        b.doWork();
                    }
                }
                """;

        try (IProject project = InlineTestProjectCreator.code(source, "com/example/Base.java")
                .addFileContents(consumer, "com/example/AnonConsumer.java")
                .build()) {

            ProjectFile baseFile = project.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().contains("Base.java"))
                    .findFirst()
                    .orElseThrow();

            CodeUnit target = new CodeUnit(baseFile, CodeUnitType.FUNCTION, "com.example", "Base.doWork");
            Set<UsageHit> hits = JdtUsageAnalyzer.findUsages(target, project.getAllFiles(), project);

            // Expect 2 usages: super.doWork() inside anon class, and b.doWork() in execute()
            assertEquals(2, hits.size(), "Should find 2 usages of doWork()");
        }
    }

    @Test
    public void testExclusionOfSelfAndImports() throws Exception {
        String source =
                """
                package com.example;
                import com.example.Target; // Explicit import of self
                import java.util.List;

                public class Target {
                    public void self() {
                        self(); // This is a usage
                    }
                }
                """;

        try (IProject project =
                InlineTestProjectCreator.code(source, "com/example/Target.java").build()) {
            ProjectFile targetFile = project.getAllFiles().iterator().next();

            // 1. Verify method self-call is found (logic in FuzzyUsageFinder filters it out, not JDT)
            CodeUnit targetUnit = new CodeUnit(targetFile, CodeUnitType.FUNCTION, "com.example", "Target.self");
            Set<UsageHit> hits = JdtUsageAnalyzer.findUsages(targetUnit, project.getAllFiles(), project);
            assertEquals(1, hits.size(), "Should find recursive call");

            // 2. Verify import and declaration are NOT hits for class usage
            CodeUnit classTarget = new CodeUnit(targetFile, CodeUnitType.CLASS, "com.example", "Target");
            Set<UsageHit> classHits = JdtUsageAnalyzer.findUsages(classTarget, project.getAllFiles(), project);

            // JDT Visitor logic skips isDeclaration() and ImportDeclaration nodes
            assertTrue(classHits.isEmpty(), "Imports and Declarations should not be usage hits");
        }
    }

    @Test
    public void testResolutionWithPartialCandidateSet() throws Exception {
        // This is the CRITICAL test: can JDT resolve Target in Consumer.java
        // if Target.java is in the environment (sourceRoots) but NOT in the candidateFiles?

        String targetSource =
                """
                package com.example;
                public class Target {
                    public void action() {}
                }
                """;

        String dependencySource =
                """
                package com.lib;
                import com.example.Target;
                public class Library {
                    public static void exec(Target t) { t.action(); }
                }
                """;

        String consumerSource =
                """
                package com.app;
                import com.example.Target;
                import com.lib.Library;
                public class Main {
                    public void run() {
                        Library.exec(new Target());
                    }
                }
                """;

        try (IProject project = InlineTestProjectCreator.code(targetSource, "com/example/Target.java")
                .addFileContents(dependencySource, "com/lib/Library.java")
                .addFileContents(consumerSource, "com/app/Main.java")
                .build()) {

            ProjectFile targetFile = project.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().contains("Target.java"))
                    .findFirst()
                    .orElseThrow();
            ProjectFile consumerFile = project.getAllFiles().stream()
                    .filter(f -> f.getRelPath().toString().contains("Main.java"))
                    .findFirst()
                    .orElseThrow();

            CodeUnit targetUnit = new CodeUnit(targetFile, CodeUnitType.FUNCTION, "com.example", "Target.action");

            // We ONLY analyze Main.java, but it calls Library.exec which takes Target.
            // JDT must be able to see Target.java and Library.java in sourceRoots to resolve the call in Main.java.
            Set<ProjectFile> candidates = Set.of(consumerFile);

            Set<UsageHit> hits = JdtUsageAnalyzer.findUsages(targetUnit, candidates, project);

            // JDT should NOT find the usage of Target.action in Library.java because Library.java is not a candidate.
            // But it MUST find the usage of Target's constructor in Main.java if searching for the class.

            CodeUnit classTarget = new CodeUnit(targetFile, CodeUnitType.CLASS, "com.example", "Target");
            Set<UsageHit> classHits = JdtUsageAnalyzer.findUsages(classTarget, candidates, project);

            assertFalse(
                    classHits.isEmpty(), "Should find Target usage in Main.java even if Target.java isn't a candidate");
            assertTrue(
                    classHits.stream().anyMatch(h -> h.file().equals(consumerFile)), "Hit should be in Consumer file");

            // Verify that we find the usage of Library.exec even though Library.java wasn't a candidate
            // but was required for resolution.
            CodeUnit libraryExec = new CodeUnit(
                    project.getAllFiles().stream()
                            .filter(f -> f.getRelPath().toString().contains("Library.java"))
                            .findFirst()
                            .get(),
                    CodeUnitType.FUNCTION,
                    "com.lib",
                    "Library.exec");

            Set<UsageHit> libraryHits = JdtUsageAnalyzer.findUsages(libraryExec, candidates, project);
            assertFalse(libraryHits.isEmpty(), "Should find Library.exec usage in Main.java");
        }
    }

    @Test
    public void testTypeReferenceInFieldDeclaration() throws Exception {
        String source =
                """
                package com.example;
                public class Target {
                    public static final Target INSTANCE = new Target();
                }
                """;

        try (IProject project =
                InlineTestProjectCreator.code(source, "com/example/Target.java").build()) {
            ProjectFile targetFile = project.getAllFiles().iterator().next();

            // Search for type usages of Target
            CodeUnit classTarget = new CodeUnit(targetFile, CodeUnitType.CLASS, "com.example", "Target");
            Set<UsageHit> hits = JdtUsageAnalyzer.findUsages(classTarget, project.getAllFiles(), project);

            // Should find the type reference on LHS of field declaration: "Target INSTANCE"
            // Note: constructor call "new Target()" is also a hit via ClassInstanceCreation visitor
            assertTrue(
                    hits.stream().anyMatch(h -> h.snippet().contains("Target INSTANCE")),
                    "Should find type reference in field declaration LHS");
        }
    }

    @Test
    public void testSignatureConsistencyWithJavaAnalyzer() throws Exception {
        String source =
                """
            package com.example;
            import java.util.List;
            import java.util.Map;

            public class OverloadedMethods {
                public void noArgs() {}
                public void singleArg(String s) {}
                public void multiArg(String s, int i) {}
                public void process() {}
                public void process(String s) {}
                public void process(String s, int i) {}
                public void genericErased(List<String> list) {}
                public void mapParam(Map<String, Integer> map) {}
                public OverloadedMethods() {}
                public OverloadedMethods(String name) {}
            }
            """;

        try (IProject project = InlineTestProjectCreator.code(source, "com/example/OverloadedMethods.java")
                .build()) {

            // Get signatures from JavaAnalyzer (Tree-sitter based)
            JavaAnalyzer javaAnalyzer = new JavaAnalyzer(project);
            Set<String> treeSitterFqNames = javaAnalyzer.getAllDeclarations().stream()
                    .filter(CodeUnit::isFunction)
                    .map(cu -> cu.fqName() + (cu.signature() != null ? cu.signature() : ""))
                    .collect(Collectors.toSet());

            // Get signatures from JDT
            Set<String> jdtFqNames = JdtUsageAnalyzer.extractMethodSignatures(source, project);

            // They should match
            assertEquals(
                    treeSitterFqNames,
                    jdtFqNames,
                    "JDT and JavaAnalyzer should produce identical method FQ names with signatures");
        }
    }
}
