package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftServerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void formatWorkspace_usesRevisionSnapshotsForFullFilesAndSummaries() throws Exception {
        initGitRepo(tempDir);

        var foo = tempDir.resolve("src/main/java/example/Foo.java");
        var helper = tempDir.resolve("src/main/java/example/Helper.java");
        Files.createDirectories(foo.getParent());
        Files.writeString(
                foo,
                """
                package example;

                class Foo {
                    void oldName() {}
                }
                """);
        Files.writeString(
                helper,
                """
                package example;

                class Helper {
                    void legacyApi() {}
                }
                """);

        var revision = commitAll(tempDir, "initial");

        Files.writeString(
                foo,
                """
                package example;

                class Foo {
                    void newName() {}
                }
                """);
        Files.writeString(
                helper,
                """
                package example;

                class Helper {
                    void newApi() {}
                }
                """);

        try (var server = new SftServer(tempDir, 0)) {
            var formatted = server.format_workspace(
                    "Rename the old API",
                    revision,
                    List.of("src/main/java/example/Foo.java"),
                    List.of(),
                    List.of("src/main/java/example/Helper.java"),
                    "");

            assertEquals(3, formatted.size());
            assertEquals("user", formatted.getFirst().role());
            assertEquals("assistant", formatted.get(1).role());
            assertEquals("user", formatted.getLast().role());

            var workspaceText = formatted.getFirst().content();
            var finalRequestText = formatted.getLast().content();
            assertTrue(workspaceText.contains("oldName"));
            assertFalse(workspaceText.contains("newName"));
            assertTrue(workspaceText.contains("<api_summaries>"));
            assertTrue(workspaceText.contains("legacyApi"));
            assertFalse(workspaceText.contains("newApi"));
            assertTrue(finalRequestText.contains("<workspace_toc>"));
            assertTrue(finalRequestText.contains("Rename the old API"));
        }
    }

    @Test
    void formatPatch_formatsLocalizedSearchReplaceBlocks() throws Exception {
        initGitRepo(tempDir);

        var foo = tempDir.resolve("src/main/java/example/Foo.java");
        Files.createDirectories(foo.getParent());
        Files.writeString(
                foo,
                """
                package example;

                class Foo {
                    void alpha() {}
                    void keep() {}
                    void omega() {}
                }
                """);
        var from = commitAll(tempDir, "before");

        Files.writeString(
                foo,
                """
                package example;

                class Foo {
                    void alphaUpdated() {}
                    void keep() {}
                    void omegaUpdated() {}
                }
                """);
        var to = commitAll(tempDir, "after");

        try (var server = new SftServer(tempDir, 0)) {
            var formatted = server.format_patch(from, to);
            var filePatch = formatted.get("src/main/java/example/Foo.java");

            assertEquals(1, formatted.size());
            assertNotNull(filePatch);
            assertTrue(filePatch.contains("src/main/java/example/Foo.java"));
            assertTrue(filePatch.contains("<<<<<<< SEARCH"));
            assertTrue(filePatch.contains("alpha()"));
            assertTrue(filePatch.contains("alphaUpdated()"));
            assertTrue(filePatch.contains("omega()"));
            assertTrue(filePatch.contains("omegaUpdated()"));
            assertFalse(filePatch.contains("BRK_ENTIRE_FILE"));
            assertFalse(filePatch.contains("src\\main\\java\\example\\Foo.java"));
        }
    }

    @Test
    void formatPatch_filtersByIncludedFilenames() throws Exception {
        initGitRepo(tempDir);

        var foo = tempDir.resolve("src/main/java/example/Foo.java");
        var bar = tempDir.resolve("src/main/java/example/Bar.java");
        Files.createDirectories(foo.getParent());
        Files.writeString(
                foo,
                """
                package example;

                class Foo {
                    void alpha() {}
                }
                """);
        Files.writeString(
                bar,
                """
                package example;

                class Bar {
                    void beta() {}
                }
                """);
        var from = commitAll(tempDir, "before");

        Files.writeString(
                foo,
                """
                package example;

                class Foo {
                    void alphaUpdated() {}
                }
                """);
        Files.writeString(
                bar,
                """
                package example;

                class Bar {
                    void betaUpdated() {}
                }
                """);
        var to = commitAll(tempDir, "after");

        try (var server = new SftServer(tempDir, 0)) {
            var formatted = server.format_patch(from, to, List.of("src/main/java/example/Foo.java"));

            assertEquals(1, formatted.size());
            assertTrue(formatted.containsKey("src/main/java/example/Foo.java"));
            assertFalse(formatted.containsKey("src/main/java/example/Bar.java"));
            assertTrue(formatted.get("src/main/java/example/Foo.java").contains("alphaUpdated()"));
        }
    }

    @Test
    void httpServer_handlesConcurrentRequests() throws Exception {
        initGitRepo(tempDir);

        var foo = tempDir.resolve("src/main/java/example/Foo.java");
        Files.createDirectories(foo.getParent());
        Files.writeString(
                foo,
                """
                package example;

                class Foo {
                    void alpha() {}
                }
                """);
        var revision = commitAll(tempDir, "initial");

        try (var server = new SftServer(tempDir, 0)) {
            server.start();
            var client = HttpClient.newHttpClient();
            var uri = URI.create("http://localhost:" + server.getPort() + "/format_workspace");
            var executor = Executors.newFixedThreadPool(4);
            try {
                var futures = IntStream.range(0, 6)
                        .mapToObj(i ->
                                CompletableFuture.supplyAsync(() -> postWorkspace(client, uri, revision), executor))
                        .toList();

                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .join();

                for (var future : futures) {
                    var response = future.get();
                    assertEquals(200, response.statusCode());
                    JsonNode json = OBJECT_MAPPER.readTree(response.body());
                    JsonNode result = json.get("result");
                    assertTrue(result.isArray());
                    assertTrue(result.size() >= 2);
                    assertTrue(response.body().contains("alpha"));
                    assertTrue(result.get(0).isObject());
                    assertEquals("user", result.get(0).get("role").asText());
                    assertTrue(result.get(0).get("content").asText().contains("alpha"));
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void formatWorkspace_includesBuildErrorWhenProvided() throws Exception {
        initGitRepo(tempDir);

        var foo = tempDir.resolve("src/main/java/example/Foo.java");
        Files.createDirectories(foo.getParent());
        Files.writeString(
                foo,
                """
                package example;

                class Foo {
                    void alpha() {}
                }
                """);
        var revision = commitAll(tempDir, "initial");

        try (var server = new SftServer(tempDir, 0)) {
            var formatted = server.format_workspace(
                    "Fix the build",
                    revision,
                    List.of("src/main/java/example/Foo.java"),
                    List.of(),
                    List.of(),
                    "src/main/java/example/Foo.java:4: error: cannot find symbol");

            assertTrue(formatted.getFirst().content().contains("[HARNESS NOTE: The build is currently FAILING."));
            assertTrue(formatted.getFirst().content().contains("cannot find symbol"));
        }
    }

    private static HttpResponse<String> postWorkspace(HttpClient client, URI uri, String revision) {
        try {
            var body = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "goal",
                    "Inspect Foo",
                    "revision",
                    revision,
                    "editable",
                    List.of("src/main/java/example/Foo.java"),
                    "readonly",
                    List.of(),
                    "summarized",
                    List.of(),
                    "build_error",
                    ""));
            var request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void initGitRepo(Path root) throws Exception {
        try (var git = Git.init().setDirectory(root.toFile()).call()) {
            // initialized
        }
    }

    private static String commitAll(Path root, String message) throws Exception {
        try (var git = Git.open(root.toFile())) {
            // JGit will sign commits when `commit.gpgsign` is true (from global/user config).
            // These tests don't provide a GPG key, so disable signing for the temporary repo.
            git.getRepository()
                    .getConfig()
                    .setBoolean("commit", null, "gpgsign", false);
            git.add().addFilepattern(".").call();
            return git.commit()
                    .setMessage(message)
                    .setAuthor("Brokk Test", "brokk@example.com")
                    .setCommitter("Brokk Test", "brokk@example.com")
                    .call()
                    .getName();
        }
    }
}
