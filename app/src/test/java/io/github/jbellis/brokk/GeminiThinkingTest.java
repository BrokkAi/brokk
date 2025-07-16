package io.github.jbellis.brokk;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;

import java.util.List;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal test to reproduce Gemini 2.5 thinking model streaming issue with LiteLLM.
 * 
 * Prerequisites:
 * 1. Run LiteLLM 1.72.2: docker pull ghcr.io/berriai/litellm:litellm_stable_release_branch-v1.72.2-stable
 * 2. Start with: docker run -v $(pwd)/docs/litellm_config.yaml:/app/config.yaml -e GEMINI_API_KEY=YOUR_KEY -p 4000:4000 ghcr.io/berriai/litellm:litellm_stable_release_branch-v1.72.2-stable --config /app/config.yaml
 * 
 * Run with: 
 *   sbt "testOnly *GeminiThinkingTest"          - tests both models
 *   sbt "runMain io.github.jbellis.brokk.GeminiThinkingTest pro"   - tests only pro model
 *   sbt "runMain io.github.jbellis.brokk.GeminiThinkingTest flash" - tests only flash model
 */
public class GeminiThinkingTest {
    
    public static void main(String[] args) throws InterruptedException {
        // Check command line arguments
        String model = null;
        if (args.length > 0) {
            model = args[0].toLowerCase();
        }
        
        // Test specific model or both
        if ("pro".equals(model)) {
            testGeminiModel("gemini-2.5-pro", "http://localhost:4000");
        } else if ("flash".equals(model)) {
            testGeminiModel("gemini-2.5-flash", "http://localhost:4000");
        } else {
            // Test both models if no specific model requested
            testGeminiModel("gemini-2.5-pro", "http://localhost:4000");
            testGeminiModel("gemini-2.5-flash", "http://localhost:4000");
        }
    }
    
    private static void testGeminiModel(String modelName, String baseUrl) throws InterruptedException {
        System.out.println("\n=== Testing " + modelName + " ===");
        
        var model = OpenAiStreamingChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey("dummy-key") // LiteLLM manages the actual keys
            .modelName(modelName)
            .timeout(java.time.Duration.ofSeconds(120))
            .build();
        
        var latch = new CountDownLatch(1);
        var completed = new AtomicBoolean(false);
        var error = new AtomicReference<Throwable>();
        var responseBuilder = new StringBuilder();
        var startTime = System.currentTimeMillis();
        
        var handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                System.out.print(token);
                responseBuilder.append(token);
            }
            
            @Override
            public void onCompleteResponse(ChatResponse response) {
                var duration = System.currentTimeMillis() - startTime;
                System.out.println("\n\nCompleted in " + duration + "ms");
                System.out.println("Total response length: " + responseBuilder.length() + " chars");
                switch(response.tokenUsage()) {
                    case OpenAiTokenUsage usage -> System.out.println("This is null for gemini: " + usage.inputTokensDetails().cachedTokens());
                    default -> {}
                }
                completed.set(true);
                latch.countDown();
            }
            
            @Override
            public void onError(Throwable throwable) {
                var duration = System.currentTimeMillis() - startTime;
                System.err.println("\n\nError after " + duration + "ms: " + throwable.getMessage());
                throwable.printStackTrace();
                error.set(throwable);
                latch.countDown();
            }
        };
        
        System.out.println("Sending request...");
        List<ChatMessage> messages = List.of(UserMessage.from("What is 2+2? Think step by step."));
        var request = ChatRequest.builder().messages(messages).build();
        model.chat(request, handler);
        
        // Wait up to 2 minutes
        var finished = latch.await(120, TimeUnit.SECONDS);
        
        if (!finished) {
            System.err.println("\n\nTIMEOUT: Request did not complete within 2 minutes");
        } else if (error.get() != null) {
            System.err.println("Request failed with error");
        } else if (completed.get()) {
            System.out.println("Request completed successfully");
        }
    }
}