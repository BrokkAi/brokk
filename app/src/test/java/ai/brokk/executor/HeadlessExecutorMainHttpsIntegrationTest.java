package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessExecutorMainHttpsIntegrationTest {

    private HeadlessExecutorMain executor;
    private Path keystorePath;
    private String keystorePassword;
    private String authToken = "https-test-token";
    private int port;
    private SSLContext clientSslContext;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        // 1. Prepare Workspace
        Path workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir.resolve(".brokk/llm-history"));
        Files.writeString(workspaceDir.resolve(".brokk/project.properties"), "# test\n");

        // 2. Generate Self-Signed Cert
        var certData = SelfSignedSslUtil.createSelfSignedCertificate("localhost");
        keystorePath = tempDir.resolve("keystore.jks");
        keystorePassword = new String(certData.password());
        try (var fos = new FileOutputStream(keystorePath.toFile())) {
            certData.keyStore().store(fos, certData.password());
        }

        // 3. Prepare Client SSL Context to trust the self-signed cert
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(certData.keyStore());
        clientSslContext = SSLContext.getInstance("TLS");
        clientSslContext.init(null, tmf.getTrustManagers(), null);

        // 4. Start Executor with TLS Config
        var project = new MainProject(workspaceDir);
        var cm = new ContextManager(project, TestService.provider(project));
        var tlsConfig =
                new HeadlessExecutorMain.TlsConfig(true, keystorePath.toString(), keystorePassword, null, false);

        executor = new HeadlessExecutorMain(UUID.randomUUID(), "127.0.0.1:0", authToken, cm, tlsConfig);
        executor.start();
        port = executor.getPort();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.stop(0);
        }
    }

    @Test
    void testHealthEndpointsOverHttps() throws Exception {
        String baseUrl = "https://127.0.0.1:" + port;

        // Verify /health/live
        {
            var url = URI.create(baseUrl + "/health/live").toURL();
            var conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(clientSslContext.getSocketFactory());
            conn.setHostnameVerifier((hostname, session) -> true);

            assertEquals(200, conn.getResponseCode());
            try (InputStream is = conn.getInputStream()) {
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("execId"));
                assertTrue(body.contains("version"));
            }
        }

        // Verify /health/ready (should be 503 as no session is loaded)
        {
            var url = URI.create(baseUrl + "/health/ready").toURL();
            var conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(clientSslContext.getSocketFactory());
            conn.setHostnameVerifier((hostname, session) -> true);

            assertEquals(503, conn.getResponseCode());
            try (InputStream is = conn.getErrorStream()) {
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("NOT_READY"));
            }
        }
    }
}
