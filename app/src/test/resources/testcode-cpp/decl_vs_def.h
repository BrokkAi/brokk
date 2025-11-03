class DeclVsDef {
    void declaration_only();
    void inline_definition() { /* body */ }
    int field_with_inline_init = 42;
};

// Out-of-line definition
void DeclVsDef::declaration_only() {
    // body
}
