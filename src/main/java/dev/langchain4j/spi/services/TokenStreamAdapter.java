package dev.langchain4j.spi.services;

import java.lang.reflect.Type;

import dev.langchain4j.Internal;
import dev.langchain4j.service.TokenStream;

@Internal
public interface TokenStreamAdapter {

    boolean canAdaptTokenStreamTo(Type type);

    Object adapt(TokenStream tokenStream);
}
