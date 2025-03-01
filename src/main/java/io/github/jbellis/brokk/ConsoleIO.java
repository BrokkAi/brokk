package io.github.jbellis.brokk;

import io.github.jbellis.brokk.Commands.Command;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.keymap.KeyMap;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public class ConsoleIO implements AutoCloseable, IConsoleIO {
    private final Logger logger = LogManager.getLogger(ConsoleIO.class);

    private final Terminal terminal;
    private final LineReader reader;
    
    private final AtomicBoolean spinnerActive = new AtomicBoolean(false);
    private final AtomicReference<String> spinnerMessage = new AtomicReference<>("");
    private Thread spinnerThread;
    private final char[] spinnerChars = {'/', '-', '\\', '|'};

    public ConsoleIO(Path sourceRoot, Collection<Command> commands, Commands.ArgumentCompleter chatCompleter) {
        try {
            // attempt to have jline sleep between polling for input
            // this doesn't work but maybe try
            // https://github.com/jline/jline3/blob/master/terminal/src/main/java/org/jline/utils/NonBlocking.java
            // System.setProperty("jline.terminal.read.timeout", "50");

            var historyFile = sourceRoot.resolve(".brokk/linereader.txt");
            this.terminal = TerminalBuilder.builder()
                    .nativeSignals(false)
                    .build();

            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("brokk")
                    .variable(LineReader.HISTORY_FILE, historyFile)
                    .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
                    .variable(LineReader.LIST_MAX, 40)
                    // Automatically list all completions (without requiring another Tab) when multiple matches exist
                    .option(LineReader.Option.AUTO_LIST, true)
                    // Optionally show a completion menu instead of a raw listing
                    .option(LineReader.Option.AUTO_MENU, true)
                    // Enable camel-case completions (CDA -> CassandraDiskAnn)
                    .option(LineReader.Option.COMPLETE_MATCHER_CAMELCASE, true)
                    // Our custom Completer will parse input lines
                    // and return a list of possible completions.
                    .completer((rdr, parsedLine, candidates) -> {
                        autocomplete(commands, chatCompleter, parsedLine, candidates);
                    })
                    .build();

            // Grab the default main keymap
            var keyMaps = reader.getKeyMaps();
            var mainKeyMap = keyMaps.get(LineReader.MAIN);
            // add ctrl-space as a completion trigger
            mainKeyMap.bind(new Reference(LineReader.EXPAND_OR_COMPLETE), KeyMap.ctrl(' '));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize console: " + e.getMessage(), e);
        }
    }

    private static void autocomplete(Collection<Command> commands,
                                     Commands.ArgumentCompleter chatCompleter,
                                     ParsedLine parsedLine,
                                     List<Candidate> candidates)
    {
        String line = parsedLine.line();
        // Non-command input: delegate to in-chat completer.
        if (!line.startsWith("/")) {
            // Only complete if we're in the middle of a word.
            if (!line.endsWith(" ")) {
                candidates.addAll(chatCompleter.complete(parsedLine.word()));
            }
            return;
        }

        // Command input
        List<String> words = parsedLine.words();
        String cmdInput = words.get(0).startsWith("/") ? words.get(0).substring(1) : words.get(0);

        // For single-word input (command name), use StringsCompleter
        if (words.size() == 1) {
            commands.forEach(cmd -> {
                String cmdName = "/" + cmd.name();
                candidates.add(new Candidate(cmdName, cmdName, null, cmd.description(), null, null, true));
            });
            return;
        }

        // For arguments, find the exact command and delegate to its completer
        var matchingCommands = commands.stream()
                .filter(cmd -> cmd.name().startsWith(cmdInput))
                .toList();
        if (matchingCommands.size() != 1) {
            return;
        }
        var foundCmd = matchingCommands.getFirst();
        // If there is a second token already, use it; otherwise, use an empty string.
        String argInput = words.size() > 1 ? parsedLine.word() : "";
        candidates.addAll(foundCmd.argumentCompleter().complete(argInput));
    }

    public String getInput(String prefill) {
        return readLineInternal("\n> ", prefill);
    }

    public String getRawInput() {
        return readLineInternal("", "");
    }

    private String readLineInternal(String prompt, String prefill) {
        try {
            // Prompt
            return reader.readLine(prompt, null, prefill);
        } catch (UserInterruptException e) {
            // User pressed Ctrl-C
            toolOutput("Canceled with ^C (use ^D to exit)");
            return "";
        } catch (EndOfFileException e) {
            // Ctrl-D => exit
            System.exit(0);
            return "";
        }
    }

    public void context(String msg) {
        var writer = terminal.writer();
        // ANSI escape code \u001B[96m sets text to cyan, \u001B[0m resets the color
        writer.println("\u001B[96m" + msg + "\u001B[0m");
        writer.flush();
    }

    @Override
    public void toolOutput(String msg) {
        var writer = terminal.writer();
        // ANSI escape code \u001B[94m sets text to blue, \u001B[0m resets the color
        writer.println("\u001B[94m" + msg + "\u001B[0m");
        writer.flush();
    }

    @Override
    public void toolErrorRaw(String msg) {
        logger.debug(msg);
        var writer = terminal.writer();
        // ANSI escape code \u001B[31m sets text to red, \u001B[0m resets the color
        writer.println("\u001B[31m" + msg + "\u001B[0m");
        writer.flush();
    }

    public void llmOutput(String token) {
        var writer = terminal.writer();
        writer.print("\u001B[32m" + token + "\u001B[0m");
        writer.flush();
    }

    public void shellOutput(String msg) {
        var writer = terminal.writer();
        writer.println("\u001B[33m" + msg + "\u001B[0m");
        writer.flush();
    }

    @Override
    public boolean confirmAsk(String msg) {
        var writer = terminal.writer();
        // ANSI escape code \u001B[35m sets text to magenta, \u001B[0m resets the color
        writer.println("\u001B[35m" + "%s (y/N) ".formatted(msg) + "\u001B[0m");
        writer.flush();
        try {
            var response = reader.readLine();
            return response.trim().equalsIgnoreCase("y");
        } catch (UserInterruptException e) {
            return false;
        }
    }

    public char askOptions(String msg, String options) {
        var writer = terminal.writer();
        // ANSI escape code \u001B[35m sets text to magenta, \u001B[0m resets the color
        writer.println("\u001B[35m" + "%s (%s) ".formatted(msg, options) + "\u001B[0m");
        writer.flush();
        try {
            var response = reader.readLine();
            if (response == null || response.isEmpty()) {
                return options.toLowerCase().charAt(options.length() - 1);  // default to last option
            }
            char choice = response.trim().toLowerCase().charAt(0);
            return options.toLowerCase().indexOf(choice) >= 0 ? choice : options.toLowerCase().charAt(options.length() - 1);
        } catch (UserInterruptException e) {
            return options.toLowerCase().charAt(options.length() - 1); // default to last option on interrupt
        }
    }

    @Override
    public void close() {
        try {
            reader.getHistory().save();
        } catch (Exception e) {
            terminal.writer().println("Warning: Could not save history: " + e.getMessage());
        } finally {
            try {
                terminal.close();
            } catch (Exception e) {
                // Ignore closure errors
            }
        }
    }

    public int getTerminalWidth() {
        return terminal.getWidth();
    }
    
    /**
     * Wraps the given text to fit within the terminal width, breaking at whitespace.
     * 
     * @param text The text to wrap
     * @return The wrapped text
     */
    public String wrap(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        int width = getTerminalWidth();
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n", -1); // -1 to keep empty lines
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() <= width) {
                result.append(line);
            } else {
                int currentPos = 0;
                int lastWrapPos = 0;
                int lineStart = 0;
                
                while (currentPos < line.length()) {
                    // If we've reached the width or end of the string
                    if (currentPos - lineStart >= width || currentPos == line.length() - 1) {
                        // If we found a wrap position, use it
                        if (lastWrapPos > lineStart) {
                            // Add the segment up to the last wrap position
                            result.append(line, lineStart, lastWrapPos);
                            lineStart = lastWrapPos + 1; // Skip the space
                            // Reset wrap position for next line
                            lastWrapPos = lineStart;
                            result.append('\n');
                        } else {
                            // No suitable break found, force break at width
                            int breakPoint = Math.min(lineStart + width, line.length());
                            result.append(line, lineStart, breakPoint);
                            lineStart = breakPoint;
                            result.append('\n');
                        }
                        // If we've processed the whole line, break out
                        if (lineStart >= line.length()) {
                            break;
                        }
                        // Reset currentPos to the new line start
                        currentPos = lineStart;
                    } else {
                        // If we encounter whitespace, mark it as a potential wrap position
                        if (Character.isWhitespace(line.charAt(currentPos))) {
                            lastWrapPos = currentPos;
                        }
                        currentPos++;
                    }
                }
                
                // Add any remaining content
                if (lineStart < line.length()) {
                    result.append(line.substring(lineStart));
                }
            }
            
            // Add newline for all but the last line
            if (i < lines.length - 1) {
                result.append('\n');
            }
        }
        
        return result.toString();
    }
    
    /**
     * Creates an animated spinner with the given message.
     * The spinner will continue until spinComplete() is called.
     * 
     * @param message The message to display next to the spinner
     */
    public synchronized void spin(String message) {
        // Update the message
        spinnerMessage.set(message);

        // If spinner is already running, just update the message
        if (spinnerActive.get()) {
            return;
        }

        // Set active flag and start spinner thread
        spinnerActive.set(true);

        // Put the terminal in raw mode to disable user input and hide cursor
        try {
            terminal.enterRawMode();
            // ANSI escape code to hide cursor
            terminal.writer().print("\u001B[?25l");
            terminal.writer().flush();
        } catch (Exception e) {
            logger.warn("Could not enter raw mode: {}", e.getMessage());
        }

        spinnerThread = new Thread(() -> {
            int index = 0;
            try {
                while (spinnerActive.get()) {
                    // Clear the current line
                    // Clear the current line
                    terminal.writer().print("\r");
                    // Clear to end of line to remove any previous message residue
                    for (int i = 0; i < getTerminalWidth() - 1; i++) {
                        terminal.writer().print(" ");
                    }
                                        terminal.writer().print("\r");

                    // Print the spinner and message
                    llmOutput(spinnerChars[index] + " " + spinnerMessage.get());

                    // Update spinner index
                    index = (index + 1) % spinnerChars.length;

                    //noinspection BusyWait
                    Thread.sleep(100);
                }

                // Clear the spinner line when done
                terminal.writer().print("\r");
                for (int i = 0; i < getTerminalWidth() - 1; i++) {
                    terminal.writer().print(" ");
                }
                terminal.writer().print("\r");
                terminal.writer().flush();
            } catch (InterruptedException e) {
                // Thread was interrupted, clean up
                Thread.currentThread().interrupt();
            }
        });
        
        spinnerThread.setDaemon(true);
        spinnerThread.start();
    }
    
    /**
     * Stops the spinner animation and restores normal input mode.
     */
    public synchronized void spinComplete() {
        if (!spinnerActive.get()) {
            return;
        }
        spinnerActive.set(false);

        // Wait for spinner thread to finish cleaning up
        try {
            if (spinnerThread != null) {
                spinnerThread.join(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Restore terminal to normal mode and show cursor
        try {
            // ANSI escape code to show cursor
            terminal.writer().print("\u001B[?25h");
            terminal.writer().flush();

                        // Reinitialize the terminal to restore normal state
            terminal.close(); // Release raw mode
            terminal.flush();

            // No direct init method available, so we'll rely on terminal.close() to reset the state
            // Try to get terminal back into a usable state
            try {
                terminal.writer().println();
                terminal.flush();
            } catch (Exception ex) {
                logger.warn("Could not reinitialize terminal: {}", ex.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Could not restore terminal mode: {}", e.getMessage());
        }

        // Force redraw to ensure output is visible
        terminal.writer().println();
        terminal.writer().flush();
    }

    @Override
    public boolean isSpinning() {
        return spinnerActive.get();
    }
}
