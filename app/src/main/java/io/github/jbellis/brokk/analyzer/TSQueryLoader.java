package io.github.jbellis.brokk.analyzer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public class TSQueryLoader {

    private TSQueryLoader() {}

    private static final String resourcePrefix = "treesitter";

    public static String loadIndexQuery(String language) {
        final String path = resourcePrefix + "/" + language + ".scm";
        try (InputStream in = TSQueryLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String loadTypeDeclarationsQuery(String language) {
        final String path = resourcePrefix + "/type_declarations/" + language + ".scm";
        try (InputStream in = TSQueryLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
