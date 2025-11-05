package ai.brokk.difftool.ui;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


import java.io.IOException;

public class CodestralApiClient {
    private static final String API_KEY = "RNh6133fQq4MpJdccMHhDnjT1fynAcAi";
    private static final String API_URL = "https://api.mistral.ai/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient();

    public String generateCode(String prompt) throws IOException {
        if (API_KEY == null || API_KEY.isEmpty()) {
            throw new IllegalStateException("MISTRAL_API_KEY environment variable is not set.");
        }

        String jsonBody = String.format("""
            {
                "model": "codestral-latest",
                "messages": [
                    {
                        "role": "user",
                        "content": "%s"
                    }
                ],
                "temperature": 0.7
            }""", CodestraUtil.escapeJson(prompt));

        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = CodestraUtil.makeRequest(body);

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response code: " + response);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    /**
     * Генерация с использованием FIM (prefix/suffix), добавлен параметр suffix
     */
    public String generateCodeWithSuffix(String prefix, String suffix) throws IOException {
        if (API_KEY == null || API_KEY.isEmpty()) {
            throw new IllegalStateException("MISTRAL_API_KEY environment variable is not set.");
        }

        String jsonBody = String.format("""
            {
                "model": "codestral-latest",
                "prompt": "%s",
                "suffix": "%s",
                "temperature": 0.7,
                "top_p": 0.95,
                "max_tokens": 256
            }""", CodestraUtil.escapeJson(prefix), CodestraUtil.escapeJson(suffix));

        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = CodestraUtil.makeFimRequest(body);

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response code: " + response);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    public static void main(String[] args) {
        CodestralApiClient client = new CodestralApiClient();
        try {
            String prompt = "Write a Java method that reverses a string";
            System.out.println("Generating code for: " + prompt);
            String result = client.generateCode(prompt);
            System.out.println("Generated code:");
            System.out.println(result);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}