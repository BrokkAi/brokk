package ai.brokk.analyzer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Represents a named code element (class, function, field, or module). */
public class CodeUnit implements Comparable<CodeUnit> {

    @JsonProperty("source")
    private final ProjectFile source;

    @JsonProperty("kind")
    private final CodeUnitType kind;

    @JsonProperty("shortName")
    private final String shortName;

    @JsonProperty("packageName")
    private final String packageName;

    @JsonProperty("signature")
    @Nullable
    private final String signature;

    @JsonProperty("synthetic")
    private final boolean synthetic;

    @JsonProperty("children")
    private final List<CodeUnit> children;

    private final transient String fqName;

    @JsonCreator
    public CodeUnit(
            @JsonProperty("source") ProjectFile source,
            @JsonProperty("kind") CodeUnitType kind,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("shortName") String shortName,
            @JsonProperty("signature") @Nullable String signature,
            @JsonProperty("synthetic") boolean synthetic,
            @JsonProperty("children") @Nullable List<CodeUnit> children) {
        if (shortName.isEmpty()) {
            throw new IllegalArgumentException("shortName must not be empty");
        }
        this.source = source;
        this.kind = kind;
        this.packageName = packageName;
        this.shortName = shortName;
        this.signature = signature;
        this.synthetic = synthetic;
        this.children = children != null ? List.copyOf(children) : List.of();
        this.fqName = packageName.isEmpty() ? shortName : packageName + "." + shortName;
    }

    public CodeUnit(
            @JsonProperty("source") ProjectFile source,
            @JsonProperty("kind") CodeUnitType kind,
            @JsonProperty("packageName") String packageName,
            @JsonProperty("shortName") String shortName,
            @JsonProperty("signature") @Nullable String signature) {
        this(source, kind, packageName, shortName, signature, false);
    }

    public CodeUnit(
            ProjectFile source,
            CodeUnitType kind,
            String packageName,
            String shortName,
            @Nullable String signature,
            boolean synthetic) {
        this(source, kind, packageName, shortName, signature, synthetic, List.of());
    }

    public CodeUnit(
            ProjectFile source, CodeUnitType kind, String packageName, String shortName, @Nullable String signature) {
        this(source, kind, packageName, shortName, signature, false, List.of());
    }

    public CodeUnit(ProjectFile source, CodeUnitType kind, String packageName, String shortName) {
        this(source, kind, packageName, shortName, null, false, List.of());
    }

    public CodeUnit withoutSignature() {
        return new CodeUnit(source(), kind(), packageName(), shortName(), null, isSynthetic(), children());
    }

    public CodeUnit withSynthetic(boolean synthetic) {
        return new CodeUnit(source(), kind(), packageName(), shortName(), signature(), synthetic, children());
    }

    public CodeUnit withChildren(List<CodeUnit> children) {
        return new CodeUnit(source(), kind(), packageName(), shortName(), signature(), isSynthetic(), children);
    }

    /**
     * Returns the fully qualified name constructed from package and short name. For MODULE, shortName is often a fixed
     * placeholder like "_module_", so fqName becomes "packageName._module_".
     *
     * @return The fully qualified name.
     */
    public String fqName() {
        return this.fqName;
    }

    /**
     * Returns just the last symbol name component. For CLASS: innermost class name (e.g., `D` from `Outer$D` or `Inner`
     * from `Outer.Inner`). For FUNCTION/FIELD: member name (foo from a.b.C.foo). For MODULE: the shortName itself (e.g.,
     * "_module_").
     *
     * @return just the last symbol name component.
     */
    public String identifier() {
        return switch (kind) {
            case CLASS -> {
                // Extract simple name from potentially nested class (e.g., "AInner" from "A.AInner" or "A$AInner")
                int lastDot = shortName.lastIndexOf('.');
                int lastDollar = shortName.lastIndexOf('$');
                int lastSep = Math.max(lastDot, lastDollar);
                yield lastSep >= 0 ? shortName.substring(lastSep + 1) : shortName;
            }
            case MODULE -> shortName; // The module's own short name, e.g., "_module_"
            default -> { // FUNCTION or FIELD
                // shortName format is "Class.member" or "simpleFunction"
                int lastDot = shortName.lastIndexOf('.');
                yield lastDot >= 0 ? shortName.substring(lastDot + 1) : shortName;
            }
        };
    }

