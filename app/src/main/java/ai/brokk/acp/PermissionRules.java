package ai.brokk.acp;

import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.util.Json;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * On-disk per-project store of "Always allow" / "Always reject" verdicts for ACP and GUI
 * permission prompts. Persisted at {@code <projectRoot>/.brokk/permission_rules.json}, this lets a
 * verdict survive ACP session restarts and IDE reboots — Phase 2 of the Codex-permission port (see
 * {@code docs/permission_phases.md} or PR #3497 for context).
 *
 * <p>The match is exact-string on the {@code (toolName, argMatch)} tuple. {@code argMatch} mirrors
 * the per-call cache-key suffix used today (e.g. for {@code runShellCommand} the raw command). A
 * {@code DENY} rule wins over any {@code ALLOW}, including the Phase 1 hardcoded safe-list.
 *
 * <p>Thread-safe: all read/write operations are guarded by a {@link ReentrantLock}.
 * {@link #save(Path)} performs a load+merge before writing so concurrent processes (e.g. two ACP
 * sessions for the same project) cannot stomp each other's rules.
 */
public final class PermissionRules {

    private static final Logger logger = LogManager.getLogger(PermissionRules.class);

    /** Schema version for forward compatibility. Bumped on breaking changes only. */
    static final int SCHEMA_VERSION = 1;

    private static final String FILE_RELATIVE_PATH = ".brokk/permission_rules.json";

    private final ReentrantLock lock = new ReentrantLock();

    /** Mutable list of rules. Always accessed under {@link #lock}. */
    private final List<Rule> rules;

    private PermissionRules(List<Rule> rules) {
        this.rules = new ArrayList<>(rules);
    }

    /** Single allow/deny rule. */
    record Rule(String toolName, String argMatch, BrokkAcpAgent.PermissionVerdict verdict) {}

    /**
     * On-disk schema. Wraps the rule list with a version field so future migrations can detect
     * old files and either upgrade or refuse them. {@code rules} is {@link Nullable} because
     * Jackson supplies {@code null} when the JSON omits the key entirely; callers must default it
     * via {@link Objects#requireNonNullElse}.
     */
    record PermissionRulesFile(
            @JsonProperty("version") int version, @Nullable @JsonProperty("rules") List<Rule> rules) {
        @JsonCreator
        PermissionRulesFile {
            // Compact constructor body intentionally empty — defaulting happens at use sites so
            // the @Nullable contract is preserved on the accessor.
        }
    }

    /**
     * Loads the rules file at {@code <projectRoot>/.brokk/permission_rules.json}. Returns an empty
     * instance on missing file, parse failure, or unsupported schema version — never throws,
     * because permission persistence must not brick the ACP agent.
     */
    @Blocking
    public static PermissionRules loadForProject(Path projectRoot) {
        var file = projectRoot.resolve(FILE_RELATIVE_PATH);
        if (!Files.exists(file)) {
            return new PermissionRules(List.of());
        }
        try {
            var json = Files.readString(file);
            var parsed = Json.fromJson(json, PermissionRulesFile.class);
            if (parsed.version() != SCHEMA_VERSION) {
                logger.warn(
                        "PermissionRules at {} has unsupported version {} (expected {}); ignoring file. "
                                + "Edit it manually or delete it to start fresh.",
                        file,
                        parsed.version(),
                        SCHEMA_VERSION);
                return new PermissionRules(List.of());
            }
            return new PermissionRules(Objects.requireNonNullElse(parsed.rules(), List.of()));
        } catch (Exception e) {
            logger.warn(
                    "Failed to parse PermissionRules at {}: {}. Treating as empty — file is preserved on disk so it can be repaired.",
                    file,
                    e.getMessage());
            return new PermissionRules(List.of());
        }
    }

    /** Returns the verdict for {@code (toolName, argMatch)}, or empty if no rule matches. */
    public Optional<BrokkAcpAgent.PermissionVerdict> lookup(String toolName, String argMatch) {
        lock.lock();
        try {
            return rules.stream()
                    .filter(r -> r.toolName().equals(toolName) && r.argMatch().equals(argMatch))
                    .map(Rule::verdict)
                    .findFirst();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts or replaces the rule for {@code (toolName, argMatch)}. Caller is responsible for
     * calling {@link #save(Path)} to persist the change.
     */
    public void put(String toolName, String argMatch, BrokkAcpAgent.PermissionVerdict verdict) {
        lock.lock();
        try {
            rules.removeIf(r -> r.toolName().equals(toolName) && r.argMatch().equals(argMatch));
            rules.add(new Rule(toolName, argMatch, verdict));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically writes the current rules to {@code <projectRoot>/.brokk/permission_rules.json}.
     * Re-reads any concurrent on-disk changes first and merges them — last-writer-wins per
     * {@code (toolName, argMatch)} key, but rules from other processes are preserved.
     */
    @Blocking
    public void save(Path projectRoot) throws IOException {
        var file = projectRoot.resolve(FILE_RELATIVE_PATH);
        Files.createDirectories(file.getParent());
        lock.lock();
        try {
            // Merge in any rules another process may have added since we loaded.
            var onDisk = loadForProject(projectRoot);
            for (var theirs : onDisk.snapshot()) {
                boolean weHaveIt = rules.stream()
                        .anyMatch(r -> r.toolName().equals(theirs.toolName())
                                && r.argMatch().equals(theirs.argMatch()));
                if (!weHaveIt) {
                    rules.add(theirs);
                }
            }
            var payload = new PermissionRulesFile(SCHEMA_VERSION, List.copyOf(rules));
            var json = Json.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            AtomicWrites.save(file, json);
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot of the current rules for read-only iteration. Test/internal use. */
    List<Rule> snapshot() {
        lock.lock();
        try {
            return List.copyOf(rules);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Splits a permission cache key into the {@code argMatch} component used by this store. Cache
     * keys are shaped as {@code toolName} (no per-call differentiation) or {@code toolName + ":" +
     * arg} (shell tools). Returns {@code ""} when there is no arg suffix.
     */
    public static String argMatchOf(String toolName, String cacheKey) {
        if (cacheKey.equals(toolName)) {
            return "";
        }
        var prefix = toolName + ":";
        if (cacheKey.startsWith(prefix)) {
            return cacheKey.substring(prefix.length());
        }
        return cacheKey;
    }
}
