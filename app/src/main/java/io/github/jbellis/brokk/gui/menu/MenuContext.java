package io.github.jbellis.brokk.gui.menu;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Base interface for menu context data */
public sealed interface MenuContext permits FileMenuContext, SymbolMenuContext {
    Chrome chrome();

    ContextManager contextManager();
}

/** Context data for file-based menus */
record FileMenuContext(List<ProjectFile> files, Chrome chrome, ContextManager contextManager) implements MenuContext {}

/** Context data for symbol-based menus */
record SymbolMenuContext(
        String symbolName, boolean symbolExists, @Nullable String fqn, Chrome chrome, ContextManager contextManager)
        implements MenuContext {}