    /**
     * Returns the short name component.
     *
     * <ul>
     *   <li>For {@link CodeUnitType#CLASS}, this is the simple class name (e.g., "MyClass", "Outer$Inner").
     *   <li>For {@link CodeUnitType#FUNCTION} or {@link CodeUnitType#FIELD}, this is "className.memberName" (e.g.,
     *       "MyClass.myMethod", "Outer$Inner.myMethod") or just "functionName".
     *   <li>For {@link CodeUnitType#MODULE}, this is typically a placeholder like "_module_" or a file-derived name.
     * </ul>
     *
     * @return The short name.
     */
    public String shortName() {
        return shortName;
    }

    public boolean isClass() {
        return kind == CodeUnitType.CLASS;
    }

    public boolean isFunction() {
        return kind == CodeUnitType.FUNCTION;
    }

    public boolean isModule() {
        return kind == CodeUnitType.MODULE;
    }

    public boolean isField() {
        return kind == CodeUnitType.FIELD;
    }

    /**
     * Returns the code unit kind, i.e., Class, module, field, function, etc.
     *
     * @return the code unit kind.
     */
    public CodeUnitType kind() {
        return kind;
    }

    /**
     * Returns accessor for the package name component.
     *
     * @return Accessor for the package name component.
     */
    public String packageName() {
        return packageName;
    }

    /**
     * Returns the source ProjectFile associated with this code unit.
     *
     * @return the project file source.
     */
    public ProjectFile source() {
        return source;
    }

    /**
     * Returns the (optional) signature associated with this CodeUnit.
     *
     * <p>
     * This field contains an optional, analyzer-populated canonical "signature" for callable code units
     * (functions / methods). The signature is a language-specific representation of the callable's
     * parameter list and signature-relevant qualifiers used for overload disambiguation. It may be
     * {@code null} for legacy items or when an analyzer cannot produce a canonical signature.
     * </p>
     *
     * <p><strong>Design notes:</strong></p>
     * <ul>
     *   <li><strong>Identity:</strong> {@code signature} is part of CodeUnit identity for overload disambiguation.
     *       Two functions with the same {@code fqName} but different signatures (e.g., {@code foo(int)} vs {@code foo(double)})
     *       are considered distinct CodeUnits.</li>
     *   <li><strong>FQN derivation:</strong> {@link #fqName()} is derived from {@code packageName} + {@code shortName}
     *       and does NOT include the signature. Signature is stored separately for clean separation.</li>
     *   <li><strong>Equality:</strong> {@code equals()} and {@code hashCode()} include {@code signature} to distinguish overloads.
     *       A CodeUnit with {@code signature=null} is NOT equal to one with {@code signature="(int)"}.</li>
     *   <li><strong>Legacy data:</strong> CodeUnits deserialized from older JSON may have {@code signature=null}.
     *       These are treated as distinct from newer CodeUnits with populated signatures.</li>
     * </ul>
     *
     * @return the signature string, or {@code null} if absent.
     */
    @Nullable
    public String signature() {
        return signature;
    }

    /**
     * Convenience to test presence of a signature.
     *
     * @return true if a signature string was recorded for this CodeUnit.
     */
    public boolean hasSignature() {
        return signature != null;
    }

    /**
     * Returns true if this code unit is synthetic (e.g. compiler-generated, or an artificial module container).
     *
     * @return true if synthetic.
     */
    public boolean isSynthetic() {
        return synthetic;
    }

    /**
     * Returns the child code units (e.g. fields of a class, variants of an enum).
     *
     * @return the list of children.
     */
    public List<CodeUnit> children() {
        return children;
    }

