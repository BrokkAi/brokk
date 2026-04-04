package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AnalyzerWrapper;
import ai.brokk.NullAnalyzerListener;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.MainProject;
import ai.brokk.watchservice.NoopWatchService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MostRelevantFilesCliTest {

    @TempDir
    Path tempDir;

    @Test
    void run_ignores_persisted_exclusion_patterns_for_explicit_seed_files() throws Exception {
        Files.createDirectories(tempDir.resolve("excluded"));
        Files.writeString(
                tempDir.resolve("excluded/geometry.h"),
                """
                struct Point {
                  int x;
                  int y;
                };
                """);
        Files.writeString(
                tempDir.resolve("excluded/geometry.cpp"),
                """
                #include "geometry.h"
                void use_point(Point p) {
                  (void)p.x;
                }
                """);

        try (var project = MainProject.forTests(tempDir, new BuildAgent.BuildDetails("", "", Set.of("excluded")))) {
            try (var analyzerWrapper =
                    new AnalyzerWrapper(project, new NullAnalyzerListener(), new NoopWatchService())) {
                analyzerWrapper.get();
            }
        }

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

        try (PrintStream capturedOut = new PrintStream(outBytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);

            int exitCode =
                    MostRelevantFilesCli.run(new String[] {"--root", tempDir.toString(), "excluded/geometry.cpp"});
            capturedOut.flush();

            String stdout = outBytes.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(stdout.contains("excluded/geometry.h"), stdout);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void run_supplements_missing_seed_language_from_project_configuration() throws Exception {
        Files.writeString(tempDir.resolve("A.java"), "public class A {}");
        Files.writeString(
                tempDir.resolve("geometry.h"),
                """
                struct Point {
                  int x;
                  int y;
                };
                """);
        Files.writeString(
                tempDir.resolve("geometry.cpp"),
                """
                #include "geometry.h"
                void use_point(Point p) {
                  (void)p.x;
                }
                """);

        try (var project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY)) {
            project.setAnalyzerLanguages(Set.of(Languages.JAVA));
        }

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

        try (PrintStream capturedOut = new PrintStream(outBytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);

            int exitCode = MostRelevantFilesCli.run(new String[] {"--root", tempDir.toString(), "geometry.cpp"});
            capturedOut.flush();

            String stdout = outBytes.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(stdout.contains("geometry.h"), stdout);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void deletePersistedAnalyzerStateForCli_clears_seed_language_snapshot_before_startup() throws Exception {
        Files.writeString(tempDir.resolve("A.ts"), "export const A = 1;\n");

        try (var project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY)) {
            Path storage = Languages.TYPESCRIPT.getStoragePath(project);
            Files.createDirectories(storage.getParent());
            Files.writeString(storage, "stale");

            MostRelevantFilesCli.deletePersistedAnalyzerStateForCli(
                    project, List.of(new ProjectFile(tempDir, "A.ts")));

            assertTrue(Files.notExists(storage), storage.toString());
        }
    }

    @Test
    void overrideAnalyzerLanguagesIfRequested_applies_internal_language_names() throws Exception {
        Files.writeString(tempDir.resolve("A.ts"), "export const A = 1;\n");
        System.setProperty("brokk.mrf.languages", "TYPESCRIPT");
        try (var project = MainProject.forTests(tempDir, BuildAgent.BuildDetails.EMPTY)) {
            project.setAnalyzerLanguages(Set.of(Languages.JAVA));

            MostRelevantFilesCli.overrideAnalyzerLanguagesIfRequested(project);

            assertEquals(Set.of(Languages.TYPESCRIPT), project.getAnalyzerLanguages());
        } finally {
            System.clearProperty("brokk.mrf.languages");
        }
    }
}
