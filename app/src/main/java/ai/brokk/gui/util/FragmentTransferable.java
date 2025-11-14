package ai.brokk.gui.util;

import ai.brokk.context.ContextFragment;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Transferable that carries a ContextFragment natively, with a plain-text fallback.
 *
 * - Primary flavor: application/x-brokk-fragment (JVM-local object of ContextFragment)
 * - Fallback flavor: stringFlavor (fragment.text())
 */
public final class FragmentTransferable implements Transferable {
    private static final Logger logger = LogManager.getLogger(FragmentTransferable.class);

    /**
     * JVM-local object flavor for ContextFragment payloads.
     *
     * We use the standard javaJVMLocalObjectMimeType with the ContextFragment class to ensure safe
     * in-JVM transfer while keeping a descriptive human-readable name.
     */
    public static final DataFlavor FRAGMENT_FLAVOR = new DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=ai.brokk.context.ContextFragment",
            "application/x-brokk-fragment");

    private static final DataFlavor[] SUPPORTED_FLAVORS = new DataFlavor[] {FRAGMENT_FLAVOR, DataFlavor.stringFlavor};

    private final ContextFragment fragment;
    private final @Nullable String precomputedText;

    public FragmentTransferable(ContextFragment fragment) {
        this(fragment, null);
    }

    public FragmentTransferable(ContextFragment fragment, @Nullable String textFallback) {
        this.fragment = fragment;
        this.precomputedText = textFallback;
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return SUPPORTED_FLAVORS.clone();
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return FRAGMENT_FLAVOR.equals(flavor) || DataFlavor.stringFlavor.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
        if (FRAGMENT_FLAVOR.equals(flavor)) {
            return fragment;
        }
        if (DataFlavor.stringFlavor.equals(flavor)) {
            if (precomputedText != null) {
                return precomputedText;
            }
            try {
                return fragment.text();
            } catch (RuntimeException ex) {
                logger.warn("Failed to compute fragment text for clipboard fallback: {}", fragment, ex);
                throw new IOException("Failed to compute fragment text", ex);
            }
        }
        throw new UnsupportedFlavorException(flavor);
    }
}


