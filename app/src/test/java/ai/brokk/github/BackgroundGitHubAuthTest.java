package ai.brokk.github;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackgroundGitHubAuthTest {

    @BeforeEach
    void setUp() {
        // Reset the service factory and clean up any ongoing auth
        BackgroundGitHubAuth.serviceFactory = null;
        BackgroundGitHubAuth.cancelCurrentAuth();
    }

    @Test
    void testExecutorIsShutDownAfterAuthCompletion() throws InterruptedException {
        // Create a mock service that completes immediately
        var mockFuture = new CompletableFuture<DeviceFlowModels.TokenPollResponse>();

        BackgroundGitHubAuth.serviceFactory = () ->
                new GitHubDeviceFlowService(
                        GitHubAuthConfig.getClientId(), new java.util.concurrent.ScheduledThreadPoolExecutor(1)) {
                    @Override
                    public CompletableFuture<DeviceFlowModels.TokenPollResponse> pollForToken(
                            String deviceCode, int pollInterval) {
                        return mockFuture;
                    }
                };

        var deviceCodeResponse = new DeviceFlowModels.DeviceCodeResponse(
                "test-device-code", "test-user-code", "https://example.com", "verification-uri", 5, 30);

        BackgroundGitHubAuth.startBackgroundAuth(deviceCodeResponse, null);

        // Verify auth is in progress
        assertTrue(BackgroundGitHubAuth.isAuthInProgress());

        // Complete the mock future with a SUCCESS response
        var tokenResponse = new DeviceFlowModels.TokenPollResponse(
                DeviceFlowModels.TokenPollResult.SUCCESS,
                new DeviceFlowModels.TokenResponse("test-token", "bearer", "3600"),
                null);
        mockFuture.complete(tokenResponse);

        // Wait for async cleanup
        Thread.sleep(500);

        // Verify auth is no longer in progress (cleanup happened)
        assertFalse(BackgroundGitHubAuth.isAuthInProgress());
    }

    @Test
    void testCancelCurrentAuthShutDownsExecutor() throws InterruptedException {
        // Create a mock service that never completes
        var mockFuture = new CompletableFuture<DeviceFlowModels.TokenPollResponse>();

        BackgroundGitHubAuth.serviceFactory = () ->
                new GitHubDeviceFlowService(
                        GitHubAuthConfig.getClientId(), new java.util.concurrent.ScheduledThreadPoolExecutor(1)) {
                    @Override
                    public CompletableFuture<DeviceFlowModels.TokenPollResponse> pollForToken(
                            String deviceCode, int pollInterval) {
                        return mockFuture;
                    }
                };

        var deviceCodeResponse = new DeviceFlowModels.DeviceCodeResponse(
                "test-device-code", "test-user-code", "https://example.com", "verification-uri", 5, 30);

        BackgroundGitHubAuth.startBackgroundAuth(deviceCodeResponse, null);

        assertTrue(BackgroundGitHubAuth.isAuthInProgress());

        // Cancel the auth
        BackgroundGitHubAuth.cancelCurrentAuth();

        // Verify auth is no longer in progress
        assertFalse(BackgroundGitHubAuth.isAuthInProgress());
    }

    @Test
    void testServiceFactoryInjection() {
        // Verify that the service factory can be injected
        var factoryCalled = new boolean[1];

        BackgroundGitHubAuth.serviceFactory = () -> {
            factoryCalled[0] = true;
            var mockFuture = new CompletableFuture<DeviceFlowModels.TokenPollResponse>();
            mockFuture.complete(
                    new DeviceFlowModels.TokenPollResponse(DeviceFlowModels.TokenPollResult.DENIED, null, null));
            return new GitHubDeviceFlowService(
                    GitHubAuthConfig.getClientId(), new java.util.concurrent.ScheduledThreadPoolExecutor(1)) {
                @Override
                public CompletableFuture<DeviceFlowModels.TokenPollResponse> pollForToken(
                        String deviceCode, int pollInterval) {
                    return mockFuture;
                }
            };
        };

        var deviceCodeResponse = new DeviceFlowModels.DeviceCodeResponse(
                "test-device-code", "test-user-code", "https://example.com", "verification-uri", 5, 30);

        BackgroundGitHubAuth.startBackgroundAuth(deviceCodeResponse, null);

        assertTrue(factoryCalled[0], "Service factory should have been called");
    }

    @Test
    void testMultipleAuthAttemptsCleanUpIndependently() throws InterruptedException {
        var mockFuture1 = new CompletableFuture<DeviceFlowModels.TokenPollResponse>();
        var mockFuture2 = new CompletableFuture<DeviceFlowModels.TokenPollResponse>();
        var callCount = new int[1];

        BackgroundGitHubAuth.serviceFactory = () -> {
            callCount[0]++;
            var futureToUse = (callCount[0] == 1) ? mockFuture1 : mockFuture2;
            return new GitHubDeviceFlowService(
                    GitHubAuthConfig.getClientId(), new java.util.concurrent.ScheduledThreadPoolExecutor(1)) {
                @Override
                public CompletableFuture<DeviceFlowModels.TokenPollResponse> pollForToken(
                        String deviceCode, int pollInterval) {
                    return futureToUse;
                }
            };
        };

        var deviceCodeResponse = new DeviceFlowModels.DeviceCodeResponse(
                "test-device-code", "test-user-code", "https://example.com", "verification-uri", 5, 30);

        // Start first auth
        BackgroundGitHubAuth.startBackgroundAuth(deviceCodeResponse, null);
        assertTrue(BackgroundGitHubAuth.isAuthInProgress());

        // Start second auth (should cancel the first)
        BackgroundGitHubAuth.startBackgroundAuth(deviceCodeResponse, null);
        assertTrue(BackgroundGitHubAuth.isAuthInProgress());

        // Complete second auth
        mockFuture2.complete(new DeviceFlowModels.TokenPollResponse(
                DeviceFlowModels.TokenPollResult.SUCCESS,
                new DeviceFlowModels.TokenResponse("test-token", "bearer", "3600"),
                null));

        Thread.sleep(500);
        assertFalse(BackgroundGitHubAuth.isAuthInProgress());
    }
}
