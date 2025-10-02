package dev.langchain4j.internal;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NetworkException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLException;

@FunctionalInterface
public interface ExceptionMapper {

    ExceptionMapper DEFAULT = new DefaultExceptionMapper();

    static <T> T mappingException(Callable<T> action) {
        return DEFAULT.withExceptionMapper(action);
    }

    default <T> T withExceptionMapper(Callable<T> action) {
        try {
            return action.call();
        } catch (Exception e) {
            throw mapException(e);
        }
    }

    RuntimeException mapException(Throwable t);

    class DefaultExceptionMapper implements ExceptionMapper {

        @Override
        public RuntimeException mapException(Throwable t) {
            Throwable rootCause = findRoot(t);

            if (rootCause instanceof HttpException httpException) {
                return mapHttpStatusCode(httpException, httpException.statusCode());
            }

            if (rootCause instanceof UnresolvedAddressException
                    || rootCause instanceof UnknownHostException
                    || rootCause instanceof NoRouteToHostException) {
                return new UnresolvedModelServerException(rootCause);
            }

            if (rootCause instanceof SSLException) {
                // TLS/SSL issues are typically configuration/proxy problems; not retriable
                return new InvalidRequestException(rootCause);
            }

            if (rootCause instanceof IOException) {
                // Generic network/IO failures (connection reset, timeouts, premature close, etc.)
                return new NetworkException(rootCause);
            }

            return t instanceof RuntimeException re ? re : new LangChain4jException(t);
        }

        protected RuntimeException mapHttpStatusCode(Throwable cause, int httpStatusCode) {
            if (httpStatusCode >= 500 && httpStatusCode < 600) {
                return new InternalServerException(cause);
            }
            if (httpStatusCode == 401 || httpStatusCode == 403) {
                return new AuthenticationException(cause);
            }
            if (httpStatusCode == 404) {
                return new ModelNotFoundException(cause);
            }
            if (httpStatusCode == 408) {
                return new TimeoutException(cause);
            }
            if (httpStatusCode == 429) {
                return new RateLimitException(cause);
            }
            if (httpStatusCode >= 400 && httpStatusCode < 500) {
                return new InvalidRequestException(cause);
            }
            return cause instanceof RuntimeException re ? re : new LangChain4jException(cause);
        }

        private static Throwable findRoot(Throwable e) {
            Throwable cause = e.getCause();
            return cause == null || cause == e ? e : findRoot(cause);
        }
    }
}
