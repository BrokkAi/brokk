package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GitWorkflowTest {

    static Stream<Arguments> preambleCases() {
        return Stream.of(
                // Gemini "thought" artifact (single word, no colon)
                Arguments.of(
                        " thought\nfeat: allow enqueuing review sections as tasks",
                        "feat: allow enqueuing review sections as tasks"),
                // No preamble — passthrough
                Arguments.of("feat: add login support", "feat: add login support"),
                // Leading blank lines then message
                Arguments.of("\n\nfix: correct null check", "fix: correct null check"),
                // Multi-line single-word artifacts before real message
                Arguments.of(
                        "thinking\nstep\nchore: update dependencies\n\nbody line",
                        "chore: update dependencies\n\nbody line"),
                // Already clean multi-line commit
                Arguments.of(
                        "feat: new feature\n\nDetailed explanation here.",
                        "feat: new feature\n\nDetailed explanation here."),
                // Only junk — no valid line found, return trimmed original
                Arguments.of("thought", "thought"),
                // Multi-word preamble passes through (structural filter only strips single-word lines)
                Arguments.of(
                        "Some random intro sentence\nfeat: real message",
                        "Some random intro sentence\nfeat: real message"));
    }

    @ParameterizedTest
    @MethodSource("preambleCases")
    void stripCommitPreamble(String input, String expected) {
        assertEquals(expected, GitWorkflow.stripCommitPreamble(input));
    }
}
