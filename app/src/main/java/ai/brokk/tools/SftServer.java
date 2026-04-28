package ai.brokk.tools;

import static java.util.Objects.requireNonNull;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.EditBlock;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.cli.HeadlessConsole;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.routers.RouterUtil;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoData;
import ai.brokk.project.MainProject;
import ai.brokk.prompts.CodePrompts;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.util.Messages;
import com.sun.net.httpserver.HttpExchange;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public final class SftServer implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(SftServer.class);
    private static final int DEFAULT_PORT = 7999;

    private final SimpleHttpServer httpServer;
    private final ConcurrentMap<Path, CachedContext> repoContexts = new ConcurrentHashMap<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public SftServer() throws IOException {
        this(DEFAULT_PORT);
    }

    public SftServer(int port) throws IOException {
        this.httpServer = new SimpleHttpServer(
                "localhost", port, "", Math.max(4, Runtime.getRuntime().availableProcessors()));
        registerContexts();
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--port requires a value");
                        return 1;
                    }
                    port = Integer.parseInt(args[++i]);
                }
                case "--help", "-h" -> {
                    printHelp();
                    return 0;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printHelp();
                    return 1;
                }
            }
        }

        try (var server = new SftServer(port)) {
            var shutdownHook = new Thread(server::close, "sft-server-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            server.start();
            server.awaitShutdown();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down.
            }
        }

        return 0;
    }

    private static void printHelp() {
        System.out.println("Usage: java ai.brokk.tools.SftServer [--port <port>]");
    }

    public void start() {
        httpServer.start();
        logger.info("SftServer listening on http://localhost:{}", getPort());
    }

    public int getPort() {
        return httpServer.getPort();
    }

    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    @Override
    public void close() {
        httpServer.stop(1);
        for (var context : repoContexts.values()) {
            context.close();
        }
        repoContexts.clear();
        shutdownLatch.countDown();
    }

    @Blocking
    public List<SftMessage> format_workspace(
            String repoPath,
            String goal,
            String revision,
            List<String> editable,
            List<String> readonly,
            List<String> summarized,
            String buildError) {
        var cachedContext = contextFor(repoPath);
        var contextManager = cachedContext.contextManager();
        var normalizedEditable = normalizePaths(editable);
        var normalizedReadonly = normalizePaths(readonly);
        var normalizedSummarized = normalizePaths(summarized);
        rejectEditableReadonlyOverlap(normalizedEditable, normalizedReadonly);

        var fragments = new ArrayList<ContextFragment>();
        var readonlyFragments = new ArrayList<ContextFragment>();

        for (var path : normalizedEditable) {
            var file = validateProjectFile(cachedContext, path);
            fragments.add(new ContextFragments.ProjectPathFragment(
                    file, contextManager, loadRevisionText(cachedContext, file, revision)));
        }

        for (var path : normalizedReadonly) {
            var file = validateProjectFile(cachedContext, path);
            var fragment = new ContextFragments.ProjectPathFragment(
                    file, contextManager, loadRevisionText(cachedContext, file, revision));
            fragments.add(fragment);
            readonlyFragments.add(fragment);
        }

        var analyzer = contextManager.getAnalyzerUninterrupted();
        for (var path : normalizedSummarized) {
            var file = validateProjectFile(cachedContext, path);
            var summaryText = analyzer.summarizeSymbols(file, loadRevisionText(cachedContext, file, revision));
            fragments.add(ContextFragments.SummaryFragment.precomputedFileSummary(contextManager, file, summaryText));
        }

        var context = new Context(contextManager, fragments, List.of());
        for (var readonlyFragment : readonlyFragments) {
            context = context.setReadonly(readonlyFragment, true);
        }
        context = context.removeSupersededSummaries();
        if (!buildError.isBlank()) {
            context = context.withBuildResult(false, buildError);
        }

        var workspaceMessages =
                WorkspacePrompts.getMessagesForCodeAgent(context, Set.of()).workspace();
        var result = new ArrayList<SftMessage>(workspaceMessages.size() + 1);
        result.addAll(workspaceMessages.stream().map(SftServer::toSftMessage).toList());
        result.add(buildAugmentedRequestMessage(context, goal));
        return List.copyOf(result);
    }

    @Blocking
    public Map<String, String> format_patch(String repoPath, String from, String to) {
        return format_patch(repoPath, from, to, List.of());
    }

    @Blocking
    public Map<String, String> format_patch(String repoPath, String from, String to, List<String> filenames) {
        var cachedContext = contextFor(repoPath);
        if (from.isBlank() || to.isBlank()) {
            throw new IllegalArgumentException("from and to must not be blank");
        }

        var includedFiles = normalizePathSet(cachedContext, defaultIfNull(filenames));
        List<GitRepoData.FileDiff> fileDiffs;
        try {
            fileDiffs = requireGitRepo(cachedContext).data().getFileDiffs(from, to);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to diff revisions '%s' and '%s'".formatted(from, to), e);
        }

        if (!includedFiles.isEmpty()) {
            fileDiffs = fileDiffs.stream()
                    .filter(diff -> isPathIncluded(diff, includedFiles))
                    .toList();
        }

        var binaryPaths = fileDiffs.stream()
                .filter(GitRepoData.FileDiff::isBinary)
                .map(diff -> diff.newFile() != null
                        ? diff.newFile().toString()
                        : requireNonNull(diff.oldFile()).toString())
                .toList();
        if (!binaryPaths.isEmpty()) {
            throw new IllegalArgumentException("Binary diffs are not supported: " + String.join(", ", binaryPaths));
        }

        SequencedSet<EditBlock.SearchReplaceBlock> blocks = SearchReplaceBlockFormatter.fromFileDiffs(fileDiffs);
        var results = new LinkedHashMap<String, String>();
        for (var block : blocks) {
            var normalizedBlock = normalizePatchBlock(block);
            results.merge(
                    requireNonNull(normalizedBlock.rawFileName()),
                    normalizedBlock.repr(),
                    (left, right) -> left + "\n" + right);
        }
        return Map.copyOf(results);
    }

    private void registerContexts() {
        httpServer.registerUnauthenticatedContext("/format_workspace", this::handleFormatWorkspace);
        httpServer.registerUnauthenticatedContext("/format-workspace", this::handleFormatWorkspace);
        httpServer.registerUnauthenticatedContext("/format_patch", this::handleFormatPatch);
        httpServer.registerUnauthenticatedContext("/format-patch", this::handleFormatPatch);
    }

    private void handleFormatWorkspace(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        var request = RouterUtil.parseJsonOr400(exchange, FormatWorkspaceRequest.class, "/v1/sft/format-workspace");
        if (request == null) return;

        try {
            var result = format_workspace(
                    requireNonBlank(request.repo_path(), "repo_path"),
                    requireNonBlank(request.goal(), "goal"),
                    requireNonBlank(request.revision(), "revision"),
                    request.editable(),
                    request.readonly(),
                    request.summarized(),
                    defaultIfNull(request.build_error()));
            SimpleHttpServer.sendJsonResponse(exchange, new FormatWorkspaceResponse(result));
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(
                    exchange, e.getMessage() != null ? e.getMessage() : "Invalid format_workspace request");
        } catch (Exception e) {
            logger.error("format_workspace failed", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to format workspace", e));
        }
    }

    private void handleFormatPatch(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        var request = RouterUtil.parseJsonOr400(exchange, FormatPatchRequest.class, "/v1/sft/format-patch");
        if (request == null) return;

        try {
            var result = format_patch(
                    requireNonBlank(request.repo_path(), "repo_path"),
                    requireNonBlank(request.from(), "from"),
                    requireNonBlank(request.to(), "to"),
                    defaultIfNull(request.filenames()));
            SimpleHttpServer.sendJsonResponse(exchange, new FormatPatchResponse(result));
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(
                    exchange, e.getMessage() != null ? e.getMessage() : "Invalid format_patch request");
        } catch (Exception e) {
            logger.error("format_patch failed", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to format patch", e));
        }
    }

    private SftMessage buildAugmentedRequestMessage(Context context, String goal) {
        var request = CodePrompts.instance.codeRequest(context, goal, new AbstractService.OfflineStreamingModel());
        return toSftMessage(
                new UserMessage(Messages.getText(request) + "\n\n" + WorkspacePrompts.formatToc(context, Set.of())));
    }

    private CachedContext contextFor(String repoPath) {
        var root =
                Path.of(requireNonBlank(repoPath, "repo_path")).toAbsolutePath().normalize();
        return repoContexts.computeIfAbsent(root, SftServer::createContext);
    }

    private static CachedContext createContext(Path projectRoot) {
        var project = new MainProject(projectRoot);
        project.setBuildDetails(BuildAgent.BuildDetails.EMPTY);
        var contextManager = new ContextManager(project);
        contextManager.createHeadless(true, new HeadlessConsole());
        return new CachedContext(project, contextManager);
    }

    private GitRepo requireGitRepo(CachedContext cachedContext) {
        var repo = cachedContext.contextManager().getRepo();
        if (repo instanceof GitRepo gitRepo) {
            return gitRepo;
        }
        throw new IllegalArgumentException("Project does not have a git repository");
    }

    private ProjectFile validateProjectFile(CachedContext cachedContext, String relativePath) {
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }

        var relPath = Path.of(relativePath);
        if (relPath.isAbsolute() || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new IllegalArgumentException("Path must be project-relative: " + relativePath);
        }

        var root = cachedContext.project().getRoot().toAbsolutePath().normalize();
        var normalized = root.resolve(relPath).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes the project root: " + relativePath);
        }

        return new ProjectFile(root, relPath);
    }

    private String loadRevisionText(CachedContext cachedContext, ProjectFile file, String revision) {
        try {
            if ("WORKING".equals(revision)) {
                if (!Files.isRegularFile(file.absPath())) {
                    throw new IllegalArgumentException("File not found in WORKING tree: " + file);
                }
                return file.read().orElseThrow(() -> new IllegalArgumentException("Failed to read file: " + file));
            }
            return requireGitRepo(cachedContext).data().getFileContent(revision, file);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("File '%s' not found at revision '%s'".formatted(file, revision), e);
        }
    }

    private static List<String> normalizePaths(List<String> paths) {
        return paths.stream().map(String::trim).toList();
    }

    private static void rejectEditableReadonlyOverlap(List<String> editable, List<String> readonly) {
        var overlap = new LinkedHashSet<>(editable);
        overlap.retainAll(readonly);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Paths cannot be both editable and readonly: " + String.join(", ", overlap));
        }
    }

    private static String requireNonBlank(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String defaultIfNull(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static List<String> defaultIfNull(@Nullable List<String> values) {
        return values == null ? List.of() : values;
    }

    private static SftMessage toSftMessage(ChatMessage message) {
        return new SftMessage(toSftRole(message), Messages.getText(message));
    }

    private Set<String> normalizePathSet(CachedContext cachedContext, List<String> paths) {
        var normalized = new LinkedHashSet<String>();
        for (var path : normalizePaths(defaultIfNull(paths))) {
            if (path.isBlank()) {
                throw new IllegalArgumentException("Path list must not contain blank entries");
            }

            normalized.add(validateProjectFile(cachedContext, path).toString().replace('\\', '/'));
        }
        return Set.copyOf(normalized);
    }

    private static boolean isPathIncluded(GitRepoData.FileDiff diff, Set<String> includedPaths) {
        if (diff.oldFile() != null
                && includedPaths.contains(diff.oldFile().toString().replace('\\', '/'))) {
            return true;
        }
        if (diff.newFile() != null
                && includedPaths.contains(diff.newFile().toString().replace('\\', '/'))) {
            return true;
        }
        return false;
    }

    private static EditBlock.SearchReplaceBlock normalizePatchBlock(EditBlock.SearchReplaceBlock block) {
        return new EditBlock.SearchReplaceBlock(
                requireNonNull(block.rawFileName()).replace('\\', '/'),
                block.beforeText(),
                block.afterText(),
                block.rawText());
    }

    private static String toSftRole(ChatMessage message) {
        return switch (message.type()) {
            case USER -> "user";
            case AI -> "assistant";
            case CUSTOM -> "system";
            default -> message.type().name().toLowerCase(Locale.ROOT);
        };
    }

    private record FormatWorkspaceRequest(
            String repo_path,
            String goal,
            String revision,
            List<String> editable,
            List<String> readonly,
            List<String> summarized,
            @Nullable String build_error) {}

    private record FormatPatchRequest(String repo_path, String from, String to, @Nullable List<String> filenames) {}

    public record SftMessage(String role, String content) {}

    private record FormatWorkspaceResponse(List<SftMessage> result) {}

    private record FormatPatchResponse(Map<String, String> result) {}

    private record CachedContext(MainProject project, ContextManager contextManager) implements AutoCloseable {
        @Override
        public void close() {
            contextManager.close();
            project.close();
        }
    }
}
