package ai.brokk.ctl.http;

import ai.brokk.ctl.CtlConfigPaths;
import ai.brokk.ctl.CtlKeyManager;
import ai.brokk.ctl.InstanceRecord;
import ai.brokk.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CtlHttpServerTest {

    @Test
    public void testCtlInfoAuthFlow(@TempDir Path tempDir) throws Exception {
        // prepare a dummy instance record
        List<String> projects = List.of("/path/to/projA", "/path/to/projB");
        InstanceRecord rec = new InstanceRecord(
                "instance-123",
                Integer.valueOf(4242),
                "127.0.0.1:0",
                projects,
                "0.1.0-test",
                System.currentTimeMillis(),
                System.currentTimeMillis()
        );

        CtlConfigPaths paths = CtlConfigPaths.forBaseConfigDir(tempDir);
        CtlKeyManager keyManager = new CtlKeyManager(paths);

        CtlHttpServer server = new CtlHttpServer(rec, keyManager, "127.0.0.1", 0);
        try {
            server.start();
            int port = server.getPort();
            String base = "http://127.0.0.1:" + port;

            // ensure key exists
            String correctKey = keyManager.loadOrCreateKey();
            assertNotNull(correctKey);
            assertFalse(correctKey.isBlank());

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();

            // 1) No header -> 401
            HttpRequest reqNoHeader = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/ctl-info"))
                    .GET()
                    .build();
            HttpResponse<String> respNoHeader = client.send(reqNoHeader, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, respNoHeader.statusCode(), "expected 401 when no key provided");
            Map<String, Object> bodyNoHeader = Json.fromJson(respNoHeader.body(), new TypeReference<>() {});
            assertEquals("unauthorized", bodyNoHeader.get("error"));

            // 2) Wrong header -> 401
            HttpRequest reqWrong = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/ctl-info"))
                    .header("Brokk-CTL-Key", "bad-key")
                    .GET()
                    .build();
            HttpResponse<String> respWrong = client.send(reqWrong, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, respWrong.statusCode(), "expected 401 when wrong key provided");
            Map<String, Object> bodyWrong = Json.fromJson(respWrong.body(), new TypeReference<>() {});
            assertEquals("unauthorized", bodyWrong.get("error"));

            // 3) Correct header -> 200 and payload contains expected fields
            HttpRequest reqOk = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/ctl-info"))
                    .header("Brokk-CTL-Key", correctKey)
                    .GET()
                    .build();
            HttpResponse<String> respOk = client.send(reqOk, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, respOk.statusCode(), "expected 200 when correct key provided");
            Map<String, Object> bodyOk = Json.fromJson(respOk.body(), new TypeReference<>() {});

            // required fields present
            assertEquals(rec.instanceId, bodyOk.get("instanceId"));
            // pid may come back as Integer/Number
            Object pidObj = bodyOk.get("pid");
            assertNotNull(pidObj);
            assertTrue(pidObj instanceof Number);
            assertEquals(rec.pid.intValue(), ((Number) pidObj).intValue());

            assertEquals(rec.listenAddr, bodyOk.get("listenAddr"));

            Object projectsObj = bodyOk.get("projects");
            assertTrue(projectsObj instanceof List, "projects should be an array");
            @SuppressWarnings("unchecked")
            List<Object> projectsResp = (List<Object>) projectsObj;
            assertEquals(projects.size(), projectsResp.size());
            for (int i = 0; i < projects.size(); i++) {
                assertEquals(projects.get(i), projectsResp.get(i));
            }

            assertEquals(rec.brokkctlVersion, bodyOk.get("brokkctlVersion"));

            Object capsObj = bodyOk.get("supportedCapabilities");
            assertTrue(capsObj instanceof List, "supportedCapabilities should be an array");
            @SuppressWarnings("unchecked")
            List<Object> caps = (List<Object>) capsObj;
            // server currently advertises a few capabilities; ensure exec.start is present
            assertTrue(caps.stream().anyMatch(x -> "exec.start".equals(x.toString())), "supportedCapabilities should include exec.start");

        } finally {
            server.stop(0);
        }
    }
}
