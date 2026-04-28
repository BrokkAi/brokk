package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.InlineCoreProject;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LongMethodAndGodObjectSmellTest {

    @Test
    void flagsLongMethodWithRangeAndRationale() {
        String source =
                """
                package com.example;
                public class Workflow {
                    public void generatedWorkflow() {
                %s
                    }
                }
                """
                        .formatted(statements(85));

        try (var project =
                InlineCoreProject.code(source, "com/example/Workflow.java").build()) {
            var smells =
                    project.getAnalyzer().findLongMethodAndGodObjectSmells(project.file("com/example/Workflow.java"));

            var longMethod = smells.stream()
                    .filter(smell -> smell.codeUnit().fqName().equals("com.example.Workflow.generatedWorkflow"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(longMethod.ownSpanLines() >= 80, longMethod.toString());
            assertFalse(longMethod.range().isEmpty(), longMethod.toString());
            assertTrue(
                    longMethod.reasons().stream().anyMatch(reason -> reason.contains("long function")),
                    longMethod.toString());
        }
    }

    @Test
    void flagsGodObjectAndHelperSprawl() {
        String source =
                """
                package com.example;
                public class GeneratedController {
                    public void executeWorkflow() {
                %s
                    }
                %s
                }
                """
                        .formatted(statements(65), helpers(16));

        try (var project = InlineCoreProject.code(source, "com/example/GeneratedController.java")
                .build()) {
            var smells = project.getAnalyzer()
                    .findLongMethodAndGodObjectSmells(project.file("com/example/GeneratedController.java"));

            var godObject = smells.stream()
                    .filter(smell -> smell.codeUnit().fqName().equals("com.example.GeneratedController"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(godObject.functionCount() >= 17, godObject.toString());
            assertTrue(godObject.directChildCount() >= 17, godObject.toString());
            assertTrue(godObject.maxFunctionSpanLines() >= 60, godObject.toString());
            assertTrue(
                    godObject.reasons().stream().anyMatch(reason -> reason.contains("helper sprawl")),
                    godObject.toString());
            assertEquals(godObject, smells.getFirst(), "god object should rank above its workflow helper");
        }
    }

    @Test
    void ignoresSmallCohesiveFile() {
        String source =
                """
                package com.example;
                public class Small {
                    public int add(int left, int right) {
                        return left + right;
                    }
                }
                """;

        try (var project =
                InlineCoreProject.code(source, "com/example/Small.java").build()) {
            var smells = project.getAnalyzer().findLongMethodAndGodObjectSmells(project.file("com/example/Small.java"));

            assertTrue(smells.isEmpty(), smells.toString());
        }
    }

    @Test
    void ignoresSyntheticConstructorAtThresholdBoundary() {
        String source =
                """
                package com.example;
                public class Boundary {
                %s
                }
                """
                        .formatted(helpers(14));
        var weights = new IAnalyzer.MaintainabilitySizeSmellWeights(
                999, // Disable long-method scoring.
                999, // Disable high-complexity scoring.
                999, // Disable span scoring.
                15, // Would trip if the synthetic constructor counted as a direct child.
                15, // Would trip if the synthetic constructor counted as a function.
                999, // Disable helper-sprawl scoring.
                999, 999);

        try (var project =
                InlineCoreProject.code(source, "com/example/Boundary.java").build()) {
            var smells = project.getAnalyzer()
                    .findLongMethodAndGodObjectSmells(project.file("com/example/Boundary.java"), weights);

            assertTrue(smells.isEmpty(), smells.toString());
        }
    }

    @Test
    void customWeightsCanLowerAndRaiseThresholds() {
        String source =
                """
                package com.example;
                public class Tunable {
                    public void smallerWorkflow() {
                %s
                    }
                }
                """
                        .formatted(statements(12));
        var permissive = new IAnalyzer.MaintainabilitySizeSmellWeights(10, 999, 999, 999, 999, 999, 999, 1);
        var strict = new IAnalyzer.MaintainabilitySizeSmellWeights(200, 999, 999, 999, 999, 999, 999, 1);

        try (var project =
                InlineCoreProject.code(source, "com/example/Tunable.java").build()) {
            var file = project.file("com/example/Tunable.java");

            assertFalse(project.getAnalyzer()
                    .findLongMethodAndGodObjectSmells(file, permissive)
                    .isEmpty());
            assertTrue(project.getAnalyzer()
                    .findLongMethodAndGodObjectSmells(file, strict)
                    .isEmpty());
        }
    }

    @Test
    void fileLevelModulePredicateAcceptsOnlyTopLevelModulesForOptInLanguages() {
        assertFileLevelModulePredicate(Languages.JAVASCRIPT, "src/module.js", "src/wrong.java");
        assertFileLevelModulePredicate(Languages.TYPESCRIPT, "src/module.ts", "src/wrong.java");
        assertFileLevelModulePredicate(Languages.PYTHON, "src/module.py", "src/wrong.java");
        assertFileLevelModulePredicate(Languages.C_CPP, "src/module.cpp", "src/wrong.java");
        assertFileLevelModulePredicate(Languages.GO, "src/module.go", "src/wrong.java");
        assertFileLevelModulePredicate(Languages.RUST, "src/module.rs", "src/wrong.java");
    }

    @Test
    void defaultFileLevelModulePredicateRejectsJavaModules() {
        try (var project = InlineCoreProject.empty()
                .languages(Set.of(Languages.JAVA))
                .addFile("package com.example; public class Sample {}\n", "src/Sample.java")
                .build()) {
            var analyzer = project.getAnalyzer();
            var module = CodeUnit.module(project.file("src/Sample.java"), "com.example", "Sample.java");

            assertFalse(analyzer.isFileLevelModule(module, true));
        }
    }

    @Test
    void fileLevelModulePredicateRejectsNestedCppNamespace() {
        String source =
                """
                namespace outer {
                namespace inner {
                int value = 1;
                }
                }
                """;

        try (var project = InlineCoreProject.empty()
                .languages(Set.of(Languages.C_CPP))
                .addFile(source, "src/nested.cpp")
                .build()) {
            var analyzer = project.getAnalyzer();
            var outer = analyzer.getTopLevelDeclarations(project.file("src/nested.cpp")).stream()
                    .filter(CodeUnit::isModule)
                    .filter(cu -> cu.identifier().equals("outer"))
                    .findFirst()
                    .orElseThrow();
            var inner = analyzer.getDirectChildren(outer).stream()
                    .filter(CodeUnit::isModule)
                    .filter(cu -> cu.identifier().equals("inner"))
                    .findFirst()
                    .orElseThrow();

            assertTrue(analyzer.isFileLevelModule(outer, true));
            assertTrue(analyzer.parentOf(inner).isPresent());
            assertFalse(analyzer.isFileLevelModule(inner, false));
            assertFalse(analyzer.isFileLevelModule(inner, true));
        }
    }

    @Test
    void fileLevelModulePredicateRejectsNestedRustModule() {
        String source =
                """
                mod outer {
                    mod inner {
                        pub const VALUE: i32 = 1;
                    }
                }
                """;

        try (var project = InlineCoreProject.empty()
                .languages(Set.of(Languages.RUST))
                .addFile(source, "src/nested.rs")
                .build()) {
            var analyzer = project.getAnalyzer();
            var outer = analyzer.getTopLevelDeclarations(project.file("src/nested.rs")).stream()
                    .filter(CodeUnit::isModule)
                    .filter(cu -> cu.identifier().equals("outer"))
                    .findFirst()
                    .orElseThrow();
            var nested = analyzer.getTopLevelDeclarations(project.file("src/nested.rs")).stream()
                    .flatMap(cu -> nestedCodeUnits(analyzer, cu).stream())
                    .toList();
            var inner = nested.stream()
                    .filter(cu -> !cu.equals(outer))
                    .filter(CodeUnit::isModule)
                    .filter(cu -> cu.fqName().contains("inner"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(nested.toString()));

            assertTrue(analyzer.isFileLevelModule(outer, true));
            assertTrue(analyzer.parentOf(inner).isPresent());
            assertFalse(analyzer.isFileLevelModule(inner, false));
            assertFalse(analyzer.isFileLevelModule(inner, true));
        }
    }

    @Test
    void javascriptModuleGetsFileLevelSizeLeewayButStillFlagsVeryLargeFiles() {
        String moderateModule =
                """
                import { dep } from "./dep.js";
                export const values = [
                %s
                ];
                """
                        .formatted(arrayElements(350));
        String oversizedModule =
                """
                import { dep } from "./dep.js";
                export const values = [
                %s
                ];
                """
                        .formatted(arrayElements(650));
        String depModule = "export const dep = 1;\n";

        try (var project = InlineCoreProject.empty()
                .addFile(depModule, "src/dep.js")
                .addFile(moderateModule, "src/moderate.js")
                .addFile(oversizedModule, "src/oversized.js")
                .build()) {
            var analyzer = project.getAnalyzer();

            var moderateSmells = analyzer.findLongMethodAndGodObjectSmells(project.file("src/moderate.js"));
            var oversizedSmells = analyzer.findLongMethodAndGodObjectSmells(project.file("src/oversized.js"));

            assertTrue(
                    moderateSmells.stream().noneMatch(smell -> smell.codeUnit().isModule()), moderateSmells.toString());
            assertTrue(
                    oversizedSmells.stream().anyMatch(smell -> smell.codeUnit().isModule()),
                    oversizedSmells.toString());
        }
    }

    @Test
    void pythonFileModuleGetsFileLevelSizeLeewayButStillFlagsVeryLargeFiles() {
        String moderateModule = """
                VALUES = [
                %s
                ]
                """
                .formatted(arrayElements(350));
        String oversizedModule = """
                VALUES = [
                %s
                ]
                """
                .formatted(arrayElements(650));

        try (var project = InlineCoreProject.empty()
                .addFile(moderateModule, "src/moderate.py")
                .addFile(oversizedModule, "src/oversized.py")
                .build()) {
            var analyzer = project.getAnalyzer();

            var moderateSmells = analyzer.findLongMethodAndGodObjectSmells(project.file("src/moderate.py"));
            var oversizedSmells = analyzer.findLongMethodAndGodObjectSmells(project.file("src/oversized.py"));

            assertTrue(
                    moderateSmells.stream().noneMatch(smell -> smell.codeUnit().isModule()), moderateSmells.toString());
            assertTrue(
                    oversizedSmells.stream().anyMatch(smell -> smell.codeUnit().isModule()),
                    oversizedSmells.toString());
        }
    }

    @Test
    void cppTopLevelNamespaceGetsLeewayButStillFlagsVeryLargeNamespaces() {
        String moderateNamespace =
                """
                namespace generated {
                int values[] = {
                %s
                };
                }
                """
                        .formatted(arrayElements(350));
        String oversizedNamespace =
                """
                namespace generated {
                int values[] = {
                %s
                };
                }
                """
                        .formatted(arrayElements(650));

        try (var project = InlineCoreProject.empty()
                .addFile(moderateNamespace, "src/moderate.cpp")
                .addFile(oversizedNamespace, "src/oversized.cpp")
                .build()) {
            var analyzer = project.getAnalyzer();

            var moderateSmells = analyzer.findLongMethodAndGodObjectSmells(project.file("src/moderate.cpp"));
            var oversizedSmells = analyzer.findLongMethodAndGodObjectSmells(project.file("src/oversized.cpp"));

            assertTrue(
                    moderateSmells.stream().noneMatch(smell -> smell.codeUnit().isModule()), moderateSmells.toString());
            assertTrue(
                    oversizedSmells.stream().anyMatch(smell -> smell.codeUnit().isModule()),
                    oversizedSmells.toString());
        }
    }

    @Test
    void rustTopLevelModuleGetsLeewayButNestedModuleDoesNot() {
        String topLevelModerate =
                """
                mod generated {
                    pub const VALUES: &[i32] = &[
                %s
                    ];
                }
                """
                        .formatted(arrayElements(350));
        String topLevelOversized =
                """
                mod generated {
                    pub const VALUES: &[i32] = &[
                %s
                    ];
                }
                """
                        .formatted(arrayElements(650));
        String nestedModerate =
                """
                mod outer {
                    mod inner {
                        pub const VALUES: &[i32] = &[
                %s
                        ];
                    }
                }
                """
                        .formatted(arrayElements(350));

        try (var project = InlineCoreProject.empty()
                .addFile(topLevelModerate, "src/moderate.rs")
                .addFile(topLevelOversized, "src/oversized.rs")
                .addFile(nestedModerate, "src/nested.rs")
                .build()) {
            var analyzer = project.getAnalyzer();

            var moderateSmells = analyzer.findLongMethodAndGodObjectSmells(project.file("src/moderate.rs"));
            var oversizedSmells = analyzer.findLongMethodAndGodObjectSmells(project.file("src/oversized.rs"));
            var nestedSmells = analyzer.findLongMethodAndGodObjectSmells(project.file("src/nested.rs"));

            assertTrue(
                    moderateSmells.stream().noneMatch(smell -> smell.codeUnit().isModule()), moderateSmells.toString());
            assertTrue(
                    oversizedSmells.stream().anyMatch(smell -> smell.codeUnit().isModule()),
                    oversizedSmells.toString());
            assertTrue(
                    nestedSmells.stream()
                            .anyMatch(smell -> smell.codeUnit().fqName().contains("inner")),
                    nestedSmells.toString());
        }
    }

    private static String statements(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "        int value" + i + " = " + i + ";")
                .collect(Collectors.joining("\n"));
    }

    private static String helpers(int count) {
        return IntStream.range(0, count)
                .mapToObj(i ->
                        """
                            private int helper%s(int value) {
                                return value + %s;
                            }
                        """
                                .formatted(i, i))
                .collect(Collectors.joining("\n"));
    }

    private static String arrayElements(int count) {
        return IntStream.range(0, count).mapToObj(i -> "    " + i + ",").collect(Collectors.joining("\n"));
    }

    private static java.util.List<CodeUnit> nestedCodeUnits(IAnalyzer analyzer, CodeUnit root) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(root),
                        analyzer.getDirectChildren(root).stream()
                                .flatMap(child -> nestedCodeUnits(analyzer, child).stream()))
                .toList();
    }

    private static void assertFileLevelModulePredicate(
            Language language, String modulePath, String wrongExtensionPath) {
        try (var project = InlineCoreProject.empty()
                .languages(Set.of(language))
                .addFile("", modulePath)
                .addFile("", wrongExtensionPath)
                .build()) {
            var analyzer = project.getAnalyzer();
            var module = CodeUnit.module(project.file(modulePath), "", "module");
            var function = CodeUnit.fn(project.file(modulePath), "", "function");
            var wrongExtensionModule = CodeUnit.module(project.file(wrongExtensionPath), "", "module");

            assertTrue(analyzer.isFileLevelModule(module, true), language.internalName());
            assertFalse(analyzer.isFileLevelModule(module, false), language.internalName());
            assertFalse(analyzer.isFileLevelModule(function, true), language.internalName());
            assertFalse(analyzer.isFileLevelModule(wrongExtensionModule, true), language.internalName());
        }
    }
}
