package ai.brokk.util;

import java.util.Optional;
import java.util.function.Supplier;

public interface IStringDiskCache extends AutoCloseable {
    Optional<String> get(String key);

    void put(String key, String value);

    String computeIfAbsent(String key, Supplier<String> computer);

    public static class NoopDiskCache implements IStringDiskCache {
        @Override
        public Optional<String> get(String key) {
            return Optional.empty();
        }

        @Override
        public void put(String key, String value) {}

        @Override
        public String computeIfAbsent(String key, Supplier<String> computer) {
            return computer.get();
        }

        @Override
        public void close() throws Exception {}
    }
}
