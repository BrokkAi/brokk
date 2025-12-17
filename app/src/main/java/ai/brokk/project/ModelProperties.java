package ai.brokk.project;

import ai.brokk.AbstractService.ModelConfig;
import ai.brokk.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Centralized accessors for model-related global properties:
 * - Per-ModelType ModelConfig get/set.
 * - Favorite models load/save and lookup.
 *
 * Saving to disk is delegated to the caller (e.g., MainProject.saveGlobalProperties),
 * so these methods only mutate the provided Properties instance.
 */
public final class ModelProperties {
    // Model name constants
    public static final String GPT_5 = "gpt-5";
    public static final String GEMINI_3_PRO_PREVIEW = "gemini-3-pro-preview";
    public static final String GEMINI_2_0_FLASH = "gemini-2.0-flash";
    public static final String GEMINI_2_5_FLASH = "gemini-2.5-flash";
    public static final String GPT_5_NANO = "gpt-5-nano";
    public static final String GCF_1 = "grok-code-fast-1";
    public static final String HAIKU_3 = "claude-haiku-3";
    public static final String GEMINI_2_0_FLASH_LITE = "gemini-2.0-flash-lite";
    private static final Logger logger = LogManager.getLogger(ModelProperties.class);

    private static final String FAVORITE_MODELS_KEY = "favoriteModelsJson";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    // these models are defined for low-latency use cases that don't require high intelligence
    public static final Set<String> SYSTEM_ONLY_MODELS =
            Set.of("gemini-2.0-flash-lite", "gpt-5-nano", "claude-haiku-3");

    private ModelProperties() {}

    public static final String OPUS_4_5 = "claude-opus-4-5";
    public static final String HAIKU_4_5 = "claude-haiku-4-5";
    public static final String GPT_5_MINI = "gpt-5-mini";
    public static final String GPT_5_2 = "gpt-5.2";
    // Default favorite models (moved from MainProject)
    static final List<Service.FavoriteModel> DEFAULT_FAVORITE_MODELS = List.of(
            new Service.FavoriteModel("Opus 4.5", new ModelConfig(OPUS_4_5, Service.ReasoningLevel.DISABLE)),
            new Service.FavoriteModel("GPT-5.2", new ModelConfig(GPT_5_2)),
            new Service.FavoriteModel("GPT-5 mini", new ModelConfig(GPT_5_MINI)),
            new Service.FavoriteModel("Haiku 4.5", new ModelConfig(HAIKU_4_5)));

    public record VendorModels(
            ModelConfig quick,
            ModelConfig quickEdit,
            ModelConfig quickest,
            ModelConfig scan,
            ModelConfig buildProcessor) {}

    private static final Map<String, VendorModels> VENDOR_MODEL_MAP = java.util.Map.of(
            "OpenAI",
            new VendorModels(
                    new ModelConfig(GPT_5_NANO),
                    new ModelConfig(GPT_5_NANO),
                    new ModelConfig(GPT_5_NANO),
                    new ModelConfig(GPT_5_MINI),
                    new ModelConfig(GPT_5_MINI)),
            "Anthropic",
            new VendorModels(
                    new ModelConfig(HAIKU_3),
                    new ModelConfig(HAIKU_4_5),
                    new ModelConfig(HAIKU_3),
                    new ModelConfig(HAIKU_4_5),
                    new ModelConfig(HAIKU_4_5)),
            "Gemini",
            new VendorModels(
                    new ModelConfig(GEMINI_2_0_FLASH),
                    new ModelConfig(GEMINI_2_5_FLASH),
                    new ModelConfig(GEMINI_2_0_FLASH_LITE),
                    new ModelConfig(GEMINI_2_5_FLASH),
                    new ModelConfig(GEMINI_2_5_FLASH)));

    public static @Nullable VendorModels getVendorModels(String vendor) {
        return VENDOR_MODEL_MAP.get(vendor);
    }

    public static VendorModels getDefaultVendorModels() {
        return new VendorModels(
                ModelType.QUICK.defaultConfig(),
                ModelType.QUICK_EDIT.defaultConfig(),
                ModelType.QUICKEST.defaultConfig(),
                ModelType.SCAN.defaultConfig(),
                ModelType.BUILD_PROCESSOR.defaultConfig());
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

    /**
     * Enum representing the different model configuration slots persisted in global properties.
     * Each enum constant owns its properties key and preferred default ModelConfig.
     */
    public enum ModelType {
        QUICK("quickConfig", new ModelConfig(GEMINI_2_0_FLASH)),
        CODE("codeConfig", new ModelConfig(HAIKU_4_5), new ModelConfig(GCF_1)),
        ARCHITECT("architectConfig", new ModelConfig(OPUS_4_5, Service.ReasoningLevel.DISABLE), new ModelConfig(GCF_1)),
        QUICK_EDIT("quickEditConfig", new ModelConfig(GCF_1)),
        QUICKEST("quickestConfig", new ModelConfig(GEMINI_2_0_FLASH_LITE)),
        SCAN("scanConfig", new ModelConfig(GPT_5_MINI), new ModelConfig(GCF_1)),
        BUILD_PROCESSOR("buildProcessorConfig", new ModelConfig(GPT_5_MINI), new ModelConfig(GPT_5_NANO));

        private final String propertyKey;
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
}
