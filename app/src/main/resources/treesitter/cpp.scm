; Namespace declarations
(namespace_definition
  name: (namespace_identifier) @namespace.name) @namespace.definition

; Class declarations
(class_specifier
  name: (type_identifier) @class.name) @class.definition

; Struct declarations
(struct_specifier
  name: (type_identifier) @struct.name) @struct.definition

; Union declarations
(union_specifier
  name: (type_identifier) @union.name) @union.definition

; Enum declarations
(enum_specifier
  name: (type_identifier) @enum.name) @enum.definition

; Global function definitions (non-method functions)
(function_definition
  declarator: (function_declarator
    declarator: (identifier) @function.name)) @function.definition

; Global function declarations - specific function declarator patterns (prototypes)
(translation_unit
  (declaration
    declarator: (function_declarator
      declarator: (identifier) @function.name)) @function.definition)

; Global variable declarations - handle both plain and extern declarations
(translation_unit
  (declaration
    declarator: (identifier) @variable.name) @variable.definition)

; Global variable declarations with init_declarator
(translation_unit
  (declaration
    declarator: (init_declarator
      declarator: (identifier) @variable.name)) @variable.definition)

; Global variable declarations with storage class specifiers (extern, static, etc.)
(translation_unit
  (declaration
    type: (_)
    declarator: (identifier) @variable.name) @variable.definition)

; Field declarations in classes and structs
(field_declaration
  declarator: (field_identifier) @field.name) @field.definition

; Method declarations within classes - field_declaration with function_declarator
(field_declaration
  declarator: (function_declarator
    declarator: (field_identifier) @method.name)) @method.definition

; Inline method definitions within classes (methods with bodies)
(function_definition
  declarator: (function_declarator
    declarator: (field_identifier) @method.name)) @method.definition

; Constructor declarations within class bodies - capture the declaration node directly
(field_declaration_list
  (declaration
    declarator: (function_declarator
      declarator: (identifier) @constructor.name)) @constructor.definition)

; Typedef declarations
(type_definition
  declarator: (_) @typedef.name) @typedef.definition

; Using declarations (type aliases)
(alias_declaration
  name: (type_identifier) @using.name) @using.definition

; Access specifiers
(access_specifier) @access.specifier