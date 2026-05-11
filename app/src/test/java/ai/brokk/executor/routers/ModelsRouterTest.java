package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelsRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelsRouter modelsRouter;
    private TestService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        var project = new MainProject(tempDir);
        var serviceProvider = TestService.testProvider(project);
        var contextManager = new ContextManager(project, serviceProvider);
        service = serviceProvider.testService();
        modelsRouter = new ModelsRouter(contextManager);
    }

    @AfterEach
    void tearDown() {
        MainProject.setHeadlessProxySettingOverride(null);
    }

    @Test
    void handleGetModels_returnsModelsArray() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/models");
        modelsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertTrue(body.containsKey("models"));
        assertTrue(body.get("models") instanceof List);
    }

    @Test
    void handleGetModels_includesTokenBudgetsWhenKnown() throws Exception {
        service.setAvailableModels(Map.of("model-a", TestService.modelInfo(true, false)));

        var exchange = TestHttpExchange.request("GET", "/v1/models");
        modelsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, List<Map<String, Object>>> body =
                MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        var model = body.get("models").getFirst();
        assertEquals("model-a", model.get("name"));
        assertEquals(8192, model.get("maxInputTokens"));
        assertEquals(2048, model.get("maxOutputTokens"));
        assertEquals(Boolean.TRUE, model.get("budgetAvailable"));
        assertEquals(Boolean.FALSE, model.get("tokensEstimated"));
    }

    @Test
    void handleGetModels_marksCustomProviderBudgetsEstimated() throws Exception {
        MainProject.setHeadlessProxySettingOverride(MainProject.LlmProxySetting.CUSTOM);
        service.setAvailableModels(Map.of("custom-model", TestService.modelInfo(true, false)));

        var exchange = TestHttpExchange.request("GET", "/v1/models");
        modelsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, List<Map<String, Object>>> body =
                MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        var model = body.get("models").getFirst();
        assertEquals("custom-model", model.get("name"));
        assertEquals(Boolean.TRUE, model.get("budgetAvailable"));
        assertEquals(Boolean.TRUE, model.get("tokensEstimated"));
    }

    @Test
    void handleGetModels_marksBudgetUnavailableWhenMetadataIsMissing() throws Exception {
        service.setAvailableModels(Map.of(
                "model-a",
                Map.of("supported_openai_params", List.of("temperature"), "supports_reasoning_disable", false)));

        var exchange = TestHttpExchange.request("GET", "/v1/models");
        modelsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, List<Map<String, Object>>> body =
                MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        var model = body.get("models").getFirst();
        assertEquals(Boolean.FALSE, model.get("budgetAvailable"));
        assertEquals(Boolean.FALSE, model.get("tokensEstimated"));
        assertFalse(model.containsKey("maxInputTokens"));
        assertFalse(model.containsKey("maxOutputTokens"));
    }

    @Test
    void handleGetModelBudget_returnsBudgetForExactModelName() throws Exception {
        service.setAvailableModels(Map.of("provider/model a", TestService.modelInfo(false, false)));

        var modelName = URLEncoder.encode("provider/model a", StandardCharsets.UTF_8);
        var exchange = TestHttpExchange.request("GET", "/v1/models/" + modelName + "/budget");
        modelsRouter.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("provider/model a", body.get("model"));
        assertEquals(8192, body.get("maxInputTokens"));
        assertEquals(2048, body.get("maxOutputTokens"));
        assertEquals(Boolean.TRUE, body.get("budgetAvailable"));
        assertEquals(Boolean.FALSE, body.get("tokensEstimated"));
    }

    @Test
    void handleGetModelBudget_returnsNotFoundWhenBudgetIsUnknown() throws Exception {
        var exchange = TestHttpExchange.request("GET", "/v1/models/unknown/budget");
        modelsRouter.handle(exchange);

        assertEquals(404, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.NOT_FOUND, payload.code());
    }

    @Test
    void handleGetModelBudget_returnsNotFoundWhenModelIsUnavailable() throws Exception {
        service.setAvailableModels(Map.of(AbstractService.UNAVAILABLE, TestService.modelInfo(false, false)));

        var modelName = URLEncoder.encode(AbstractService.UNAVAILABLE, StandardCharsets.UTF_8);
        var exchange = TestHttpExchange.request("GET", "/v1/models/" + modelName + "/budget");
        modelsRouter.handle(exchange);

        assertEquals(404, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.NOT_FOUND, payload.code());
    }

    @Test
    void handlePostModels_returnsMethodNotAllowed() throws Exception {
        var exchange = TestHttpExchange.request("POST", "/v1/models");
        modelsRouter.handle(exchange);

        assertEquals(405, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.METHOD_NOT_ALLOWED, payload.code());
    }
}
