package ai.brokk.analyzer;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a re-export statement in TypeScript/JavaScript.
 *
 * <p>Re-exports are ES6 module patterns that allow files to re-export declarations from other
 * modules. Common uses include barrel files, public API boundaries, and namespace organization.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code export * from './users'} → ReexportInfo(exportAll=true, source="./users", ...)
 *   <li>{@code export { User, Role } from './models'} → ReexportInfo(symbols=["User", "Role"], source="./models", ...)
 *   <li>{@code export { User as PublicUser } from './internal'} → ReexportInfo(symbols=["User"], renamed={"User": "PublicUser"}, ...)
 *   <li>{@code export * as Types from './types'} → ReexportInfo(namespace="Types", source="./types", ...)
 * </ul>
 */
public record ReexportInfo(
        /**
         * The source module path being re-exported from (e.g., "./users", "../models").
         * This is the raw import path as written in the source code.
         */
        String source,

        /**
         * True if this is an "export * from" wildcard statement.
         * When true, all exports from the source module are re-exported.
         */
        boolean exportAll,

        /**
         * List of specific symbol names being re-exported.
         * Empty if exportAll is true or for namespace re-exports.
         */
        List<String> symbols,

        /**
         * Map of original name → renamed name for aliased re-exports.
         * Example: {@code export { User as PublicUser }} → {"User": "PublicUser"}
         * Empty if no renaming occurs.
         */
        Map<String, String> renamed,

        /**
         * Namespace name for "export * as Namespace" pattern.
         * Null if not a namespace re-export.
         */
        String namespace) {

    /**
     * Compact constructor that validates and makes defensive copies.
     */
    public ReexportInfo {
        Objects.requireNonNull(source, "source cannot be null");
        symbols = symbols != null ? List.copyOf(symbols) : List.of();
        renamed = renamed != null ? Map.copyOf(renamed) : Map.of();
    }

    /**
     * Creates a wildcard re-export: {@code export * from 'source'}
     *
     * @param source the module path to re-export from
     * @return a ReexportInfo representing a wildcard re-export
     */
    public static ReexportInfo wildcard(String source) {
        return new ReexportInfo(source, true, List.of(), Map.of(), null);
    }

    /**
     * Creates a named re-export: {@code export { symbols } from 'source'}
     *
     * @param source the module path to re-export from
     * @param symbols the list of symbol names to re-export
     * @return a ReexportInfo representing a named re-export
     */
    public static ReexportInfo named(String source, List<String> symbols) {
        return new ReexportInfo(source, false, symbols, Map.of(), null);
    }

    /**
     * Creates a namespace re-export: {@code export * as namespace from 'source'}
     *
     * @param source the module path to re-export from
     * @param namespace the namespace name to export under
     * @return a ReexportInfo representing a namespace re-export
     */
    public static ReexportInfo namespace(String source, String namespace) {
        return new ReexportInfo(source, false, List.of(), Map.of(), namespace);
    }

    /**
     * Creates a renamed re-export: {@code export { originalName as newName } from 'source'}
     *
     * @param source the module path to re-export from
     * @param symbols the list of original symbol names
     * @param renamed the map of original name → new name
     * @return a ReexportInfo representing a renamed re-export
     */
    public static ReexportInfo renamed(String source, List<String> symbols, Map<String, String> renamed) {
        return new ReexportInfo(source, false, symbols, renamed, null);
    }
}
