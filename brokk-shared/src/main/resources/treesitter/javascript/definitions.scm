; Top-level class (non-exported, direct child of program)
(program
  (class_declaration
    name: (identifier) @class.name) @class.definition)

; Top-level function (non-exported, direct child of program)
(program
  (function_declaration
    name: (identifier) @function.name) @function.definition)

; Top-level lexical arrow functions
(program
  (lexical_declaration
    ["const" "let"] @keyword.modifier
    (variable_declarator
      name: (identifier) @arrow_function.name
      value: ((arrow_function) @arrow_function.definition))))

; Class method
(
  (class_declaration
    body: (class_body
            (method_definition
              name: [(property_identifier) (identifier)] @function.name
            ) @function.definition
          )
  )
)

; Top-level non-exported variable assignment
(program
  [
    (lexical_declaration
      ["const" "let"] @keyword.modifier
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
          (new_expression)
        ]
      ) @field.definition
    )
    (variable_declaration
      ["var"] @keyword.modifier
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
          (new_expression)
        ]
      ) @field.definition
    )
  ]
)

; Exported top-level variable assignment
(
  (export_statement
    "export" @keyword.modifier
    declaration: [
      (lexical_declaration
        ["const" "let"] @keyword.modifier
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
            (new_expression)
          ]
        ) @field.definition
      )
      (variable_declaration
        ["var"] @keyword.modifier
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
            (new_expression)
         ]
        ) @field.definition
      )
    ]
  )
)

; Exported top-level class
((export_statement
  "export" @keyword.modifier
  declaration: (class_declaration
    name: (identifier) @class.name
  )
) @class.definition)

; Exported top-level function
((export_statement
  "export" @keyword.modifier
  declaration: (function_declaration
    name: (identifier) @function.name
  )
) @function.definition)

; Exported top-level arrow function
(
  (export_statement
    "export" @keyword.modifier
    declaration: (lexical_declaration
      ["const" "let"] @keyword.modifier
      (variable_declarator
        name: (identifier) @arrow_function.name
        value: ((arrow_function) @arrow_function.definition)
      )
    )
  )
)
