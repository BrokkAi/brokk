package ai.brokk.executor.routers;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.util.Messages;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Router for /v1/context endpoints.
 */
@NullMarked
public final class ContextRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(ContextRouter.class);

    private final ContextManager contextManager;

    public ContextRouter(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if (method.equals("GET") && normalizedPath.equals("/v1/context")) {
            handleGetContext(exchange);
            return;
        }

        if (!method.equals("POST")) {
            RouterUtil.sendMethodNotAllowed(exchange);
            return;
        }

        switch (normalizedPath) {
            case "/v1/context/drop" -> handlePostContextDrop(exchange);
            case "/v1/context/pin" -> handlePostContextPin(exchange);
            case "/v1/context/readonly" -> handlePostContextReadonly(exchange);
            case "/v1/context/compress-history" -> handlePostCompressHistory(exchange);
            case "/v1/context/clear-history" -> handlePostClearHistory(exchange);
            case "/v1/context/drop-all" -> handlePostDropAll(exchange);
            case "/v1/context/files" -> handlePostContextFiles(exchange);
            case "/v1/context/classes" -> handlePostContextClasses(exchange);
            case "/v1/context/methods" -> handlePostContextMethods(exchange);
            case "/v1/context/text" -> handlePostContextText(exchange);
            default ->
                SimpleHttpServer.sendJsonResponse(
                        exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
        }
    }

    private void handleGetContext(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        try {
            var live = contextManager.liveContext();
            var fragments = live.getAllFragmentsInDisplayOrder();

            var fragmentList = new ArrayList<Map<String, Object>>();
            int totalUsedTokens = 0;

            for (var fragment : fragments) {
                var map = new HashMap<String, Object>();
                map.put("id", fragment.id());
                map.put("type", fragment.getType().name());
                map.put("shortDescription", fragment.shortDescription().renderNowOr(""));
                map.put("chipKind", classifyChipKind(fragment));
                map.put("pinned", live.isPinned(fragment));
                map.put("readonly", live.isMarkedReadonly(fragment));
                map.put("valid", fragment.isValid());
                map.put("editable", fragment.getType().isEditable());

                int tokens = estimateFragmentTokens(fragment);
                map.put("tokens", tokens);
                totalUsedTokens += tokens;

                fragmentList.add(map);
            }

            int maxTokens = 200_000;
            var response = Map.of(
                    "fragments", fragmentList,
                    "usedTokens", totalUsedTokens,
                    "maxTokens", maxTokens);

            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/context", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to retrieve context", e));
        }
    }

    private void handlePostContextDrop(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        var request = RouterUtil.parseJsonOr400(exchange, DropFragmentsRequest.class, "/v1/context/drop");
        if (request == null) return;

        if (request.fragmentIds() == null || request.fragmentIds().isEmpty()) {
            RouterUtil.sendValidationError(exchange, "fragmentIds must not be empty");
            return;
        }

        var idSet = new HashSet<>(request.fragmentIds());
        var live = contextManager.liveContext();
        var toDrop = live.allFragments().filter(f -> idSet.contains(f.id())).toList();

        if (toDrop.isEmpty()) {
            RouterUtil.sendValidationError(exchange, "No matching fragments found for the given IDs");
            return;
        }

        contextManager.drop(toDrop);
        SimpleHttpServer.sendJsonResponse(exchange, Map.of("dropped", toDrop.size()));
    }

    private void handlePostContextPin(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        var request = RouterUtil.parseJsonOr400(exchange, PinFragmentRequest.class, "/v1/context/pin");
        if (request == null) return;

        if (request.fragmentId() == null || request.fragmentId().isBlank()) {
            RouterUtil.sendValidationError(exchange, "fragmentId is required");
            return;
        }

        var live = contextManager.liveContext();
        var fragment = live.allFragments()
                .filter(f -> f.id().equals(request.fragmentId()))
                .findFirst()
                .orElse(null);

        if (fragment == null) {
            RouterUtil.sendValidationError(exchange, "Fragment not found: " + request.fragmentId());
            return;
        }

        contextManager.pushContext(ctx -> ctx.withPinned(fragment, request.pinned()));
        SimpleHttpServer.sendJsonResponse(
                exchange, Map.of("fragmentId", request.fragmentId(), "pinned", request.pinned()));
    }

    private void handlePostContextReadonly(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        var request = RouterUtil.parseJsonOr400(exchange, ReadonlyFragmentRequest.class, "/v1/context/readonly");
        if (request == null) return;

        if (request.fragmentId() == null || request.fragmentId().isBlank()) {
            RouterUtil.sendValidationError(exchange, "fragmentId is required");
            return;
        }

        var live = contextManager.liveContext();
        var fragment = live.allFragments()
                .filter(f -> f.id().equals(request.fragmentId()))
                .findFirst()
                .orElse(null);

        if (fragment == null) {
            RouterUtil.sendValidationError(exchange, "Fragment not found: " + request.fragmentId());
            return;
        }

        if (!fragment.getType().isEditable()) {
            RouterUtil.sendValidationError(exchange, "Fragment is not editable and cannot be marked readonly");
            return;
        }

        contextManager.pushContext(ctx -> ctx.setReadonly(fragment, request.readonly()));
        SimpleHttpServer.sendJsonResponse(
                exchange, Map.of("fragmentId", request.fragmentId(), "readonly", request.readonly()));
    }

    private void handlePostCompressHistory(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        contextManager.compressHistoryAsync();
        SimpleHttpServer.sendJsonResponse(exchange, 202, Map.of("status", "compressing"));
    }

    private void handlePostClearHistory(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        contextManager.clearHistory();
        SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "cleared"));
    }

    private void handlePostDropAll(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        contextManager.dropAll();
        SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "dropped"));
    }

    private void handlePostContextFiles(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        var request = RouterUtil.parseJsonOr400(exchange, AddContextFilesRequest.class, "/v1/context/files");
        if (request == null) return;

        if (request.relativePaths().isEmpty()) {
            RouterUtil.sendValidationError(exchange, "relativePaths must not be empty");
            return;
        }

        var root = contextManager.getProject().getRoot();
        var validProjectFiles = new HashSet<ProjectFile>();
        var invalidPaths = new ArrayList<String>();

        for (var pathStr : request.relativePaths()) {
            if (pathStr == null || pathStr.isBlank()) {
                invalidPaths.add("(blank path)");
                continue;
            }
            var pathObj = Path.of(pathStr);
            if (pathObj.isAbsolute()) {
                invalidPaths.add(pathStr + " (absolute path not allowed)");
                continue;
            }
            var absolutePath = root.resolve(pathObj).normalize();
            if (!absolutePath.startsWith(root)) {
                invalidPaths.add(pathStr + " (escapes workspace)");
                continue;
            }
            if (!Files.isRegularFile(absolutePath)) {
                invalidPaths.add(pathStr + " (not a regular file or does not exist)");
                continue;
            }
            validProjectFiles.add(
                    contextManager.toFile(root.relativize(absolutePath).toString()));
        }

        if (validProjectFiles.isEmpty()) {
            RouterUtil.sendValidationError(
                    exchange, "No valid relative paths provided; invalid: " + String.join(", ", invalidPaths));
            return;
        }

        var before = contextManager.getFilesInContext();
        contextManager.addFiles(validProjectFiles);
        var after = contextManager.getFilesInContext();
        var addedFiles = after.stream().filter(pf -> !before.contains(pf)).toList();

        var addedContextFiles = new ArrayList<AddedContextFile>();
        var live = contextManager.liveContext();
        for (var projectFile : addedFiles) {
            var fragId = live.allFragments()
                    .filter(f -> f instanceof ContextFragments.PathFragment pf
                            && pf.file().absPath().equals(projectFile.absPath()))
                    .map(ContextFragment::id)
                    .findFirst()
                    .orElse("");
            addedContextFiles.add(new AddedContextFile(
                    fragId, root.relativize(projectFile.absPath()).toString()));
        }

        SimpleHttpServer.sendJsonResponse(exchange, new AddContextFilesResponse(addedContextFiles));
    }

    private void handlePostContextClasses(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        var request = RouterUtil.parseJsonOr400(exchange, AddContextClassesRequest.class, "/v1/context/classes");
        if (request == null) return;
        if (request.classNames().isEmpty()) {
            RouterUtil.sendValidationError(exchange, "classNames must not be empty");
            return;
        }

        var analyzer = contextManager.getAnalyzerUninterrupted();
        var validClassNames = new ArrayList<String>();
        for (var className : request.classNames()) {
            if (className == null || className.isBlank()) continue;
            var trimmed = className.strip();
            if (analyzer.getDefinitions(trimmed).stream().anyMatch(CodeUnit::isClass)) {
                validClassNames.add(trimmed);
            }
        }

        if (validClassNames.isEmpty()) {
            RouterUtil.sendValidationError(exchange, "No valid class names provided");
            return;
        }

        contextManager.addSummaries(
                Set.of(),
                validClassNames.stream()
                        .flatMap(name -> analyzer.getDefinitions(name).stream().filter(CodeUnit::isClass))
                        .collect(Collectors.toSet()));

        var addedClasses = new ArrayList<AddedContextClass>();
        var live = contextManager.liveContext();
        for (var className : validClassNames) {
            var fragId = live.allFragments()
                    .filter(f -> f instanceof ContextFragments.SummaryFragment sf
                            && sf.getTargetIdentifier().contains(className))
                    .map(ContextFragment::id)
                    .findFirst()
                    .orElse("");
            addedClasses.add(new AddedContextClass(fragId, className));
        }

        SimpleHttpServer.sendJsonResponse(exchange, new AddContextClassesResponse(addedClasses));
    }

    private void handlePostContextMethods(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        var request = RouterUtil.parseJsonOr400(exchange, AddContextMethodsRequest.class, "/v1/context/methods");
        if (request == null) return;
        if (request.methodNames().isEmpty()) {
            RouterUtil.sendValidationError(exchange, "methodNames must not be empty");
            return;
        }

        var analyzer = contextManager.getAnalyzerUninterrupted();
        var validMethodNames = new ArrayList<String>();
        for (var methodName : request.methodNames()) {
            if (methodName == null || methodName.isBlank()) continue;
            var trimmed = methodName.strip();
            if (analyzer.getDefinitions(trimmed).stream().anyMatch(CodeUnit::isFunction)) {
                validMethodNames.add(trimmed);
            }
        }

        if (validMethodNames.isEmpty()) {
            RouterUtil.sendValidationError(exchange, "No valid method names provided");
            return;
        }

        var addedMethods = new ArrayList<AddedContextMethod>();
        for (var methodName : validMethodNames) {
            var fragment = new ContextFragments.CodeFragment(contextManager, methodName);
            contextManager.addFragments(fragment);
            addedMethods.add(new AddedContextMethod(fragment.id(), methodName));
        }

        SimpleHttpServer.sendJsonResponse(exchange, new AddContextMethodsResponse(addedMethods));
    }

    private void handlePostContextText(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;
        var request = RouterUtil.parseJsonOr400(exchange, AddContextTextRequest.class, "/v1/context/text");
        if (request == null) return;

        var text = request.text();
        if (text.isBlank()) {
            RouterUtil.sendValidationError(exchange, "text must not be blank");
            return;
        }

        if (text.getBytes(UTF_8).length > 1024 * 1024) {
            RouterUtil.sendValidationError(exchange, "text exceeds maximum size of 1 MiB");
            return;
        }

        contextManager.addPastedTextFragment(text);
        var live = contextManager.liveContext();
        var fragments = live.getAllFragmentsInDisplayOrder();
        String id = "";
        for (int i = fragments.size() - 1; i >= 0; i--) {
            if (fragments.get(i).getType() == ContextFragment.FragmentType.PASTE_TEXT) {
                id = fragments.get(i).id();
                break;
            }
        }

        SimpleHttpServer.sendJsonResponse(exchange, Map.of("id", id, "chars", text.length()));
    }

    private String classifyChipKind(ContextFragment fragment) {
        if (fragment.getType() == ContextFragment.FragmentType.SKELETON) return "SUMMARY";
        if (!fragment.isValid()) return "INVALID";
        if (fragment.getType().isEditable()) return "EDIT";
        if (fragment.getType() == ContextFragment.FragmentType.HISTORY) return "HISTORY";
        if (fragment instanceof ContextFragments.StringFragment sf
                && SpecialTextType.TASK_LIST
                        .description()
                        .equals(sf.description().renderNowOrNull())) return "TASK_LIST";
        return "OTHER";
    }

    private int estimateFragmentTokens(ContextFragment f) {
        try {
            if (f.isText() || f.getType().isOutput()) {
                var text = f.text().renderNowOr("");
                if (!text.isBlank()) return Messages.getApproximateTokens(text);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private record DropFragmentsRequest(List<String> fragmentIds) {}

    private record PinFragmentRequest(String fragmentId, boolean pinned) {}

    private record ReadonlyFragmentRequest(String fragmentId, boolean readonly) {}

    private record AddContextFilesRequest(List<String> relativePaths) {}

    private record AddedContextFile(String id, String relativePath) {}

    private record AddContextFilesResponse(List<AddedContextFile> added) {}

    private record AddContextClassesRequest(List<String> classNames) {}

    private record AddedContextClass(String id, String className) {}

    private record AddContextClassesResponse(List<AddedContextClass> added) {}

    private record AddContextMethodsRequest(List<String> methodNames) {}

    private record AddedContextMethod(String id, String methodName) {}

    private record AddContextMethodsResponse(List<AddedContextMethod> added) {}

    private record AddContextTextRequest(String text) {}
}
