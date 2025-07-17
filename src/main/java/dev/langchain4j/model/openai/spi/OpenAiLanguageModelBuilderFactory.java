package dev.langchain4j.model.openai.spi;

import java.util.function.Supplier;

import dev.langchain4j.Internal;
import dev.langchain4j.model.openai.OpenAiLanguageModel;

/**
 * A factory for building {@link OpenAiLanguageModel.OpenAiLanguageModelBuilder} instances.
 */
@Internal
public interface OpenAiLanguageModelBuilderFactory extends Supplier<OpenAiLanguageModel.OpenAiLanguageModelBuilder> {
}
