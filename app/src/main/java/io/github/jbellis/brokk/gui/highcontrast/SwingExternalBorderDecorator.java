package io.github.jbellis.brokk.gui.highcontrast;

import io.github.jbellis.brokk.gui.theme.ThemeBorderManager;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.*;
import javax.swing.border.Border;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Decorator that installs a non-opaque overlay into a Window's layered pane (PALETTE_LAYER) and paints a themed border
 * at the very edge (inset by stroke/2). The overlay is mouse-transparent (contains() returns false) so events fall
 * through to underlying components. The PALETTE_LAYER ensures the border draws above content but below popups.
 */
public final class SwingExternalBorderDecorator {

    private static final Logger logger = LogManager.getLogger(SwingExternalBorderDecorator.class);

    private final Window window;
    private final JComponent overlay;
    private boolean installed = false;
    private final ComponentAdapter resizeListener;
    private final WindowAdapter windowListener;

    // Border configuration
    private ThemeBorderManager.BorderConfig config;

    // If layered-pane overlay can't be installed (security, missing layered pane, etc.) we apply a reversible
    // LineBorder on the root content pane as a degraded fallback. Track that state so uninstall can undo it.
    private boolean fallbackApplied = false;

    @Nullable
    private JComponent fallbackTarget = null;

    @Nullable
    private Border originalBorder = null;

