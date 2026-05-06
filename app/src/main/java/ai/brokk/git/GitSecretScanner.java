package ai.brokk.git;

import ai.brokk.analyzer.BrokkFile;
import ai.brokk.concurrent.ExecutorsUtil;
import ai.brokk.concurrent.LoggingExecutorService;
import ai.brokk.concurrent.LoggingFuture;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.NullMarked;

/**
 * Scans git repository text blobs for secret-looking strings.
 */
@NullMarked
public class GitSecretScanner {
    private static final Logger logger = LogManager.getLogger(GitSecretScanner.class);

    private static final int SECRET_SCAN_PARALLELISM = 20;
    private static final int MAX_IN_FLIGHT_BLOB_SCANS = SECRET_SCAN_PARALLELISM * 4;
    private static final int MAX_BLOB_BYTES = 1024 * 1024;
    private static final int MAX_SECRET_SAMPLE_VALUE_CHARS = 12;
    private static final int HISTORY_PROGRESS_LOG_COMMITS = 100;
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "changeme",
            "change_me",
            "example",
            "example-secret",
            "dummy",
            "test",
            "testing",
            "placeholder",
            "password",
            "secret",
            "token",
            "xxx",
            "xxxx",
            "your-token",
            "your-secret");

    private static final Set<CredentialKeyword> CREDENTIAL_KEYWORDS = Set.of(
            new CredentialKeyword("password", Set.of("password")),
            new CredentialKeyword("passwd", Set.of("passwd")),
            new CredentialKeyword("secret", Set.of("secret")),
            new CredentialKeyword("token", Set.of("token")),
            new CredentialKeyword("api[_-]?key", Set.of("apikey", "api_key", "api-key")),
            new CredentialKeyword("client[_-]?secret", Set.of("clientsecret", "client_secret", "client-secret")),
            new CredentialKeyword("private[_-]?key", Set.of("privatekey", "private_key", "private-key")),
            new CredentialKeyword("access[_-]?key", Set.of("accesskey", "access_key", "access-key")));

    private static final String CREDENTIAL_NAME_PATTERN_FRAGMENT = CREDENTIAL_KEYWORDS.stream()
            .map(CredentialKeyword::patternFragment)
            .collect(Collectors.joining("|", "(?:", ")"));

    private static final Pattern ASSIGNMENT_SECRET_PATTERN =
            Pattern.compile("(?i)([A-Za-z0-9_.-]*%s[A-Za-z0-9_.-]*)\\s*[:=]\\s*['\"]?([^'\"\\s,;#}]+)"
                    .formatted(CREDENTIAL_NAME_PATTERN_FRAGMENT));

    private static final Pattern LOW_CONFIDENCE_SECRET_PATTERN =
            Pattern.compile("(?i)([A-Za-z0-9_.-]*%s[A-Za-z0-9_.-]*)\\s*[:=]\\s*['\"]?([^'\"\\s,;#}]{4,11})"
                    .formatted(CREDENTIAL_NAME_PATTERN_FRAGMENT));

    private static final List<SecretRule> HIGH_CONFIDENCE_RULES = List.of(
            new SecretRule(
                    "AWS access key id",
                    SecretConfidence.HIGH,
                    Pattern.compile("\\b(A3T[A-Z0-9]|AKIA|ASIA)[A-Z0-9]{16}\\b"),
                    0,
                    Set.of("AKIA", "ASIA", "A3T")),
            new SecretRule(
                    "GitHub token",
                    SecretConfidence.HIGH,
                    Pattern.compile("\\bgh[opurs]_[A-Za-z0-9_]{36,}\\b"),
                    0,
                    Set.of("gho_", "ghp_", "ghu_", "ghr_", "ghs_")),
            new SecretRule(
                    "Slack token",
                    SecretConfidence.HIGH,
                    Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
                    0,
                    Set.of("xoxb-", "xoxa-", "xoxp-", "xoxr-", "xoxs-")),
            new SecretRule(
                    "Private key block",
                    SecretConfidence.HIGH,
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                    0,
                    Set.of("-----BEGIN ")),
            new SecretRule(
                    "JWT",
                    SecretConfidence.HIGH,
                    Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
                    0,
                    Set.of("eyJ")),
            new SecretRule(
                    "Google API key",
                    SecretConfidence.HIGH,
                    Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
                    0,
                    Set.of("AIza")),
            new SecretRule(
                    "Stripe key",
                    SecretConfidence.HIGH,
                    Pattern.compile("\\b[rs]k_(?:live|test)_[0-9A-Za-z]{16,}\\b"),
                    0,
                    Set.of("sk_live_", "sk_test_", "rk_live_", "rk_test_")));

    private final GitRepo repo;

    public GitSecretScanner(GitRepo repo) {
        this.repo = repo;
    }

    public SecretScanReport scan(int maxCommits, boolean includeHistoryOnly, boolean includeLowConfidence)
            throws GitAPIException, IOException {
        return scan(maxCommits, includeHistoryOnly, includeLowConfidence, MAX_IN_FLIGHT_BLOB_SCANS);
    }

    SecretScanReport scan(
            int maxCommits, boolean includeHistoryOnly, boolean includeLowConfidence, int maxInFlightBlobScans)
            throws GitAPIException, IOException {
        long startedNanos = System.nanoTime();
        int commitCap = maxCommits > 0 ? maxCommits : 2000;
        var refInfo = resolveDefaultBranchRef();
        var accumulator = new SecretScanAccumulator();
        var blobScanCache = new ConcurrentHashMap<ObjectId, BlobContentScanResult>();
        CurrentScanResult current = scanDefaultRef(refInfo.refName(), includeLowConfidence, blobScanCache);
        current.keys().forEach(key -> accumulator.add(key, SecretLocation.CURRENT, "", ""));

        HistoryScanResult history = scanHistory(commitCap, includeLowConfidence, blobScanCache, maxInFlightBlobScans);
        history.commitOrder().forEach((commit, commitIndex) -> history.keysByCommit()
                .getOrDefault(commit, Set.of())
                .forEach(key -> {
                    var shortHash = repo.shortHash(commit);
                    accumulator.add(key, SecretLocation.HISTORY, shortHash, shortHash, commitIndex);
                }));

        var findings = accumulator.findings().stream()
                .filter(f -> includeHistoryOnly || f.location() != SecretLocation.HISTORY)
                .sorted(Comparator.comparingInt((SecretFinding f) -> locationRank(f.location()))
                        .thenComparing((SecretFinding f) -> confidenceRank(f.confidence()))
                        .thenComparing(SecretFinding::path)
                        .thenComparingInt(SecretFinding::line)
                        .thenComparing(SecretFinding::rule))
                .toList();

        logger.debug(
                "Secret scan complete for {}: defaultRef={}, fallback={}, commitsScanned={}, currentBlobsScanned={}, historyBlobsScanned={}, cacheHits={}, cacheMisses={}, missingBlobsSkipped={}, nonTextOrOversizedBlobsSkipped={}, findings={}, elapsedMs={}",
                repo.getWorkTreeRoot(),
                refInfo.displayName(),
                refInfo.fallback(),
                history.commitsScanned(),
                current.blobsScanned(),
                history.blobsScanned(),
                current.cacheHits() + history.cacheHits(),
                current.cacheMisses() + history.cacheMisses(),
                current.missingEntriesSkipped() + history.missingEntriesSkipped(),
                current.nonTextEntriesSkipped() + history.nonTextEntriesSkipped(),
                findings.size(),
                elapsedMillis(startedNanos));

        return new SecretScanReport(
                repo.getWorkTreeRoot().toString(),
                refInfo.displayName(),
                refInfo.fallback(),
                commitCap,
                history.commitsScanned(),
                history.missingEntriesSkipped(),
                history.nonTextEntriesSkipped(),
                findings);
    }

    private CurrentScanResult scanDefaultRef(
            String refName, boolean includeLowConfidence, Map<ObjectId, BlobContentScanResult> blobScanCache)
            throws GitAPIException, IOException {
        long startedNanos = System.nanoTime();
        Repository repository = repo.getGit().getRepository();
        ObjectId ref = repository.resolve(refName + "^{commit}");
        if (ref == null) {
            ref = repository.resolve(refName);
        }
        if (ref == null) {
            return CurrentScanResult.EMPTY;
        }

        var tasks = new ArrayList<BlobScanTask>();
        Path workTreeRoot = repo.getWorkTreeRoot();
        Path projectRoot = repo.getProjectRoot().toAbsolutePath().normalize();
        try (RevWalk walk = new RevWalk(repository);
                TreeWalk treeWalk = new TreeWalk(repository)) {
            RevCommit commit = walk.parseCommit(ref);
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                if (!isRegularFileMode(treeWalk.getRawMode(0))) {
                    continue;
                }
                String gitPath = treeWalk.getPathString();
                Optional<String> projectPath = toProjectRelativePath(projectRoot, workTreeRoot, gitPath);
                if (projectPath.isEmpty() || looksLikeTestPath(projectPath.get())) {
                    continue;
                }
                tasks.add(new BlobScanTask(commit.getName(), projectPath.get(), treeWalk.getObjectId(0)));
            }
        }

        try (LoggingExecutorService executor =
                ExecutorsUtil.newVirtualThreadExecutor("brokk-secret-scan", SECRET_SCAN_PARALLELISM)) {
            var futures = tasks.stream()
                    .map(task -> LoggingFuture.supplyAsync(
                            () -> scanBlob(repository, task, includeLowConfidence, blobScanCache), executor))
                    .toList();

            LoggingFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<BlobScanResult> results =
                    futures.stream().map(CompletableFuture::join).toList();
            Set<SecretKey> keys =
                    results.stream().flatMap(result -> result.keys().stream()).collect(Collectors.toSet());
            var result = new CurrentScanResult(
                    keys,
                    results.stream().mapToInt(BlobScanResult::blobsScanned).sum(),
                    results.stream().mapToInt(BlobScanResult::cacheHits).sum(),
                    results.stream().mapToInt(BlobScanResult::cacheMisses).sum(),
                    results.stream()
                            .mapToInt(BlobScanResult::missingEntriesSkipped)
                            .sum(),
                    results.stream()
                            .mapToInt(BlobScanResult::nonTextEntriesSkipped)
                            .sum());
            logger.debug(
                    "Secret scan current ref complete: ref={}, candidateBlobs={}, blobsScanned={}, cacheHits={}, cacheMisses={}, missingBlobsSkipped={}, nonTextOrOversizedBlobsSkipped={}, findings={}, elapsedMs={}",
                    refName,
                    tasks.size(),
                    result.blobsScanned(),
                    result.cacheHits(),
                    result.cacheMisses(),
                    result.missingEntriesSkipped(),
                    result.nonTextEntriesSkipped(),
                    result.keys().size(),
                    elapsedMillis(startedNanos));
            return result;
        }
    }

    private HistoryScanResult scanHistory(
            int maxCommits,
            boolean includeLowConfidence,
            Map<ObjectId, BlobContentScanResult> blobScanCache,
            int maxInFlightBlobScans)
            throws GitAPIException, IOException {
        Repository repository = repo.getGit().getRepository();
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null) {
            return HistoryScanResult.EMPTY();
        }

        int inFlightCap = Math.max(1, maxInFlightBlobScans);
        var accumulator = new HistoryScanAccumulator();
        var commitOrder = new LinkedHashMap<String, Integer>();
        int commitsScanned = 0;
        Path workTreeRoot = repo.getWorkTreeRoot();
        Path projectRoot = repo.getProjectRoot().toAbsolutePath().normalize();
        var inFlight = new ArrayDeque<CompletableFuture<BlobScanResult>>();
        long startedNanos = System.nanoTime();

        try (RevWalk walk = new RevWalk(repository);
                LoggingExecutorService executor =
                        ExecutorsUtil.newVirtualThreadExecutor("brokk-secret-scan", SECRET_SCAN_PARALLELISM)) {
            walk.markStart(walk.parseCommit(head));
            RevCommit commit;
            while ((commit = walk.next()) != null && commitsScanned < maxCommits) {
                String commitName = commit.getName();
                commitOrder.put(commitName, commitsScanned);
                commitsScanned++;
                if (commitsScanned % HISTORY_PROGRESS_LOG_COMMITS == 0) {
                    logger.debug(
                            "Secret scan history progress: commitsScanned={}, blobsScanned={}, cacheHits={}, cacheMisses={}, missingBlobsSkipped={}, nonTextOrOversizedBlobsSkipped={}, findings={}, elapsedMs={}",
                            commitsScanned,
                            accumulator.blobsScanned(),
                            accumulator.cacheHits(),
                            accumulator.cacheMisses(),
                            accumulator.missingEntriesSkipped(),
                            accumulator.nonTextEntriesSkipped(),
                            accumulator.findingCount(),
                            elapsedMillis(startedNanos));
                }

                try (TreeWalk treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(commit.getTree());
                    treeWalk.setRecursive(true);
                    while (treeWalk.next()) {
                        if (!isRegularFileMode(treeWalk.getRawMode(0))) {
                            continue;
                        }
                        String gitPath = treeWalk.getPathString();
                        Optional<String> projectPath = toProjectRelativePath(projectRoot, workTreeRoot, gitPath);
                        if (projectPath.isEmpty() || looksLikeTestPath(projectPath.get())) {
                            continue;
                        }
                        var task = new BlobScanTask(commitName, projectPath.get(), treeWalk.getObjectId(0));
                        inFlight.addLast(LoggingFuture.supplyAsync(
                                () -> scanBlob(repository, task, includeLowConfidence, blobScanCache), executor));
                        if (inFlight.size() >= inFlightCap) {
                            accumulator.add(inFlight.removeFirst().join());
                        }
                    }
                } catch (MissingObjectException e) {
                    accumulator.addMissingEntry();
                    logger.debug("Skipping missing tree while scanning {}: {}", repo.shortHash(commitName), e);
                }
            }
            while (!inFlight.isEmpty()) {
                accumulator.add(inFlight.removeFirst().join());
            }
        }

        logger.debug(
                "Secret scan history complete: commitsScanned={}, blobsScanned={}, cacheHits={}, cacheMisses={}, missingBlobsSkipped={}, nonTextOrOversizedBlobsSkipped={}, findings={}, elapsedMs={}",
                commitsScanned,
                accumulator.blobsScanned(),
                accumulator.cacheHits(),
                accumulator.cacheMisses(),
                accumulator.missingEntriesSkipped(),
                accumulator.nonTextEntriesSkipped(),
                accumulator.findingCount(),
                elapsedMillis(startedNanos));

        return new HistoryScanResult(
                accumulator.keysByCommit(),
                commitOrder,
                commitsScanned,
                accumulator.blobsScanned(),
                accumulator.cacheHits(),
                accumulator.cacheMisses(),
                accumulator.missingEntriesSkipped(),
                accumulator.nonTextEntriesSkipped());
    }

    private static BlobScanResult scanBlob(
            Repository repository,
            BlobScanTask task,
            boolean includeLowConfidence,
            Map<ObjectId, BlobContentScanResult> blobScanCache) {
        boolean[] cacheMiss = {false};
        BlobContentScanResult contentResult = blobScanCache.computeIfAbsent(task.objectId(), ignored -> {
            cacheMiss[0] = true;
            return scanBlobContent(repository, task, includeLowConfidence);
        });
        return new BlobScanResult(
                task.commit(),
                rebasePath(contentResult.keys(), task.path()),
                contentResult.blobsScanned(),
                cacheMiss[0] ? 0 : 1,
                cacheMiss[0] ? 1 : 0,
                contentResult.missingEntriesSkipped(),
                contentResult.nonTextEntriesSkipped());
    }

    private static BlobContentScanResult scanBlobContent(
            Repository repository, BlobScanTask task, boolean includeLowConfidence) {
        try {
            ObjectLoader loader = repository.open(task.objectId());
            if (loader.getSize() > MAX_BLOB_BYTES) {
                return new BlobContentScanResult(Set.of(), 0, 0, 1);
            }
            byte[] bytes = loader.getBytes(MAX_BLOB_BYTES);
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (BrokkFile.isBinary(text)) {
                return new BlobContentScanResult(Set.of(), 0, 0, 1);
            }
            return new BlobContentScanResult(scanText(task.path(), text, includeLowConfidence), 1, 0, 0);
        } catch (MissingObjectException e) {
            logger.trace("Skipping missing blob {} in {}: {}", task.path(), task.commit(), e.getMessage());
            return new BlobContentScanResult(Set.of(), 0, 1, 0);
        } catch (LargeObjectException | CharacterCodingException e) {
            logger.trace(
                    "Skipping non-text or oversized blob {} in {}: {}", task.path(), task.commit(), e.getMessage());
            return new BlobContentScanResult(Set.of(), 0, 0, 1);
        } catch (IOException e) {
            return new BlobContentScanResult(Set.of(), 0, 1, 0);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static Set<SecretKey> rebasePath(Set<SecretKey> keys, String path) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        return keys.stream()
                .map(key -> new SecretKey(path, key.line(), key.rule(), key.confidence(), key.sample()))
                .collect(Collectors.toSet());
    }

    public static Set<SecretKey> scanText(String path, String text, boolean includeLowConfidence) {
        var findings = new HashSet<SecretKey>();
        int lineNumber = 1;
        for (String line : (Iterable<String>) text.lines()::iterator) {
            for (SecretRule rule : HIGH_CONFIDENCE_RULES) {
                if (rule.matchesSignal(line)) {
                    var matcher = rule.pattern().matcher(line);
                    while (matcher.find()) {
                        String value = matcher.group(rule.secretGroup());
                        if (!isPlaceholder(value)) {
                            findings.add(new SecretKey(
                                    path,
                                    lineNumber,
                                    rule.name(),
                                    rule.confidence(),
                                    redactedLine(line, value, matcher.toMatchResult())));
                        }
                    }
                }
            }

            String lowerLine = line.toLowerCase(Locale.ROOT);
            if (hasCredentialKeyword(lowerLine)) {
                addAssignmentFindings(
                        findings, path, lineNumber, line, ASSIGNMENT_SECRET_PATTERN, SecretConfidence.MEDIUM);
                if (includeLowConfidence) {
                    addAssignmentFindings(
                            findings, path, lineNumber, line, LOW_CONFIDENCE_SECRET_PATTERN, SecretConfidence.LOW);
                }
            }
            lineNumber++;
        }
        return findings;
    }

    private static boolean hasCredentialKeyword(String lowerLine) {
        for (CredentialKeyword keyword : CREDENTIAL_KEYWORDS) {
            if (keyword.matchesLowercaseSignal(lowerLine)) {
                return true;
            }
        }
        return false;
    }

    private static void addAssignmentFindings(
            Set<SecretKey> findings,
            String path,
            int lineNumber,
            String line,
            Pattern pattern,
            SecretConfidence confidence) {
        var matcher = pattern.matcher(line);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (isPlausibleAssignmentSecret(value, confidence)) {
                findings.add(new SecretKey(
                        path,
                        lineNumber,
                        confidence == SecretConfidence.LOW ? "Credential-like name" : "Credential assignment",
                        confidence,
                        redactedLine(line, value, matcher.toMatchResult())));
            }
        }
    }

    private static boolean isPlausibleAssignmentSecret(String value, SecretConfidence confidence) {
        if (isPlaceholder(value)) {
            return false;
        }
        if (confidence == SecretConfidence.LOW) {
            return value.length() >= 4;
        }
        return value.length() >= 12 && approximateEntropy(value) >= 3.0;
    }

    private static boolean isPlaceholder(String value) {
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.startsWith("${") || stripped.startsWith("<")) {
            return true;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_VALUES.contains(lower) || lower.chars().distinct().count() <= 2;
    }

    private static double approximateEntropy(String value) {
        Map<Integer, Long> counts =
                value.chars().boxed().collect(Collectors.groupingBy(ch -> ch, Collectors.counting()));
        double length = value.length();
        return counts.values().stream()
                .mapToDouble(count -> {
                    double p = count / length;
                    return -p * (Math.log(p) / Math.log(2));
                })
                .sum();
    }

    public static String redactedLine(String line, String secret, MatchResult match) {
        String redacted = redactSecret(secret);
        int start = Math.max(0, match.start());
        int end = Math.min(line.length(), match.end());
        String excerpt = line.substring(start, end).replace(secret, redacted).strip();
        return excerpt.length() > 120 ? excerpt.substring(0, 117) + "..." : excerpt;
    }

    private static String redactSecret(String secret) {
        if (secret.length() <= 8) {
            return "[REDACTED]";
        }
        int visible = Math.min(4, MAX_SECRET_SAMPLE_VALUE_CHARS / 2);
        return secret.substring(0, visible) + "..." + secret.substring(secret.length() - visible);
    }

    private static Optional<String> toProjectRelativePath(Path projectRoot, Path workTreeRoot, String gitPath) {
        try {
            Path absolutePath = workTreeRoot.resolve(gitPath).normalize();
            if (!absolutePath.startsWith(projectRoot)) {
                return Optional.empty();
            }
            String rel = projectRoot.relativize(absolutePath).toString();
            // Normalize Windows separators to '/', but don't rewrite legitimate backslashes on POSIX.
            if (File.separatorChar == '\\') {
                rel = rel.replace('\\', '/');
            }
            return Optional.of(rel);
        } catch (IllegalArgumentException e) {
            logger.debug("Skipping unmappable git path {}: {}", gitPath, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean looksLikeTestPath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        return normalized.contains("/test/")
                || normalized.contains("/tests/")
                || normalized.contains("/src/test/")
                || fileName.startsWith("test_")
                || fileName.endsWith("_test.py")
                || fileName.endsWith("test.java")
                || fileName.endsWith("tests.java")
                || fileName.endsWith(".test.js")
                || fileName.endsWith(".spec.js")
                || fileName.endsWith(".test.ts")
                || fileName.endsWith(".spec.ts");
    }

    private static boolean isRegularFileMode(int rawMode) {
        return rawMode == FileMode.REGULAR_FILE.getBits() || rawMode == FileMode.EXECUTABLE_FILE.getBits();
    }

    private DefaultRefInfo resolveDefaultBranchRef() {
        try {
            String defaultBranch = repo.getDefaultBranch();
            return new DefaultRefInfo(defaultBranch, defaultBranch, false);
        } catch (GitAPIException e) {
            logger.debug("Could not determine default branch, falling back to HEAD: {}", e.getMessage());
            return new DefaultRefInfo("HEAD", "HEAD (default branch unavailable)", true);
        }
    }

    private static int locationRank(SecretLocation location) {
        return switch (location) {
            case BOTH -> 0;
            case CURRENT -> 1;
            case HISTORY -> 2;
        };
    }

    private static int confidenceRank(SecretConfidence confidence) {
        return switch (confidence) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }

    public enum SecretConfidence {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum SecretLocation {
        CURRENT,
        HISTORY,
        BOTH
    }

    private record SecretRule(
            String name, SecretConfidence confidence, Pattern pattern, int secretGroup, Set<String> signalSubstrings) {
        boolean matchesSignal(String line) {
            for (String signal : signalSubstrings) {
                if (line.contains(signal)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record CredentialKeyword(String patternFragment, Set<String> signalSubstrings) {
        boolean matchesLowercaseSignal(String lowerLine) {
            for (String signal : signalSubstrings) {
                if (lowerLine.contains(signal)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record SecretKey(String path, int line, String rule, SecretConfidence confidence, String sample) {}

    public record SecretFinding(
            String path,
            int line,
            String rule,
            SecretConfidence confidence,
            SecretLocation location,
            String firstSeenCommit,
            String lastSeenCommit,
            String sample) {}

    public record SecretScanReport(
            String repository,
            String defaultRefDisplayName,
            boolean defaultRefFallback,
            int maxCommits,
            int commitsScanned,
            int missingEntriesSkipped,
            int nonTextEntriesSkipped,
            List<SecretFinding> findings) {}

    private record DefaultRefInfo(String refName, String displayName, boolean fallback) {}

    private record BlobScanTask(String commit, String path, ObjectId objectId) {}

    private record BlobScanResult(
            String commit,
            Set<SecretKey> keys,
            int blobsScanned,
            int cacheHits,
            int cacheMisses,
            int missingEntriesSkipped,
            int nonTextEntriesSkipped) {}

    private record CurrentScanResult(
            Set<SecretKey> keys,
            int blobsScanned,
            int cacheHits,
            int cacheMisses,
            int missingEntriesSkipped,
            int nonTextEntriesSkipped) {
        private static final CurrentScanResult EMPTY = new CurrentScanResult(Set.of(), 0, 0, 0, 0, 0);
    }

    private record BlobContentScanResult(
            Set<SecretKey> keys, int blobsScanned, int missingEntriesSkipped, int nonTextEntriesSkipped) {}

    private record HistoryScanResult(
            Map<String, Set<SecretKey>> keysByCommit,
            Map<String, Integer> commitOrder,
            int commitsScanned,
            int blobsScanned,
            int cacheHits,
            int cacheMisses,
            int missingEntriesSkipped,
            int nonTextEntriesSkipped) {
        static HistoryScanResult EMPTY() {
            return new HistoryScanResult(Map.of(), Map.of(), 0, 0, 0, 0, 0, 0);
        }
    }

    private static final class HistoryScanAccumulator {
        private final Map<String, Set<SecretKey>> keysByCommit = new LinkedHashMap<>();
        private int blobsScanned;
        private int cacheHits;
        private int cacheMisses;
        private int missingEntriesSkipped;
        private int nonTextEntriesSkipped;

        void add(BlobScanResult result) {
            blobsScanned += result.blobsScanned();
            cacheHits += result.cacheHits();
            cacheMisses += result.cacheMisses();
            missingEntriesSkipped += result.missingEntriesSkipped();
            nonTextEntriesSkipped += result.nonTextEntriesSkipped();
            if (!result.keys().isEmpty()) {
                keysByCommit
                        .computeIfAbsent(result.commit(), ignored -> new HashSet<>())
                        .addAll(result.keys());
            }
        }

        void addMissingEntry() {
            missingEntriesSkipped++;
        }

        Map<String, Set<SecretKey>> keysByCommit() {
            return keysByCommit;
        }

        int blobsScanned() {
            return blobsScanned;
        }

        int cacheHits() {
            return cacheHits;
        }

        int cacheMisses() {
            return cacheMisses;
        }

        int missingEntriesSkipped() {
            return missingEntriesSkipped;
        }

        int nonTextEntriesSkipped() {
            return nonTextEntriesSkipped;
        }

        int findingCount() {
            return keysByCommit.values().stream().mapToInt(Set::size).sum();
        }
    }

    private static final class SecretScanAccumulator {
        private final Map<SecretKey, MutableSecretFinding> findings = new HashMap<>();

        void add(SecretKey key, SecretLocation location, String firstSeen, String lastSeen) {
            add(key, location, firstSeen, lastSeen, -1);
        }

        void add(SecretKey key, SecretLocation location, String firstSeen, String lastSeen, int commitIndex) {
            findings.computeIfAbsent(key, MutableSecretFinding::new).add(location, firstSeen, lastSeen, commitIndex);
        }

        List<SecretFinding> findings() {
            return findings.values().stream()
                    .map(MutableSecretFinding::toFinding)
                    .toList();
        }
    }

    private static final class MutableSecretFinding {
        private final SecretKey key;
        private boolean current;
        private boolean history;
        private String firstSeenCommit = "";
        private String lastSeenCommit = "";
        private int firstSeenCommitIndex = Integer.MIN_VALUE;
        private int lastSeenCommitIndex = Integer.MAX_VALUE;

        MutableSecretFinding(SecretKey key) {
            this.key = key;
        }

        void add(SecretLocation location, String firstSeen, String lastSeen, int commitIndex) {
            if (location == SecretLocation.CURRENT) {
                current = true;
                return;
            }
            history = true;
            if (commitIndex < 0) {
                return;
            }
            if (!firstSeen.isBlank() && commitIndex > firstSeenCommitIndex) {
                firstSeenCommit = firstSeen;
                firstSeenCommitIndex = commitIndex;
            }
            if (!lastSeen.isBlank() && commitIndex < lastSeenCommitIndex) {
                lastSeenCommit = lastSeen;
                lastSeenCommitIndex = commitIndex;
            }
        }

        SecretFinding toFinding() {
            SecretLocation location = current && history
                    ? SecretLocation.BOTH
                    : current ? SecretLocation.CURRENT : SecretLocation.HISTORY;
            return new SecretFinding(
                    key.path(),
                    key.line(),
                    key.rule(),
                    key.confidence(),
                    location,
                    firstSeenCommit,
                    lastSeenCommit,
                    key.sample());
        }
    }
}
