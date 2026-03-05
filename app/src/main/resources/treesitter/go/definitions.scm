(package_clause (package_identifier) @package.name)

(function_declaration
  name: (identifier) @function.name) @function.definition

; Named types
(type_declaration
  (type_spec
    name: (type_identifier) @type.name
    type: (_) @type.kind) @type.definition)

; True type aliases
(type_declaration
  (type_alias
    name: (type_identifier) @type.name
    type: (_) @type.kind) @type.definition)

(var_declaration
  (var_spec
    name: (identifier) @variable.name
    ; type: (_) @variable.type ; optional for future use
    ; value: (_) @variable.value ; optional for future use
  ) @variable.definition ; @variable.definition now points to var_spec
)

(const_declaration
  (const_spec
    name: (identifier) @constant.name
    ; type: (_) @constant.type ; optional for future use
    ; value: (_) @constant.value ; optional for future use
  ) @constant.definition ; @constant.definition now points to const_spec
)

(method_declaration
  receiver: (parameter_list
    (parameter_declaration
      type: [ (type_identifier) @method.receiver.type (pointer_type (type_identifier) @method.receiver.type) ]
    )
  )
  name: (field_identifier) @method.name
) @method.definition

; Captures field declarations within a struct.
; We capture each field_identifier as the name and its parent field_declaration as the definition.
; This allows Tree-sitter to generate multiple matches for one field_declaration node (e.g. Field1, Field2 int).
(struct_type
  (field_declaration_list
    (field_declaration
      [
        (field_identifier) @struct.field.name
        (field_declaration_list (field_declaration (field_identifier) @struct.field.name))
      ]
    ) @struct.field.definition
  )
)

; Captures method specifications within an interface type
(interface_type
  (method_elem
    name: (field_identifier) @interface.method.name
    (parameter_list) @interface.method.parameters
  ) @interface.method.definition
)

; Semantic test marker candidate detection
; Matches top-level function declarations. Predicate-free so GoAnalyzer can filter in Java.
; Uses node shape/ordering to avoid capturing methods with receivers.
(function_declaration
  "func"
  !type_parameters
  (identifier) @test_candidate.name
  (parameter_list) @test_candidate.params
) @test_marker
