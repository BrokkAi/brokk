package io.github.jbellis.brokk.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.util.stream.*;

public class CheckThreadViolationRepaintManager extends RepaintManager {
    private static final Logger logger = LogManager.getLogger(CheckThreadViolationRepaintManager.class);

    public synchronized void addInvalidComponent(JComponent component) {
        checkThreadViolations(component);
        super.addInvalidComponent(component);
    }

    public void addDirtyRegion(JComponent component, int x, int y, int w, int h) {
        checkThreadViolations(component);
        super.addDirtyRegion(component, x, y, w, h);
    }

    private void checkThreadViolations(JComponent c) {
        // 1) Allow the normal Swing EDT
        if (SwingUtilities.isEventDispatchThread()) {
            return;
        }

        // 2) Allow the JVM's animated-image thread (e.g. spinner GIFs)
        //    The thread is created by sun.awt.image.ImageFetcher and is
        //    named "AWT-Image" (older JDKs) or "Image Animator" (JDK 21+).
        var threadName = Thread.currentThread().getName();
        if (threadName != null && (threadName.startsWith("AWT-Image") || threadName.startsWith("Image Animator"))) {
            // Updates driven by the image loader are thread-safe as they only call repaint(), which is documented as thread-safe.
            return;
        }

        // 3) Original heuristic: ignore calls that originate from a repaint()
        var exception = new IllegalStateException();
        boolean repaint = false;
        boolean fromSwing = false;
            StackTraceElement[] stackTrace = exception.getStackTrace();
            for (StackTraceElement st : stackTrace) {
            if (repaint && st.getClassName().startsWith("javax.swing.")) {
                fromSwing = true;
            }
            if ("repaint".equals(st.getMethodName())) {
                repaint = true;
            }
        }
        if (repaint && !fromSwing) {
                //no problems here, since repaint() is thread safe
            return;
        }

        // 4) Everything else is a violation - log details before throwing
        logger.error("""
                Off-EDT paint detected
                Thread   : {} (id={})
                Component: {}  showing={}  displayable={}
                Stacktrace:
                {}
                """.stripIndent(),
                threadName,
                Thread.currentThread().getId(),
                c.getClass().getName(),
                c.isShowing(),
                c.isDisplayable(),
                String.join("\n", 
                    Stream.of(exception.getStackTrace())
                          .map(StackTraceElement::toString)
                          .collect(Collectors.toList()))
        );
        throw exception;
    }
}
