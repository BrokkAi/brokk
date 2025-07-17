package dev.langchain4j.model.openai.spi;

import java.util.function.Supplier;

import dev.langchain4j.Internal;
import dev.langchain4j.model.openai.OpenAiImageModel;

/**
 * A factory for building {@link OpenAiImageModel.OpenAiImageModelBuilder} instances.
 */
@Internal
public interface OpenAiImageModelBuilderFactory extends Supplier<OpenAiImageModel.OpenAiImageModelBuilder> {
}
