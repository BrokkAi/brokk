package ai.brokk.gui.components;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.mop.ThemeColors;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.jetbrains.annotations.Nullable;

/**
 * A MaterialButton that displays progress as a fill effect inside the button.
 * When progress is active (0-100), a colored overlay fills from left to right.
 * Click behavior can be customized for cancel functionality during progress.
 * Text changes to "Cancel" during progress (like MaterialLoadingButton).
 */
public class MaterialProgressButton extends MaterialButton {
    private static final String CANCEL_TEXT = "Cancel";
    private static final double PROGRESS_MIN_PERCENT = 0.05; // 5%
    private static final double PROGRESS_MAX_PERCENT = 0.95; // 95%

    private final Chrome chrome;
    private int progress = -1; // -1 means no progress shown
    private @Nullable String idleText = null;
    private @Nullable Runnable cancelAction = null;
    private @Nullable Runnable idleAction = null;

    public MaterialProgressButton(String text, Chrome chrome) {
        super(text);
        this.chrome = chrome;
        this.idleText = text;
    }

    /**
     * Sets the progress value. Values 0-100 show progress; -1 hides progress.
     * Must be called on EDT.
     */
    public void setProgress(int progress) {
        assert SwingUtilities.isEventDispatchThread();
        this.progress = Math.max(-1, Math.min(100, progress));
        if (progress >= 0) {
            super.setText(CANCEL_TEXT);
        } else if (idleText != null) {
            super.setText(idleText);
        }
        repaint();
    }

    /**
     * Returns true if progress is currently being shown (0-100).
     */
    public boolean isShowingProgress() {
        return progress >= 0;
    }

    /**
     * Resets the button to idle state with no progress.
     */
    public void resetToIdle() {
        setProgress(-1);
    }

    /**
     * Sets the action to run when clicked during progress (cancel action).
     */
    public void setCancelAction(@Nullable Runnable action) {
        this.cancelAction = action;
    }

    /**
     * Sets the action to run when clicked in idle state.
     */
    public void setIdleAction(@Nullable Runnable action) {
        this.idleAction = action;
        // Clear existing listeners and add our dispatcher
        for (var al : getActionListeners()) {
            removeActionListener(al);
        }
        addActionListener(e -> {
            if (isShowingProgress()) {
                if (cancelAction != null) {
                    cancelAction.run();
                }
            } else {
                if (idleAction != null) {
                    idleAction.run();
                }
            }
        });
    }

    @Override
    public void setText(@Nullable String text) {
        super.setText(text);
        if (!isShowingProgress()) {
            this.idleText = text;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (progress >= 0 && progress <= 100) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int arc = 6;

            // Map progress 0-100 to 5%-95% of button width
            double mappedProgress =
                    PROGRESS_MIN_PERCENT + (progress / 100.0) * (PROGRESS_MAX_PERCENT - PROGRESS_MIN_PERCENT);
            int fillWidth = (int) (w * mappedProgress);

            // Get accent color from theme
            boolean isDark = chrome.getTheme().isDarkTheme();
            Color accentColor = ThemeColors.getColor(isDark, ThemeColors.ACCENT_GRADIENT_START);

            // Draw progress fill with transparency (painted after parent so it's visible)
            Color fillColor = new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 100);
            g2.setColor(fillColor);
            g2.setClip(new RoundRectangle2D.Float(0, 0, fillWidth, h, arc, arc));
            g2.fillRoundRect(0, 0, w, h, arc, arc);

            g2.dispose();
        }
    }
}
