package ai.brokk.http;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import ai.brokk.ContextManager;
import ai.brokk.MainProject;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HttpServer {

    private static final Logger logger = LogManager.getLogger(HttpServer.class);

    private final Server server;
    private final int port;

    public HttpServer(int port) {
        this.port = port;
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        handler.setContextPath("/");
        handler.addServlet(new ServletHolder(new HealthServlet()), "/health");
        handler.addServlet(new ServletHolder(new ReviewCliServlet()), "/v1/review/cli");
        handler.addServlet(new ServletHolder(new ReviewServlet()), "/v1/review");
        server.setHandler(handler);

    }

    /**
     * Start the HTTP server. This method returns after the server has started.
     */
    public void start() throws Exception {
        server.start();
        logger.info("HTTP server started on port {}", getPort());
    }

    /**
     * Start the HTTP server and block the current thread until the server is stopped.
     */
    public void startAndJoin() throws Exception {
        start();
        server.join();
    }

    /**
     * Stop the HTTP server gracefully.
     *
     * @param delaySeconds number of seconds to wait for ongoing requests before forceful stop
     */
    public void stop(int delaySeconds) throws Exception {
        long timeoutMs = Math.max(0, delaySeconds) * 1000L;
        server.setStopTimeout(timeoutMs);
        server.stop();
        logger.info("HTTP server stopped");
    }

    /**
     * Return the actual port the server is bound to (useful when configured with port 0).
     */
    public int getPort() {
        return (server.getConnectors().length > 0 && server.getConnectors()[0] instanceof ServerConnector sc)
                ? sc.getLocalPort()
                : this.port;
    }

    /**
     * Functional interface for servlet actions that may throw checked exceptions.
     */
    @FunctionalInterface
    public interface CheckedServletAction {
        void handle(HttpServletRequest req, HttpServletResponse resp) throws Exception;
    }

    public static class HelloServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.getWriter().println("{ \"message\": \"Brokk HTTP server\" }");
        }
    }

    public static class HealthServlet extends HttpServlet {
     @Override
     protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
         resp.setContentType("application/json");
         resp.setStatus(HttpServletResponse.SC_OK);
         resp.getWriter().println("{\"status\":\"healthy\"}");
     }
    }

    public static class ReviewCliServlet extends HttpServlet {
        private final ObjectMapper mapper = new ObjectMapper();
        private static final Object BROKK_KEY_LOCK = new Object();

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");

            String apiKey = req.getHeader("BROKK_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().println("{\"error\":\"Missing BROKK_API_KEY header\"}");
                return;
            }
            String codeModel = req.getHeader("CODE_MODEL");
            if (codeModel == null || codeModel.isBlank()) {
                codeModel = "GPT-5";
            }
            String SessionId = req.getHeader("X_SESSION_ID");

            String body = req.getReader().lines().collect(Collectors.joining("\n"));
            if (body == null || body.isBlank()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().println("{\"error\":\"Empty request body\"}");
                return;
            }

            try {
                mapper.readTree(body);
            } catch (Exception e) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().println("{\"error\":\"Invalid JSON payload\"}");
                return;
            }
            if (codeModel == null || codeModel.isBlank()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().println("{\"error\":\"Missing BRK_CODEMODEL environment variable\"}");
                return;
            }

            int exitCode;
            synchronized (BROKK_KEY_LOCK) {
                String prevKey = MainProject.getBrokkKey();
                try {
                    MainProject.setBrokkKey(apiKey.trim());
                    if (SessionId != null && !SessionId.isBlank()) {
                        exitCode = new CommandLine(new ai.brokk.cli.BrokkCli())
                                .execute("--codemodel", codeModel, "--review", body, "--session", SessionId);
                    } else {
                        exitCode = new CommandLine(new ai.brokk.cli.BrokkCli())
                                .execute("--codemodel", codeModel, "--review", body);
                    }
                } catch (Throwable t) {
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    resp.getWriter().println(mapper.writeValueAsString(Map.of(
                            "error", "Failed to invoke CLI",
                            "detail", t.getMessage())));
                    return;
                } finally {
                    try {
                        if (prevKey == null || prevKey.isBlank()) {
                            MainProject.setBrokkKey("");
                        } else {
                            MainProject.setBrokkKey(prevKey);
                        }
                    } catch (Throwable t) {
                        System.err.println("Warning: Failed to restore previous Brokk API key: " + t.getMessage());
                    }
                }
            }

            if (exitCode != 0) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().println(mapper.writeValueAsString(Map.of(
                        "error", "CLI returned non-zero exit code",
                        "exitCode", exitCode)));
                return;
            }

            Path reviewPath = Paths.get("review.json");
            if (!Files.exists(reviewPath)) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                resp.getWriter().println(mapper.writeValueAsString(Map.of(
                        "error", "review.json not found after CLI execution")));
                return;
            }

            String reviewJson;
            try {
                reviewJson = Files.readString(reviewPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                resp.getWriter().println(mapper.writeValueAsString(Map.of(
                        "error", "Failed to read review.json",
                        "detail", e.getMessage())));
                return;
            }

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().println(reviewJson);
            try {
                Files.deleteIfExists(reviewPath);
            } catch (IOException e) {
                System.err.println("Warning: Failed to delete review.json after response: " + e.getMessage());
            }
        }
    }

    public static class ReviewServlet extends HttpServlet {
        private final ObjectMapper mapper = new ObjectMapper();
        private static final Object BROKK_KEY_LOCK = new Object();

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");

            String apiKey = req.getHeader("BROKK_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().println("{\"error\":\"Missing BROKK_API_KEY header\"}");
                return;
            }
            String codeModelAlias = req.getHeader("CODE_MODEL");
            if (codeModelAlias == null || codeModelAlias.isBlank()) {
                codeModelAlias = "GPT-5";
            }
            String sessionIdStr = req.getHeader("X_SESSION_ID");

            String body = req.getReader().lines().collect(Collectors.joining("\n"));
            if (body == null || body.isBlank()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().println("{\"error\":\"Empty request body\"}");
                return;
            }

            Map<String, Object> prData;
            try {
                prData = ai.brokk.cli.BrokkCli.parseReviewInput(body);
            } catch (Exception e) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().println(mapper.writeValueAsString(Map.of(
                        "error", "Invalid JSON payload",
                        "detail", e.getMessage())));
                return;
            }

            if (!ai.brokk.cli.BrokkCli.validatePrData(prData)) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().println(mapper.writeValueAsString(Map.of(
                        "error", "PR data is missing required fields",
                        "required", "pr_number, title, author, base_branch, head_branch, changed_files")));
                return;
            }

            synchronized (BROKK_KEY_LOCK) {
                String prevKey = MainProject.getBrokkKey();
                Path tempProjectPath = null;
                ContextManager cm = null;

                try {
                    MainProject.setBrokkKey(apiKey.trim());

                    tempProjectPath = Files.createTempDirectory("brokk-review-");
                    var mainProject = new MainProject(tempProjectPath);
                    var project = mainProject;
                    cm = new ContextManager(project);

                    cm.createHeadless();
                    cm.replaceIo(new ai.brokk.cli.SilentConsole());

                    Service.FavoriteModel favModel;
                    try {
                        favModel = MainProject.getFavoriteModel(codeModelAlias);
                    } catch (IllegalArgumentException e) {
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        resp.getWriter().println(mapper.writeValueAsString(Map.of(
                                "error", "Unknown code model",
                                "model", codeModelAlias)));
                        return;
                    }

                    var codeModel = cm.getService().getModel(favModel.config());
                    if (codeModel == null) {
                        resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        resp.getWriter().println(mapper.writeValueAsString(Map.of(
                                "error", "Failed to initialize code model")));
                        return;
                    }

                    if (sessionIdStr != null && !sessionIdStr.isBlank()) {
                        try {
                            UUID sessionId = UUID.fromString(sessionIdStr);
                            cm.switchSessionAsync(sessionId).join();
                        } catch (Exception e) {
                            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                            resp.getWriter().println(mapper.writeValueAsString(Map.of(
                                    "error", "Invalid session ID",
                                    "detail", e.getMessage())));
                            return;
                        }
                    }

                    int prNumber = ((Number) prData.get("pr_number")).intValue();
                    String prTitle = (String) prData.get("title");
                    String descriptionText = (String) prData.get("body");
                    String baseBranch = (String) prData.get("base_branch");
                    String headBranch = (String) prData.get("head_branch");

                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> changedFilesData = (List<Map<String, String>>) prData.get("changed_files");

                    StringBuilder diffBuilder = new StringBuilder();
                    List<String> filenames = new ArrayList<>();
                    if (changedFilesData != null && !changedFilesData.isEmpty()) {
                        for (Map<String, String> fileData : changedFilesData) {
                            String filename = fileData.get("filename");
                            String patch = fileData.get("patch");
                            if (filename != null) {
                                filenames.add(filename);
                            }
                            if (filename != null && patch != null && !patch.isEmpty()) {
                                diffBuilder
                                    .append("diff --git a/").append(filename).append(" b/").append(filename).append("\n")
                                    .append("--- a/").append(filename).append("\n")
                                    .append("+++ b/").append(filename).append("\n")
                                    .append(patch);
                                if (!patch.endsWith("\n")) {
                                    diffBuilder.append("\n");
                                }
                                diffBuilder.append("\n");
                            }
                        }
                    }

                    String diff = diffBuilder.toString();

                    if (!diff.isEmpty()) {
                        String fileNamesSummary = filenames.isEmpty()
                                ? "(no files)"
                                : filenames.stream().collect(Collectors.joining(", "));
                        String description = String.format(
                                "Diff of PR #%d (%s): %s [HEAD branch: %s vs Base branch: %s]",
                                prNumber,
                                prTitle,
                                fileNamesSummary,
                                headBranch,
                                baseBranch);

                        String syntaxStyle = org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_NONE;
                        if (!filenames.isEmpty()) {
                            String first = filenames.get(0);
                            int dot = first.lastIndexOf('.');
                            String ext = dot >= 0 ? first.substring(dot + 1) : "";
                            syntaxStyle = ai.brokk.util.SyntaxDetector.fromExtension(ext);
                        }

                        var fragment = new ai.brokk.context.ContextFragment.StringFragment(cm, diff, description, syntaxStyle);
                        cm.addVirtualFragment(fragment);
                    }

                    if (descriptionText != null && !descriptionText.isBlank()) {
                        var descriptionFragment = new ai.brokk.context.ContextFragment.StringFragment(
                                cm,
                                descriptionText,
                                ai.brokk.gui.PrTitleFormatter.formatDescriptionTitle(prNumber),
                                "markdown");
                        cm.addVirtualFragment(descriptionFragment);
                    }

                    String reviewGuide = project.getReviewGuide();
                    String reviewPromptFormatted = ai.brokk.cli.BrokkCli.formatReviewPrompt(prNumber, prTitle, reviewGuide, prData);

                    TaskResult result;
                    try (var scope = cm.beginTask("Review", false)) {
                        result = ai.brokk.gui.InstructionsPanel.executeAskCommand(cm, codeModel, reviewPromptFormatted);
                        scope.append(result);
                    }

                    var msg = (dev.langchain4j.data.message.AiMessage) result.output().messages().getLast();

                    Map<String, Object> reviewOutput;
                    String rawText = msg.text();
                    try {
                        String cleaned = rawText;

                        int jsonStart = rawText.indexOf("{");
                        int jsonEnd = rawText.lastIndexOf("}");
                        if (jsonStart >= 0 && jsonEnd > jsonStart) {
                            cleaned = rawText.substring(jsonStart, jsonEnd + 1);
                        }

                        cleaned = cleaned.replaceAll("(?s)^```json\\s*|\\s*```$", "").trim();

                        reviewOutput = mapper.readValue(cleaned, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        if (!reviewOutput.containsKey("action") || !reviewOutput.containsKey("comments") || !reviewOutput.containsKey("summary")) {
                            throw new IllegalArgumentException("Missing required fields in model output");
                        }
                    } catch (Exception e) {
                        reviewOutput = new LinkedHashMap<>();
                        reviewOutput.put("action", "COMMENT");
                        reviewOutput.put("comments", List.of());
                        reviewOutput.put("summary", rawText);
                    }

                    reviewOutput.put("pr_number", prNumber);
                    reviewOutput.put("pr_title", prTitle);
                    reviewOutput.put("timestamp", java.time.Instant.now().toString());

                    resp.setStatus(HttpServletResponse.SC_OK);
                    mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
                    resp.getWriter().println(mapper.writeValueAsString(reviewOutput));

                } catch (Throwable t) {
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    resp.getWriter().println(mapper.writeValueAsString(Map.of(
                            "error", "Failed to execute review",
                            "detail", t.getMessage())));
                } finally {
                    if (cm != null) {
                        try {
                            cm.closeAsync(5000).get(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception e) {
                            System.err.println("Warning: Failed to shutdown ContextManager: " + e.getMessage());
                        }
                    }

                    if (tempProjectPath != null) {
                        try {
                            Files.walk(tempProjectPath)
                                .sorted(java.util.Comparator.reverseOrder())
                                .forEach(path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (IOException e) {
                                        // Ignore
                                    }
                                });
                        } catch (IOException e) {
                            System.err.println("Warning: Failed to cleanup temp directory: " + e.getMessage());
                        }
                    }

                    try {
                        if (prevKey == null || prevKey.isBlank()) {
                            MainProject.setBrokkKey("");
                        } else {
                            MainProject.setBrokkKey(prevKey);
                        }
                    } catch (Throwable t) {
                        System.err.println("Warning: Failed to restore previous Brokk API key: " + t.getMessage());
                    }
                }
            }
        }
    }
}
