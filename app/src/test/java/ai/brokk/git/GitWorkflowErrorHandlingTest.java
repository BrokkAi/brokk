package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AbstractService;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.testutil.TestConsoleIO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for GitWorkflow.suggestCommitMessageStreaming error handling.
 *
 * These tests simulate different LLM error scenarios using a FakeLlm and verify that:
 * - Retriable/provider failures (InternalServerException, RateLimitException) do not
 *   cause the generic RuntimeException("LLM error while generating commit message")
 *   to be thrown; instead an empty string is returned when there is no partial text.
 * - Non-retriable/programmer errors (InvalidRequestException) are wrapped in a
 *   RuntimeException whose cause is the original error and whose message contains
 *   the expected prefix.
 */
class GitWorkflowErrorHandlingTest {

    @TempDir
    Path tempDir;

    private FakeProject project;
    private FakeGitRepo repo;
    private TestConsoleIO io;
    private FakeContextManager cm;
    private FakeLlm fakeLlm;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize a real git repository so GitRepo constructor succeeds
        Git.init().setDirectory(tempDir.toFile()).call();

        repo = new FakeGitRepo(tempDir);
        project = new FakeProject(tempDir, repo);
        io = new TestConsoleIO();
        cm = new FakeContextManager(project, io);
        fakeLlm = new FakeLlm(cm);
        cm.setLlm(fakeLlm);
    }

    @Test
    void internalServerError_returnsEmptyStringAndDoesNotThrow() throws Exception {
        fakeLlm.errorToReturn = new InternalServerException("server error");

        GitWorkflow workflow = new GitWorkflow(cm);
        String result = workflow.suggestCommitMessageStreaming(List.of(), false, io);

        assertEquals("", result); // no exception, empty suggestion
    }

    @Test
    void rateLimitError_returnsEmptyStringAndDoesNotThrow() throws Exception {
        fakeLlm.errorToReturn = new RateLimitException("rate limit");

        GitWorkflow workflow = new GitWorkflow(cm);
        String result = workflow.suggestCommitMessageStreaming(List.of(), false, io);

        assertEquals("", result); // no exception, empty suggestion
    }

    @Test
    void invalidRequest_throwsRuntimeExceptionWrappingError() {
        fakeLlm.errorToReturn = new InvalidRequestException("bad request");

        GitWorkflow workflow = new GitWorkflow(cm);

        RuntimeException ex = assertThrows(
                RuntimeException.class, () -> workflow.suggestCommitMessageStreaming(List.of(), false, io));
        assertNotNull(ex.getMessage(), "RuntimeException message should not be null");
        assertTrue(
                ex.getMessage().contains("LLM error while generating commit message"),
                "Wrapper message should contain commit message prefix. Got: " + ex.getMessage());
        assertSame(fakeLlm.errorToReturn, ex.getCause());
    }

    /**
     * Fake LLM that always returns a StreamingResult with a configured error
     * and no partial text.
     */
    private static final class FakeLlm extends Llm {
        volatile Exception errorToReturn;

        FakeLlm(IContextManager cm) {
            super(null, "test-task", TaskResult.Type.SUMMARIZE, cm, false, false, false, false);
        }

        @Override
        public StreamingResult sendRequest(List<ChatMessage> messages) {
            // Simulate pure-error StreamingResult (no partial response text)
            return new StreamingResult(null, errorToReturn);
        }
    }

    /**
     * Minimal IContextManager implementation for this test.
     */
    private static final class FakeContextManager implements IContextManager {
        private final FakeProject project;
        private final TestConsoleIO io;
        private final FakeService service;
        private volatile FakeLlm llm;

        FakeContextManager(FakeProject project, TestConsoleIO io) {
            this.project = project;
            this.io = io;
            this.service = new FakeService(project);
        }

        void setLlm(FakeLlm llm) {
            this.llm = llm;
        }

        @Override
        public IProject getProject() {
            return project;
        }

        @Override
        public AbstractService getService() {
            return service;
        }

        @Override
        public Llm getLlm(Llm.Options options) {
            if (llm == null) {
                throw new IllegalStateException("FakeLlm not initialized");
            }
            return llm;
        }

        @Override
        public IConsoleIO getIo() {
            return io;
        }
    }

    /**
     * Minimal AbstractService implementation satisfying abstract methods.
     */
    private static final class FakeService extends AbstractService {

        FakeService(IProject project) {
            super(project);
        }

        @Override
        public float getUserBalance() {
            return 0f;
        }

        @Override
        public void sendFeedback(String category, String feedbackText, boolean includeDebugLog, File screenshotFile) {
            // no-op for tests
        }

        @Override
        public StreamingChatModel getModel(ModelProperties.ModelType type) {
            // We never actually call through to a real model in these tests.
            return null;
        }

        @Override
        public JsonNode reportClientException(
                String stacktrace, String clientVersion, Map<String, String> optionalFields) {
            // Keep it simple: return an empty object node.
            return new ObjectMapper().createObjectNode();
        }
    }

    /**
     * Minimal IProject implementation for this test.
     */
    private static final class FakeProject implements IProject {
        private final Path root;
        private final GitRepo repo;

        FakeProject(Path root, GitRepo repo) {
            this.root = root;
            this.repo = repo;
        }

        @Override
        public GitRepo getRepo() {
            return repo;
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Set<ProjectFile> getAllFiles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getCommitMessageFormat() {
            return "";
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /**
     * Minimal GitRepo subclass that only supports the operations used by
     * suggestCommitMessageStreaming.
     */
    private static final class FakeGitRepo extends GitRepo {
        FakeGitRepo(Path projectRoot) throws GitAPIException {
            super(projectRoot);
        }

        @Override
        public String diff() {
            // Must look like a real git diff or ContentDiffUtils will reject it,
            // preventing the LLM call entirely.
            return """
                   diff --git a/file.txt b/file.txt
                   index 0000000..1234567
                   --- a/file.txt
                   +++ b/file.txt
                   @@ -1 +1 @@
                   -old
                   +new
                   """;
        }

        @Override
        public String diffFiles(Collection<ProjectFile> files) {
            return diff();
        }

        @Override
        public String getCurrentCommitId() {
            return "deadbeef";
        }
    }
}
