package dev.langchain4j.model.openai.spi;

import java.util.function.Supplier;

import dev.langchain4j.Internal;
import dev.langchain4j.model.openai.OpenAiStreamingLanguageModel;

/**
 * A factory for building {@link OpenAiStreamingLanguageModel.OpenAiStreamingLanguageModelBuilder} instances.
 */
@Internal
public interface OpenAiStreamingLanguageModelBuilderFactory extends Supplier<OpenAiStreamingLanguageModel.OpenAiStreamingLanguageModelBuilder> {
}
