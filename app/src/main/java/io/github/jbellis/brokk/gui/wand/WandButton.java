package io.github.jbellis.brokk.gui.wand;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.util.Icons;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

public class WandButton extends MaterialButton {
    private static final String WAND_TOOLTIP = "Refine Prompt: rewrites your prompt for clarity and specificity.";

    public WandButton(
            ContextManager contextManager,
            IConsoleIO consoleIO,
            JTextArea instructionsArea,
            Supplier<String> promptSupplier,
            Consumer<String> promptConsumer) {
        super();
        SwingUtilities.invokeLater(() -> setIcon(Icons.WAND));
        setToolTipText(WAND_TOOLTIP);
        addActionListener(e -> {
            var wandAction = new WandAction(contextManager);
            wandAction.execute(promptSupplier, promptConsumer, consoleIO, instructionsArea);
        });
    }
}
