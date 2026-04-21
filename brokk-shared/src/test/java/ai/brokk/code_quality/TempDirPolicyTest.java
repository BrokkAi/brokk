package ai.brokk.code_quality;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TempDirPolicyTest {
    private static final List<Path> TEST_SOURCE_ROOTS =
            List.of(Path.of("app/src/test/java"), Path.of("brokk-shared/src/test/java"));

    private static final List<Pattern> FORBIDDEN_REGEX = List.of(
            Pattern.compile("Path\\.of\\(\\s*\\\"/tmp\\\"\\s*\\)"),
            Pattern.compile("Paths\\.get\\(\\s*\\\"/tmp\\\"\\s*\\)"),
            Pattern.compile("System\\.getProperty\\(\\s*\\\"java\\.io\\.tmpdir\\\"\\s*\\)"));

    private static final List<String> FORBIDDEN_SUBSTRINGS = List.of("java.io.tmpdir", "java.io.temp", "\"/tmp\"");

    @Test
    void testsUseTempDirOrDedicatedTemps() {
        List<String> violations = new ArrayList<>();

        for (Path root : TEST_SOURCE_ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }

            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> checkFile(p, violations));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        assertTrue(
                violations.isEmpty(),
                """
                Temp directory policy violated.

                Do not hardcode /tmp or use java.io.tmpdir in tests. Prefer JUnit 5 @TempDir or Files.createTemp*.

                Offenders:
                %s
                """
                        .formatted(String.join(System.lineSeparator(), violations)));
    }

    private static void checkFile(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String normalized = line.toLowerCase(Locale.ROOT);

            if (FORBIDDEN_SUBSTRINGS.stream().anyMatch(s -> normalized.contains(s))) {
                violations.add("%s:%d: %s".formatted(file, i + 1, line.strip()));
                continue;
            }

            for (Pattern forbidden : FORBIDDEN_REGEX) {
                if (forbidden.matcher(line).find()) {
                    violations.add("%s:%d: %s".formatted(file, i + 1, line.strip()));
                    break;
                }
            }
        }
    }
}
