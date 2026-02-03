package dev.langchain4j.internal;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.langchain4j.exception.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExceptionMapperTest {

    private final ExceptionMapper.DefaultExceptionMapper mapper = new ExceptionMapper.DefaultExceptionMapper();

    @ParameterizedTest(name = "HTTP {0} should map to {1}")
    @MethodSource("httpStatusMappingProvider")
    void testMapHttpStatusCode(int statusCode, Class<? extends RuntimeException> expectedClass) {
        HttpException cause = new HttpException(statusCode, "Error " + statusCode);
        RuntimeException mapped = mapper.mapHttpStatusCode(cause, statusCode);
        assertInstanceOf(
                expectedClass, mapped, "Status " + statusCode + " did not map to " + expectedClass.getSimpleName());
    }

    private static Stream<Arguments> httpStatusMappingProvider() {
        return Stream.of(
                Arguments.of(401, AuthenticationException.class),
                Arguments.of(403, AuthenticationException.class),
                Arguments.of(402, PaymentRequiredException.class),
                Arguments.of(404, ModelNotFoundException.class),
                Arguments.of(408, TimeoutException.class),
                Arguments.of(413, ContextTooLargeException.class),
                Arguments.of(429, RateLimitException.class),
                Arguments.of(500, InternalServerException.class),
                Arguments.of(502, InternalServerException.class),
                Arguments.of(503, InternalServerException.class),
                Arguments.of(504, InternalServerException.class),
                Arguments.of(400, InvalidRequestException.class),
                Arguments.of(418, InvalidRequestException.class));
    }

    @Test
    void testContextErrorMapping() {
        // Test "context" or "token" keywords in message for 4xx errors
        HttpException contextError = new HttpException(400, "The context length is too long");
        RuntimeException mapped = mapper.mapHttpStatusCode(contextError, 400);
        assertInstanceOf(ContextTooLargeException.class, mapped);
    }
}
