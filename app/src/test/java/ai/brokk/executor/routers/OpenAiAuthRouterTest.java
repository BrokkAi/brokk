package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.openai.OpenAiOAuthService;
import ai.brokk.project.MainProject;
import ai.brokk.project.TestConfigHelper;
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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenAiAuthRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private OpenAiAuthRouter router;
    private String originalTestMode;
    private String originalSandboxRoot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        router = new OpenAiAuthRouter();
        originalTestMode = System.getProperty("brokk.test.mode");
        originalSandboxRoot = System.getProperty("brokk.test.sandbox.root");

        System.setProperty("brokk.test.mode", "true");
        System.setProperty("brokk.test.sandbox.root", tempDir.toString());
        TestConfigHelper.resetGlobalConfigCaches();
    }

    @AfterEach
    void tearDown() {
        OpenAiOAuthService.testAuthorizationHook = null;
        if (originalTestMode != null) {
            System.setProperty("brokk.test.mode", originalTestMode);
        } else {
            System.clearProperty("brokk.test.mode");
        }
        if (originalSandboxRoot != null) {
            System.setProperty("brokk.test.sandbox.root", originalSandboxRoot);
        } else {
            System.clearProperty("brokk.test.sandbox.root");
        }
        TestConfigHelper.resetGlobalConfigCaches();
    }

    @Test
    void handlePostStart_success_invokesAuthorizationAndReturnsStarted() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        OpenAiOAuthService.testAuthorizationHook = callCount::incrementAndGet;

        var exchange = TestHttpExchange.request("POST", "/v1/openai/oauth/start");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("started", body.get("status"));
        assertEquals(1, callCount.get());
    }

    @Test
    void handlePostStart_error_returns500() throws Exception {
        OpenAiOAuthService.testAuthorizationHook = () -> {
            throw new RuntimeException("boom");
        };

        var exchange = TestHttpExchange.request("POST", "/v1/openai/oauth/start");
        router.handle(exchange);

        assertEquals(500, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.INTERNAL_ERROR, payload.code());
        assertTrue(payload.message().contains("Failed to start OpenAI OAuth"));
    }

    @Test
    void handleGetStatus_returnsConnectedFlag() throws Exception {
        MainProject.setOpenAiCodexOauthConnected(true);
        var ex1 = TestHttpExchange.request("GET", "/v1/openai/oauth/status");
        router.handle(ex1);

        assertEquals(200, ex1.responseCode());
        Map<String, Object> body1 = MAPPER.readValue(ex1.responseBodyBytes(), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, body1.get("connected"));

        MainProject.setOpenAiCodexOauthConnected(false);
        var ex2 = TestHttpExchange.request("GET", "/v1/openai/oauth/status");
        router.handle(ex2);

        assertEquals(200, ex2.responseCode());
        Map<String, Object> body2 = MAPPER.readValue(ex2.responseBodyBytes(), new TypeReference<>() {});
        assertEquals(Boolean.FALSE, body2.get("connected"));
    }

    @Test
    void handleInvalidMethod_returns405() throws Exception {
        // Case A: GET /v1/openai/oauth/start
        var exA = TestHttpExchange.request("GET", "/v1/openai/oauth/start");
        router.handle(exA);
        assertEquals(405, exA.responseCode());
        var payloadA = MAPPER.readValue(exA.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.METHOD_NOT_ALLOWED, payloadA.code());

        // Case B: POST /v1/openai/oauth/status
        var exB = TestHttpExchange.request("POST", "/v1/openai/oauth/status");
        router.handle(exB);
        assertEquals(405, exB.responseCode());
        var payloadB = MAPPER.readValue(exB.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.METHOD_NOT_ALLOWED, payloadB.code());
    }

    @Test
    void handleUnknownPath_returns404() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/openai/oauth/unknown");
        router.handle(exchange);

        assertEquals(404, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.NOT_FOUND, payload.code());
    }

    private static final class TestHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private String method = "GET";
        private URI uri = URI.create("/");
        private byte[] requestBodyBytes = new byte[0];
        private int responseCode = -1;

        static TestHttpExchange request(String method, String path) {
            var ex = new TestHttpExchange();
            ex.method = method;
            ex.uri = URI.create(path);
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
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
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
