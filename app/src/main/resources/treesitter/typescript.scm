; ============================================================================
; TYPESCRIPT TREESITTER QUERY PATTERNS
; ============================================================================
; This file defines patterns for extracting code structure from TypeScript files.
; The patterns identify and capture various TypeScript constructs including:
; - Classes, interfaces, enums, and namespaces
; - Functions, methods, and arrow functions
; - Variables, fields, and properties
; - Type aliases and ambient declarations
;
; Capture names used:
; - @type.definition: Class-like structures (classes, interfaces, enums, namespaces)
; - @function.definition: Function-like structures (functions, methods, arrow functions)
; - @value.definition: Field-like structures (variables, properties, enum members)
; - @typealias.definition: Type alias declarations
; - @keyword.modifier: Keywords like export, async, static, etc.
; - Various .name captures: Names of the captured elements
; - @class.type_parameters, @function.type_parameters: Generic type parameters

; ============================================================================
; EXPORTED DECLARATIONS
; ============================================================================

; Export statements for class-like declarations
; Matches: export class Foo<T> { }, export default class Bar { }, etc.
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
; Matches: export function foo() { }, export async function bar() { }, export default function baz() { }
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (function_declaration
    "async"? @keyword.modifier
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters)) @function.definition

; Export statements for function signatures (overloads)
; Matches: export function foo(x: string): void; (signature only, no body)
(export_statement
  "export" @keyword.modifier
  (function_signature
    "async"? @keyword.modifier
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters)) @function.definition

; Export statements for type aliases
; Matches: export type MyType = string, export default type DefaultType<T> = T[]
(export_statement
  "export" @keyword.modifier
  "default"? @keyword.modifier
  (type_alias_declaration
    name: (type_identifier) @typealias.name) @typealias.definition)

; Export variable declarations (unified for all value types)
; Matches: export const myFunc = () => {}, export let PI = 3.14, export var x = 1
; Arrow functions will be reclassified as FUNCTION_LIKE by TypescriptAnalyzer.refineSkeletonType
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
; Matches: export const myFunc = () => {}, export let handler = async () => {}
; Note: Captures arrow functions with specific @arrow_function capture names (distinct from @value)
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

; ============================================================================
; TOP-LEVEL DECLARATIONS (NON-EXPORTED)
; ============================================================================

; Top-level class-like declarations (non-export)
; Matches: class Foo { }, interface Bar { }, enum Baz { }, namespace NS { }
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
; Matches: function foo() { }, async function bar() { }
(program
  (function_declaration
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters) @function.definition)

; Top-level function signatures (non-export, for overloads)
; Matches: function foo(x: string): void; (signature only, typically followed by implementation)
(program
  (function_signature
    name: (identifier) @function.name
    type_parameters: (_)? @function.type_parameters) @function.definition)

; Top-level type aliases (non-export)
; Matches: type MyType = string, type Generic<T> = T[]
(program
  (type_alias_declaration
    name: (type_identifier) @typealias.name) @typealias.definition)

; Top-level variable declarations (unified for all value types)
; Matches: const myFunc = () => {}, let PI = 3.14, var x = 1
; Arrow functions will be reclassified as FUNCTION_LIKE by TypescriptAnalyzer.refineSkeletonType
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

; ============================================================================
; AMBIENT DECLARATIONS
; ============================================================================

; Ambient declarations
; Matches: declare var $: any, declare function fetch(): Promise<any>, declare namespace ThirdParty { }
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
; Matches content inside: declare namespace NS { function foo(): void; interface Bar { } }
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

; Any namespace wrapped in expression_statement (covers nested cases)
; Matches: nested namespaces that appear as expression statements
; Note: This catches namespaces at any nesting level
(expression_statement
  (internal_module name: (_) @type.name) @type.definition)


; ============================================================================
; CLASS AND INTERFACE MEMBERS
; ============================================================================

; Method definitions in classes
; Matches: public async foo<T>(): void { }, private static bar() { }, @decorator baz() { }
; Note: Decorators are captured separately to avoid issues with pattern matching
(method_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
  type_parameters: (_)? @function.type_parameters) @function.definition

; Interface method signatures
; Matches: method signatures in interfaces: foo(): void, bar<T>(x: T): T
(method_signature
  name: [(property_identifier) (string) (number) (computed_property_name)] @function.name
  type_parameters: (_)? @function.type_parameters) @function.definition

; Constructor signatures in interfaces
; Matches: new (x: string): MyClass inside interface definitions
; Note: Uses #set! to provide a default name since constructor signatures don't have explicit names
(construct_signature
  type_parameters: (_)? @function.type_parameters) @function.definition (#set! "default_name" "new")

; Abstract method signatures in abstract classes
; Matches: abstract doWork(): void, abstract compute<T>(x: T): T
(abstract_method_signature
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @function.name
  type_parameters: (_)? @function.type_parameters) @function.definition

; Class fields
(public_field_definition
  name: [(property_identifier) (private_property_identifier) (string) (number) (computed_property_name)] @value.name) @value.definition

; Interface properties (only direct children of interface_body)
(interface_body
  (property_signature
    name: [(property_identifier) (string) (number) (computed_property_name)] @value.name) @value.definition)

; Index signatures in interfaces
; Matches: [key: string]: any, [index: number]: string
(interface_body
  (index_signature) @value.definition (#set! "default_name" "[index]"))

; Call signatures in interfaces
; Matches: (x: number): string, (): void
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
