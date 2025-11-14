package ai.brokk.cli;

import java.io.PrintStream;
import java.util.Objects;

public final class Ansi {
    private static final String CLEAR_SCREEN = "\u001b[2J";
    private static final String CURSOR_HOME = "\u001b[H";
    private static final String RESET = "\u001b[0m";

    private Ansi() {}

    public static void clearScreen(PrintStream out) {
        Objects.requireNonNull(out, "out");
        out.print(CLEAR_SCREEN);
    }

    public static void home(PrintStream out) {
        Objects.requireNonNull(out, "out");
        out.print(CURSOR_HOME);
    }

    public static void reset(PrintStream out) {
        Objects.requireNonNull(out, "out");
        out.print(RESET);
    }
}
