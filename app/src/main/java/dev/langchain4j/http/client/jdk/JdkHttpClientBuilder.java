package dev.langchain4j.http.client.jdk;

import dev.langchain4j.http.client.HttpClientBuilder;
import java.net.http.HttpClient;
import java.time.Duration;

public class JdkHttpClientBuilder implements HttpClientBuilder {

    private HttpClient.Builder httpClientBuilder;
    private Duration connectTimeout;
    private Duration readTimeout;

    public HttpClient.Builder httpClientBuilder() {
        return httpClientBuilder;
    }

    public JdkHttpClientBuilder httpClientBuilder(HttpClient.Builder httpClientBuilder) {
        this.httpClientBuilder = httpClientBuilder;
        return this;
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public JdkHttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public JdkHttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    @Override
    public JdkHttpClient build() {
        return new JdkHttpClient(this);
    }
}
