package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

/**
 * Smoke test for HeadlessExecCli end-to-end workflow.
 *
 * <p>This test verifies that the CLI argument parsing and help flag work correctly.
 * Full end-to-end tests (with executor startup) require LLM model access and working Git
 * repos, so they are gated behind the BROKK_CLI_SMOKE=true environment variable.
 *
 * <p>The --help test validates CLI argument parsing without invoking the executor.
 * The validation test ensures required arguments are enforced without starting services.
 */
class HeadlessExecCliSmokeTest {

  @Test
  void testHeadlessExecCliHelpFlag() {
    var args = new String[] {"--help"};

    int exitCode = HeadlessExecCli.runCli(args);

    assertEquals(0, exitCode, "CLI should exit with code 0 for --help");
  }

  @Test
  void testHeadlessExecCliMissingPlannerModelFails() {
    var args = new String[] {
      "--mode", "ASK",
      "Some prompt"
    };

    int exitCode = HeadlessExecCli.runCli(args);

    assertEquals(1, exitCode, "CLI should exit with code 1 when planner-model is missing");
  }

  /**
   * Full end-to-end smoke test with actual executor startup.
   * Skipped by default; only runs when BROKK_CLI_SMOKE=true is set.
   * Requires LLM model access and working Git repository in the test environment.
   */
  @DisabledIfEnvironmentVariable(
      named = "BROKK_CLI_SMOKE",
      matches = "^(?!true).*",
      disabledReason = "Full smoke test skipped unless BROKK_CLI_SMOKE=true (requires LLM models and Git)")
  @Test
  void testHeadlessExecCliAskModeWithExecutor() {
    var args = new String[] {
      "--mode", "ASK",
      "--planner-model", "gpt-5-mini",
      "Find the main package in this workspace"
    };

    int exitCode = HeadlessExecCli.runCli(args);

    assertEquals(0, exitCode, "CLI should exit successfully (exit code 0)");
  }
}
