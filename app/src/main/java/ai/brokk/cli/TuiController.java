package ai.brokk.cli;

import ai.brokk.ContextManager;
import ai.brokk.agents.SearchAgent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.util.Locale;
import java.util.Objects;

public final class TuiController {
    private final ContextManager cm;
    private final TuiConsole console;
    private final BufferedReader reader;
    private final PrintStream out;
    private volatile boolean running = false;

    public TuiController(ContextManager cm, TuiConsole console) {
        this(cm, console, new InputStreamReader(System.in), System.out);
    }

    public TuiController(ContextManager cm, TuiConsole console, Reader inputReader, PrintStream out) {
        this.cm = Objects.requireNonNull(cm, "cm");
        this.console = Objects.requireNonNull(console, "console");
        Objects.requireNonNull(inputReader, "inputReader");
        this.reader = inputReader instanceof BufferedReader br ? br : new BufferedReader(inputReader);
        this.out = Objects.requireNonNull(out, "out");
    }

    public void run() {
        printWelcome();
        running = true;
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                var trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("/")) {
                    handleCommand(trimmed);
                } else {
                    submitLutz(trimmed);
                }
            }
        } catch (IOException e) {
            cm.getIo().toolError("Input error: " + e.getMessage(), "TUI");
        } finally {
            console.shutdown();
        }
    }

    private void handleCommand(String cmd) {
        var normalized = cmd.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "/c", "/chips" -> {
                console.toggleChipPanel();
                out.println("[TUI] Chip panel toggled.");
                out.flush();
            }
            case "/t", "/tasks" -> {
                console.toggleTaskList();
                out.println("[TUI] Task list toggled.");
                out.flush();
            }
            case "/q", "/quit", "/exit" -> {
                running = false;
                out.println("[TUI] Exiting...");
                out.flush();
            }
            default -> {
                out.println("[TUI] Unknown command: " + cmd);
                out.flush();
            }
        }
    }

    private void submitLutz(String prompt) {
        console.setTaskInProgress(true);
        try {
            var future =
                    cm.submitLlmAction(() -> {
                        try (var scope = cm.beginTask(prompt, true)) {
                            var planningModel = cm.getCodeModel();
                            var agent = new SearchAgent(
                                    cm.liveContext(),
                                    prompt,
                                    planningModel,
                                    SearchAgent.Objective.LUTZ,
                                    scope);
                            agent.execute();
                        }
                    });
            future.whenComplete((ignored, ex) -> {
                console.setTaskInProgress(false);
                if (ex != null) {
                    cm.getIo().toolError("Lutz execution failed: " + ex.getMessage(), "TUI");
                }
            });
        } catch (Throwable t) {
            console.setTaskInProgress(false);
            cm.getIo().toolError("Unable to submit Lutz run: " + t.getMessage(), "TUI");
        }
    }

    private void printWelcome() {
        out.println();
        out.println("Brokk TUI - interactive mode");
        out.println("Slash commands (map to Ctrl/Cmd shortcuts):");
        out.println("  /chips (/c)  -> toggle context chip panel");
        out.println("  /tasks (/t)  -> toggle read-only task list");
        out.println("  /quit  (/q)  -> exit the TUI");
        out.println("Enter any other text to launch a Lutz search with the default models.");
        out.flush();
    }
}
