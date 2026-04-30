package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticAnalysisRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void handlePostSeeds_returnsRealSeeds(@TempDir Path root) throws Exception {
        var file = new ProjectFile(root, "src/main/java/Example.java");
        Files.createDirectories(file.absPath().getParent());
        Files.writeString(file.absPath(), "class Example { void complex() {} }");
        var method = new CodeUnit(file, CodeUnitType.FUNCTION, "Example", "complex", "()", false);
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(method);
        analyzer.setComplexity(method, 18);
        analyzer.setRanges(method, List.of(new IAnalyzer.Range(0, 200, 0, 90, 0)));
        var router = new StaticAnalysisRouter(new TestContextManager(
                new TestProject(root, Languages.JAVA), new TestConsoleIO(), java.util.Set.of(), analyzer));

        var exchange = TestHttpExchange.request(
                "POST",
                "/v1/static-analysis/seeds",
                """
                {"scanId":"scan-123","targetSeedCount":10,"maxDurationMs":5000,"includePreview":true}
                """);

        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("scan-123", body.get("scanId"));
        assertEquals("static_seed", body.get("phase"));
        assertEquals("completed", body.get("state"), body.toString());
        var seeds = (List<?>) body.get("seeds");
        assertFalse(seeds.isEmpty());
        var seed = (Map<?, ?>) seeds.getFirst();
        assertEquals("src/main/java/Example.java", seed.get("file"));
        assertEquals(List.of("reportLongMethodAndGodObjectSmells"), seed.get("suggestedTools"));
        var previews = (List<?>) body.get("previews");
        assertFalse(previews.isEmpty());
        var preview = (Map<?, ?>) previews.getFirst();
        assertEquals("src/main/java/Example.java", preview.get("file"));
        assertEquals("reportLongMethodAndGodObjectSmells", preview.get("tool"));
        assertEquals("Example.complex", preview.get("symbol"));
        var events = (List<?>) body.get("events");
        assertEquals(2, events.size());
        var event = (Map<?, ?>) events.getLast();
        assertEquals("static_seed", event.get("phase"));
        assertEquals("completed", event.get("state"));
        var outcome = (Map<?, ?>) event.get("outcome");
        assertEquals("STATIC_SEED_COMPLETED", outcome.get("code"));
        assertEquals(1, outcome.get("findingCount"));
    }

    @Test
    void handlePostLeadExpansion_returnsUsageExpansionSeeds(@TempDir Path root) throws Exception {
        var target = new ProjectFile(root, "src/main/java/p/Target.java");
        Files.createDirectories(target.absPath().getParent());
        Files.writeString(target.absPath(), "package p; public class Target {}");
        var user = new ProjectFile(root, "src/main/java/p/User.java");
        Files.writeString(user.absPath(), "package p; class User { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        var router = new StaticAnalysisRouter(new TestContextManager(
                new TestProject(root, Languages.JAVA), new TestConsoleIO(), java.util.Set.of(), analyzer));

        var exchange = TestHttpExchange.request(
                "POST",
                "/v1/static-analysis/lead-expansion",
                """
                {"scanId":"scan-123","knownFiles":["src/main/java/p/Target.java"],"frontierFiles":["src/main/java/p/Target.java"],"maxResults":5,"maxDurationMs":5000}
                """);

        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("scan-123", body.get("scanId"));
        assertEquals("completed", body.get("state"));
        var seeds = (List<?>) body.get("seeds");
        assertFalse(seeds.isEmpty());
        var seed = (Map<?, ?>) seeds.getFirst();
        assertEquals("src/main/java/p/User.java", seed.get("file"));
        assertEquals(
                List.of("reportExceptionHandlingSmells", "reportCommentDensityForFiles", "computeCognitiveComplexity"),
                seed.get("suggestedTools"));
        var selection = (Map<?, ?>) seed.get("selection");
        assertEquals("usage_expansion", selection.get("kind"));
    }

    @Test
    void handlePostSeeds_rejectsInvalidTargetSeedCount() throws Exception {
        var router = emptyRouter();
        var exchange = TestHttpExchange.request(
                "POST", "/v1/static-analysis/seeds", "{\"scanId\":\"scan-123\",\"targetSeedCount\":0}");

        router.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());
        assertTrue(payload.message().contains("targetSeedCount"));
    }

    @Test
    void handlePostSeeds_rejectsInvalidMaxDuration() throws Exception {
        var router = emptyRouter();
        var exchange = TestHttpExchange.request(
                "POST", "/v1/static-analysis/seeds", "{\"scanId\":\"scan-123\",\"maxDurationMs\":120001}");

        router.handle(exchange);

        assertEquals(400, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.VALIDATION_ERROR, payload.code());
        assertTrue(payload.message().contains("maxDurationMs"));
    }

    private static StaticAnalysisRouter emptyRouter() {
        var root =
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        return new StaticAnalysisRouter(new TestContextManager(
                new TestProject(root, Languages.JAVA), new TestConsoleIO(), java.util.Set.of(), new TestAnalyzer()));
    }

    private static final class TestHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private String method = "GET";
        private URI uri = URI.create("/");
        private byte[] requestBodyBytes = new byte[0];
        private int responseCode = -1;

        static TestHttpExchange request(String method, String path, String body) {
            var ex = new TestHttpExchange();
            ex.method = method;
            ex.uri = URI.create(path);
            ex.requestBodyBytes = body.getBytes(StandardCharsets.UTF_8);
            return ex;
        }

        int responseCode() {
            return responseCode;
        }

        byte[] responseBodyBytes() {
            return responseBody.toByteArray();
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(requestBodyBytes);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {}

        @Override
        public void setStreams(InputStream i, OutputStream o) {}

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
