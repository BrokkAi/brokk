package io.github.jbellis.brokk.gui;

import static io.github.jbellis.brokk.gui.Constants.H_GLUE;

import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.components.TokenUsageBar;
import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class InteractiveHoverPanel extends JPanel {
    private final WorkspaceItemsChipPanel chips;
    private final TokenUsageBar tokenBar;
    private final HoverMouseListener mouseListener = new HoverMouseListener();
    private volatile Collection<ContextFragment> currentHover = List.of();

    public InteractiveHoverPanel(
            JComponent chipContainer,
            JComponent tokenBarContainer,
            WorkspaceItemsChipPanel chips,
            TokenUsageBar tokenBar) {
        super(new BorderLayout(H_GLUE, 0));
        setOpaque(false);
        this.chips = chips;
        this.tokenBar = tokenBar;

        add(chipContainer, BorderLayout.CENTER);
        add(tokenBarContainer, BorderLayout.SOUTH);
    }

    public void install() {
        SwingUtilities.invokeLater(() -> {
            addMouseListener(mouseListener);
            addMouseMotionListener(mouseListener);
            chips.setOnHover((frag, entered) -> {
                if (entered && frag != null) {
                    setHoverTarget(List.of(frag));
                }
                // Individual chip exit is ignored to prevent flicker; clearing is handled by hostContainer exit.
            });
            tokenBar.setOnHoverFragments((frags, entered) -> {
                if (entered) {
                    setHoverTarget(List.copyOf(frags));
                }
                // Individual segment exit is ignored; clearing is handled by hostContainer exit.
            });
        });
    }

    public void dispose() {
        SwingUtilities.invokeLater(() -> {
            removeMouseListener(mouseListener);
            removeMouseMotionListener(mouseListener);
            chips.setOnHover(null);
            tokenBar.setOnHoverFragments(null);
            setHoverTarget(List.of());
        });
    }

    @Nullable
    public Collection<ContextFragment> getCurrentHover() {
        return currentHover;
    }

    private void setHoverTarget(Collection<ContextFragment> targets) {
        assert SwingUtilities.isEventDispatchThread();
        if (currentHover.size() == targets.size() && currentHover.containsAll(targets)) {
            return;
        }
        this.currentHover = List.copyOf(targets);
        chips.applyGlobalStyling(this.currentHover);
        tokenBar.applyGlobalStyling(this.currentHover);
    }

    private class HoverMouseListener extends MouseAdapter {
        @Override
        public void mouseMoved(MouseEvent e) {
        // This listener is on InteractiveHoverPanel. We use it to clear the hover state
        // when the mouse moves into an empty area of the panel, i.e., not over an
        // interactive child component that manages its own hover state.
        
        // Check if mouse is over a chip. If so, let the chip's listener handle it.
        var pChips = SwingUtilities.convertPoint(InteractiveHoverPanel.this, e.getPoint(), chips);
        if (chips.contains(pChips) && chips.getComponentAt(pChips) != chips) {
        return;
        }
        
        // Check if mouse is over the token bar. If so, let its listener handle it.
        // Note: This check might be redundant if TokenUsageBar consumes mouse motion events,
        // preventing this listener from being called. It is included for robustness.
        var pTokenBar = SwingUtilities.convertPoint(InteractiveHoverPanel.this, e.getPoint(), tokenBar);
        if (tokenBar.contains(pTokenBar)) {
        return;
        }
        
        // If we reach here, the mouse is over an empty area. Clear any existing hover state.
        if (!currentHover.isEmpty()) {
        setHoverTarget(List.of());
        }
        }

        @Override
        public void mouseExited(MouseEvent e) {
            // If the mouse exits the host container, clear the hover state.
            // This prevents flicker when moving between chips and the token bar.
            if (!contains(e.getPoint())) {
                setHoverTarget(List.of());
            }
        }
    }
}
