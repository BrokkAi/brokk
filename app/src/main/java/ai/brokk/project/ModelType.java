package ai.brokk.project;

import ai.brokk.AbstractService.ModelConfig;
import ai.brokk.Service;

import java.util.Locale;

/**
 * Enum representing the different model configuration slots persisted in global properties.
 * Each enum constant owns its properties key and preferred default ModelConfig.
 */
public enum ModelType {
    QUICK("quickConfig", new ModelConfig(Service.GEMINI_2_0_FLASH)),
    CODE("codeConfig", new ModelConfig(Service.HAIKU_4_5)),
    ARCHITECT("architectConfig", new ModelConfig(Service.GPT_5)),
    QUICK_EDIT("quickEditConfig", new ModelConfig("cerebras/gpt-oss-120b")),
    QUICKEST("quickestConfig", new ModelConfig("gemini-2.0-flash-lite")),
    SCAN("scanConfig", new ModelConfig(Service.GPT_5_MINI));

    private final String propertyKey;
    private final ModelConfig preferredConfig;

    ModelType(String propertyKey, ModelConfig preferredConfig) {
        this.propertyKey = propertyKey;
        this.preferredConfig = preferredConfig;
    }

    /**
     * The string key used in global properties for this model type.
     */
    public String propertyKey() {
        return propertyKey;
    }

    /**
     * The preferred default ModelConfig for this model type.
     */
    public ModelConfig preferredConfig() {
        return preferredConfig;
    }

    /**
     * Human-friendly display name derived from the enum constant.
     */
    public String displayName() {
        String s = name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (cap && Character.isLetter(c)) {
                out.append(Character.toUpperCase(c));
                cap = false;
            } else {
                out.append(c);
            }
            if (c == ' ') cap = true;
        }
        return out.toString();
    }
}
