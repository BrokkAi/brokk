package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.Service;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Helper responsible for resolving models and applying model-level overrides (reasoning level,
 * temperature) for JobRunner. This centralizes logic previously living in JobRunner.
 */
public final class JobModelResolver {
    private static final Logger logger = LogManager.getLogger(JobModelResolver.class);

    private final ContextManager cm;

    public JobModelResolver(ContextManager cm) {
        this.cm = cm;
    }

    private record Applied(
            Service.ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {}

    private Applied applyOverrides(
            Service.ModelConfig baseConfig,
            @Nullable String reasoningLevelOverride,
            @Nullable Double temperatureOverride) {

        Service.ReasoningLevel reasoning =
                Service.ReasoningLevel.fromString(reasoningLevelOverride, baseConfig.reasoning());

        var config = reasoning == baseConfig.reasoning()
                ? baseConfig
                : new Service.ModelConfig(baseConfig.name(), reasoning, baseConfig.tier());

        @Nullable OpenAiChatRequestParameters.Builder parametersOverride = null;
        if (temperatureOverride != null) {
            if (cm.getService().supportsTemperature(baseConfig.name())) {
                parametersOverride = OpenAiChatRequestParameters.builder().temperature(temperatureOverride);
            } else {
                logger.debug("Skipping temperature override for model {} as it is not supported.", baseConfig.name());
            }
        }

        return new Applied(config, parametersOverride);
    }

    public StreamingChatModel resolveModelOrThrow(
            String name, @Nullable String reasoningLevelOverride, @Nullable Double temperatureOverride) {
        var applied = applyOverrides(new Service.ModelConfig(name), reasoningLevelOverride, temperatureOverride);
        var model = cm.getService().getModel(applied.config(), applied.parametersOverride());
        if (model == null) {
            throw new IllegalArgumentException("MODEL_UNAVAILABLE: " + name);
        }
        return model;
    }

    public StreamingChatModel resolveModelOrThrow(
            Service.ModelConfig baseConfig,
            @Nullable String reasoningLevelOverride,
            @Nullable Double temperatureOverride) {
        var applied = applyOverrides(baseConfig, reasoningLevelOverride, temperatureOverride);
        var model = cm.getService().getModel(applied.config(), applied.parametersOverride());
        if (model == null) {
            throw new IllegalArgumentException("MODEL_UNAVAILABLE: " + baseConfig.name());
        }
        return model;
    }

    public StreamingChatModel defaultCodeModel(JobSpec spec) {
        var service = cm.getService();
        var baseConfig = Service.ModelConfig.from(cm.getCodeModel(), service);
        return resolveModelOrThrow(baseConfig, spec.reasoningLevelCode(), spec.temperatureCode());
    }

    public StreamingChatModel defaultScanModel(JobSpec spec) {
        var service = cm.getService();
        var baseConfig = Service.ModelConfig.from(service.getScanModel(), service);
        return resolveModelOrThrow(baseConfig, spec.reasoningLevel(), spec.temperature());
    }

    /**
     * Plan-mode scan model selection helper. Mirrors previous behavior in JobRunner:
     * prefer explicit spec.scanModel (trimmed, non-empty) otherwise use provided project default supplier.
     */
    public static String chooseScanModelNameForPlan(
            JobSpec spec, java.util.function.Supplier<String> projectDefaultSupplier) {
        String raw = spec.scanModel();
        if (raw != null) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return projectDefaultSupplier.get();
    }
}
