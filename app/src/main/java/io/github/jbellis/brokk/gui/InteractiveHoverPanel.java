package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.components.TokenUsageBar;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class InteractiveHoverPanel {
    private final JComponent hostContainer;
    private final WorkspaceItemsChipPanel chips;
    private final TokenUsageBar tokenBar;
    private final HoverMouseListener mouseListener = new HoverMouseListener();
    private volatile Collection<ContextFragment> currentHover = List.of();

    public InteractiveHoverPanel(JComponent hostContainer, WorkspaceItemsChipPanel chips, TokenUsageBar tokenBar) {
        this.hostContainer = hostContainer;
        this.chips = chips;
        this.tokenBar = tokenBar;
    }

    public void install() {
        SwingUtilities.invokeLater(() -> {
            hostContainer.addMouseListener(mouseListener);
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
            hostContainer.removeMouseListener(mouseListener);
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
        public void mouseExited(MouseEvent e) {
            // If the mouse exits the host container, clear the hover state.
            // This prevents flicker when moving between chips and the token bar.
            if (!hostContainer.contains(e.getPoint())) {
                setHoverTarget(List.of());
            }
        }
    }
}
