package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.brokk.project.ModelProperties.ModelType;
import org.junit.jupiter.api.Test;

class ModelPropertiesTest {

    @Test
    void summarizeDefaultUsesGpt54Nano() {
        var summarizeModel = ModelType.SUMMARIZE.defaultConfig().name();

        assertEquals("gpt-5.4-nano", summarizeModel);
        assertFalse(summarizeModel.endsWith("-preview"));
    }
}
