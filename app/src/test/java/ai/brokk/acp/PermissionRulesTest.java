package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionRulesTest {

    @Test
    void emptyOnMissingFile(@TempDir Path projectRoot) {
        var rules = PermissionRules.loadForProject(projectRoot);
        assertTrue(rules.snapshot().isEmpty());
        assertTrue(rules.lookup("runShellCommand", "ls").isEmpty());
    }

    @Test
    void putAndLookup(@TempDir Path projectRoot) {
        var rules = PermissionRules.loadForProject(projectRoot);
        rules.put("runShellCommand", "mvn test", BrokkAcpAgent.PermissionVerdict.ALLOW);
        rules.put("runShellCommand", "git push", BrokkAcpAgent.PermissionVerdict.DENY);

        assertEquals(
                java.util.Optional.of(BrokkAcpAgent.PermissionVerdict.ALLOW),
                rules.lookup("runShellCommand", "mvn test"));
        assertEquals(
                java.util.Optional.of(BrokkAcpAgent.PermissionVerdict.DENY),
                rules.lookup("runShellCommand", "git push"));
        assertTrue(rules.lookup("runShellCommand", "unknown").isEmpty());
    }

    @Test
    void putReplacesExistingRule(@TempDir Path projectRoot) {
        var rules = PermissionRules.loadForProject(projectRoot);
        rules.put("runShellCommand", "mvn test", BrokkAcpAgent.PermissionVerdict.ALLOW);
        rules.put("runShellCommand", "mvn test", BrokkAcpAgent.PermissionVerdict.DENY);

        assertEquals(1, rules.snapshot().size());
        assertEquals(
                java.util.Optional.of(BrokkAcpAgent.PermissionVerdict.DENY),
                rules.lookup("runShellCommand", "mvn test"));
    }

    @Test
    void saveAndReload(@TempDir Path projectRoot) throws IOException {
        var first = PermissionRules.loadForProject(projectRoot);
        first.put("runShellCommand", "mvn test", BrokkAcpAgent.PermissionVerdict.ALLOW);
        first.put("callShellAgent", "deploy", BrokkAcpAgent.PermissionVerdict.DENY);
        first.save(projectRoot);

        assertTrue(Files.exists(projectRoot.resolve(".brokk/permission_rules.json")));

        var reloaded = PermissionRules.loadForProject(projectRoot);
        assertEquals(2, reloaded.snapshot().size());
        assertEquals(
                java.util.Optional.of(BrokkAcpAgent.PermissionVerdict.ALLOW),
                reloaded.lookup("runShellCommand", "mvn test"));
        assertEquals(
                java.util.Optional.of(BrokkAcpAgent.PermissionVerdict.DENY),
                reloaded.lookup("callShellAgent", "deploy"));
    }

    @Test
    void corruptJsonReturnsEmptyAndPreservesFile(@TempDir Path projectRoot) throws IOException {
        var brokk = projectRoot.resolve(".brokk");
        Files.createDirectories(brokk);
        var file = brokk.resolve("permission_rules.json");
        Files.writeString(file, "{ this is not valid json");

        var rules = PermissionRules.loadForProject(projectRoot);
        assertTrue(rules.snapshot().isEmpty());
        assertTrue(Files.exists(file), "corrupt file must be preserved for manual repair");
    }

    @Test
    void unsupportedVersionReturnsEmpty(@TempDir Path projectRoot) throws IOException {
        var brokk = projectRoot.resolve(".brokk");
        Files.createDirectories(brokk);
        var file = brokk.resolve("permission_rules.json");
        Files.writeString(file, "{\"version\": 99, \"rules\": []}");

        var rules = PermissionRules.loadForProject(projectRoot);
        assertTrue(rules.snapshot().isEmpty());
    }

    @Test
    void mergeOnSavePreservesConcurrentRules(@TempDir Path projectRoot) throws IOException {
        // Process A loads, adds rule, persists.
        var procA = PermissionRules.loadForProject(projectRoot);
        procA.put("runShellCommand", "ls", BrokkAcpAgent.PermissionVerdict.ALLOW);
        procA.save(projectRoot);

        // Process B loads (sees A's rule), but a concurrent C writes a DIFFERENT rule before B saves.
        var procB = PermissionRules.loadForProject(projectRoot);
        procB.put("runShellCommand", "pwd", BrokkAcpAgent.PermissionVerdict.ALLOW);

        var procC = PermissionRules.loadForProject(projectRoot);
        procC.put("runShellCommand", "cat", BrokkAcpAgent.PermissionVerdict.ALLOW);
        procC.save(projectRoot);

        // B saves now — must merge C's "cat" rule, not stomp it.
        procB.save(projectRoot);

        var finalState = PermissionRules.loadForProject(projectRoot);
        assertEquals(3, finalState.snapshot().size());
        assertTrue(finalState.lookup("runShellCommand", "ls").isPresent());
        assertTrue(finalState.lookup("runShellCommand", "pwd").isPresent());
        assertTrue(finalState.lookup("runShellCommand", "cat").isPresent());
    }

    @Test
    void differentToolNamesIsolated(@TempDir Path projectRoot) {
        var rules = PermissionRules.loadForProject(projectRoot);
        rules.put("runShellCommand", "deploy", BrokkAcpAgent.PermissionVerdict.ALLOW);
        rules.put("callShellAgent", "deploy", BrokkAcpAgent.PermissionVerdict.DENY);

        assertEquals(
                java.util.Optional.of(BrokkAcpAgent.PermissionVerdict.ALLOW),
                rules.lookup("runShellCommand", "deploy"));
        assertEquals(
                java.util.Optional.of(BrokkAcpAgent.PermissionVerdict.DENY), rules.lookup("callShellAgent", "deploy"));
    }

    @Test
    void emptyArgMatchSupported(@TempDir Path projectRoot) throws IOException {
        var rules = PermissionRules.loadForProject(projectRoot);
        rules.put("editFile", "", BrokkAcpAgent.PermissionVerdict.ALLOW);
        rules.save(projectRoot);

        var reloaded = PermissionRules.loadForProject(projectRoot);
        assertEquals(java.util.Optional.of(BrokkAcpAgent.PermissionVerdict.ALLOW), reloaded.lookup("editFile", ""));
        assertFalse(reloaded.lookup("editFile", "something").isPresent());
    }
}
