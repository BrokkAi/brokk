; ============================================================================
; REUSABLE NAME PATTERNS (for reference and consistency)
; ============================================================================
; member_name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)]
; interface_member_name: [(property_identifier) (string) (number) (computed_property_name)]
; Namespace/Module declarations are handled by specific patterns below (ambient and non-ambient)

; Export statements (both default and regular) for class-like declarations
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  [
    (class_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters)
    (abstract_class_declaration
      "abstract" @keyword.modifier
      name: (type_identifier) @type.name
      type_parameters: (_)? @class.type_parameters)
    (enum_declaration name: (identifier) @type.name)
    (interface_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters)
  ]) @type.definition

; Export statements with decorators for class declarations
(
  (decorator)*
  . (export_statement
      "export" @keyword.modifier
      declaration: (class_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters)) @type.definition
)

; Export statements with decorators for abstract class declarations
(
  (decorator)*
  . (export_statement
      "export" @keyword.modifier
      declaration: (abstract_class_declaration
        "abstract" @keyword.modifier
        name: (type_identifier) @type.name
        type_parameters: (_)? @class.type_parameters)) @type.definition
)

; Export statements (both default and regular) for functions
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (function_declaration
    "async"? @keyword.modifier
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters)) @function.definition

; Export statements (both default and regular) for type aliases
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (type_alias_declaration
    name: (type_identifier) @typealias.name) @typealias.definition)


; Non-export type alias declarations
(program
  (type_alias_declaration
    name: (type_identifier) @typealias.name) @typealias.definition)

; Arrow functions in export const/let declarations
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @function.name
      value: (arrow_function)))) @function.definition

; Arrow functions in top-level const/let declarations
(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @function.name
      value: (arrow_function))) @function.definition)

; Non-arrow values in export const/let declarations
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @value.name
      value: (_)) @value.definition))

; Non-arrow values in top-level const/let declarations
(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @value.name
      value: (_)) @value.definition))

; Export variable declarations
(export_statement
  "export" @keyword.modifier
  (variable_declaration
    ["var" "let" "const"] @keyword.modifier
    (variable_declarator
      name: (identifier) @value.name) @value.definition))

; Top-level variable declarations
(program
  (variable_declaration
    ["var" "let" "const"] @keyword.modifier
    (variable_declarator
      name: (identifier) @value.name) @value.definition))

; Ambient variable declarations
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (variable_declaration
      ["var" "let" "const"] @keyword.modifier
      (variable_declarator
        name: (identifier) @value.name) @value.definition)))

; Ambient function declarations (declare function)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (function_signature
      name: (identifier) @function.name
      type_parameters: (_)? @function.type_parameters) @function.definition))

; Ambient class declarations (declare class)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (class_declaration
      name: (type_identifier) @type.name
      type_parameters: (_)? @class.type_parameters) @type.definition))

; Ambient interface declarations (declare interface)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (interface_declaration
      name: (type_identifier) @type.name
      type_parameters: (_)? @class.type_parameters) @type.definition))

; Ambient enum declarations (declare enum)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (enum_declaration
      name: (identifier) @type.name) @type.definition))

; Ambient namespace declarations (declare namespace/module)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (internal_module
      name: (_) @type.name) @type.definition))

