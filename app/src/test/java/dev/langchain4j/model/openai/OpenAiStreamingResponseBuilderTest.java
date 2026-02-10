package dev.langchain4j.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Delta;
import dev.langchain4j.model.output.FinishReason;
import java.util.List;
import org.junit.jupiter.api.Test;

public class OpenAiStreamingResponseBuilderTest {

    @Test
    void streaming_opus_final_chunk_with_stop_sets_finish_reason_stop() {
        OpenAiStreamingResponseBuilder builder = new OpenAiStreamingResponseBuilder();

        // initial partials (no finishReason)
        ChatCompletionChoice part1Choice = ChatCompletionChoice.builder()
                .delta(Delta.builder().content("Hel").build())
                .build();

        ChatCompletionResponse part1 = ChatCompletionResponse.builder()
                .model("gpt-4.1-opus")
                .choices(List.of(part1Choice))
                .build();

        builder.append(part1);

        ChatCompletionChoice part2Choice = ChatCompletionChoice.builder()
                .delta(Delta.builder().content("lo").build())
                .build();

        ChatCompletionResponse part2 = ChatCompletionResponse.builder()
                .model("gpt-4.1-opus")
                .choices(List.of(part2Choice))
                .build();

        builder.append(part2);

        // final chunk that sets finishReason to "stop"
        ChatCompletionChoice finalChoice = ChatCompletionChoice.builder()
                .delta(Delta.builder().content("").build())
                .finishReason("stop")
                .build();

        ChatCompletionResponse finalPart = ChatCompletionResponse.builder()
                .model("gpt-4.1-opus")
                .choices(List.of(finalChoice))
                .build();

        builder.append(finalPart);

        ChatResponse chatResponse = builder.build();
        assertNotNull(chatResponse);
        assertTrue(chatResponse.metadata() instanceof OpenAiChatResponseMetadata);

        OpenAiChatResponseMetadata metadata = (OpenAiChatResponseMetadata) chatResponse.metadata();
        assertNotNull(metadata.finishReason(), "finishReason should be non-null when OpenAI provided one");
        assertEquals(FinishReason.STOP, metadata.finishReason());

        // content should be concatenation of deltas "Hel" + "lo" == "Hello"
        assertEquals("Hello", chatResponse.aiMessage().text());
    }

    @Test
    void streaming_unknown_finish_reason_maps_to_other() {
        OpenAiStreamingResponseBuilder builder = new OpenAiStreamingResponseBuilder();

        ChatCompletionChoice choice = ChatCompletionChoice.builder()
                .delta(Delta.builder().content("Hi").build())
                .finishReason("something_new")
                .build();

        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .model("gpt-5.2-flash3")
                .choices(List.of(choice))
                .build();

        builder.append(response);

        ChatResponse chatResponse = builder.build();
        assertNotNull(chatResponse);
        assertTrue(chatResponse.metadata() instanceof OpenAiChatResponseMetadata);

        OpenAiChatResponseMetadata metadata = (OpenAiChatResponseMetadata) chatResponse.metadata();
        assertNotNull(metadata.finishReason(), "finishReason should be non-null for unknown non-null strings");
        assertEquals(FinishReason.OTHER, metadata.finishReason());

        // content preserved
        assertEquals("Hi", chatResponse.aiMessage().text());
    }
}
