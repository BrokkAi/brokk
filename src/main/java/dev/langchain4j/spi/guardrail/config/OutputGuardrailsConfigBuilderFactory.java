package dev.langchain4j.spi.guardrail.config;

import java.util.function.Supplier;

import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;

/**
 * SPI for overriding and/or extending the default {@link OutputGuardrailsConfig.OutputGuardrailsConfigBuilder} implementation.
 */
public interface OutputGuardrailsConfigBuilderFactory
        extends Supplier<OutputGuardrailsConfig.OutputGuardrailsConfigBuilder> {}
