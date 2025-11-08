package ai.brokk.gui.dialogs;

import java.net.URI;
import java.net.http.HttpRequest;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jetbrains.annotations.NotNull;

public class CodestraUtil {
    // URL API Codestral (chat)
    private static final String CODESTRAL_API_URL = "https://api.mistral.ai/v1/chat/completions";
    // URL API Codestral (FIM)
    private static final String CODESTRAL_FIM_API_URL = "https://api.mistral.ai/v1/fim/completions";
    // API ключ (в реальном приложении лучше хранить в защищенном месте)
    private static final String API_KEY = "RNh6133fQq4MpJdccMHhDnjT1fynAcAi";

    public static @NotNull String makeRequestBody(String code, int cursorPosition) {
        return String.format(
                """
                {
                    "model": "codestral-latest",
                    "messages": [
                        {
                            "role": "user",
                            "content": "Complete the following Java code at position %d: %s"
                        }
                    ],
                    "temperature": 0.7,
                    "max_tokens": 100
                }""",
                cursorPosition, escapeJson(code));
    }

    /**
     * FIM: формирует тело запроса с prefix/suffix
     */
    public static @NotNull String makeFimRequestBody(String code, int cursorPosition) {
        int safePos = Math.max(0, Math.min(cursorPosition, code.length()));
        String prefix = code.substring(0, safePos);
        String suffix = code.substring(safePos);

        return String.format(
                """
                {
                    "model": "codestral-latest",
                    "prompt": "%s",
                    "suffix": "%s",
                    "temperature": 0.7,
                    "top_p": 0.95,
                    "max_tokens": 100
                }""",
                escapeJson(prefix), escapeJson(suffix));
    }

    /** Вспомогательная функция для экранирования строк для JSON-поля */
    public static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"");
    }

    public static HttpRequest getBuild(String requestBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create(CODESTRAL_API_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    public static @NotNull Request makeRequest(RequestBody body) {
        return new Request.Builder()
                .url(CODESTRAL_API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    /**
     * FIM: строит OkHttp-запрос на FIM endpoint
     */
    public static @NotNull Request makeFimRequest(RequestBody body) {
        return new Request.Builder()
                .url(CODESTRAL_FIM_API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }
}
