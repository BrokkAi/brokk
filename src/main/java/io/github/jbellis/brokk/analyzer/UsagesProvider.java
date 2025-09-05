package io.github.jbellis.brokk.analyzer;

import java.util.List;

/** Implemented by analyzers that can readily provide some kind of points-to analysis. */
public interface UsagesProvider extends CapabilityProvider {

    /**
     * Provides a list of code units that use the code unit matching the given full name.
     *
     * @param fqName the full name of the code unit to determine usages for.
     * @return code units using the matching code unit, if any.
     */
    List<CodeUnit> getUses(String fqName);
}
