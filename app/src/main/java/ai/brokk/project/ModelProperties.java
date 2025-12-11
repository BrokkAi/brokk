package ai.brokk.project;

import ai.brokk.AbstractService.ModelConfig;
import ai.brokk.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Centralized accessors for model-related global properties:
 * - Per-ModelType ModelConfig get/set.
 * - Favorite models load/save and lookup.
 *
 * Saving to disk is delegated to the caller (e.g., MainProject.saveGlobalProperties),
 * so these methods only mutate the provided Properties instance.
 */
public final class ModelProperties {
    private static final Logger logger = LogManager.getLogger(ModelProperties.class);

    private static final String FAVORITE_MODELS_KEY = "favoriteModelsJson";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private ModelProperties() {}

    // Default favorite models (moved from MainProject)
    static final List<Service.FavoriteModel> DEFAULT_FAVORITE_MODELS = List.of(
            new Service.FavoriteModel("GPT-5", new ModelConfig(Service.GPT_5)),
            new Service.FavoriteModel("GPT-5 mini", new ModelConfig(Service.GPT_5_MINI)),
            new Service.FavoriteModel("Gemini Pro 2.5", new ModelConfig(Service.GEMINI_2_5_PRO)),
            new Service.FavoriteModel("Sonnet 4.5", new ModelConfig(Service.SONNET_4_5, Service.ReasoningLevel.MEDIUM)),
            new Service.FavoriteModel("Haiku 4.5", new ModelConfig(Service.HAIKU_4_5, Service.ReasoningLevel.DEFAULT)));

    /**
     * Reads a ModelConfig for the given modelType from props, with fallback to preferred defaults.
     * Ensures ProcessingTier is non-null (backward compatibility against older JSON).
     */
    static ModelConfig getModelConfig(Properties props, ModelType modelType) {
        String jsonString = props.getProperty(modelType.propertyKey());
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
                        modelType.propertyKey(),
                        e.getMessage(),
                        jsonString);
            }
        }
        return modelType.preferredConfig();
    }

    /**
     * Writes the ModelConfig for the given modelType into props.
     * The caller is responsible for persisting the mutated Properties.
     */
    static void setModelConfig(Properties props, ModelType modelType, ModelConfig config) {
        try {
            String jsonString = objectMapper.writeValueAsString(config);
            props.setProperty(modelType.propertyKey(), jsonString);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize ModelConfig for " + modelType, e);
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
    enum ModelType {
        QUICK("quickConfig", new ModelConfig(Service.GEMINI_2_0_FLASH)),
        CODE("codeConfig", new ModelConfig(Service.HAIKU_4_5)),
        ARCHITECT("architectConfig", new ModelConfig(Service.GPT_5)),
        QUICK_EDIT("quickEditConfig", new ModelConfig(Service.GEMINI_2_5_FLASH)),
        QUICKEST("quickestConfig", new ModelConfig(Service.GEMINI_2_0_FLASH_LITE)),
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
            for (int i = 0, len = s.length(); i < len; i++) {
                char c = s.charAt(i);
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
}
