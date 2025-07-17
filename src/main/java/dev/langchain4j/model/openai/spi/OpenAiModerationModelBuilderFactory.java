package dev.langchain4j.model.openai.spi;

import java.util.function.Supplier;

import dev.langchain4j.Internal;
import dev.langchain4j.model.openai.OpenAiModerationModel;

/**
 * A factory for building {@link OpenAiModerationModel.OpenAiModerationModelBuilder} instances.
 */
@Internal
public interface OpenAiModerationModelBuilderFactory extends Supplier<OpenAiModerationModel.OpenAiModerationModelBuilder> {
}
