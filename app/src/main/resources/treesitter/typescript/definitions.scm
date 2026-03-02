; ============================================================================
; TYPESCRIPT TREESITTER QUERY PATTERNS - DEFINITIONS
; ============================================================================

; Export statements for class-like declarations
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (class_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters)) @type.definition

(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (abstract_class_declaration
    "abstract" @keyword.modifier
    name: (type_identifier) @type.name
    type_parameters: (_)? @class.type_parameters)) @type.definition

(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (enum_declaration name: (identifier) @type.name)) @type.definition

(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (interface_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters)) @type.definition

(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (internal_module name: (_) @type.name)) @type.definition

; Export statements for functions
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (function_declaration
    "async"? @keyword.modifier
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters)) @function.definition

; Export statements for function signatures (overloads)
(export_statement
  "export" @keyword.modifier
  (function_signature
    "async"? @keyword.modifier
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters)) @function.definition

; Export statements for type aliases
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (type_alias_declaration
    name: (type_identifier) @typealias.name) @typealias.definition)

; Export variable declarations
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @value.name)) @value.definition)

(export_statement
  "export" @keyword.modifier
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @value.name)) @value.definition)

; Export arrow function declarations
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @arrow_function.name
      value: (arrow_function))) @arrow_function.definition)

(export_statement
  "export" @keyword.modifier
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @arrow_function.name
      value: (arrow_function))) @arrow_function.definition)

; Top-level class-like declarations (non-export)
(program
  [
    (class_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters) @type.definition
    (abstract_class_declaration
      name: (type_identifier) @type.name
      type_parameters: (_)? @class.type_parameters) @type.definition
    (interface_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters) @type.definition
    (enum_declaration name: (identifier) @type.name) @type.definition
    (internal_module name: (_) @type.name) @type.definition
  ])

; Top-level function declarations (non-export)
(program
  (function_declaration
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters) @function.definition)

; Top-level function signatures (non-export)
(program
  (function_signature
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters) @function.definition)

; Top-level type aliases (non-export)
(program
  (type_alias_declaration
    name: (type_identifier) @typealias.name) @typealias.definition)

; Top-level variable declarations (non-exported)
(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @value.name)) @value.definition)

(program
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @value.name)) @value.definition)

; Top-level arrow function assignments (non-exported)
(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @arrow_function.name
      value: (arrow_function))) @arrow_function.definition)

(program
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @arrow_function.name
      value: (arrow_function))) @arrow_function.definition)

; Ambient declarations
(program
  (ambient_declaration
    "declare" @keyword.modifier
    [
      (class_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters) @type.definition
      (interface_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters) @type.definition
      (enum_declaration name: (identifier) @type.name) @type.definition
      (internal_module name: (_) @type.name) @type.definition
      (function_signature name: (identifier) @function.name type_parameters: (_)? @function.type_parameters) @function.definition
      (variable_declaration
        "var" @keyword.modifier
        (variable_declarator name: (identifier) @value.name) @value.definition)
    ]))

; Declarations inside ambient namespaces
(ambient_declaration
  (internal_module
    body: (statement_block
      [
        (function_signature
          name: (identifier) @function.name
          type_parameters: (_)? @function.type_parameters) @function.definition
        (interface_declaration
          name: (type_identifier) @type.name
          type_parameters: (_)? @class.type_parameters) @type.definition
        (class_declaration
          name: (type_identifier) @type.name
          type_parameters: (_)? @class.type_parameters) @type.definition
        (enum_declaration
          name: (identifier) @type.name) @type.definition
      ])))

; Any namespace wrapped in expression_statement
(expression_statement
  (internal_module name: (_) @type.name) @type.definition)

; Method definitions in classes
(method_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
  type_parameters: (_)? @function.type_parameters) @function.definition

; Interface method signatures
(method_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @function.name
  type_parameters: (_)? @function.type_parameters) @function.definition

; Constructor signatures in interfaces
(construct_signature
  type_parameters: (_)? @function.type_parameters) @function.definition (#set! "default_name" "new")

; Abstract method signatures
(abstract_method_signature
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
  type_parameters: (_)? @function.type_parameters) @function.definition

; Class fields
(public_field_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @value.name) @value.definition

; Interface properties
(interface_body
  (property_signature
    name: [(property_identifier) (string) (number) (computed_property_name)] @value.name) @value.definition)

; Index signatures in interfaces
(interface_body
  (index_signature) @value.definition (#set! "default_name" "[index]"))

; Call signatures in interfaces
(interface_body
  (call_signature
    type_parameters: (_)? @function.type_parameters) @function.definition (#set! "default_name" "[call]"))

; Enum members
(enum_body
  [
    ((property_identifier) @value.name) @value.definition
    (enum_assignment
      name: (property_identifier) @value.name
      value: (_)) @value.definition
  ])
