package ai.brokk.cli.tui;

import static java.util.Objects.requireNonNull;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class TuiApp implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(TuiApp.class);

    private final Terminal terminal;
    private final Screen screen;
    private final ConcurrentLinkedQueue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean dirty = new AtomicBoolean(true);

    private final TuiBuffer buffer;

    private int scrollFromBottom;
    private boolean wrapEnabled = true;
    private volatile boolean exitRequested;

    private String title = "Brokk TUI";
    private String status = "";
    private boolean spinnerVisible;
    private @Nullable String spinnerMessage;

    private @Nullable ConfirmState confirmState;
    private final Deque<String> transientMessages = new ArrayDeque<>();

    TuiApp(TuiBuffer buffer) throws IOException {
        this.buffer = requireNonNull(buffer);
        this.terminal = new DefaultTerminalFactory().createTerminal();
        this.screen = new TerminalScreen(terminal);

        screen.startScreen();
        screen.setCursorPosition(null);
    }

    public void setTitle(String title) {
        postUi(() -> {
            this.title = requireNonNull(title);
            dirty.set(true);
        });
    }

    public void setStatus(String status) {
        postUi(() -> {
            this.status = requireNonNull(status);
            dirty.set(true);
        });
    }

    public void showSpinner(String message) {
        postUi(() -> {
            this.spinnerVisible = true;
            this.spinnerMessage = requireNonNull(message);
            dirty.set(true);
        });
    }

    public void hideSpinner() {
        postUi(() -> {
            this.spinnerVisible = false;
            this.spinnerMessage = null;
            dirty.set(true);
        });
    }

    public void pushTransientMessage(String message) {
        postUi(() -> {
            transientMessages.addLast(requireNonNull(message));
            while (transientMessages.size() > 5) {
                transientMessages.removeFirst();
            }
            dirty.set(true);
        });
    }

    public void clearTransientMessages() {
        postUi(() -> {
            transientMessages.clear();
            dirty.set(true);
        });
    }

    public void requestRedraw() {
        dirty.set(true);
    }

    public void postUi(Runnable runnable) {
        uiQueue.add(requireNonNull(runnable));
        dirty.set(true);
    }

    public int promptYesNo(String title, String message) {
        var state = new ConfirmState(title, message);
        postUi(() -> {
            confirmState = state;
            dirty.set(true);
        });

        while (true) {
            Integer r = state.result;
            if (r != null) {
                postUi(() -> {
                    confirmState = null;
                    dirty.set(true);
                });
                return r;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                postUi(() -> {
                    confirmState = null;
                    dirty.set(true);
                });
                return state.defaultResult();
            }
        }
    }

    public void runUiLoop(BooleanSupplier shouldExit) throws IOException {
        while (!shouldExit.getAsBoolean()) {
            flushUiQueue();

            TerminalSize resize = screen.doResizeIfNecessary();
            if (resize != null) {
                dirty.set(true);
            }

            KeyStroke keyStroke = screen.pollInput();
            if (keyStroke != null) {
                handleKeyStroke(keyStroke);
            }

            if (dirty.get()) {
                draw();
            } else {
                // keep CPU modest
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        flushUiQueue();
        if (dirty.get()) {
            draw();
        }
    }

    private void flushUiQueue() {
        for (Runnable r = uiQueue.poll(); r != null; r = uiQueue.poll()) {
            try {
                r.run();
            } catch (RuntimeException e) {
                logger.warn("UI task failed", e);
            }
        }
    }

    private void handleKeyStroke(KeyStroke ks) {
        if (confirmState != null) {
            handleConfirmKeyStroke(confirmState, ks);
            return;
        }

        if (ks.isCtrlDown() && ks.getKeyType() == KeyType.Character && (ks.getCharacter() == 'c' || ks.getCharacter() == 'C')) {
            // Let the caller decide what to do; this just marks status.
            exitRequested = true;
            setStatus("Exit requested (Ctrl+C)");
            return;
        }

        switch (ks.getKeyType()) {
            case PageUp -> scrollPageUp();
            case PageDown -> scrollPageDown();
            case Home -> scrollToTop();
            case End -> scrollToBottom();
            case Character -> {
                char ch = ks.getCharacter();
                if (ks.isCtrlDown() && (ch == 'l' || ch == 'L')) {
                    buffer.clear();
                    scrollFromBottom = 0;
                    dirty.set(true);
                } else if (ks.isCtrlDown() && (ch == 'r' || ch == 'R')) {
                    wrapEnabled = !wrapEnabled;
                    dirty.set(true);
                }
            }
            default -> {
                // ignore
            }
        }
    }

    private void handleConfirmKeyStroke(ConfirmState state, KeyStroke ks) {
        switch (ks.getKeyType()) {
            case Enter -> state.result = state.yesResult();
            case Escape -> state.result = state.noResult();
            case Character -> {
                char ch = ks.getCharacter();
                if (ch == 'y' || ch == 'Y') {
                    state.result = state.yesResult();
                } else if (ch == 'n' || ch == 'N') {
                    state.result = state.noResult();
                }
            }
            default -> {
                // ignore
            }
        }
        if (state.result != null) {
            dirty.set(true);
        }
    }

    private void scrollPageUp() {
        int height = contentHeight();
        scrollFromBottom = scrollFromBottom + Math.max(height - 1, 1);
        dirty.set(true);
    }

    private void scrollPageDown() {
        int height = contentHeight();
        scrollFromBottom = Math.max(scrollFromBottom - Math.max(height - 1, 1), 0);
        dirty.set(true);
    }

    private void scrollToTop() {
        int height = contentHeight();
        int total = buffer.totalWrappedLines(contentWidth(), wrapEnabled);
        scrollFromBottom = Math.max(total - height, 0);
        dirty.set(true);
    }

    private void scrollToBottom() {
        scrollFromBottom = 0;
        dirty.set(true);
    }

    private int contentWidth() {
        return Math.max(screen.getTerminalSize().getColumns(), 1);
    }

    private int contentHeight() {
        // header + status line
        return Math.max(screen.getTerminalSize().getRows() - 2, 1);
    }

    private void draw() throws IOException {
        dirty.set(false);

        screen.clear();
        TextGraphics tg = screen.newTextGraphics();

        TerminalSize size = screen.getTerminalSize();
        int width = Math.max(size.getColumns(), 1);
        int height = Math.max(size.getRows(), 1);

        drawHeader(tg, width);
        drawContent(tg, width, height);
        drawStatus(tg, width, height);

        screen.refresh();
    }

    private void drawHeader(TextGraphics tg, int width) {
        String left = title;
        String right = "Wrap:" + (wrapEnabled ? "on" : "off") + "  Ctrl+L clear  Ctrl+R wrap  PgUp/PgDn scroll";
        String line = padOrTrim(left, width);
        tg.setBackgroundColor(TextColor.ANSI.BLUE);
        tg.setForegroundColor(TextColor.ANSI.WHITE);
        tg.putString(0, 0, line);

        int rightStart = Math.max(width - right.length(), 0);
        if (rightStart < width) {
            tg.putString(rightStart, 0, right.substring(Math.max(right.length() - width, 0)));
        }

        tg.setBackgroundColor(TextColor.ANSI.DEFAULT);
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

    private void drawContent(TextGraphics tg, int width, int height) {
        int contentRows = Math.max(height - 2, 1);
        List<String> lines = buffer.snapshotWrapped(width, wrapEnabled);

        int total = lines.size();
        int startFromTop = Math.max(total - contentRows - scrollFromBottom, 0);
        int endExclusive = Math.min(startFromTop + contentRows, total);

        int y = 1;
        for (int i = startFromTop; i < endExclusive; i++) {
            String line = padOrTrim(lines.get(i), width);
            tg.putString(0, y, line);
            y++;
        }

        while (y < 1 + contentRows) {
            tg.putString(0, y, padOrTrim("", width));
            y++;
        }
    }

    private void drawStatus(TextGraphics tg, int width, int height) {
        int y = height - 1;
        String spinner = "";
        if (spinnerVisible) {
            spinner = "[working]";
            if (spinnerMessage != null && !spinnerMessage.isBlank()) {
                spinner = spinner + " " + spinnerMessage;
            }
        }

        String transientMsg = transientMessages.peekLast();
        String base = status;
        if (transientMsg != null && !transientMsg.isBlank()) {
            base = base.isBlank() ? transientMsg : base + " | " + transientMsg;
        }

        if (confirmState != null) {
            base = confirmState.title + ": " + confirmState.message + " (y/n)";
        }

        String line;
        if (spinner.isBlank()) {
            line = base;
        } else if (base.isBlank()) {
            line = spinner;
        } else {
            line = spinner + " | " + base;
        }

        tg.setBackgroundColor(TextColor.ANSI.WHITE);
        tg.setForegroundColor(TextColor.ANSI.BLACK);
        tg.putString(0, y, padOrTrim(line, width));
        tg.setBackgroundColor(TextColor.ANSI.DEFAULT);
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

    private static String padOrTrim(String s, int width) {
        if (s.length() == width) {
            return s;
        }
        if (s.length() > width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - s.length());
    }

    @Override
    public void close() {
        try {
            screen.stopScreen();
        } catch (IOException e) {
            logger.warn("Failed to stop screen", e);
        }
        try {
            terminal.close();
        } catch (IOException e) {
            logger.warn("Failed to close terminal", e);
        }
    }

    public boolean exitRequested() {
        return exitRequested;
    }

    private static final class ConfirmState {
        private final String title;
        private final String message;
        private volatile @Nullable Integer result;

        private ConfirmState(String title, String message) {
            this.title = requireNonNull(title);
            this.message = requireNonNull(message);
        }

        private int defaultResult() {
            return noResult();
        }

        private int yesResult() {
            return javax.swing.JOptionPane.YES_OPTION;
        }

        private int noResult() {
            return javax.swing.JOptionPane.NO_OPTION;
        }
    }
}
