; ============================================================================
; REUSABLE NAME PATTERNS (for reference and consistency)
; ============================================================================
; member_name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)]
; interface_member_name: [(property_identifier) (string) (number) (computed_property_name)]

; Namespace/Module declarations are handled by specific patterns below (ambient and non-ambient)

; ============================================================================
; EXPORT STATEMENTS  
; ============================================================================

; Export statements (both default and regular) for class-like declarations
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  [
    (class_declaration name: (type_identifier) @class.name type_parameters: (_)? @class.type_parameters)
    (abstract_class_declaration 
      "abstract" @keyword.modifier
      name: (type_identifier) @class.name 
      type_parameters: (_)? @class.type_parameters)
    (enum_declaration name: (identifier) @class.name)
    (interface_declaration name: (type_identifier) @class.name type_parameters: (_)? @class.type_parameters)
  ]) @class.definition

; Export statements with decorators for class declarations
(
  (decorator)*
  . (export_statement
      "export" @keyword.modifier
      declaration: (class_declaration name: (type_identifier) @class.name type_parameters: (_)? @class.type_parameters)) @class.definition
)

; Export statements with decorators for abstract class declarations
(
  (decorator)*
  . (export_statement
      "export" @keyword.modifier
      declaration: (abstract_class_declaration
        "abstract" @keyword.modifier
        name: (type_identifier) @class.name 
        type_parameters: (_)? @class.type_parameters)) @class.definition
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
    name: (type_identifier) @field.name) @field.definition)


; ============================================================================
; TYPE ALIAS DECLARATIONS (non-export)
; ============================================================================

; Non-export type alias declarations
(program 
  (type_alias_declaration
    name: (type_identifier) @field.name) @field.definition)

; ============================================================================
; LEXICAL DECLARATIONS (const, let) - consolidated patterns
; ============================================================================

; Arrow functions in const/let (export and non-export)
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @function.name
      value: (arrow_function)))) @function.definition

(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @function.name
      value: (arrow_function))) @function.definition)

; Other values in const/let (export and non-export) - Note: arrow functions are handled by specific patterns above
(export_statement
  "export" @keyword.modifier
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)) @field.definition))

(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name
      value: (_)) @field.definition))

; ============================================================================
; VARIABLE DECLARATIONS (var) - consolidated
; ============================================================================

; Export var declarations
(export_statement
  "export" @keyword.modifier
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name) @field.definition))

; Top-level non-export var declarations
(program
  (variable_declaration
    "var" @keyword.modifier
    (variable_declarator
      name: (identifier) @field.name) @field.definition))

; ============================================================================
; AMBIENT DECLARATIONS (declare statements)
; ============================================================================

; Ambient variable declarations (declare var, declare let, declare const)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (variable_declaration
      ["var" "let" "const"] @keyword.modifier
      (variable_declarator
        name: (identifier) @field.name) @field.definition)))

; Ambient function declarations (declare function) 
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (function_signature
      name: (identifier) @function.name
      type_parameters: (_)? @function.type_parameters)) @function.definition)

; Ambient class declarations (declare class)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (class_declaration
      name: (type_identifier) @class.name
      type_parameters: (_)? @class.type_parameters) @class.definition))

; Ambient interface declarations (declare interface)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (interface_declaration
      name: (type_identifier) @class.name
      type_parameters: (_)? @class.type_parameters) @class.definition))

; Ambient enum declarations (declare enum)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (enum_declaration
      name: (identifier) @class.name) @class.definition))

; Ambient namespace declarations (declare namespace/module)
(program
  (ambient_declaration
    "declare" @keyword.modifier
    (internal_module
      name: (_) @class.name) @class.definition))

; Function signatures inside ambient namespaces
(ambient_declaration
  (internal_module
    body: (statement_block
      (function_signature
        name: (identifier) @function.name
        type_parameters: (_)? @function.type_parameters) @function.definition)))

; Interface declarations inside ambient namespaces
(ambient_declaration
  (internal_module
    body: (statement_block
      (interface_declaration
        name: (type_identifier) @class.name
        type_parameters: (_)? @class.type_parameters) @class.definition)))

; Class declarations inside ambient namespaces
(ambient_declaration
  (internal_module
    body: (statement_block
      (class_declaration
        name: (type_identifier) @class.name
        type_parameters: (_)? @class.type_parameters) @class.definition)))

; Variable declarations inside ambient namespaces
(ambient_declaration
  (internal_module
    body: (statement_block
      (variable_declaration
        ["var" "let" "const"] @keyword.modifier
        (variable_declarator
          name: (identifier) @field.name) @field.definition))))

; ============================================================================
; FUNCTION DECLARATIONS
; ============================================================================

; Top-level non-export function declarations
(program
  (function_declaration
    "async"? @keyword.modifier
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters) @function.definition)

; Top-level non-export interface declarations (not nested in export_statement)
(program
  (interface_declaration
    name: (type_identifier) @class.name
    type_parameters: (_)? @class.type_parameters) @class.definition)

; Top-level non-export class declarations that are direct children of program (not nested in export_statement)
(program
  . (class_declaration
      name: (type_identifier) @class.name
      type_parameters: (_)? @class.type_parameters) @class.definition)

; Top-level non-export abstract class declarations that are direct children of program (not nested in export_statement)
(program
  . (abstract_class_declaration
      "abstract" @keyword.modifier
      name: (type_identifier) @class.name
      type_parameters: (_)? @class.type_parameters) @class.definition)

