package ai.brokk.errorprone;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Utility methods for traversing and querying the Java type hierarchy using Javac symbols.
 */
public final class TypeHierarchyUtils {

    private TypeHierarchyUtils() {
        // Utility class
    }

    /**
     * Recursively checks if the given class symbol implements or extends a target type.
     *
     * <p>This method traverses the superclass chain and all implemented interfaces.
     * It uses a set to keep track of visited types to prevent infinite loops in case of
     * malformed or complex hierarchies.
     *
     * @param cs the class symbol to check (may be null)
     * @param targetFqcn the fully qualified name of the target interface or class
     * @return true if the symbol or any of its supertypes match the target FQCN, false otherwise
     */
    public static boolean implementsOrExtends(Symbol.@Nullable ClassSymbol cs, String targetFqcn) {
        if (cs == null) {
            return false;
        }
        return walkHierarchy(cs, targetFqcn, new HashSet<>());
    }

    private static boolean walkHierarchy(Symbol.ClassSymbol cs, String targetFqcn, Set<Symbol.ClassSymbol> visited) {
        if (!visited.add(cs)) {
            return false;
        }

        if (cs.getQualifiedName().contentEquals(targetFqcn)) {
            return true;
        }

        // Check implemented interfaces
        for (Type interfaceType : cs.getInterfaces()) {
            if (interfaceType.tsym instanceof Symbol.ClassSymbol interfaceSymbol) {
                if (walkHierarchy(interfaceSymbol, targetFqcn, visited)) {
                    return true;
                }
            }
        }

        // Check superclass
        Type superclass = cs.getSuperclass();
        if (superclass.tsym instanceof Symbol.ClassSymbol superclassSymbol) {
            return walkHierarchy(superclassSymbol, targetFqcn, visited);
        }

        return false;
    }
}
