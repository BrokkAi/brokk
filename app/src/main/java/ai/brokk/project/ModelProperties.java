package ai.brokk.project;

import ai.brokk.AbstractService.ModelConfig;
import ai.brokk.AbstractService.ReasoningLevel;
import ai.brokk.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class ModelProperties {
    private static final Logger logger = LogManager.getLogger(ModelProperties.class);

    // Model name constants
    public static final String GPT_5 = "gpt-5";
    private static final String GEMINI_3_PRO_PREVIEW = "gemini-3-pro-preview";
    private static final String FLASH_2_0 = "gemini-2.0-flash";
    private static final String FLASH_3 = "gemini-3-flash-preview";
    private static final String GPT_5_NANO = "gpt-5-nano";
    private static final String GCF_1 = "grok-code-fast-1";
    private static final String HAIKU_3 = "claude-haiku-3";
    private static final String FLASH_2_0_LITE = "gemini-2.0-flash-lite";
    private static final String OPUS_4_5 = "claude-opus-4-5";
    private static final String HAIKU_4_5 = "claude-haiku-4-5";
    private static final String GPT_5_MINI = "gpt-5-mini";
    private static final String GPT_5_2 = "gpt-5.2";

    // Common configurations. Note that we override thinking levels in some cases for speed.
    private static final ModelConfig gpt5Nano = new ModelConfig(GPT_5_NANO);
    private static final ModelConfig gpt5Mini = new ModelConfig(GPT_5_MINI, ReasoningLevel.LOW);
    private static final ModelConfig gpt5_2 = new ModelConfig(GPT_5_2, ReasoningLevel.MEDIUM);

    private static final ModelConfig haiku3 = new ModelConfig(HAIKU_3);
    private static final ModelConfig haiku4_5 = new ModelConfig(HAIKU_4_5);
    private static final ModelConfig opus4_5 = new ModelConfig(OPUS_4_5, ReasoningLevel.DISABLE);

    private static final ModelConfig flash2Lite = new ModelConfig(FLASH_2_0_LITE);
    private static final ModelConfig flash2 = new ModelConfig(FLASH_2_0);
    private static final ModelConfig flash3 = new ModelConfig(FLASH_3, ReasoningLevel.DISABLE);
    private static final ModelConfig gp3 = new ModelConfig(GEMINI_3_PRO_PREVIEW);

    private static final ModelConfig gcf1 = new ModelConfig(GCF_1);

    // these models are defined for low-latency use cases that don't require high intelligence
    public static final Set<String> SYSTEM_ONLY_MODELS = Set.of(FLASH_2_0_LITE, GPT_5_NANO, HAIKU_3);

    // Json stuff
    public static final String FAVORITE_MODELS_KEY = "favoriteModelsJson";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Default vendor selection for "Other Models" settings
    public static final String DEFAULT_VENDOR = "Default";

    /**
     * Current version for model settings. Increment this to force a reset of favorite models,
     * code model, and architect model to their current defaults on the next app upgrade.
     */
    public static final int MODEL_SETTINGS_VERSION = 1;

    public static final String MODEL_SETTINGS_VERSION_KEY = "modelSettingsVersion";

    private ModelProperties() {}

    static final List<Service.FavoriteModel> DEFAULT_FAVORITE_MODELS = List.of(
            new Service.FavoriteModel("Opus 4.5", opus4_5),
            new Service.FavoriteModel("GPT-5.2", gpt5_2),
            new Service.FavoriteModel("Flash 3", flash3),
            new Service.FavoriteModel("Haiku 4.5", haiku4_5));

    /**
     * Enum representing the different model configuration slots persisted in global properties.
     * Each enum constant owns its properties key and preferred default ModelConfig.
     */
    public enum ModelType {
        // directly selected in the UI
        CODE("codeConfig", flash3, gcf1),
        ARCHITECT("architectConfig", opus4_5, gcf1),

        // indirectly selectable via vendor preference
        SUMMARIZE("quickConfig", gpt5Mini, gcf1),
        QUICK_EDIT("quickEditConfig", flash3, gcf1),
        QUICKEST("quickestConfig", flash2Lite),
        COMMIT_MESSAGE("commitMessageConfig", flash3, flash2),
        SCAN("scanConfig", flash3, gcf1),
        ARCHITECT_FALLBACK("architectFallbackConfig", gp3, gcf1),
        BUILD_PROCESSOR("buildProcessorConfig", gpt5Mini, gpt5Nano);

        public final String propertyKey;
        private final ModelConfig defaultConfig;
        private final ModelConfig defaultFreeConfig;

        ModelType(String propertyKey, ModelConfig defaultConfig) {
            this(propertyKey, defaultConfig, defaultConfig);
        }

        ModelType(String propertyKey, ModelConfig defaultConfig, ModelConfig defaultFreeConfig) {
            this.propertyKey = propertyKey;
            this.defaultConfig = defaultConfig;
            this.defaultFreeConfig = defaultFreeConfig;
        }

        public ModelConfig defaultConfig() {
            return defaultConfig;
        }

        public ModelConfig freeConfig() {
            return defaultFreeConfig;
        }
    }

    // lazily initialized to avoid circular dependency
    private static @Nullable Map<String, Map<ModelType, ModelConfig>> vendorModelMap;

    private static Map<String, Map<ModelType, ModelConfig>> getVendorModelMap() {
        if (vendorModelMap == null) {
            // Use LinkedHashMap to maintain a consistent order in the UI
            var map = new LinkedHashMap<String, Map<ModelType, ModelConfig>>();
            map.put(
                    "Anthropic",
                    Map.of(
                            ModelType.SUMMARIZE, haiku3,
                            ModelType.QUICK_EDIT, haiku4_5,
                            ModelType.QUICKEST, haiku3,
                            ModelType.COMMIT_MESSAGE, haiku3,
                            ModelType.SCAN, haiku4_5,
                            ModelType.ARCHITECT_FALLBACK, opus4_5,
                            ModelType.BUILD_PROCESSOR, haiku4_5));
            map.put(
                    "Gemini",
                    Map.of(
                            ModelType.SUMMARIZE, flash3,
                            ModelType.QUICK_EDIT, flash3,
                            ModelType.QUICKEST, flash2Lite,
                            ModelType.COMMIT_MESSAGE, flash3,
                            ModelType.SCAN, flash3,
                            ModelType.ARCHITECT_FALLBACK, gp3,
                            ModelType.BUILD_PROCESSOR, flash3));
            map.put(
                    "OpenAI",
                    Map.of(
                            ModelType.SUMMARIZE, gpt5Mini,
                            ModelType.QUICK_EDIT, gpt5Mini,
                            ModelType.QUICKEST, gpt5Nano,
                            ModelType.COMMIT_MESSAGE, gpt5Mini,
                            ModelType.SCAN, gpt5Mini,
                            ModelType.ARCHITECT_FALLBACK, gpt5_2,
                            ModelType.BUILD_PROCESSOR, gpt5Mini));

            // Validate that all vendors have configurations for all internal ModelTypes
            for (var entry : map.entrySet()) {
                String vendor = entry.getKey();
                Map<ModelType, ModelConfig> configs = entry.getValue();
                for (ModelType type : ModelType.values()) {
                    if (type == ModelType.CODE || type == ModelType.ARCHITECT) {
                        continue;
                    }
                    assert configs.containsKey(type) : "Vendor '" + vendor + "' is missing config for " + type;
                }
            }
            vendorModelMap = map;
        }
        return vendorModelMap;
    }

    public static @Nullable Map<ModelType, ModelConfig> getVendorModels(String vendor) {
        return getVendorModelMap().get(vendor);
    }

    public static Set<String> getAvailableVendors() {
        return getVendorModelMap().keySet();
    }

    /**
     * Reads a ModelConfig for the given modelType from props, with fallback to preferred defaults.
     * Ensures ProcessingTier is non-null (backward compatibility against older JSON).
     */
    static ModelConfig getModelConfig(Properties props, ModelType modelType) {
        String jsonString = props.getProperty(modelType.propertyKey);
        if (jsonString != null && !jsonString.isBlank()) {
            try {
                var mc = objectMapper.readValue(jsonString, ModelConfig.class);
                @SuppressWarnings("RedundantNullCheck")
                ModelConfig checkedMc = (mc.tier() == null)
                        ? new ModelConfig(mc.name(), mc.reasoning(), Service.ProcessingTier.DEFAULT)
                        : mc;
                return checkedMc;
            } catch (Exception e) {
                logger.warn(
                        "Error parsing ModelConfig JSON for {} from key '{}': {}. Using preferred default. JSON: '{}'",
                        modelType,
                        modelType.propertyKey,
                        e.getMessage(),
                        jsonString);
            }
        }
        return modelType.defaultConfig();
    }

    /**
     * Writes the ModelConfig for the given modelType into props.
     * The caller is responsible for persisting the mutated Properties.
     */
    static void setModelConfig(Properties props, ModelType modelType, ModelConfig config) {
        try {
            var jsonString = objectMapper.writeValueAsString(config);
            props.setProperty(modelType.propertyKey, jsonString);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads favorite models from props. Returns defaults on missing/invalid JSON.
     */
    static List<Service.FavoriteModel> loadFavoriteModels(Properties props) {
        String json = props.getProperty(FAVORITE_MODELS_KEY);
        if (json != null && !json.isEmpty()) {
            try {
                var typeFactory = objectMapper.getTypeFactory();
                var listType = typeFactory.constructCollectionType(List.class, Service.FavoriteModel.class);
                return objectMapper.readValue(json, listType);
            } catch (JsonProcessingException e) {
                logger.error("Error loading/casting favorite models from JSON: {}", json, e);
            }
        }
        return new ArrayList<>(DEFAULT_FAVORITE_MODELS);
    }

    /**
     * Saves favorite models to props if changed. Returns true if the value changed.
     * The caller is responsible for persisting the mutated Properties.
     */
    static boolean saveFavoriteModels(Properties props, List<Service.FavoriteModel> favorites) {
        String newJson;
        try {
            newJson = objectMapper.writeValueAsString(favorites);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing favorite models to JSON", e);
        }
        String oldJson = props.getProperty(FAVORITE_MODELS_KEY, "");
        if (!newJson.equals(oldJson)) {
            props.setProperty(FAVORITE_MODELS_KEY, newJson);
            return true;
        }
        return false;
    }

    /**
     * Looks up a favorite model by alias (case-insensitive).
     */
    static Service.FavoriteModel getFavoriteModel(Properties props, String alias) {
        return loadFavoriteModels(props).stream()
                .filter(fm -> fm.alias().equalsIgnoreCase(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown favorite model alias: " + alias));
    }
}
