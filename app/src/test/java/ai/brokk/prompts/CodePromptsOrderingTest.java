package ai.brokk.prompts;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for verifying ordering behavior in CodePrompts:
 *
 * - The Context-based helpers should preserve the existing mtime-based ordering (older files first).
 * - The explicit-list overloads should preserve the caller-provided insertion order.
 */
public class CodePromptsOrderingTest {

    private Path tmpDir;

    @AfterEach
    public void cleanup() throws Exception {
        if (tmpDir != null && Files.exists(tmpDir)) {
            // Best-effort cleanup
            try (var stream = Files.walk(tmpDir)) {
                stream.sorted((a, b) -> b.compareTo(a)) // delete children first
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {}
                        });
            }
        }
    }

    /**
     * Ensure that the Context-based workspace editable messages preserve the legacy mtime ordering.
     * We create two files, make A older than B, add both as editable fragments to the Context, and
     * assert that the generated editable message lists A before B (older first).
     */
    @Test
    public void testEditableOrder_mtimePreserved_viaContext() throws Exception {
        tmpDir = Files.createTempDirectory("codeprompts-order-mtime-");
        Path a = tmpDir.resolve("A.java");
        Path b = tmpDir.resolve("B.java");

        Files.writeString(a, "/* A */\nclass A {}");
        // Ensure A is older
        long now = Instant.now().toEpochMilli();
        Files.setLastModifiedTime(a, FileTime.fromMillis(now - TimeUnit.SECONDS.toMillis(10)));

        Files.writeString(b, "/* B */\nclass B {}");
        Files.setLastModifiedTime(b, FileTime.fromMillis(now));

        var cm = new TestContextManager(tmpDir, java.util.Set.of());
        var project = new TestProject(tmpDir, Languages.JAVA);

        // Create ProjectFile wrappers
        var pfA = new ProjectFile(tmpDir.toAbsolutePath(), "A.java");
        var pfB = new ProjectFile(tmpDir.toAbsolutePath(), "B.java");

        // Build fragments and add to context in arbitrary insertion order (B then A) to ensure ordering is by mtime
        var ctx = cm.liveContext();
        var ppfB = new ContextFragment.ProjectPathFragment(pfB, cm);
        var ppfA = new ContextFragment.ProjectPathFragment(pfA, cm);

        ctx = ctx.addPathFragments(List.of(ppfB, ppfA)); // inserted B then A

        // Generate workspace editable messages using the Context-based helper (mtime ordering expected)
        var messages = CodePrompts.instance.getWorkspaceEditableMessages(ctx);
        String text = extractText(messages);

        assertNotNull(text, "Editable workspace text should be present in messages");

        // The mtime ordering should put older file (A.java) before newer file (B.java)
        int idxA = text.indexOf("A.java");
        int idxB = text.indexOf("B.java");
        assertTrue(idxA >= 0 && idxB >= 0, "Both filenames must be present in the editable message text");
        assertTrue(idxA < idxB, "Expected A.java (older) to appear before B.java (newer) in mtime-ordered output");
    }

    /**
     * Ensure that the explicit-list overload preserves the provided insertion order.
     * Build fragments B then A and pass them explicitly as [B, A] to the overload and assert B appears before A.
     */
    @Test
    public void testEditableOrder_insertionPreserved_viaExplicitList() throws Exception {
        tmpDir = Files.createTempDirectory("codeprompts-order-insert-");
        Path a = tmpDir.resolve("A.java");
        Path b = tmpDir.resolve("B.java");

        Files.writeString(a, "/* A */\nclass A {}");
        Files.writeString(b, "/* B */\nclass B {}");

        var cm = new TestContextManager(tmpDir, java.util.Set.of());
        var pfA = new ProjectFile(tmpDir.toAbsolutePath(), "A.java");
        var pfB = new ProjectFile(tmpDir.toAbsolutePath(), "B.java");

        var ppfA = new ContextFragment.ProjectPathFragment(pfA, cm);
        var ppfB = new ContextFragment.ProjectPathFragment(pfB, cm);

        // Intentionally provide B then A
        var messages = CodePrompts.instance.getWorkspaceEditableMessages(List.of(ppfB, ppfA));
        String text = extractText(messages);

        assertNotNull(text, "Editable workspace text should be present in messages");
        int idxA = text.indexOf("A.java");
        int idxB = text.indexOf("B.java");
        assertTrue(idxA >= 0 && idxB >= 0, "Both filenames must be present in the editable message text");
        assertTrue(idxB < idxA, "Expected B.java to appear before A.java when passed in that explicit order");
    }

    // Helper to extract the primary text from the returned messages (UserMessage TextContent)
    private static String extractText(Collection<ChatMessage> messages) {
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .flatMap(um -> um.contents().stream())
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .findFirst()
                .orElse(null);
    }
}
