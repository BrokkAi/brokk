package io.github.jbellis.brokk;

import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jetbrains.annotations.Nullable;

/**
 * An adapter for StreamingChatResponseHandler that intercepts and processes
 * inline <think>...</think> reasoning tags from the model's response stream.
 * <p>
 * This interceptor uses a state machine to parse tokens as they arrive.
 * Text outside of think-tags is forwarded to the delegate's `onPartialResponse`.
 * Text inside think-tags is buffered and sent to the delegate's `onReasoningResponse`
 * once a complete, closing `</think>` tag is found.
 * <p>
 * This class is not designed to handle nested <think> tags. If an unclosed tag
 * is present when the stream completes or errors out, the buffered content is

 * flushed as regular text to avoid data loss.
 */
public final class ThinkTagInterceptor implements StreamingChatResponseHandler {

    private enum State {
        DEFAULT,      // Outside any tag
        OPENING_TAG,  // Matched '<', checking for "<think>"
        INSIDE_TAG,   // Inside <think>...</think>
        CLOSING_TAG   // Inside <think>... and matched '<', checking for "</think>"
    }

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private final StreamingChatResponseHandler delegate;

    private State state = State.DEFAULT;
    private final StringBuilder partialTagBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();

    public ThinkTagInterceptor(StreamingChatResponseHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onPartialResponse(String token) {
        for (int i = 0; i < token.length(); i++) {
            processChar(token.charAt(i));
        }
    }

    @Override
    public void onReasoningResponse(String reasoningContent) {
        // This interceptor produces reasoning calls, it doesn't consume them.
        // Forward if the underlying model somehow produces both.
        delegate.onReasoningResponse(reasoningContent);
    }

    @Override
    public void onCompleteResponse(@Nullable ChatResponse response) {
        flushUnclosedAsLiteral();
        delegate.onCompleteResponse(response);
    }

    @Override
    public void onError(Throwable error) {
        flushUnclosedAsLiteral();
        delegate.onError(error);
    }

    private void processChar(char c) {
        switch (state) {
            case DEFAULT -> {
                if (c == '<') {
                    state = State.OPENING_TAG;
                    partialTagBuffer.setLength(0);
                    partialTagBuffer.append(c);
                } else {
                    delegate.onPartialResponse(String.valueOf(c));
                }
            }
            case OPENING_TAG -> {
                partialTagBuffer.append(c);
                String currentOpenTag = partialTagBuffer.toString();
                if (OPEN_TAG.equals(currentOpenTag)) {
                    state = State.INSIDE_TAG;
                    partialTagBuffer.setLength(0);
                } else if (!OPEN_TAG.startsWith(currentOpenTag)) {
                    // Not a think tag, treat as literal
                    delegate.onPartialResponse(currentOpenTag);
                    state = State.DEFAULT;
                    partialTagBuffer.setLength(0);
                }
                // else: still matching, continue buffering
            }
            case INSIDE_TAG -> {
                if (c == '<') {
                    state = State.CLOSING_TAG;
                    partialTagBuffer.setLength(0);
                    partialTagBuffer.append(c);
                } else {
                    reasoningBuffer.append(c);
                }
            }
            case CLOSING_TAG -> {
                partialTagBuffer.append(c);
                String currentCloseTag = partialTagBuffer.toString();
                if (CLOSE_TAG.equals(currentCloseTag)) {
                    if (reasoningBuffer.length() > 0) {
                        delegate.onReasoningResponse(reasoningBuffer.toString());
                        reasoningBuffer.setLength(0);
                    }
                    state = State.DEFAULT;
                    partialTagBuffer.setLength(0);
                } else if (!CLOSE_TAG.startsWith(currentCloseTag)) {
                    // Not a closing think tag, treat as literal text inside the reasoning block
                    reasoningBuffer.append(currentCloseTag);
                    state = State.INSIDE_TAG;
                    partialTagBuffer.setLength(0);
                }
                // else: still matching, continue buffering
            }
        }
    }

    /**
     * If the stream ends while a tag is unclosed, flush all buffered content
     * as literal text to avoid losing information.
     */
    private void flushUnclosedAsLiteral() {
        if (state == State.DEFAULT) {
            return;
        }

        var literalFlush = new StringBuilder();

        // If we were matching an open tag, it's just literal text
        if (state == State.OPENING_TAG) {
            literalFlush.append(partialTagBuffer);
        } else {
            // If we were inside or closing, we need to prepend the open tag and the reasoning content
            literalFlush.append(OPEN_TAG);
            literalFlush.append(reasoningBuffer);
            if (state == State.CLOSING_TAG) {
                literalFlush.append(partialTagBuffer);
            }
        }

        if (literalFlush.length() > 0) {
            delegate.onPartialResponse(literalFlush.toString());
        }

        // Reset state
        state = State.DEFAULT;
        partialTagBuffer.setLength(0);
        reasoningBuffer.setLength(0);
    }
}
