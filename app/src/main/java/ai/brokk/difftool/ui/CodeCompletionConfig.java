package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Конфигурация API для работы с сервисом подсказок кода
 */
class CodeCompletionConfig {
    static final String MISTRAL_API_URL = "https://api.mistral.ai/v1/chat/completions";
    static final String MISTRAL_FIM_API_URL = "https://api.mistral.ai/v1/fim/completions";
    static final String API_KEY = "RNh6133fQq4MpJdccMHhDnjT1fynAcAi";
    static final String MODEL_NAME = "codestral-latest";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Параметры по умолчанию
    static final double DEFAULT_TEMPERATURE = 0.2;
    static final double DEFAULT_TOP_P = 0.95;
    static final int DEFAULT_MAX_TOKENS = 1024;
}