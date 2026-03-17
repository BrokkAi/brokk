package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("unchecked")
class DependenciesRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path projectRoot;
    private ContextManager contextManager;
    private DependenciesRouter router;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        projectRoot = tempDir;
        var project = new MainProject(tempDir);
        contextManager = new ContextManager(project);
        router = new DependenciesRouter(contextManager);
    }

    @AfterEach
    void tearDown() {
        if (contextManager != null) {
            contextManager.close();
        }
    }

    private Path depsDir() {
        return projectRoot.resolve(".brokk").resolve("dependencies");
    }

    private Path createDepDir(String name) throws Exception {
        var dir = depsDir().resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private Map<String, Object> getResponse(TestHttpExchange exchange) throws Exception {
        return MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
    }

    private ErrorPayload getError(TestHttpExchange exchange) throws Exception {
        return MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
    }

    // ── GET /v1/dependencies ─────────────────────────────

    @Test
    void noDependencies_returnsEmptyList() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/dependencies");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        var body = getResponse(exchange);
        var deps = (List<?>) body.get("dependencies");
        assertTrue(deps.isEmpty());
    }

    @Test
    void withDependencies_returnsCorrectFields() throws Exception {
        var dep1 = createDepDir("lib-alpha");
        Files.writeString(dep1.resolve("file1.txt"), "content");
        Files.writeString(dep1.resolve("file2.txt"), "content");

        var dep2 = createDepDir("lib-beta");
        Files.writeString(dep2.resolve("source.java"), "class Foo {}");

        var exchange = TestHttpExchange.request("GET", "/v1/dependencies");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        var body = getResponse(exchange);
        var deps = (List<Map<String, Object>>) body.get("dependencies");
        assertEquals(2, deps.size());

        // Find each dep by name (ordering not guaranteed)
        var alpha = deps.stream()
                .filter(d -> "lib-alpha".equals(d.get("name")))
                .findFirst()
                .orElseThrow();
        var beta = deps.stream()
                .filter(d -> "lib-beta".equals(d.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals("lib-alpha", alpha.get("displayName"));
        assertEquals(2, ((Number) alpha.get("fileCount")).intValue());
        assertFalse((Boolean) alpha.get("isLive"));
        assertNull(alpha.get("metadata"));

        assertEquals("lib-beta", beta.get("displayName"));
        assertEquals(1, ((Number) beta.get("fileCount")).intValue());
        assertFalse((Boolean) beta.get("isLive"));
        assertNull(beta.get("metadata"));
    }

    @Test
    void withLiveDependency_isLiveTrue() throws Exception {
        createDepDir("live-dep");

        // Set it live via PUT
        var putExchange = TestHttpExchange.jsonRequest(
                "PUT", "/v1/dependencies", Map.of("liveDependencyNames", List.of("live-dep")));
        router.handle(putExchange);
        assertEquals(200, putExchange.responseCode());

        // GET and verify isLive
        var getExchange = TestHttpExchange.request("GET", "/v1/dependencies");
        router.handle(getExchange);

        assertEquals(200, getExchange.responseCode());
        var deps = (List<Map<String, Object>>) getResponse(getExchange).get("dependencies");
        assertEquals(1, deps.size());
        assertTrue((Boolean) deps.get(0).get("isLive"));
    }

    @Test
    void methodNotAllowed_post() throws Exception {
        var exchange = TestHttpExchange.request("POST", "/v1/dependencies");
        router.handle(exchange);

        assertEquals(405, exchange.responseCode());
    }

    // ── PUT /v1/dependencies ─────────────────────────────

    @Test
    void setLiveDependencies_updatesLiveSet() throws Exception {
        createDepDir("dep-a");
        createDepDir("dep-b");

        var exchange = TestHttpExchange.jsonRequest(
                "PUT", "/v1/dependencies", Map.of("liveDependencyNames", List.of("dep-a")));
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        var body = getResponse(exchange);
        assertEquals("updated", body.get("status"));
        assertEquals(1, ((Number) body.get("liveCount")).intValue());

        // Verify via GET
        var getExchange = TestHttpExchange.request("GET", "/v1/dependencies");
        router.handle(getExchange);
        var deps = (List<Map<String, Object>>) getResponse(getExchange).get("dependencies");
        var depA = deps.stream()
                .filter(d -> "dep-a".equals(d.get("name")))
                .findFirst()
                .orElseThrow();
        var depB = deps.stream()
                .filter(d -> "dep-b".equals(d.get("name")))
                .findFirst()
                .orElseThrow();
        assertTrue((Boolean) depA.get("isLive"));
        assertFalse((Boolean) depB.get("isLive"));
    }

    @Test
    void nullNames_returns400() throws Exception {
        var body = new HashMap<String, Object>();
        body.put("liveDependencyNames", null);

        var exchange = TestHttpExchange.jsonRequest("PUT", "/v1/dependencies", body);
        router.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = getError(exchange);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());
        assertTrue(payload.message().contains("liveDependencyNames"));
    }

    @Test
    void pathTraversal_skipped() throws Exception {
        var exchange = TestHttpExchange.jsonRequest(
                "PUT", "/v1/dependencies", Map.of("liveDependencyNames", List.of("../etc")));
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        var body = getResponse(exchange);
        assertEquals(0, ((Number) body.get("liveCount")).intValue());
    }

    // ── DELETE /v1/dependencies/{name} ───────────────────

    @Test
    void deleteExisting_removesDirectory() throws Exception {
        var depDir = createDepDir("to-delete");
        Files.writeString(depDir.resolve("file.txt"), "data");
        assertTrue(Files.exists(depDir));

        var exchange = TestHttpExchange.request("DELETE", "/v1/dependencies/to-delete");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        var body = getResponse(exchange);
        assertEquals("deleted", body.get("status"));
        assertEquals("to-delete", body.get("name"));
        assertFalse(Files.exists(depDir));
    }

    @Test
    void deleteNonexistent_returns404() throws Exception {
        // Ensure deps dir exists but target doesn't
        Files.createDirectories(depsDir());

        var exchange = TestHttpExchange.request("DELETE", "/v1/dependencies/ghost");
        router.handle(exchange);

        assertEquals(404, exchange.responseCode());
        var payload = getError(exchange);
        assertEquals(ErrorPayload.Code.NOT_FOUND, payload.code());
    }

    @Test
    void deletePathTraversal_returns400() throws Exception {
        var exchange = TestHttpExchange.request("DELETE", "/v1/dependencies/..%2F..%2Fetc");
        router.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = getError(exchange);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());
    }

    // ── POST /v1/dependencies/{name}/update ──────────────

    @Test
    void updateNonexistent_returns404() throws Exception {
        var exchange = TestHttpExchange.request("POST", "/v1/dependencies/unknown/update");
        router.handle(exchange);

        assertEquals(404, exchange.responseCode());
        var payload = getError(exchange);
        assertEquals(ErrorPayload.Code.NOT_FOUND, payload.code());
    }

    @Test
    void updateNoMetadata_returns400() throws Exception {
        createDepDir("no-meta");

        var exchange = TestHttpExchange.request("POST", "/v1/dependencies/no-meta/update");
        router.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = getError(exchange);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());
        assertTrue(payload.message().contains("No metadata"));
    }

    @Test
    void blankName_returns400() throws Exception {
        var exchange = TestHttpExchange.request("POST", "/v1/dependencies//update");
        router.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = getError(exchange);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());
    }

    // ── Routing ──────────────────────────────────────────

    @Test
    void unknownSubpath_getOnNamePattern_returns405() throws Exception {
        // /v1/dependencies/foo/bar/baz doesn't match /update suffix,
        // but does match the {name} catch-all which only allows DELETE
        var exchange = TestHttpExchange.request("GET", "/v1/dependencies/foo/bar/baz");
        router.handle(exchange);

        assertEquals(405, exchange.responseCode());
    }

    @Test
    void unrelatedPath_returns404() throws Exception {
        // Path that doesn't match any of the router's patterns
        var exchange = TestHttpExchange.request("GET", "/v1/somethingelse");
        router.handle(exchange);

        assertEquals(404, exchange.responseCode());
    }
}
