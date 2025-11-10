package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.Service;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestProject;
import ai.brokk.testutil.TestService;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for Service-level error handling that InstructionsPanel depends on.
 * Verifies that the Service layer correctly handles various failure scenarios
 * (offline state, model unavailability, fallback behavior) without throwing exceptions.
 *
 * <p>These tests are fully headless and can run on CI servers.
 */
@DisplayName("InstructionsPanel Error Handling")
class InstructionsPanelErrorHandlingTest {

    @TempDir
    Path tempDir;

    private TestProject testProject;
    private ContextManager contextManager;
    private TestService testService;
    private TestConsoleIO testConsoleIO;

    @BeforeEach
    void setUp() {
        testProject = new TestProject(tempDir);
        contextManager = new ContextManager(testProject);
        testService = new TestService(testProject);
        testConsoleIO = new TestConsoleIO();
    }

    @Test
    @DisplayName("Service.isOnline returns false when quickModel is UnavailableStreamingModel")
    void serviceIsOnline_returnsFalse_whenQuickModelIsUnavailable() {
        // Configure TestService to return an unavailable model
        testService.setQuickestModel(new Service.UnavailableStreamingModel());

        assertFalse(testService.isOnline(), "Service should be offline when quickModel is unavailable");
    }

    @Test
    @DisplayName("TestService.getModel returns a stub model for any config")
    void getModel_returnsStubModel_forAnyConfig() {
        // TestService is a test double that always returns a stub model
        var config = new Service.ModelConfig("any-model-name");

        StreamingChatModel result = testService.getModel(config);

        assertNotNull(result, "TestService should return a stub model for any config");
    }

    @Test
    @DisplayName("TestService.quickestModel returns custom model when configured")
    void quickestModel_returnsCustomModel_whenConfigured() {
        var customModel = new Service.UnavailableStreamingModel();
        testService.setQuickestModel(customModel);

        StreamingChatModel result = testService.quickestModel();

        assertSame(customModel, result, "quickestModel should return the configured custom model");
    }

    @Test
    @DisplayName("TestConsoleIO captures toolError messages")
    void testConsoleIO_capturesToolError() {
        String errorMsg = "Test error message";
        String errorTitle = "Test Title";

        testConsoleIO.toolError(errorMsg, errorTitle);

        assertTrue(testConsoleIO.getErrorLog().contains(errorMsg), "TestConsoleIO should capture toolError message");
    }

    /**
     * This test verifies that when Service.isOnline() returns false, the offline state
     * is correctly detected. InstructionsPanel checks this condition before attempting operations.
     */
    @Test
    @DisplayName("Service behavior when offline - isOnline returns false")
    void serviceBehavior_whenOffline_isOnlineReturnsFalse() {
        // Set up an offline service
        testService.setQuickestModel(new Service.UnavailableStreamingModel());

        // Verify the conditions that InstructionsPanel checks
        assertFalse(testService.isOnline(), "Service should report offline");
        assertInstanceOf(
                Service.UnavailableStreamingModel.class,
                testService.quickestModel(),
                "Quick model should be UnavailableStreamingModel");
    }

    /**
     * Verifies that TestService returns a model for GPT_5_MINI config.
     * This tests the fallback scenario used by InstructionsPanel.
     */
    @Test
    @DisplayName("TestService returns model for GPT_5_MINI fallback")
    void testService_returnsModel_forGpt5MiniFallback() {
        // TestService returns stub models for all configs, including GPT_5_MINI
        StreamingChatModel fallback = testService.getModel(Service.GPT_5_MINI);

        assertNotNull(fallback, "TestService should return a stub model for GPT_5_MINI config");
    }
}
