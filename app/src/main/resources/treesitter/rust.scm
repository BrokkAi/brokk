
;; Struct definitions
(struct_item
  (visibility_modifier)? @keyword.modifier
  name: (type_identifier) @class.name
  ; type_parameters: (_) @class.type_parameters ; optional
  ; where_clause: (_) @class.where_clause ; optional
) @class.definition

;; Enum definitions
(enum_item
  (visibility_modifier)? @keyword.modifier
  name: (type_identifier) @class.name
  ; type_parameters: (_) @class.type_parameters ; optional
  ; where_clause: (_) @class.where_clause ; optional
) @class.definition

;; Trait definitions
(trait_item
  (visibility_modifier)? @keyword.modifier
  name: (type_identifier) @class.name
  ; type_parameters: (_) @class.type_parameters ; optional
  ; where_clause: (_) @class.where_clause ; optional
  ; body: (declaration_list) ; contains function_signature_item for methods
) @class.definition

;; Impl blocks
(impl_item
  ; For `impl MyType` or `impl Trait for MyType`
  ; We capture MyType as the "class name" for associating methods.
  ; If it's `impl Trait for MyType`, type arguments can make it complex.
  ; This captures the direct type identifier after `impl` or after `for`.
  type: [
    (type_identifier) @impl.name               ; e.g., impl MyType
    (generic_type type: (type_identifier) @impl.name) ; e.g., impl<T> MyType<T>
    (scoped_type_identifier name: (type_identifier) @impl.name) ; e.g., impl foo::MyType
    ; This list covers the type being implemented in `impl MyType`
    ; or the concrete type in `impl Trait for MyType`.
  ]
  ; body: (declaration_list) ; contains function_item for methods
) @impl.definition

;; Free functions and methods within impl blocks
(function_item
  (visibility_modifier)? @keyword.modifier
  name: (identifier) @function.name
  ; parameters: (parameters) @function.parameters
  ; return_type: (_)? @function.return_type
  ; body: (block)
) @function.definition

;; Method signatures within trait definitions (no body)
(function_signature_item
  (visibility_modifier)? @keyword.modifier
  name: (identifier) @function.name
  ; parameters: (parameters) @function.parameters
  ; return_type: (_)? @function.return_type
) @function.definition

;; Fields within a struct_item's field_declaration_list
(struct_item
  body: (field_declaration_list
    (field_declaration
      (visibility_modifier)? @keyword.modifier
      name: (field_identifier) @field.name
      ; type: (_) @field.type
    ) @field.definition
  )
)

;; Top-level constants
(const_item
  (visibility_modifier)? @keyword.modifier
  name: (identifier) @field.name
  ; type: (_) @field.type
  ; value: (_) @field.value
) @field.definition

;; Top-level static items
(static_item
  (visibility_modifier)? @keyword.modifier
  name: (identifier) @field.name
  ; type: (_) @field.type
  ; value: (_) @field.value
) @field.definition

;; Enum variants within an enum_item's enum_variant_list
(enum_item
  body: (enum_variant_list
    (enum_variant
      name: (identifier) @field.name
      ; parameters: (_)? @field.parameters ; For tuple-like variants, e.g., Variant(u32, String)
      ; body: (field_declaration_list)? @field.body ; For struct-like variants, e.g., Variant { field1: u32 }
    ) @field.definition
  )
)

;; Associated constants within impl blocks
(impl_item
  body: (declaration_list
    (const_item
      (visibility_modifier)? @keyword.modifier
      name: (identifier) @field.name
      ; type: (_) @field.type
      ; value: (_) @field.value
    ) @field.definition
  )
)

;; Associated constants within trait definitions
(trait_item
  body: (declaration_list
    (const_item
      (visibility_modifier)? @keyword.modifier
      name: (identifier) @field.name
      ; type: (_) @field.type
      ; value: (_) @field.value
    ) @field.definition
  )
)
