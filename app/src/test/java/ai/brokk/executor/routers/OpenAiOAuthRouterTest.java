package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.executor.jobs.ErrorPayload;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiOAuthRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private OpenAiOAuthRouter router;

    @BeforeEach
    void setUp() {
        router = new OpenAiOAuthRouter();
    }

    @Test
    void handlePost_startFlow_returnsStartedStatus() throws Exception {
        var exchange = TestHttpExchange.request("POST", "/v1/openai/oauth/start");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<?, ?> body = MAPPER.readValue(exchange.responseBodyBytes(), Map.class);
        assertEquals("started", body.get("status"));
    }

    @Test
    void handleGet_returnsMethodNotAllowed() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/openai/oauth/start");
        router.handle(exchange);

        assertEquals(405, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.METHOD_NOT_ALLOWED, payload.code());
    }

    private static final class TestHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private String method = "GET";
        private URI uri = URI.create("/");
        private int responseCode = -1;

        static TestHttpExchange request(String method, String path) {
            var ex = new TestHttpExchange();
            ex.method = method;
            ex.uri = URI.create(path);
            return ex;
        }

        public int responseCode() {
            return responseCode;
        }

        public byte[] responseBodyBytes() {
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
            return new ByteArrayInputStream(new byte[0]);
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
