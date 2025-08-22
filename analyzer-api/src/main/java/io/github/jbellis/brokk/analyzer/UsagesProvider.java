package io.github.jbellis.brokk.analyzer;

import java.util.List;

/** Implemented by analyzers that can readily provide some kind of points-to analysis. */
public interface UsagesProvider extends CapabilityProvider {

    List<CodeUnit> getUses(String fqName);
}
