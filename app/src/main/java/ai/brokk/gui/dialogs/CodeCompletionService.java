package ai.brokk.gui.dialogs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;

/**
 * Класс для работы с API подсказок кода
 */
class CodeCompletionService {
    private final HttpClient httpClient;

    public CodeCompletionService() {
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Получает подсказки кода от API
     */
    public List<String> getCodeSuggestions(
            String code, int cursorPosition, double temperature, double topP, int maxTokens) {
        List<String> suggestions = new ArrayList<>();
        if (java.util.Objects.requireNonNullElse(code, "").isEmpty()) {
            return suggestions;
        }

        try {
            String requestBody = createRequestBody(code, cursorPosition, temperature, topP, maxTokens);
            System.out.println("requestBody:" + requestBody);
            HttpRequest request = createRequest(requestBody);
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            return parseResponse(response);
        } catch (Exception e) {
            handleError("Ошибка при получении подсказок: ", e);
            return suggestions;
        }
    }

    /**
     * Формирует FIM-запрос с prefix/suffix (suffix — код после курсора)
     */
    private String createRequestBody(String code, int cursorPosition, double temperature, double topP, int maxTokens) {
        int safePos = Math.max(0, Math.min(cursorPosition, code.length()));
        String prefix = code.substring(0, safePos);
        String suffix = code.substring(safePos);

        JsonArray stop = new JsonArray();
        stop.add("\\n\\n");
        stop.add("//");
        stop.add("/*");
        stop.add("\"\"\"");
        stop.add("'''");

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", CodeCompletionConfig.MODEL_NAME);
        // Mistral FIM expects "prompt" (prefix) + "suffix"
        requestBody.addProperty("prompt", prefix);
        requestBody.addProperty("suffix", suffix);
        requestBody.addProperty("temperature", temperature);
        requestBody.addProperty("top_p", topP);
        requestBody.addProperty("max_tokens", maxTokens);
        requestBody.add("stop", stop);

        return CodeCompletionConfig.GSON.toJson(requestBody);
    }

    private HttpRequest createRequest(String requestBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create(CodeCompletionConfig.MISTRAL_FIM_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + CodeCompletionConfig.API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    private List<String> parseResponse(HttpResponse<String> response) {
        List<String> suggestions = new ArrayList<>();
        if (response.statusCode() == 200) {
            JsonObject jsonResponse = CodeCompletionConfig.GSON.fromJson(response.body(), JsonObject.class);
            if (jsonResponse.has("choices")
                    && !jsonResponse.getAsJsonArray("choices").isEmpty()) {
                JsonObject choice =
                        jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject();

                // FIM формат: choices[].text
                if (choice.has("text")) {
                    String content = choice.get("text").getAsString().trim();
                    content = content.replace("```", "").trim();
                    if (!content.isEmpty()) {
                        suggestions.add(content);
                    }
                }
                // Запасной вариант: Chat формат: choices[].message.content
                else if (choice.has("message")
                        && choice.getAsJsonObject("message").has("content")) {
                    String content = choice.getAsJsonObject("message")
                            .get("content")
                            .getAsString()
                            .trim();
                    content = content.replace("```", "").trim();
                    if (!content.isEmpty()) {
                        suggestions.add(content);
                    }
                }
            }
        } else {
            System.err.println("Ошибка API: " + response.body());
        }
        return suggestions;
    }

    private void handleError(String message, Exception e) {
        System.err.println(message + e.getMessage());
        e.printStackTrace();
    }
}
