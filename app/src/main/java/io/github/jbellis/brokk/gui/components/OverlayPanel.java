package io.github.jbellis.brokk.gui.components;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * A reusable overlay panel that can be placed over other components to make them appear disabled while optionally
 * allowing interaction through click-to-activate behavior.
 */
public class OverlayPanel extends JPanel {
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0); // Fully transparent color

    private final Consumer<OverlayPanel> onActivate;
    @Nullable
    private final String tooltipText;
    private final Color overlayColor; // Never null
    private final boolean isBlocking;
    private boolean isActive = true;

    public OverlayPanel(Consumer<OverlayPanel> onActivate, @Nullable String tooltipText, Color overlayColor, boolean isBlocking) {
        this.onActivate = onActivate;
        this.tooltipText = tooltipText;
        this.overlayColor = overlayColor;
        this.isBlocking = isBlocking;

        setupOverlay();
    }

    /** Creates a transparent overlay panel with a click handler. */
    public OverlayPanel(Consumer<OverlayPanel> onActivate, @Nullable String tooltipText) {
        this(onActivate, tooltipText, TRANSPARENT, true);
    }

    /** Creates a gray overlay panel with a click handler and specified transparency. */
    public static OverlayPanel createGrayOverlay(
            Consumer<OverlayPanel> onActivate, String tooltipText, int transparency) {
        // Clamp transparency to valid range
        int alpha = Math.max(0, Math.min(255, transparency));
        return new OverlayPanel(onActivate, tooltipText, new Color(128, 128, 128, alpha), true);
    }

    /** Creates a non-blocking gray overlay panel that allows mouse events to pass through. */
    public static OverlayPanel createNonBlockingGrayOverlay(
            Consumer<OverlayPanel> onActivate, String tooltipText, int transparency) {
        // Clamp transparency to valid range
        int alpha = Math.max(0, Math.min(255, transparency));
        return new OverlayPanel(onActivate, tooltipText, new Color(128, 128, 128, alpha), false);
    }

    private void setupOverlay() {
        setOpaque(false); // Always transparent background, we paint manually
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        setToolTipText(tooltipText);

        if (isBlocking) {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (isActive) {
                        onActivate.accept(OverlayPanel.this);
                    }
                }
            });
        } else {
            // If non-blocking, don't handle clicks, just make sure mouse events pass through
            setCursor(Cursor.getDefaultCursor());
            setEnabled(false);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setColor(overlayColor); // overlayColor is never null
        g2d.fillRect(0, 0, getWidth(), getHeight());

        // Draw placeholder / tooltip text (centered) if active
        if (isActive && tooltipText != null && !tooltipText.isBlank()) {
            g2d.setColor(Color.GRAY);
            FontMetrics fm = g2d.getFontMetrics();
            int x = 5; // left-aligned with small padding
            int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2d.drawString(tooltipText, x, y);
        }

        g2d.dispose();
        super.paintComponent(g);
    }

    /** Creates a layered pane with the given component and this overlay on top. */
    public JLayeredPane createLayeredPane(JComponent component) {
        var layeredPane = new JLayeredPane();
        layeredPane.setLayout(new OverlayLayout(layeredPane));
        layeredPane.setPreferredSize(component.getPreferredSize());
        layeredPane.setMinimumSize(component.getMinimumSize());
        layeredPane.setMaximumSize(component.getMaximumSize());

        // Add components to layers
        layeredPane.add(component, JLayeredPane.DEFAULT_LAYER);
        layeredPane.add(this, JLayeredPane.PALETTE_LAYER);

        return layeredPane;
    }

    public void showOverlay() {
        setVisible(true);
        isActive = true;
    }

    public void hideOverlay() {
        setVisible(false);
        isActive = false;
    }

    @Override
    public boolean contains(int x, int y) {
        // If non-blocking, don't claim to contain any points, allowing events to pass through
        return isBlocking && super.contains(x, y);
    }

    /**
     * Convenience helper: wraps a JTextComponent with a placeholder OverlayPanel that
     * automatically shows when the field is empty and unfocused, and hides on focus/typing.
     *
     * @param textComponent  the field/area to decorate
     * @param placeholderTxt text to show when empty
     * @return a JLayeredPane containing the component and its overlay
     */
    public static JLayeredPane wrapTextComponentWithPlaceholder(JTextComponent textComponent,
                                                                String placeholderTxt)
    {
        // Clicking the overlay just gives focus to the text component
        var overlay = new OverlayPanel(op -> textComponent.requestFocusInWindow(),
                                       placeholderTxt);

        var layered = overlay.createLayeredPane(textComponent);

        Runnable updateVisibility = () -> {
            boolean shouldShow = !textComponent.isFocusOwner() && textComponent.getText().isBlank();
            if (shouldShow) overlay.showOverlay();
            else overlay.hideOverlay();
        };

        // Focus listener – hide on focus, maybe re-show on lost
        textComponent.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                overlay.hideOverlay();
            }

            @Override
            public void focusLost(FocusEvent e) {
                updateVisibility.run();
            }
        });

        // Document listener – hide when user types, show if becomes empty and unfocused
        textComponent.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateVisibility.run(); }
            @Override public void removeUpdate(DocumentEvent e) { updateVisibility.run(); }
            @Override public void changedUpdate(DocumentEvent e) { updateVisibility.run(); }
        });

        // Initial state
        updateVisibility.run();
        return layered;
    }
}
