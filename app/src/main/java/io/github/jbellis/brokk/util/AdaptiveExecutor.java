package io.github.jbellis.brokk.util;

import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.Service;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

public final class AdaptiveExecutor {
    public static ExecutorService create(Service service, StreamingChatModel model, int taskCount) {
        @Nullable Integer maxConcurrentRequests = service.getMaxConcurrentRequests(model);
        @Nullable Integer tokensPerMinute = service.getTokensPerMinute(model);

        if (maxConcurrentRequests != null) {
            int poolSize = Math.min(maxConcurrentRequests, Math.max(1, taskCount));
            return Executors.newFixedThreadPool(poolSize);
        }
        if (tokensPerMinute != null) {
            // A large pool size is used, with the expectation that the TokenRateLimiter will be the primary
            // mechanism for controlling throughput.
            return new RateLimitedExecutor(100, tokensPerMinute);
        }
        throw new IllegalStateException("Neither max_concurrent_requests nor tokens_per_minute defined for model "
                                        + service.nameOf(model));
    }

    private AdaptiveExecutor() {
    }

    public static class RateLimitedExecutor extends java.util.concurrent.ThreadPoolExecutor {
        private final TokenRateLimiter rateLimiter;

        public RateLimitedExecutor(int poolSize, int tokensPerMinute) {
            super(poolSize, poolSize,
                  0L, TimeUnit.MILLISECONDS,
                  new java.util.concurrent.LinkedBlockingQueue<>());
            this.rateLimiter = new TokenRateLimiter(tokensPerMinute);
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            // Determine the token cost; falls back to 0 when the wrapper doesn't expose it.
            int tokens = extractTokens(r);
            try {
                rateLimiter.acquire(tokens);
            } catch (InterruptedException e) {
                t.interrupt();
                throw new RuntimeException("Interrupted while waiting for rate limiter", e);
            }
            super.beforeExecute(t, r);
        }

        /**
         * Return the token cost of the task, or 0 if the outer wrapper doesn't expose it.
         */
        private static int extractTokens(Runnable r) {
            return (r instanceof TokenAware ta) ? ta.tokens() : 0;
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(java.util.concurrent.Callable<T> callable) {
            if (callable instanceof TokenAware ta) {
                return new TokenAwareFutureTask<>(callable, ta.tokens());
            }
            return super.newTaskFor(callable);
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
            if (runnable instanceof TokenAware ta) {
                return new TokenAwareFutureTask<>(Executors.callable(runnable, value), ta.tokens());
            }
            return super.newTaskFor(runnable, value);
        }


        private static final class TokenAwareFutureTask<V> extends FutureTask<V> implements TokenAware {
            private final int tokens;
            private TokenAwareFutureTask(java.util.concurrent.Callable<V> callable, int tokens) {
                super(callable);
                this.tokens = tokens;
            }
            @Override
            public int tokens() {
                return tokens;
            }
        }

        /**
         * Token-bucket rate limiter for models that expose only tokens_per_minute.
         * Thread-safe; callers block in acquire() until they can spend the requested tokens.
         */
        private static final class TokenRateLimiter {
            private final int capacity;
            private double tokens;
            private final double refillPerMs;
            private long last;

            private TokenRateLimiter(int tokensPerMinute) {
                assert tokensPerMinute > 0;
                this.capacity = tokensPerMinute;
                this.tokens = tokensPerMinute;
                this.refillPerMs = tokensPerMinute / 60_000.0;
                this.last = System.currentTimeMillis();
            }

            public void acquire(int requested) throws InterruptedException {
                if (requested <= 0) return;
                synchronized (this) {
                    while (true) {
                        refill();
                        if (tokens >= requested) {
                            tokens -= requested;
                            return;
                        }
                        long sleep = (long) Math.ceil((requested - tokens) / refillPerMs);
                        sleep = Math.max(sleep, 50);
                        this.wait(sleep);
                    }
                }
            }

            private void refill() {
                long now = System.currentTimeMillis();
                long elapsed = now - last;
                if (elapsed > 0) {
                    tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
                    last = now;
                    this.notifyAll();
                }
            }
        }
    }
}
