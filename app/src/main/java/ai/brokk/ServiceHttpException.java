package ai.brokk;

import java.io.IOException;

public class ServiceHttpException extends IOException {
    private final int statusCode;
    private final String responseBody;

    public ServiceHttpException(int statusCode, String responseBody, String message) {
        super(message + ": " + statusCode + " - " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
