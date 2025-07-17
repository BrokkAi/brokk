package dev.langchain4j.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.Internal;

@Internal
public class InternalStreamingChatResponseHandlerUtils {

    private static final Logger log = LoggerFactory.getLogger(InternalStreamingChatResponseHandlerUtils.class);

    public static void withLoggingExceptions(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("An exception occurred during the invocation of StreamingChatResponseHandler.onError(). "
                    + "This exception has been ignored.", e);
        }
    }
}
