; Top-level class (non-exported, direct child of program)
(program
  (class_declaration
    name: (identifier) @class.name) @class.definition)

; Top-level function (non-exported, direct child of program)
(program
  (function_declaration
    name: (identifier) @function.name) @function.definition)

; Top-level const/let/var MyComponent = () => { ... }
; This needs to be a direct child of program, or within a block that is a direct child of program.
; For simplicity, anchoring to program for top-level lexical arrow functions.
(program
  (lexical_declaration
    (variable_declarator
      name: (identifier) @function.name
      value: ((arrow_function) @function.definition))))

; Top-level non-exported const/let/var variable assignment
; ((lexical_declaration
;  (variable_declarator
;    name: (identifier) @field.name
;    value: _ ; Ensures it's an assignment with a value
;  )
; ) @field.definition) ; Capture the entire lexical_declaration as the definition

; Class method
; Captures method_definition within a class_body. Name can be property_identifier or identifier.
(
  (class_declaration
    body: (class_body
            (method_definition
              name: [(property_identifier) (identifier)] @function.name
            ) @function.definition
          )
  )
)

; Top-level non-exported const/let/var variable assignment
; Catches 'const x = 1;', 'let y = "foo";', 'var z = {};' etc.
; but not 'const F = () => ...' or 'const C = class ...'
; Anchoring to program for top-level variables.
(program
  [
    (lexical_declaration
      (variable_declarator
        name: (identifier) @field.name
        value: [
          (string)
          (template_string)
          (number)
          (regex)
          (true)
          (false)
          (null)
          (undefined)
          (object)
          (array)
          (identifier)
          (binary_expression)
          (unary_expression)
          (member_expression)
          (subscript_expression)
          (call_expression)
          (jsx_element)
          (jsx_self_closing_element)
        ]
      ) @field.definition
    )
    (variable_declaration
      (variable_declarator
        name: (identifier) @field.name
        value: [
          (string)
          (template_string)
          (number)
          (regex)
          (true)
          (false)
          (null)
          (undefined)
          (object)
          (array)
          (identifier)
          (binary_expression)
          (unary_expression)
          (member_expression)
          (subscript_expression)
          (call_expression)
          (jsx_element)
          (jsx_self_closing_element)
        ]
      ) @field.definition
    )
  ]
)

; Exported top-level const/let/var variable assignment
; Catches 'export const x = 1;' etc.
(
  (export_statement
    declaration: [
      (lexical_declaration
        (variable_declarator
          name: (identifier) @field.name
          value: [
            (string)
            (template_string)
            (number)
            (regex)
            (true)
            (false)
            (null)
            (undefined)
            (object)
            (array)
            (identifier)
            (binary_expression)
            (unary_expression)
            (member_expression)
            (subscript_expression)
            (call_expression)
            (jsx_element)
            (jsx_self_closing_element)
          ]
        ) @field.definition
      )
      (variable_declaration
        (variable_declarator
          name: (identifier) @field.name
          value: [
            (string)
            (template_string)
            (number)
            (regex)
            (true)
            (false)
            (null)
            (undefined)
            (object)
            (array)
            (identifier)
            (binary_expression)
            (unary_expression)
            (member_expression)
            (subscript_expression)
            (call_expression)
            (jsx_element)
            (jsx_self_closing_element)
          ]
        ) @field.definition
      )
    ]
  )
)

; Exported top-level class
((export_statement
  declaration: (class_declaration
    name: (identifier) @class.name
  )
) @class.definition)

; Exported top-level function
((export_statement
  declaration: (function_declaration
    name: (identifier) @function.name
  )
) @function.definition)

; Exported top-level arrow function (e.g., export const Foo = () => {})
(
  (export_statement
    declaration: (lexical_declaration
      (variable_declarator
        name: (identifier) @function.name
        value: ((arrow_function) @function.definition) ; Capture the arrow_function itself as the definition
      )
    )
  )
)

; Capture import statements to be part of the module preamble
(import_statement) @module.import_statement

; Ignore decorators / modifiers for now
