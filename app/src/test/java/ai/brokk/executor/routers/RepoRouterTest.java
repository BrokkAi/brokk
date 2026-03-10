package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RepoRouter repoRouter;
    private Path projectRoot;
    private ContextManager contextManager;
    private Git git;

    @AfterEach
    void tearDown() throws Exception {
        if (git != null) {
            git.close();
        }
        if (contextManager != null) {
            contextManager.close();
        }
    }

    private void initGitRepoAndRecreateContext(@TempDir Path tempDir, boolean modifyAfterCommit) throws Exception {
        projectRoot = tempDir;
        git = Git.init().setDirectory(projectRoot.toFile()).call();
        var testFile = projectRoot.resolve("initial.txt");
        Files.writeString(testFile, "initial content");
        git.add().addFilepattern("initial.txt").call();
        git.commit().setMessage("Initial commit").setSign(false).call();

        if (modifyAfterCommit) {
            Files.writeString(testFile, "modified content");
        }

        if (contextManager != null) {
            contextManager.close();
        }
        var project = new MainProject(projectRoot);
        contextManager = new ContextManager(project);
        repoRouter = new RepoRouter(contextManager);
    }

    @Test
    void handlePostCommit_noChanges_returnsNoChangesStatus(@TempDir Path tempDir) throws Exception {
        initGitRepoAndRecreateContext(tempDir, false);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/commit", Map.of());
        repoRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("no_changes", body.get("status"));
    }

    @Test
    void handlePostCommit_withChanges_returnsCommitMetadata(@TempDir Path tempDir) throws Exception {
        initGitRepoAndRecreateContext(tempDir, true);

        var exchange =
                TestHttpExchange.jsonRequest("POST", "/v1/repo/commit", Map.of("message", "Test commit message"));
        repoRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertNotNull(body.get("commitId"));
        assertFalse(((String) body.get("commitId")).isBlank());
        assertEquals("Test commit message", body.get("firstLine"));
    }

    @Test
    void handlePostCommit_blankMessage_usesDefaultMessage(@TempDir Path tempDir) throws Exception {
        initGitRepoAndRecreateContext(tempDir, true);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/commit", Map.of("message", ""));
        repoRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertNotNull(body.get("commitId"));
        assertFalse(((String) body.get("commitId")).isBlank());
        assertEquals("Manual commit", body.get("firstLine"));
    }

    @Test
    void handlePostCommit_omittedMessage_usesDefaultMessage(@TempDir Path tempDir) throws Exception {
        initGitRepoAndRecreateContext(tempDir, true);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/commit", Map.of());
        repoRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        assertNotNull(body.get("commitId"));
        assertFalse(((String) body.get("commitId")).isBlank());
        assertEquals("Manual commit", body.get("firstLine"));
    }

    // --- PR Suggest endpoint tests ---

    @Test
    void handlePostPrSuggest_invalidSessionId_returns400(@TempDir Path tempDir) throws Exception {
        initGitRepoWithFeatureBranch(tempDir);

        var exchange = TestHttpExchange.jsonRequest(
                "POST",
                "/v1/repo/pr/suggest",
                Map.of(
                        "sourceBranch", "feature",
                        "targetBranch", "master",
                        "sessionIds", java.util.List.of("not-a-valid-uuid")));
        repoRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.get("message").toString().contains("UUID"));
    }

    @Test
    void handlePostPrSuggest_noGitRepo_returns400(@TempDir Path tempDir) throws Exception {
        // Initialize without git
        projectRoot = tempDir;
        var project = new MainProject(projectRoot);
        contextManager = new ContextManager(project);
        repoRouter = new RepoRouter(contextManager);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/pr/suggest", Map.of());
        repoRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.get("message").toString().contains("git repository"));
    }

    // --- PR Create endpoint tests ---

    @Test
    void handlePostPrCreate_missingTitle_returns400(@TempDir Path tempDir) throws Exception {
        initGitRepoWithFeatureBranch(tempDir);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/pr/create", Map.of("body", "PR description"));
        repoRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.get("message").toString().contains("title"));
    }

    @Test
    void handlePostPrCreate_blankTitle_returns400(@TempDir Path tempDir) throws Exception {
        initGitRepoWithFeatureBranch(tempDir);

        var exchange = TestHttpExchange.jsonRequest(
                "POST", "/v1/repo/pr/create", Map.of("title", "  ", "body", "PR description"));
        repoRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.get("message").toString().contains("title"));
    }

    @Test
    void handlePostPrCreate_missingBody_returns400(@TempDir Path tempDir) throws Exception {
        initGitRepoWithFeatureBranch(tempDir);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/pr/create", Map.of("title", "My PR"));
        repoRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.get("message").toString().contains("body"));
    }

    @Test
    void handlePostPrCreate_noGitRepo_returns400(@TempDir Path tempDir) throws Exception {
        // Initialize without git
        projectRoot = tempDir;
        var project = new MainProject(projectRoot);
        contextManager = new ContextManager(project);
        repoRouter = new RepoRouter(contextManager);

        var exchange = TestHttpExchange.jsonRequest(
                "POST", "/v1/repo/pr/create", Map.of("title", "My PR", "body", "Description"));
        repoRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.get("message").toString().contains("git repository"));
    }

    @Test
    void handlePostPrCreate_invalidSessionId_returns400(@TempDir Path tempDir) throws Exception {
        initGitRepoWithFeatureBranch(tempDir);

        var exchange = TestHttpExchange.jsonRequest(
                "POST",
                "/v1/repo/pr/create",
                Map.of(
                        "title", "My PR",
                        "body", "Description",
                        "sessionIds", java.util.List.of("invalid-uuid-format")));
        repoRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.get("message").toString().contains("UUID"));
    }

    @Test
    void handlePostPrCreate_wrongMethod_returns405(@TempDir Path tempDir) throws Exception {
        initGitRepoWithFeatureBranch(tempDir);

        var exchange = TestHttpExchange.request("GET", "/v1/repo/pr/create");
        repoRouter.handle(exchange);

        assertEquals(405, exchange.responseCode());
    }

    @Test
    void handlePostPrSuggest_wrongMethod_returns405(@TempDir Path tempDir) throws Exception {
        initGitRepoWithFeatureBranch(tempDir);

        var exchange = TestHttpExchange.request("GET", "/v1/repo/pr/suggest");
        repoRouter.handle(exchange);

        assertEquals(405, exchange.responseCode());
    }

    // --- PR Sessions endpoint tests ---

    @Test
    void handlePostPrSessions_noGitRepo_returns400(@TempDir Path tempDir) throws Exception {
        // Initialize without git
        projectRoot = tempDir;
        var project = new MainProject(projectRoot);
        contextManager = new ContextManager(project);
        repoRouter = new RepoRouter(contextManager);

        var exchange = TestHttpExchange.jsonRequest("POST", "/v1/repo/pr/sessions", Map.of());
        repoRouter.handle(exchange);

        assertEquals(400, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.get("message").toString().contains("git repository"));
    }

    @Test
    void handlePostPrSessions_wrongMethod_returns405(@TempDir Path tempDir) throws Exception {
        initGitRepoWithFeatureBranch(tempDir);

        var exchange = TestHttpExchange.request("GET", "/v1/repo/pr/sessions");
        repoRouter.handle(exchange);

        assertEquals(405, exchange.responseCode());
    }

    @Test
    void handlePostPrSessions_withFeatureBranch_returnsSessionsAndBranches(@TempDir Path tempDir) throws Exception {
        initGitRepoWithFeatureBranch(tempDir);

        var exchange = TestHttpExchange.jsonRequest(
                "POST", "/v1/repo/pr/sessions", Map.of("sourceBranch", "feature", "targetBranch", "master"));
        repoRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});

        // Verify response shape
        assertNotNull(body.get("sessions"));
        assertTrue(body.get("sessions") instanceof java.util.List);
        assertEquals("feature", body.get("sourceBranch"));
        assertEquals("master", body.get("targetBranch"));
    }

    /**
     * Sets up a git repo with a master branch and a feature branch with one additional commit.
     */
    private void initGitRepoWithFeatureBranch(@TempDir Path tempDir) throws Exception {
        projectRoot = tempDir;
        git = Git.init()
                .setDirectory(projectRoot.toFile())
                .setInitialBranch("master")
                .call();

        // Initial commit on master
        var testFile = projectRoot.resolve("initial.txt");
        Files.writeString(testFile, "initial content");
        git.add().addFilepattern("initial.txt").call();
        git.commit().setMessage("Initial commit").setSign(false).call();

        // Create and checkout feature branch
        git.branchCreate().setName("feature").call();
        git.checkout().setName("feature").call();

        // Add a commit on feature branch
        var featureFile = projectRoot.resolve("feature.txt");
        Files.writeString(featureFile, "feature content");
        git.add().addFilepattern("feature.txt").call();
        git.commit().setMessage("Add feature").setSign(false).call();

        if (contextManager != null) {
            contextManager.close();
        }
        var project = new MainProject(projectRoot);
        contextManager = new ContextManager(project);
        repoRouter = new RepoRouter(contextManager);
    }
}
