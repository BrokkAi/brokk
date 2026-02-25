package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessExecutorMainMtlsIntegrationTest {

    private HeadlessExecutorMain executor;
    private Path serverKeystorePath;
    private String serverKeystorePassword;
    private Path clientCaPath;
    private String authToken = "mtls-test-token";
    private int port;
    private SSLContext clientSslContextWithCert;
    private SSLContext clientSslContextNoCert;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        Path workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir.resolve(".brokk/llm-history"));
        Files.writeString(workspaceDir.resolve(".brokk/project.properties"), "# test\n");

        // 1. Generate Server Cert
        var serverCertData = SelfSignedSslUtil.createSelfSignedCertificate("localhost");
        serverKeystorePath = tempDir.resolve("server.jks");
        serverKeystorePassword = new String(serverCertData.password());
        try (var fos = new FileOutputStream(serverKeystorePath.toFile())) {
            serverCertData.keyStore().store(fos, serverCertData.password());
        }

        // 2. Generate Client Cert and save its public cert as the CA the server trusts
        var clientCertData = SelfSignedSslUtil.createSelfSignedCertificate("brokk-client");
        clientCaPath = tempDir.resolve("client-ca.pem");
        Files.writeString(
                clientCaPath,
                "-----BEGIN CERTIFICATE-----\n"
                        + java.util.Base64.getMimeEncoder()
                                .encodeToString(clientCertData.certificate().getEncoded())
                        + "\n-----END CERTIFICATE-----");

        // 3. Client Context 1: Trusts server, but HAS NO client certificate
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(serverCertData.keyStore());
        clientSslContextNoCert = SSLContext.getInstance("TLS");
        clientSslContextNoCert.init(null, tmf.getTrustManagers(), null);

        // 4. Client Context 2: Trusts server AND HAS client certificate
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientCertData.keyStore(), clientCertData.password());
        clientSslContextWithCert = SSLContext.getInstance("TLS");
        clientSslContextWithCert.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        // 5. Start Executor with mTLS required
        var project = new MainProject(workspaceDir);
        var cm = new ContextManager(project, TestService.provider(project));
        var tlsConfig = new HeadlessExecutorMain.TlsConfig(
                true, serverKeystorePath.toString(), serverKeystorePassword, clientCaPath.toString(), true);

        executor = new HeadlessExecutorMain(UUID.randomUUID(), "127.0.0.1:0", authToken, cm, tlsConfig);
        executor.start();
        port = executor.getPort();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.stop(0);
    }

    @Test
    void rejectsRequestWithoutClientCertificate() throws Exception {
        var url = URI.create("https://127.0.0.1:" + port + "/health/live").toURL();
        var conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(clientSslContextNoCert.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);

        // Should fail during handshake or return 403/connection reset depending on JDK version/implementation
        assertThrows(Exception.class, conn::getResponseCode);
    }

    @Test
    void acceptsRequestWithValidClientCertificate() throws Exception {
        var url = URI.create("https://127.0.0.1:" + port + "/health/live").toURL();
        var conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(clientSslContextWithCert.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);

        assertEquals(200, conn.getResponseCode());
    }
}
