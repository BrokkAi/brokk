package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;
import java.awt.event.ActionListener;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * Backwards-compatible thin wrapper for the new MaterialLoadingButton. Existing code that constructs `LoadingButton`
 * continues to work, while the real implementation lives in MaterialLoadingButton.
 */
public final class LoadingButton extends MaterialLoadingButton {

    public LoadingButton(
            String initialText, @Nullable Icon initialIcon, Chrome chrome, @Nullable ActionListener actionListener) {
        super(initialText, initialIcon, chrome, actionListener);
    }
}
