package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.ICoreProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.junit.jupiter.api.Test;

class RegexUsageAnalyzerTest {

    @Test
    void multipleTargetsScannedTogetherMatchIndividualResults() throws Exception {
        String targetCode =
                """
                package com.example;
                public class Target {
                    public void action() {}
                    public void action(int value) {}
                }
                """;
        String consumerCode =
                """
                package com.example;
                public class Consumer {
                    public void run(Target target) {
                        target.action();
                        target.action(1);
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.code(targetCode, "com/example/Target.java")
                .addFileContents(consumerCode, "com/example/Consumer.java")
                .build()) {
            var analyzer = new JavaAnalyzer(project);
            var usageAnalyzer = new RegexUsageAnalyzer(analyzer);
            var overloads = List.copyOf(analyzer.getDefinitions("com.example.Target.action"));
            assertEquals(2, overloads.size());
            var noArg = overloads.get(0);
            var intArg = overloads.get(1);
            var consumerFile = projectFile(project.getAllFiles(), "com/example/Consumer.java");

            var batched = assertInstanceOf(
                    FuzzyResult.Ambiguous.class, usageAnalyzer.findUsages(overloads, Set.of(consumerFile), 100));
            var noArgOnly = assertInstanceOf(
                    FuzzyResult.Ambiguous.class, usageAnalyzer.findUsages(List.of(noArg), Set.of(consumerFile), 100));
            var intArgOnly = assertInstanceOf(
                    FuzzyResult.Ambiguous.class, usageAnalyzer.findUsages(List.of(intArg), Set.of(consumerFile), 100));

            assertEquals(
                    noArgOnly.hitsByOverload().get(noArg),
                    batched.hitsByOverload().get(noArg));
            assertEquals(
                    intArgOnly.hitsByOverload().get(intArg),
                    batched.hitsByOverload().get(intArg));
        }
    }

    @Test
    void asciiContentReportsCharacterOffsets() throws Exception {
        String targetCode =
                """
                package com.example;
                public class Target {}
                """;
        String consumerCode =
                """
                package com.example;
                public class Consumer {
                    private Target target;
                }
                """;

        try (var project = InlineTestProjectCreator.code(targetCode, "com/example/Target.java")
                .addFileContents(consumerCode, "com/example/Consumer.java")
                .build()) {
            var analyzer = new JavaAnalyzer(project);
            var target = singleDefinition(analyzer, "com.example.Target");
            var consumerFile = projectFile(project.getAllFiles(), "com/example/Consumer.java");

            var result = assertInstanceOf(
                    FuzzyResult.Success.class,
                    new RegexUsageAnalyzer(analyzer).findUsages(List.of(target), Set.of(consumerFile), 100));

            var hit = result.hitsByOverload().get(target).iterator().next();
            assertEquals(consumerCode.indexOf("Target"), hit.startOffset());
            assertEquals("Target target;", consumerCode.substring(hit.startOffset(), hit.endOffset()));
        }
    }

    @Test
    void nonAsciiContentPreservesUtf8ByteOffsetsForAccessChecks() throws Exception {
        String targetCode =
                """
                package com.example;
                public class Target {}
                """;
        String consumerCode =
                """
                package com.example;
                public class Consumer {
                    String label = "cafe \u00e9 \uD83D\uDE80";
                    private Target target;
                }
                """;

        try (var project = InlineTestProjectCreator.code(targetCode, "com/example/Target.java")
                .addFileContents(consumerCode, "com/example/Consumer.java")
                .build()) {
            var analyzer = new RecordingJavaAnalyzer(project);
            var target = singleDefinition(analyzer, "com.example.Target");
            var consumerFile = projectFile(project.getAllFiles(), "com/example/Consumer.java");
            int charStart = consumerCode.indexOf("Target");
            int expectedByteStart = consumerCode.substring(0, charStart).getBytes(StandardCharsets.UTF_8).length;

            var result = assertInstanceOf(
                    FuzzyResult.Success.class,
                    new RegexUsageAnalyzer(analyzer).findUsages(List.of(target), Set.of(consumerFile), 100));

            assertEquals(1, result.hitsByOverload().get(target).size());
            assertTrue(analyzer.seenStartBytes().contains(expectedByteStart));
        }
    }

    @Test
    void tooManyCallsitesStillTriggersPerTarget() throws Exception {
        String targetCode =
                """
                package com.example;
                public class Target {}
                """;
        String consumerCode =
                """
                package com.example;
                public class Consumer {
                    private Target first;
                    private Target second;
                }
                """;

        try (var project = InlineTestProjectCreator.code(targetCode, "com/example/Target.java")
                .addFileContents(consumerCode, "com/example/Consumer.java")
                .build()) {
            var analyzer = new JavaAnalyzer(project);
            var target = singleDefinition(analyzer, "com.example.Target");
            var consumerFile = projectFile(project.getAllFiles(), "com/example/Consumer.java");

            var result = new RegexUsageAnalyzer(analyzer).findUsages(List.of(target), Set.of(consumerFile), 1);

            assertInstanceOf(FuzzyResult.TooManyCallsites.class, result);
        }
    }

    @Test
    void ambiguousIdentifierStillReturnsAmbiguousResult() throws Exception {
        String first = """
                package first;
                public class Dupe {}
                """;
        String second = """
                package second;
                public class Dupe {}
                """;
        String consumer =
                """
                package use;
                public class Consumer {
                    private Dupe dupe;
                }
                """;

        try (var project = InlineTestProjectCreator.code(first, "first/Dupe.java")
                .addFileContents(second, "second/Dupe.java")
                .addFileContents(consumer, "use/Consumer.java")
                .build()) {
            var analyzer = new JavaAnalyzer(project);
            var target = singleDefinition(analyzer, "first.Dupe");
            var consumerFile = projectFile(project.getAllFiles(), "use/Consumer.java");

            var result = new RegexUsageAnalyzer(analyzer).findUsages(List.of(target), Set.of(consumerFile), 100);

            assertInstanceOf(FuzzyResult.Ambiguous.class, result);
        }
    }

    private static CodeUnit singleDefinition(IAnalyzer analyzer, String fqName) {
        return analyzer.getDefinitions(fqName).stream().findFirst().orElseThrow();
    }

    private static ProjectFile projectFile(Set<ProjectFile> files, String suffix) {
        String normalizedSuffix = suffix.replace('\\', '/');
        return files.stream()
                .filter(file -> file.toString().replace('\\', '/').endsWith(normalizedSuffix))
                .findFirst()
                .orElseThrow();
    }

    private static final class RecordingJavaAnalyzer extends JavaAnalyzer {
        private final Set<Integer> seenStartBytes = new ConcurrentSkipListSet<>();

        private RecordingJavaAnalyzer(ICoreProject project) {
            super(project);
        }

        @Override
        public boolean isAccessExpression(ProjectFile file, int startByte, int endByte) {
            seenStartBytes.add(startByte);
            return super.isAccessExpression(file, startByte, endByte);
        }

        private Set<Integer> seenStartBytes() {
            return seenStartBytes;
        }
    }
}
