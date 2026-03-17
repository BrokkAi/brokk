package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.testutil.TestProject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServicePreviewAutocompleteTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void previewAutocompleteModelName_prefersCodestralAheadOfConfiguredQwenCoder() {
        var project = new ConfigurableTestProject(tempDir, "qwen2.5-coder-7b");
        var service = new PreviewAutocompleteTestService(project);
        service.addModel("qwen2.5-coder-7b");
        service.addModel("codestral-2501");

        assertEquals("codestral-2501", service.previewAutocompleteModelName());
    }

    @Test
    void previewAutocompleteModelName_prefersCodestralWhenConfiguredCodeModelIsGeneric() {
        var project = new ConfigurableTestProject(tempDir, "gpt-5-mini");
        var service = new PreviewAutocompleteTestService(project);
        service.addModel("gpt-5-mini");
        service.addModel("codestral-2501");
        service.addModel("starcoder2-15b");

        assertEquals("codestral-2501", service.previewAutocompleteModelName());
    }

    @Test
    void extractFimCompletionText_readsTextChoice() throws Exception {
        JsonNode response = OBJECT_MAPPER.readTree("""
                {
                  "choices": [
                    {
                      "text": " completion();"
                    }
                  ]
                }
                """);

        assertEquals(" completion();", Service.extractFimCompletionText(response));
    }

    @Test
    void extractFimCompletionText_readsStructuredMessageContent() throws Exception {
        JsonNode response = OBJECT_MAPPER.readTree("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": [
                          { "type": "text", "text": "foo" },
                          { "type": "text", "text": "()" }
                        ]
                      }
                    }
                  ]
                }
                """);

        assertEquals("foo()", Service.extractFimCompletionText(response));
    }

    private static final class ConfigurableTestProject extends TestProject {
        private final AbstractService.ModelConfig codeModelConfig;

        private ConfigurableTestProject(Path root, String codeModelName) {
            super(root);
            this.codeModelConfig = new AbstractService.ModelConfig(codeModelName);
        }

        @Override
        public AbstractService.ModelConfig getModelConfig(ModelType modelType) {
            if (modelType == ModelType.CODE) {
                return codeModelConfig;
            }
            return new AbstractService.ModelConfig("test-model");
        }
    }

    private static final class PreviewAutocompleteTestService extends AbstractService {
        private PreviewAutocompleteTestService(IProject project) {
            super(project);
            this.modelLocations = new HashMap<>();
            this.modelInfoMap = new HashMap<>();
        }

        private void addModel(String modelName) {
            modelLocations.put(modelName, modelName);
            modelInfoMap.put(modelName, Map.of());
        }

        @Override
        public float getUserBalance() {
            return 0;
        }

        @Override
        public void sendFeedback(String category, String feedbackText, boolean includeDebugLog, File screenshotFile) {}

        @Override
        public JsonNode reportClientException(JsonNode exceptionReport) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }
}
