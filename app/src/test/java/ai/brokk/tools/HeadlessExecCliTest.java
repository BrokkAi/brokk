package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HeadlessExecCliTest {

    @Test
    void emptyReasoningLevelIsAccepted_butInvalidTemperatureFailsParsing() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        try (PrintStream capturedErr = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedErr);

            String[] args = {"--planner-model", "gpt-5", "--reasoning-level", "", "--temperature", "not-a-number"};

            int exitCode = HeadlessExecCli.runCli(args);
            capturedErr.flush();

            String stderr = errBytes.toString(StandardCharsets.UTF_8);

            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: Invalid --temperature value: not-a-number"), stderr);
            assertFalse(stderr.toLowerCase().contains("reasoning"), stderr);
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testParseArgs_IssueMode_RequiredFields() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        try (PrintStream capturedErr = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedErr);

            // Test missing --github-token
            String[] argsMissingToken = {"--planner-model", "gpt-5", "--mode", "ISSUE",
                    "--repo-owner", "owner", "--repo-name", "repo", "--issue-number", "123", "Fix bug"};
            int exitCode = HeadlessExecCli.runCli(argsMissingToken);
            capturedErr.flush();
            String stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --github-token is required for ISSUE mode"), stderr);

            // Reset
            errBytes.reset();

            // Test missing --repo-owner
            String[] argsMissingOwner = {"--planner-model", "gpt-5", "--mode", "ISSUE",
                    "--github-token", "token123", "--repo-name", "repo", "--issue-number", "123", "Fix bug"};
            exitCode = HeadlessExecCli.runCli(argsMissingOwner);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --repo-owner is required for ISSUE mode"), stderr);

            // Reset
            errBytes.reset();

            // Test missing --repo-name
            String[] argsMissingRepo = {"--planner-model", "gpt-5", "--mode", "ISSUE",
                    "--github-token", "token123", "--repo-owner", "owner", "--issue-number", "123", "Fix bug"};
            exitCode = HeadlessExecCli.runCli(argsMissingRepo);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --repo-name is required for ISSUE mode"), stderr);

            // Reset
            errBytes.reset();

            // Test missing --issue-number
            String[] argsMissingIssue = {"--planner-model", "gpt-5", "--mode", "ISSUE",
                    "--github-token", "token123", "--repo-owner", "owner", "--repo-name", "repo", "Fix bug"};
            exitCode = HeadlessExecCli.runCli(argsMissingIssue);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --issue-number is required for ISSUE mode"), stderr);

            // Reset
            errBytes.reset();

            // Test invalid --issue-number (not positive)
            String[] argsInvalidIssue = {"--planner-model", "gpt-5", "--mode", "ISSUE",
                    "--github-token", "token123", "--repo-owner", "owner", "--repo-name", "repo",
                    "--issue-number", "0", "Fix bug"};
            exitCode = HeadlessExecCli.runCli(argsInvalidIssue);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --issue-number must be a positive integer"), stderr);

            // Reset
            errBytes.reset();

            // Test invalid --issue-number (not a number)
            String[] argsNonNumericIssue = {"--planner-model", "gpt-5", "--mode", "ISSUE",
                    "--github-token", "token123", "--repo-owner", "owner", "--repo-name", "repo",
                    "--issue-number", "abc", "Fix bug"};
            exitCode = HeadlessExecCli.runCli(argsNonNumericIssue);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: Invalid --issue-number value: abc"), stderr);

        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testParseArgs_IssueMode_OptionalFields() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        try (PrintStream capturedErr = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedErr);

            // Test invalid --max-issue-fix-attempts (not positive)
            String[] argsInvalidAttempts = {"--planner-model", "gpt-5", "--mode", "ISSUE",
                    "--github-token", "token123", "--repo-owner", "owner", "--repo-name", "repo",
                    "--issue-number", "123", "--max-issue-fix-attempts", "0", "Fix bug"};
            int exitCode = HeadlessExecCli.runCli(argsInvalidAttempts);
            capturedErr.flush();
            String stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --max-issue-fix-attempts must be a positive integer"), stderr);

            // Reset
            errBytes.reset();

            // Test invalid --max-issue-fix-attempts (not a number)
            String[] argsNonNumericAttempts = {"--planner-model", "gpt-5", "--mode", "ISSUE",
                    "--github-token", "token123", "--repo-owner", "owner", "--repo-name", "repo",
                    "--issue-number", "123", "--max-issue-fix-attempts", "abc", "Fix bug"};
            exitCode = HeadlessExecCli.runCli(argsNonNumericAttempts);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: Invalid --max-issue-fix-attempts value: abc"), stderr);

        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testParseArgs_ReviewMode_RequiredFields() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        try (PrintStream capturedErr = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedErr);

            // Test missing --github-token
            String[] argsMissingToken = {"--planner-model", "gpt-5", "--mode", "REVIEW",
                    "--repo-owner", "owner", "--repo-name", "repo", "--pr-number", "101", "Review this"};
            int exitCode = HeadlessExecCli.runCli(argsMissingToken);
            capturedErr.flush();
            String stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --github-token is required for REVIEW mode"), stderr);

            // Reset
            errBytes.reset();

            // Test missing --repo-owner
            String[] argsMissingOwner = {"--planner-model", "gpt-5", "--mode", "REVIEW",
                    "--github-token", "token123", "--repo-name", "repo", "--pr-number", "101", "Review this"};
            exitCode = HeadlessExecCli.runCli(argsMissingOwner);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --repo-owner is required for REVIEW mode"), stderr);

            // Reset
            errBytes.reset();

            // Test missing --repo-name
            String[] argsMissingRepo = {"--planner-model", "gpt-5", "--mode", "REVIEW",
                    "--github-token", "token123", "--repo-owner", "owner", "--pr-number", "101", "Review this"};
            exitCode = HeadlessExecCli.runCli(argsMissingRepo);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --repo-name is required for REVIEW mode"), stderr);

            // Reset
            errBytes.reset();

            // Test missing --pr-number
            String[] argsMissingPr = {"--planner-model", "gpt-5", "--mode", "REVIEW",
                    "--github-token", "token123", "--repo-owner", "owner", "--repo-name", "repo", "Review this"};
            exitCode = HeadlessExecCli.runCli(argsMissingPr);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --pr-number is required for REVIEW mode"), stderr);

            // Reset
            errBytes.reset();

            // Test invalid --pr-number (0)
            String[] argsInvalidPr = {"--planner-model", "gpt-5", "--mode", "REVIEW",
                    "--github-token", "token123", "--repo-owner", "owner", "--repo-name", "repo",
                    "--pr-number", "0", "Review this"};
            exitCode = HeadlessExecCli.runCli(argsInvalidPr);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: --pr-number must be a positive integer"), stderr);

            // Reset
            errBytes.reset();

            // Test non-numeric --pr-number
            String[] argsNonNumericPr = {"--planner-model", "gpt-5", "--mode", "REVIEW",
                    "--github-token", "token123", "--repo-owner", "owner", "--repo-name", "repo",
                    "--pr-number", "abc", "Review this"};
            exitCode = HeadlessExecCli.runCli(argsNonNumericPr);
            capturedErr.flush();
            stderr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: Invalid --pr-number value: abc"), stderr);

        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void testParseArgs_ReviewMode_ValidArgs() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        try (PrintStream capturedErr = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedErr);

            String[] args = {
                "--planner-model", "gpt-5",
                "--mode", "REVIEW",
                "--github-token", "token123",
                "--repo-owner", "owner",
                "--repo-name", "repo",
                "--pr-number", "101",
                "Review this"
            };

            HeadlessExecCli cli = new HeadlessExecCli();
            java.lang.reflect.Method parseArgs = HeadlessExecCli.class.getDeclaredMethod("parseArgs", String[].class);
            parseArgs.setAccessible(true);

            boolean result = (boolean) parseArgs.invoke(cli, (Object) args);
            assertTrue(result, "Parsing should succeed for valid REVIEW mode arguments");

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void chooseWorkspaceRootForMode_nonIssueReview_usesCwd() {
        Path expected = Path.of("").toAbsolutePath().normalize();
        var selection = HeadlessExecCli.chooseWorkspaceRootForMode("ASK", "", "");
        assertEquals(expected, selection.root());
        assertFalse(selection.isTemporary());
    }

    @Test
    void chooseWorkspaceRootForMode_issue_usesTemp_notCwd() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        var selection = HeadlessExecCli.chooseWorkspaceRootForMode("ISSUE", "owner", "repo");
        assertTrue(selection.isTemporary());
        assertTrue(selection.root().isAbsolute());
        assertFalse(selection.root().equals(cwd), "ISSUE workspace should not be CWD");
    }

    @Test
    void chooseWorkspaceRootForMode_review_usesTemp_notCwd() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        var selection = HeadlessExecCli.chooseWorkspaceRootForMode("REVIEW", "owner", "repo");
        assertTrue(selection.isTemporary());
        assertTrue(selection.root().isAbsolute());
        assertFalse(selection.root().equals(cwd), "REVIEW workspace should not be CWD");
    }
}
