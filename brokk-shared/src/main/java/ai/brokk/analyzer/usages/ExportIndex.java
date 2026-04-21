package ai.brokk.analyzer.usages;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Language-agnostic representation of what a compilation unit exports.
 *
 * <p>Export entries may be direct (declared in-file) or re-exported from another module.
 */
public record ExportIndex(
        Map<String, ExportEntry> exportsByName,
        List<ReexportStar> reexportStars,
        Set<ClassExtendsEdge> classExtendsEdges) {

    public static ExportIndex empty() {
        return new ExportIndex(Map.of(), List.of(), Set.of());
    }

    public sealed interface ExportEntry permits LocalExport, ReexportedNamed, DefaultExport {}

    /**
     * Exported name maps to a local top-level identifier in this file.
     */
    public record LocalExport(String localName) implements ExportEntry {}

    /**
     * Exported name re-exports an imported name from another module.
     */
    public record ReexportedNamed(String moduleSpecifier, String importedName) implements ExportEntry {}

    /**
     * Export default. localName is null when the default export is anonymous.
     */
    public record DefaultExport(@Nullable String localName) implements ExportEntry {}

    public record ReexportStar(String moduleSpecifier) {}

    /**
     * Minimal class inheritance edge for flow-insensitive "polymorphism" in JS/TS.
     */
    public record ClassExtendsEdge(String childClassName, String parentClassName) {}
}