    /**
     * Returns true if this CodeUnit represents an anonymous or synthetic element that contains "$anon$" in its name,
     * or if it is explicitly marked as synthetic.
     * Used to filter out lambda/anonymous artifacts from summaries and recommendations.
     */
    public boolean isAnonymous() {
        return synthetic || fqName.contains("$anon$");
    }

    @Override
    public int compareTo(CodeUnit other) {
        // Compare based on the derived fully qualified name
        return this.fqName().compareTo(other.fqName());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CodeUnit other)) return false;
        // Equality based on the derived fully qualified name, kind, source file, signature AND synthetic flag
        // Signature inclusion ensures overloaded functions are distinct (e.g., foo(int) vs foo(double))
        return kind == other.kind
                && synthetic == other.synthetic
                && Objects.equals(this.fqName(), other.fqName())
                && Objects.equals(this.source, other.source)
                && Objects.equals(this.signature, other.signature);
    }

    @Override
    public int hashCode() {
        // Hash code based on the derived fully qualified name, kind, source file, signature AND synthetic flag
        return Objects.hash(kind, fqName(), source, signature, synthetic);
    }

    @Override
    public String toString() {
        // Use derived fqName in toString representation
        return switch (kind) {
            case CLASS -> "CLASS[" + fqName() + "]";
            case FUNCTION -> "FUNCTION[" + fqName() + "]";
            case FIELD -> "FIELD[" + fqName() + "]";
            case MODULE -> "MODULE[" + fqName() + "]";
        };
    }

    /**
     * Factory method to create a CodeUnit of type CLASS. Assumes correct arguments.
     *
     * @param source The source file.
     * @param packageName The package name (can be empty).
     * @param shortName The simple class name (e.g., "MyClass", "Outer$Inner").
     */
    public static CodeUnit cls(ProjectFile source, String packageName, String shortName) {
        return new CodeUnit(source, CodeUnitType.CLASS, packageName, shortName, null, false);
    }

    /**
     * Factory method to create a CodeUnit of type FUNCTION. Assumes correct arguments.
     *
     * @param source The source file.
     * @param packageName The package name (e.g., "com.example", or "" for default package). Does NOT include the class
     *     name.
     * @param shortName The short name including the simple class name and member name (e.g., "MyClass.myMethod",
     *     "Outer$Inner.myMethod").
     */
    public static CodeUnit fn(ProjectFile source, String packageName, String shortName) {
        // The shortName for FUNCTION can be a simple function name or ClassName.methodName.
        // fqName() handles prefixing with packageName if present.
        return new CodeUnit(source, CodeUnitType.FUNCTION, packageName, shortName, null, false);
    }

    /**
     * Factory method to create a CodeUnit of type FIELD. Assumes correct arguments.
     *
     * @param source The source file.
     * @param packageName The package name (e.g., "com.example", or "" for default package). Does NOT include the class
     *     name.
     * @param shortName The short name. For fields that are members of classes, this should be "ClassName.myField" or
     *     "Outer$Inner.myField". For top-level variables/constants, it might be prefixed by a module identifier like
     *     "_module_.myVar" if required by analyzers to ensure a "." is present.
     */
    public static CodeUnit field(ProjectFile source, String packageName, String shortName) {
        // Note: shortName format varies by language:
        // - Class members: "ClassName.fieldName" (contains dot)
        // - Top-level fields: may or may not contain a dot depending on the language
        //   - JS: "_module_.myVar" (uses synthetic module container)
        //   - Python: "fieldName" (no container for module-level fields)
        return new CodeUnit(source, CodeUnitType.FIELD, packageName, shortName, null, false);
    }

    /**
     * Factory method to create a CodeUnit of type MODULE. Assumes correct arguments. Used to represent file-level
     * constructs like a collection of imports.
     *
     * @param source The source file.
     * @param packageName The package name (e.g., "com.example", or "" for default package).
     * @param shortName A short name for the module, often a placeholder like "_module_" or derived from the filename.
     */
    public static CodeUnit module(ProjectFile source, String packageName, String shortName) {
        return new CodeUnit(source, CodeUnitType.MODULE, packageName, shortName, null, false);
    }
}
