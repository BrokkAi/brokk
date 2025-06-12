package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.util.Environment;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A JLabel that displays a clickable hyperlink, opening it in the default browser.
 * Handles common errors like browser unavailability.
 */
public class BrowserLabel extends JLabel {
    private final String url;

    /**
     * Creates a new BrowserLabel for the specified URL.
     *
     * @param url The URL to link to. The label text will be the same as the URL.
     */
    public BrowserLabel(String url) {
        this(url, url); // Default text is the URL itself
    }

    /**
     * Creates a new BrowserLabel for the specified URL with custom display text.
     *
     * @param url  The URL to link to.
     * @param text The text to display on the label (will be wrapped in HTML link tags).
     */
    public BrowserLabel(String url, String text) {
        super("<html><a href=\"" + url + "\">" + text + "</a></html>");
        this.url = url;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openBrowser();
            }
        });
    }

    private void openBrowser() {
        Environment.openInBrowser(url, SwingUtilities.getWindowAncestor(this));
    }
}