; Top-level non-export enum declarations that are direct children of program (not nested in export_statement)
(program
  . (enum_declaration
      name: (identifier) @class.name) @class.definition)

; Top-level non-export namespace/module declarations (direct children of program, not export_statement or ambient_declaration)
(program
  (internal_module
    name: (_) @class.name) @class.definition)

; Top-level non-export namespace/module declarations wrapped in expression_statement
(program
  (expression_statement
    (internal_module
      name: (_) @class.name) @class.definition))

; Function declarations inside regular namespaces
(internal_module
  body: (statement_block
    (function_declaration
      "async"? @keyword.modifier
      name: (identifier) @function.name
      type_parameters: (_)? @function.type_parameters) @function.definition))

; Function signatures inside regular namespaces
(internal_module
  body: (statement_block
    (function_signature
      name: (identifier) @function.name
      type_parameters: (_)? @function.type_parameters) @function.definition))

; Interface declarations inside regular namespaces
(internal_module
  body: (statement_block
    (interface_declaration
      name: (type_identifier) @class.name
      type_parameters: (_)? @class.type_parameters) @class.definition))

; Class declarations inside regular namespaces
(internal_module
  body: (statement_block
    (class_declaration
      name: (type_identifier) @class.name
      type_parameters: (_)? @class.type_parameters) @class.definition))

; Enum declarations inside regular namespaces
(internal_module
  body: (statement_block
    (enum_declaration
      name: (identifier) @class.name) @class.definition))

; Variable declarations inside regular namespaces
(internal_module
  body: (statement_block
    (variable_declaration
      ["var" "let" "const"] @keyword.modifier
      (variable_declarator
        name: (identifier) @field.name) @field.definition)))

; Lexical declarations inside regular namespaces
(internal_module
  body: (statement_block
    (lexical_declaration
      ["const" "let"] @keyword.modifier
      (variable_declarator
        name: (identifier) @field.name
        value: (_)) @field.definition)))

; Type alias declarations inside regular namespaces
(internal_module
  body: (statement_block
    (type_alias_declaration
      name: (type_identifier) @field.name) @field.definition))

; Nested namespace declarations inside regular namespaces
(internal_module
  body: (statement_block
    (expression_statement
      (internal_module
        name: (_) @class.name) @class.definition)))

; Nested namespace declarations inside regular namespaces (direct)
(internal_module
  body: (statement_block
    (internal_module
      name: (_) @class.name) @class.definition))

; Export statements inside regular namespaces
(internal_module
  body: (statement_block
    (export_statement
      "export" @keyword.modifier
      [
        (class_declaration name: (type_identifier) @class.name type_parameters: (_)? @class.type_parameters)
        (interface_declaration name: (type_identifier) @class.name type_parameters: (_)? @class.type_parameters)
        (enum_declaration name: (identifier) @class.name)
      ]) @class.definition))

; Export statements for functions inside regular namespaces
(internal_module
  body: (statement_block
    (export_statement
      "export" @keyword.modifier
      (function_declaration
        "async"? @keyword.modifier
        name: (identifier) @function.name
        type_parameters: (_)? @function.type_parameters)) @function.definition))

; Export statements for type aliases inside regular namespaces
(internal_module
  body: (statement_block
    (export_statement
      "export" @keyword.modifier
      (type_alias_declaration
        name: (type_identifier) @field.name) @field.definition)))

; Handle nested exports in deeply nested namespaces
(internal_module
  body: (statement_block
    (expression_statement
      (internal_module
        body: (statement_block
          (export_statement
            "export" @keyword.modifier
            [
              (class_declaration name: (type_identifier) @class.name type_parameters: (_)? @class.type_parameters)
              (interface_declaration name: (type_identifier) @class.name type_parameters: (_)? @class.type_parameters)
              (enum_declaration name: (identifier) @class.name)
            ]) @class.definition)))))

; Handle nested exports for functions in deeply nested namespaces
(internal_module
  body: (statement_block
    (expression_statement
      (internal_module
        body: (statement_block
          (export_statement
            "export" @keyword.modifier
            (function_declaration
              "async"? @keyword.modifier
              name: (identifier) @function.name
              type_parameters: (_)? @function.type_parameters)) @function.definition)))))

; Handle nested exports for type aliases in deeply nested namespaces
(internal_module
  body: (statement_block
    (expression_statement
      (internal_module
        body: (statement_block
          (export_statement
            "export" @keyword.modifier
            (type_alias_declaration
              name: (type_identifier) @field.name) @field.definition))))))

; ============================================================================
; FUNCTION SIGNATURES (Overloads)
; ============================================================================

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

; ============================================================================
; CLASS/INTERFACE MEMBERS
; ============================================================================

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
      name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @field.name) @field.definition
)

; Interface properties - uses interface_member_name pattern
(interface_body
  (property_signature
    name: [(property_identifier) (string) (number) (computed_property_name)] @field.name) @field.definition)

; ============================================================================
; ENUM MEMBERS
; ============================================================================

; Enum members for proper enum reconstruction - capture individual members
(enum_body
  ((property_identifier) @field.name) @field.definition)

; Enum members with values (e.g., Green = 3, Active = "active")
(enum_body
  (enum_assignment
    name: (property_identifier) @field.name
    value: (_)) @field.definition)
