package ai.brokk.executor.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentStoreTest {
    @TempDir
    Path tempDir;

    private Path projectDir;
    private Path userDir;
    private AgentStore store;

    @BeforeEach
    void setUp() {
        projectDir = tempDir.resolve("project-agents");
        userDir = tempDir.resolve("user-agents");
        store = new AgentStore(projectDir, userDir);
    }

    private static AgentDefinition testAgent(String name, String scope) {
        return new AgentDefinition(
                name,
                "Test agent " + name,
                List.of("searchSymbols", "scanUsages"),
                "claude-sonnet-4-20250514",
                10,
                "You are a test agent named " + name + ".",
                scope);
    }

    // ---- Round-trip (serialize -> parse -> verify) ----

    @Test
    void saveAndGet_roundTrip() throws IOException {
        var original = testAgent("my-agent", "project");
        store.save(original);

        var loaded = store.get("my-agent");
        assertTrue(loaded.isPresent());
        assertEquals("my-agent", loaded.get().name());
        assertEquals("Test agent my-agent", loaded.get().description());
        assertEquals(List.of("searchSymbols", "scanUsages"), loaded.get().tools());
        assertEquals("claude-sonnet-4-20250514", loaded.get().model());
        assertEquals(10, loaded.get().maxTurns());
        assertEquals("You are a test agent named my-agent.", loaded.get().systemPrompt());
        assertEquals("project", loaded.get().scope());
    }

    @Test
    void parseMarkdown_roundTrip_preservesAllFields() throws IOException {
        var original = testAgent("round-trip", "project");
        var markdown = AgentStore.toMarkdown(original);
        var parsed = AgentStore.parseMarkdown(markdown, "project");

        assertEquals(original.name(), parsed.name());
        assertEquals(original.description(), parsed.description());
        assertEquals(original.tools(), parsed.tools());
        assertEquals(original.model(), parsed.model());
        assertEquals(original.maxTurns(), parsed.maxTurns());
        assertEquals(original.systemPrompt(), parsed.systemPrompt());
    }

    @Test
    void parseMarkdown_minimalFrontmatter() throws IOException {
        var markdown =
                """
                ---
                name: minimal
                description: A minimal agent
                ---

                Just a simple prompt.
                """;
        var parsed = AgentStore.parseMarkdown(markdown, "user");

        assertEquals("minimal", parsed.name());
        assertEquals("A minimal agent", parsed.description());
        assertTrue(parsed.tools() == null || parsed.tools().isEmpty());
        assertTrue(parsed.model() == null);
        assertTrue(parsed.maxTurns() == null);
        assertEquals("Just a simple prompt.", parsed.systemPrompt());
        assertEquals("user", parsed.scope());
    }

    @Test
    void parseMarkdown_missingName_throws() {
        var markdown =
                """
                ---
                description: No name
                ---

                Prompt here.
                """;
        assertThrows(IOException.class, () -> AgentStore.parseMarkdown(markdown, "project"));
    }

    @Test
    void parseMarkdown_missingDescription_throws() {
        var markdown =
                """
                ---
                name: no-desc
                ---

                Prompt here.
                """;
        assertThrows(IOException.class, () -> AgentStore.parseMarkdown(markdown, "project"));
    }

    @Test
    void parseMarkdown_noFrontmatter_throws() {
        assertThrows(IOException.class, () -> AgentStore.parseMarkdown("Just plain text", "project"));
    }

    @Test
    void parseMarkdown_noClosingDelimiter_throws() {
        var markdown =
                """
                ---
                name: broken
                description: no closing
                """;
        assertThrows(IOException.class, () -> AgentStore.parseMarkdown(markdown, "project"));
    }

    // ---- Layered storage ----

    @Test
    void list_mergesProjectAndUser() throws IOException {
        store.save(testAgent("user-only", "user"), "user");
        store.save(testAgent("project-only", "project"), "project");

        var agents = store.list();
        var names = agents.stream().map(AgentDefinition::name).toList();
        assertTrue(names.contains("user-only"));
        assertTrue(names.contains("project-only"));
    }

    @Test
    void list_projectOverridesUser() throws IOException {
        store.save(new AgentDefinition("shared", "User version", null, null, null, "User prompt", "user"), "user");
        store.save(
                new AgentDefinition("shared", "Project version", null, null, null, "Project prompt", "project"),
                "project");

        var agents = store.list();
        var shared = agents.stream().filter(a -> a.name().equals("shared")).findFirst();
        assertTrue(shared.isPresent());
        assertEquals("Project version", shared.get().description());
        assertEquals("project", shared.get().scope());
    }

    @Test
    void get_projectTakesPrecedence() throws IOException {
        store.save(new AgentDefinition("shared", "User", null, null, null, "User prompt", "user"), "user");
        store.save(new AgentDefinition("shared", "Project", null, null, null, "Project prompt", "project"), "project");

        var loaded = store.get("shared");
        assertTrue(loaded.isPresent());
        assertEquals("Project", loaded.get().description());
    }

    @Test
    void get_fallsBackToUser_whenProjectMissing() throws IOException {
        store.save(new AgentDefinition("user-agent", "User only", null, null, null, "User prompt", "user"), "user");

        var loaded = store.get("user-agent");
        assertTrue(loaded.isPresent());
        assertEquals("User only", loaded.get().description());
        assertEquals("user", loaded.get().scope());
    }

    // ---- Delete ----

    @Test
    void delete_removesAgent() throws IOException {
        store.save(testAgent("to-delete", "project"));
        assertTrue(store.get("to-delete").isPresent());

        assertTrue(store.delete("to-delete"));
        assertFalse(store.get("to-delete").isPresent());
    }

    @Test
    void delete_returnsFalse_whenNotFound() throws IOException {
        assertFalse(store.delete("nonexistent"));
    }

    // ---- Path traversal protection ----

    @Test
    void get_rejectsPathTraversal() {
        assertEquals(Optional.empty(), store.get("../../../etc/passwd"));
    }

    @Test
    void get_rejectsInvalidNames() {
        assertEquals(Optional.empty(), store.get("BadName"));
        assertEquals(Optional.empty(), store.get("has spaces"));
        assertEquals(Optional.empty(), store.get("has.dots"));
    }

    @Test
    void delete_rejectsPathTraversal() throws IOException {
        assertFalse(store.delete("../../../etc/passwd"));
    }

    // ---- Empty directories ----

    @Test
    void list_emptyDirectories_returnsEmpty() {
        assertTrue(store.list().isEmpty());
    }

    @Test
    void get_nonexistent_returnsEmpty() {
        assertTrue(store.get("nonexistent").isEmpty());
    }

    // ---- Markdown with special characters ----

    @Test
    void toMarkdown_quotesDescriptionWithColons() throws IOException {
        var def = new AgentDefinition("test", "Agent: does things", null, null, null, "Prompt", "project");
        var markdown = AgentStore.toMarkdown(def);
        var parsed = AgentStore.parseMarkdown(markdown, "project");
        assertEquals("Agent: does things", parsed.description());
    }
}
