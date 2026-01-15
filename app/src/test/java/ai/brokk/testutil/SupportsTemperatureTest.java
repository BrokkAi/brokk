package ai.brokk.testutil;

import ai.brokk.project.IProject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class SupportsTemperatureTest {

    private static class ExposedTestService extends TestService {
        ExposedTestService(IProject project) {
            super(project);
        }

        void setModelLocations(Map<String, String> modelLocations) {
            this.modelLocations = modelLocations;
        }

        void setModelInfoMap(Map<String, Map<String, Object>> modelInfoMap) {
            this.modelInfoMap = modelInfoMap;
        }
    }

    @Test
    void supportsTemperature_returnsTrue_whenTemperatureInParams() {
        ExposedTestService service = new ExposedTestService(new IProject() {});
        String modelName = "MyModel";
        String location = "loc/my-model";

        service.setModelLocations(Map.of(modelName, location));
        service.setModelInfoMap(Map.of(
            location, Map.of("supported_openai_params", List.of("temperature", "top_p"))
        ));

        Assertions.assertTrue(service.supportsTemperature(modelName), 
            "Expected supportsTemperature to be true when 'temperature' is in supported_openai_params");
    }

    @Test
    void supportsTemperature_returnsFalse_whenTemperatureMissingFromParams() {
        ExposedTestService service = new ExposedTestService(new IProject() {});
        String modelName = "MyModel";
        String location = "loc/my-model";

        service.setModelLocations(Map.of(modelName, location));
        service.setModelInfoMap(Map.of(
            location, Map.of("supported_openai_params", List.of("top_p"))
        ));

        Assertions.assertFalse(service.supportsTemperature(modelName), 
            "Expected supportsTemperature to be false when 'temperature' is missing from supported_openai_params");
    }

    @Test
    void supportsTemperature_returnsFalse_whenModelUnknown() {
        ExposedTestService service = new ExposedTestService(new IProject() {});
        
        Assertions.assertFalse(service.supportsTemperature("UnknownModel"), 
            "Expected supportsTemperature to be false for unknown model names");
    }
}
