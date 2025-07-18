package dev.langchain4j.model.openai.spi;

import java.util.function.Supplier;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * A factory for building {@link OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder} instances.
 */
public interface OpenAiStreamingChatModelBuilderFactory extends Supplier<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder> {
}
