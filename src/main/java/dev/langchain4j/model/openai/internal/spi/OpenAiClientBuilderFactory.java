package dev.langchain4j.model.openai.internal.spi;

import java.util.function.Supplier;

import dev.langchain4j.model.openai.internal.OpenAiClient;

@SuppressWarnings("rawtypes")
public interface OpenAiClientBuilderFactory extends Supplier<OpenAiClient.Builder> {
}
