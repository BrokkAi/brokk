package ai.brokk.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.OfflineService;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestAnalyzer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LlmUsageAnalyzerTest {

    @Test
    void cachesFileScanInputAcrossSymbolQueries() throws IOException, InterruptedException {
        String source =
                """
                package com.example;
                class Caller {
                    void call(Target target) {
                        target.foo();
                        target.bar();
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.empty().build()) {
            var file = new CountingProjectFile(project.getRoot(), "src/main/java/com/example/Caller.java");
            Files.createDirectories(file.absPath().getParent());
            Files.writeString(file.absPath(), source);

            var caller = new CodeUnit(file, CodeUnitType.CLASS, "com.example", "Caller");
            var foo = new CodeUnit(file, CodeUnitType.FUNCTION, "com.example", "Target.foo");
            var bar = new CodeUnit(file, CodeUnitType.FUNCTION, "com.example", "Target.bar");

            var analyzer = new TestAnalyzer(List.of(caller, foo, bar), java.util.Map.of(), project);
            analyzer.setRanges(caller, List.of(new ai.brokk.analyzer.IAnalyzer.Range(0, source.length(), 1, 6, 6)));

            var usageAnalyzer = new LlmUsageAnalyzer(project, analyzer, new OfflineService(project), null);
            var candidateFiles = Set.<ProjectFile>of(file);

            usageAnalyzer.findUsages(List.of(foo), candidateFiles, 100);
            usageAnalyzer.findUsages(List.of(bar), candidateFiles, 100);

            assertEquals(1, file.readCount());
            assertEquals(1, usageAnalyzer.cachedFileReadCount());
            assertEquals(1, usageAnalyzer.cachedLineStartComputationCount());
        }
    }

    @Test
    void invalidatesFileScanInputWhenMtimeChanges() throws IOException, InterruptedException {
        String firstSource =
                """
                package com.example;
                class Caller {
                    void call(Target target) {
                        target.foo();
                    }
                }
                """;
        String secondSource =
                """
                package com.example;
                class Caller {
                    void call(Target target) {
                        target.bar();
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.empty().build()) {
            var file = new CountingProjectFile(project.getRoot(), "src/main/java/com/example/Caller.java");
            Files.createDirectories(file.absPath().getParent());
            Files.writeString(file.absPath(), firstSource);

            var caller = new CodeUnit(file, CodeUnitType.CLASS, "com.example", "Caller");
            var foo = new CodeUnit(file, CodeUnitType.FUNCTION, "com.example", "Target.foo");
            var bar = new CodeUnit(file, CodeUnitType.FUNCTION, "com.example", "Target.bar");

            var analyzer = new TestAnalyzer(List.of(caller, foo, bar), java.util.Map.of(), project);
            analyzer.setRanges(
                    caller, List.of(new ai.brokk.analyzer.IAnalyzer.Range(0, secondSource.length(), 1, 6, 6)));

            var usageAnalyzer = new LlmUsageAnalyzer(project, analyzer, new OfflineService(project), null);
            var candidateFiles = Set.<ProjectFile>of(file);

            usageAnalyzer.findUsages(List.of(foo), candidateFiles, 100);

            Files.writeString(file.absPath(), secondSource);
            file.advanceMtime();

            usageAnalyzer.findUsages(List.of(bar), candidateFiles, 100);

            assertEquals(2, file.readCount());
            assertEquals(2, usageAnalyzer.cachedFileReadCount());
            assertEquals(2, usageAnalyzer.cachedLineStartComputationCount());
        }
    }

    private static final class CountingProjectFile extends ProjectFile {
        private final AtomicInteger readCount = new AtomicInteger();
        private final AtomicInteger mtime = new AtomicInteger(1);

        private CountingProjectFile(Path root, String relName) {
            super(root, relName);
        }

        @Override
        public Optional<String> read() {
            readCount.incrementAndGet();
            return super.read();
        }

        private int readCount() {
            return readCount.get();
        }

        @Override
        public long mtime() {
            return mtime.get();
        }

        private void advanceMtime() {
            mtime.incrementAndGet();
        }
    }
}
