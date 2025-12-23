package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.Service;
import ai.brokk.project.ModelProperties;
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
        testService.setModel(ModelProperties.ModelType.QUICKEST, new AbstractService.UnavailableStreamingModel());

        assertFalse(testService.isOnline(), "Service should be offline when quickModel is unavailable");
    }

    @Test
    @DisplayName("TestService.quickestModel returns custom model when configured")
    void quickestModel_returnsCustomModel_whenConfigured() {
        var customModel = new Service.UnavailableStreamingModel();
        testService.setModel(ModelProperties.ModelType.QUICKEST, customModel);

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
        testService.setModel(ModelProperties.ModelType.QUICKEST, new AbstractService.UnavailableStreamingModel());

        // Verify the conditions that InstructionsPanel checks
        assertFalse(testService.isOnline(), "Service should report offline");
        assertInstanceOf(
                Service.UnavailableStreamingModel.class,
                testService.quickestModel(),
                "Quick model should be UnavailableStreamingModel");
    }
}