    public SwingExternalBorderDecorator(Window w, ThemeBorderManager.BorderConfig config) {
        this.window = w;
        this.config = config;
        this.overlay = new BorderOverlay();
        this.overlay.setOpaque(false);
        this.overlay.setFocusable(false);

        // Listeners to keep bounds in sync
        this.resizeListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateBounds();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                updateBounds();
            }
        };

        this.windowListener = new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                uninstall();
            }
        };
    }

    /** Install overlay into the window's layered pane (PALETTE_LAYER). Safe to call from any thread. */
    public void install() {
        if (installed) {
            return;
        }
        if (!(window instanceof RootPaneContainer rpc)) {
            logger.warn("Window is not RootPaneContainer; cannot install border overlay: {}", window);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                // Check if layered pane is available - if not, defer installation
                JRootPane root = rpc.getRootPane();
                JLayeredPane layered = root != null ? root.getLayeredPane() : null;

                if (layered == null && !window.isDisplayable()) {
                    // Window not ready yet - defer installation until it becomes visible
                    window.addComponentListener(new ComponentAdapter() {
                        @Override
                        public void componentShown(ComponentEvent e) {
                            window.removeComponentListener(this);
                            install();
                        }
                    });
                    return;
                }

                // Only apply borders if enabled in config
                if (!config.enabled) {
                    logger.debug("Skipping border installation: borders not enabled in config");
                    return;
                }

                if (layered == null) {
                    // fallback to contentPane border (only if contentPane is a JComponent)
                    try {
                        Component cp = root.getContentPane();
                        if (cp instanceof JComponent jc) {
                            // Store original border to restore later
                            fallbackTarget = jc;
                            originalBorder = jc.getBorder();
                            jc.setBorder(BorderFactory.createLineBorder(config.color, Math.max(1, config.width)));
                            fallbackApplied = true;
                            installed = true;
                        } else {
                            logger.warn(
                                    "Content pane is not a JComponent; cannot apply fallback border for {}", window);
                        }
                    } catch (Throwable fbEx) {
                        logger.warn("Fallback border application failed for {}: {}", window, fbEx.getMessage(), fbEx);
                    }
                    return;
                }

                // Ensure overlay bounds match layered pane
                updateBoundsInternal(layered);

                // Add overlay at a high z-order so it draws above content but below popups
                try {
                    layered.add(overlay, JLayeredPane.PALETTE_LAYER);
                    layered.revalidate();
                    layered.repaint();

                    // Register listeners on the window and root pane to keep in sync
                    window.addComponentListener(resizeListener);
                    window.addWindowListener(windowListener);
                    root.addComponentListener(resizeListener);

                    installed = true;
                } catch (Throwable addEx) {
                    logger.warn(
                            "Adding overlay failed for {}, applying fallback border: {}", window, addEx.getMessage());
                    try {
                        Component cp = root.getContentPane();
                        if (cp instanceof JComponent jc) {
                            fallbackTarget = jc;
                            jc.setBorder(BorderFactory.createLineBorder(config.color, Math.max(1, config.width)));
                            fallbackApplied = true;
                            installed = true;
                        } else {
                            logger.warn(
                                    "Content pane is not a JComponent; cannot apply fallback border for {}", window);
                        }
                    } catch (Throwable fbEx) {
                        logger.warn("Fallback border application failed for {}: {}", window, fbEx.getMessage(), fbEx);
                    }
                }
            } catch (Exception ex) {
                logger.warn(
                        "Failed to install HighContrast border overlay for window {}: {}", window, ex.getMessage(), ex);
            }
        });
    }

    /** Uninstall overlay and remove listeners. Safe to call from any thread. */
    public void uninstall() {
        if (!installed && !fallbackApplied) return;
        SwingUtilities.invokeLater(() -> {
            try {
                if (window instanceof RootPaneContainer rpc) {
                    JRootPane root = rpc.getRootPane();
                    JLayeredPane layered = root.getLayeredPane();
                    if (layered != null) {
                        try {
                            layered.remove(overlay);
                            layered.revalidate();
                            layered.repaint();
                        } catch (Throwable ex) {
                            logger.debug(
                                    "Failed to remove overlay from layered pane for {}: {}",
                                    window,
                                    ex.getMessage(),
                                    ex);
                        }
                    }
                    try {
                        root.removeComponentListener(resizeListener);
                    } catch (Throwable ex) {
                        logger.debug(
                                "Failed to remove root component listener for {}: {}", window, ex.getMessage(), ex);
                    }
                }

                try {
                    window.removeComponentListener(resizeListener);
                } catch (Throwable ex) {
                    logger.debug("Failed to remove window component listener for {}: {}", window, ex.getMessage(), ex);
                }
                try {
                    window.removeWindowListener(windowListener);
                } catch (Throwable ex) {
                    logger.debug("Failed to remove window listener for {}: {}", window, ex.getMessage(), ex);
                }

                // Remove fallback border if applied
                if (fallbackApplied && fallbackTarget != null) {
                    try {
                        fallbackTarget.setBorder(originalBorder);
                    } catch (Throwable ex) {
                        logger.debug("Failed to remove fallback border for {}: {}", window, ex.getMessage(), ex);
                    } finally {
                        fallbackApplied = false;
                        fallbackTarget = null;
                        originalBorder = null;
                    }
                }
            } catch (Exception ex) {
                logger.debug("Error uninstalling border overlay: {}", ex.getMessage(), ex);
            } finally {
                installed = false;
            }
        });
    }

    /** Force update of overlay bounds to match current window size and any title inset (macOS). */
    public void updateBounds() {
        SwingUtilities.invokeLater(() -> {
            try {
                if (window instanceof RootPaneContainer rpc) {
                    JLayeredPane layered = rpc.getRootPane().getLayeredPane();
                    updateBoundsInternal(layered);
                }
            } catch (Exception ex) {
                logger.debug("Failed to update overlay bounds: {}", ex.getMessage(), ex);
            }
        });
    }

    private void updateBoundsInternal(@Nullable JLayeredPane layered) {
        if (layered == null) return;
        int w = Math.max(0, layered.getWidth());
        int h = Math.max(0, layered.getHeight());

        // Border should include the entire window including title bar on all platforms
        // No topInset needed - draw from the very top (y=0)
        overlay.setBounds(0, 0, w, h);
        overlay.putClientProperty("hc.topInset", 0); // Always 0 - include title bar in border
        overlay.revalidate();
        overlay.repaint();
    }

    /** Update the border configuration. */
    public void updateConfiguration(ThemeBorderManager.BorderConfig newConfig) {
        this.config = newConfig;

        // If installed, repaint to apply new configuration
        if (installed) {
            SwingUtilities.invokeLater(() -> {
                overlay.repaint();
            });
        }
    }

    /** Returns whether the decorator is currently installed. */
    public boolean isInstalled() {
        return installed;
    }

    // ------------ Inner overlay component ------------
    private class BorderOverlay extends JComponent {
        BorderOverlay() {
            setOpaque(false);
            setFocusable(false);
        }

        @Override
        public boolean contains(int x, int y) {
            // Always return false so this component doesn't consume mouse events; events fall through.
            return false;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            var g2 = (Graphics2D) g.create();
            try {
                // enable antialiasing for smooth stroke
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(config.color);

                int topInset = 0;
                Object o = getClientProperty("hc.topInset");
                if (o instanceof Integer i) topInset = i;

                float stroke = Math.max(1f, config.width);
                g2.setStroke(new BasicStroke(stroke));
                // Ensure half is at least 1 so the stroke doesn't get clipped at component edges
                int half = Math.max(1, Math.round(stroke / 2f));

                int x = half;
                int y = half + topInset;
                int w = Math.max(0, getWidth() - half * 2);
                int h = Math.max(0, getHeight() - half * 2 - topInset);

                if (w <= 0 || h <= 0) {
                    return;
                }

                // Draw rect inset by half stroke so stroke sits on the edge
                g2.drawRoundRect(x, y, w - 1, h - 1, config.cornerRadius, config.cornerRadius);
            } finally {
                g2.dispose();
            }
        }
    }
}
