package ai.brokk.analyzer.macro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import org.jetbrains.annotations.Blocking;

public final class MacroPolicyLoader {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private MacroPolicyLoader() {}

    @Blocking
    public static MacroPolicy loadFromResource(String resourcePath) throws IOException {
        try (InputStream is = MacroPolicyLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return YAML_MAPPER.readValue(is, MacroPolicy.class);
        }
    }

    @Blocking
    public static MacroPolicy load(InputStream inputStream) throws IOException {
        return YAML_MAPPER.readValue(inputStream, MacroPolicy.class);
    }
}