; Generic pattern for declarations inside any module/namespace context
; This consolidates patterns for both regular namespaces and ambient namespaces
[(internal_module) (ambient_declaration (internal_module))]
  body: (statement_block
    [
      (function_declaration
        "async"? @keyword.modifier
        name: (identifier) @function.name
        type_parameters: (_)? @function.type_parameters) @function.definition
      (function_signature
        name: (identifier) @function.name
        type_parameters: (_)? @function.type_parameters) @function.definition
      (class_declaration
        name: (type_identifier) @type.name
        type_parameters: (_)? @class.type_parameters) @type.definition
      (interface_declaration
        name: (type_identifier) @type.name
        type_parameters: (_)? @class.type_parameters) @type.definition
      (enum_declaration
        name: (identifier) @type.name) @type.definition
      (type_alias_declaration
        name: (type_identifier) @type.name) @typealias.definition
      (lexical_declaration
        ["const" "let"] @keyword.modifier
        (variable_declarator
          name: (identifier) @function.name
          value: (arrow_function))) @function.definition
      (internal_module
        name: (_) @type.name) @type.definition
      (expression_statement
        (internal_module
          name: (_) @type.name) @type.definition)
      (export_statement
        "export" @keyword.modifier
        [
          (class_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters)
          (interface_declaration name: (type_identifier) @type.name type_parameters: (_)? @class.type_parameters)
          (enum_declaration name: (identifier) @type.name)
        ]) @type.definition
      (export_statement
        "export" @keyword.modifier
        (function_declaration
          "async"? @keyword.modifier
          name: (identifier) @function.name
          type_parameters: (_)? @function.type_parameters)) @function.definition
      (export_statement
        "export" @keyword.modifier
        (type_alias_declaration
          name: (type_identifier) @typealias.name) @typealias.definition)
    ])

; Top-level non-export function declarations
(program
  (function_declaration
    "async"? @keyword.modifier
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters) @function.definition)

; Top-level non-export interface declarations (not nested in export_statement)
(program
  (interface_declaration
    name: (type_identifier) @type.name
    type_parameters: (_)? @class.type_parameters) @type.definition)

; Top-level non-export class declarations that are direct children of program (not nested in export_statement)
(program
  . (class_declaration
      name: (type_identifier) @type.name
      type_parameters: (_)? @class.type_parameters) @type.definition)

; Top-level non-export abstract class declarations that are direct children of program (not nested in export_statement)
(program
  . (abstract_class_declaration
      "abstract" @keyword.modifier
      name: (type_identifier) @type.name
      type_parameters: (_)? @class.type_parameters) @type.definition)

; Top-level non-export enum declarations that are direct children of program (not nested in export_statement)
(program
  . (enum_declaration
      name: (identifier) @type.name) @type.definition)

; Top-level non-export namespace/module declarations (direct children of program, not export_statement or ambient_declaration)
(program
  (internal_module
    name: (_) @type.name) @type.definition)

; Top-level non-export namespace/module declarations wrapped in expression_statement
(program
  (expression_statement
    (internal_module
      name: (_) @type.name) @type.definition))

; Export function signatures (overloads)
(export_statement
  "export" @keyword.modifier
  (function_signature
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters)) @function.definition

; Top-level non-export function signatures
(program
  (function_signature
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters) @function.definition)

; Method definitions (with optional decorators and modifiers) - uses member_name pattern
(
  (decorator)*
  . (method_definition
      (accessibility_modifier)? @keyword.modifier
      ["static" "readonly" "async"]* @keyword.modifier
      name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
      type_parameters: (_)? @function.type_parameters) @function.definition
)

; Interface method signatures - uses interface_member_name pattern
(method_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @function.name
  type_parameters: (_)? @function.type_parameters) @function.definition

; Interface constructor signatures (new signatures)
(construct_signature
  type_parameters: (_)? @function.type_parameters) @function.definition (#set! "default_name" "new")

; Abstract method signatures (captured anywhere they appear)
; Note: Abstract methods are typically method_signature nodes in abstract classes
(
  (decorator)*
  . (abstract_method_signature
      "abstract" @keyword.modifier
      name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
      type_parameters: (_)? @function.type_parameters) @function.definition
)

; Class fields (with optional decorators) - uses member_name pattern
(
  (decorator)*
  . (public_field_definition
      name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @value.name) @value.definition
)

; Interface properties - uses interface_member_name pattern
(interface_body
  (property_signature
    name: [(property_identifier) (string) (number) (computed_property_name)] @value.name) @value.definition)

; Enum members for proper enum reconstruction - capture individual members
(enum_body
  ((property_identifier) @value.name) @value.definition)

; Enum members with values (e.g., Green = 3, Active = "active")
(enum_body
  (enum_assignment
    name: (property_identifier) @value.name
    value: (_)) @value.definition)
