package ai.brokk;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/**
 * Interface for services that can report client exceptions.
 * This interface allows for testing without requiring full Service initialization.
 */
public interface IExceptionReportingService {
    /**
     * Reports a client exception to the server.
     *
     * @param stacktrace The formatted stack trace of the exception
     * @param clientVersion The version of the client application
     * @return JsonNode response from the server
     * @throws IOException if the HTTP request fails
     */
    JsonNode reportClientException(String stacktrace, String clientVersion) throws IOException;
}
