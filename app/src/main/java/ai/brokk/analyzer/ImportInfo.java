package ai.brokk.analyzer;

import org.jetbrains.annotations.Nullable;

/**
 * Represents structured information about an import statement.
 *
 * @param rawSnippet The original import statement text (e.g., "import foo.bar.Baz;").
 * @param isWildcard True if this is a wildcard import (e.g., "import foo.bar.*").
 * @param identifier The simple name that can be used in code (e.g., "Baz"), or null for wildcards.
 * @param alias The alias used for the import (e.g., "Y" in "import X as Y"), or null if no alias.
 */
public record ImportInfo(String rawSnippet, boolean isWildcard, @Nullable String identifier, @Nullable String alias) {}
