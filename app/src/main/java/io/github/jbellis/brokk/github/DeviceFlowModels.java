package io.github.jbellis.brokk.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.Nullable;

public class DeviceFlowModels {
    public record DeviceCodeResponse(
            @JsonProperty("device_code") String deviceCode,
            @JsonProperty("user_code") String userCode,
            @JsonProperty("verification_uri") String verificationUri,
            @JsonProperty("verification_uri_complete") String verificationUriComplete,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("interval") int interval) {

        public String getPreferredVerificationUri() {
            return (verificationUriComplete != null && !verificationUriComplete.isEmpty())
                    ? verificationUriComplete
                    : verificationUri;
        }

        public boolean hasCompleteUri() {
            return verificationUriComplete != null && !verificationUriComplete.isEmpty();
        }
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("scope") String scope) {}

    public enum TokenPollResult {
        SUCCESS,
        PENDING,
        SLOW_DOWN,
        DENIED,
        EXPIRED,
        ERROR
    }

    public record TokenPollResponse(TokenPollResult result, @Nullable TokenResponse token, String errorMessage) {}

    public record DeviceCodeRequest(@JsonProperty("client_id") String clientId, @JsonProperty("scope") String scope) {}

    public record TokenRequest(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("device_code") String deviceCode,
            @JsonProperty("grant_type") String grantType) {}
}
