; Namespace declarations
(namespace_definition
  name: (namespace_identifier) @namespace.name) @namespace.declaration

; Class declarations
(class_specifier
  name: (type_identifier) @class.name
  body: (field_declaration_list)) @class.definition

; Struct declarations
(struct_specifier
  name: (type_identifier) @struct.name
  body: (field_declaration_list)) @struct.definition

; Function definitions with names
(function_definition
  declarator: (function_declarator
    declarator: (identifier) @function.name)) @function.definition

; Field declarations (class/struct members, global variables)
(field_declaration) @field.definition

; Declaration (captures various declarations)
(declaration) @declaration