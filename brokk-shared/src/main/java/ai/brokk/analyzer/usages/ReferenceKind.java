package ai.brokk.analyzer.usages;

public enum ReferenceKind {
    METHOD_CALL,
    CONSTRUCTOR_CALL,
    FIELD_READ,
    FIELD_WRITE,
    TYPE_REFERENCE,
    STATIC_REFERENCE,
    SUPER_CALL,
    INHERITANCE
}
