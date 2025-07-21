package io.github.jbellis.brokk.gui;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * A simplified DocumentListener interface that forwards all change events
 * to a single {@code changed} method.
 */
interface SimpleListener extends DocumentListener {
    void changed(DocumentEvent e);

    @Override
    default void insertUpdate(DocumentEvent e) {
        changed(e);
    }

    @Override
    default void removeUpdate(DocumentEvent e) {
        changed(e);
    }

    @Override
    default void changedUpdate(DocumentEvent e) {
        // Default is no-op for attribute changes, can be overridden if needed.
        // Often, UI updates on text content changes (insert/remove) are sufficient.
    }
}
