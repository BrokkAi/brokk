package io.github.jbellis.brokk.gui.dependencies;

import io.github.jbellis.brokk.gui.Chrome;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

/** A panel for managing project dependencies. */
public class DependenciesDrawerPanel extends JPanel {
    private @Nullable DependenciesPanel activeDependenciesPanel;
    private final Chrome chrome;

    /**
     * Creates a new dependencies panel.
     *
     * @param chrome main ui
     */
    public DependenciesDrawerPanel(Chrome chrome) {
        super(new BorderLayout());
        this.chrome = chrome;
        setBorder(BorderFactory.createEmptyBorder());
    }

    /** Creates and shows the panel contents. */
    public void openPanel() {
        SwingUtilities.invokeLater(() -> {
            if (activeDependenciesPanel == null) {
                activeDependenciesPanel = new DependenciesPanel(chrome);
                add(activeDependenciesPanel, BorderLayout.CENTER);
                revalidate();
                repaint();
            }
        });
    }
}
