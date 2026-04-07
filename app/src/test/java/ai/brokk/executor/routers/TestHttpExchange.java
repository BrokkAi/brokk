package ai.brokk.executor.routers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

final class TestHttpExchange extends HttpExchange {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private String method = "GET";
    private URI uri = URI.create("/");
    private byte[] requestBodyBytes = new byte[0];
    private int responseCode = -1;

    static TestHttpExchange jsonRequest(String method, String path, Object body) throws IOException {
        var ex = request(method, path);
        ex.requestHeaders.set("Content-Type", "application/json");
        ex.requestBodyBytes = MAPPER.writeValueAsBytes(body);
        return ex;
    }

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
