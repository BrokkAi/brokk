package io.github.jbellis.brokk;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThinkTagInterceptorTest {

    private TestHandler delegate;
    private ThinkTagInterceptor interceptor;

    @BeforeEach
    void setUp() {
        delegate = new TestHandler();
        interceptor = new ThinkTagInterceptor(delegate);
    }

    private static class TestHandler implements StreamingChatResponseHandler {
        final StringBuilder partialResponses = new StringBuilder();
        final List<String> reasoningCalls = new ArrayList<>();
        @Nullable
        ChatResponse completedResponse;
        @Nullable
        Throwable error;
        boolean completed = false;

        @Override
        public void onPartialResponse(String token) {
            partialResponses.append(token);
        }

        @Override
        public void onReasoningResponse(String reasoningContent) {
            reasoningCalls.add(reasoningContent);
        }

        @Override
        public void onCompleteResponse(@Nullable ChatResponse response) {
            this.completedResponse = response;
            this.completed = true;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }
    }

    @Test
    void shouldPassThroughTextWithoutTags() {
        interceptor.onPartialResponse("Hello, world!");
        assertEquals("Hello, world!", delegate.partialResponses.toString());
        assertTrue(delegate.reasoningCalls.isEmpty());
    }

    @Test
    void shouldExtractReasoningFromSingleToken() {
        interceptor.onPartialResponse("some text <think>reasoning here</think> more text");
        assertEquals("some text  more text", delegate.partialResponses.toString());
        assertEquals(List.of("reasoning here"), delegate.reasoningCalls);
    }

    @Test
    void shouldHandleTagSplitAcrossTokens() {
        interceptor.onPartialResponse("prefix ");
        interceptor.onPartialResponse("<");
        interceptor.onPartialResponse("think");
        interceptor.onPartialResponse(">");
        interceptor.onPartialResponse("inside");
        interceptor.onPartialResponse("</");
        interceptor.onPartialResponse("think");
        interceptor.onPartialResponse(">");
        interceptor.onPartialResponse(" suffix");

        assertEquals("prefix  suffix", delegate.partialResponses.toString());
        assertEquals(List.of("inside"), delegate.reasoningCalls);
    }

    @Test
    void shouldHandleMultipleTags() {
        interceptor.onPartialResponse("one<think>two</think>three<think>four</think>five");
        assertEquals("onethreefive", delegate.partialResponses.toString());
        assertEquals(List.of("two", "four"), delegate.reasoningCalls);
    }

    @Test
    void shouldFlushUnclosedTagOnComplete() {
        interceptor.onPartialResponse("text <think>unclosed");
        assertNull(delegate.completedResponse);
        assertFalse(delegate.completed);

        interceptor.onCompleteResponse(null);
        assertEquals("text <think>unclosed", delegate.partialResponses.toString());
        assertTrue(delegate.reasoningCalls.isEmpty());
        assertTrue(delegate.completed);
    }

    @Test
    void shouldFlushUnclosedTagOnError() {
        var e = new RuntimeException("test error");
        interceptor.onPartialResponse("text <think>unclosed on error");
        assertNull(delegate.error);

        interceptor.onError(e);
        assertEquals("text <think>unclosed on error", delegate.partialResponses.toString());
        assertTrue(delegate.reasoningCalls.isEmpty());
        assertEquals(e, delegate.error);
    }

    @Test
    void shouldHandleMalformedOpenTag() {
        interceptor.onPartialResponse("this is <thinking> not a tag");
        assertEquals("this is <thinking> not a tag", delegate.partialResponses.toString());
        assertTrue(delegate.reasoningCalls.isEmpty());
    }

    @Test
    void shouldHandleMalformedCloseTag() {
        interceptor.onPartialResponse("<think>oops </thinking>");
        interceptor.onCompleteResponse(null);

        assertEquals("<think>oops </thinking>", delegate.partialResponses.toString());
        assertTrue(delegate.reasoningCalls.isEmpty());
    }

    @Test
    void shouldHandleLessThanInsideReasoning() {
        interceptor.onPartialResponse("<think>a < b</think>");
        assertEquals("", delegate.partialResponses.toString());
        assertEquals(List.of("a < b"), delegate.reasoningCalls);
    }

    @Test
    void shouldHandleEmptyTag() {
        interceptor.onPartialResponse("<think></think>");
        assertEquals("", delegate.partialResponses.toString());
        assertTrue(delegate.reasoningCalls.isEmpty());
    }

    @Test
    void shouldHandleEmptyTagWithContentFollowing() {
        interceptor.onPartialResponse("<think></think>foo");
        assertEquals("foo", delegate.partialResponses.toString());
        assertTrue(delegate.reasoningCalls.isEmpty());
    }

    @Test
    void shouldFlushPartialOpenTagOnComplete() {
        interceptor.onPartialResponse("text <thi");
        interceptor.onCompleteResponse(null);
        assertEquals("text <thi", delegate.partialResponses.toString());
        assertTrue(delegate.reasoningCalls.isEmpty());
    }

    @Test
    void shouldFlushPartialCloseTagOnComplete() {
        interceptor.onPartialResponse("<think>reasoning</th");
        interceptor.onCompleteResponse(null);
        assertEquals("<think>reasoning</th", delegate.partialResponses.toString());
        assertTrue(delegate.reasoningCalls.isEmpty());
    }

    @Test
    void shouldDelegateOnReasoningResponse() {
        interceptor.onReasoningResponse("delegate test");
        assertEquals(List.of("delegate test"), delegate.reasoningCalls);
    }
}
