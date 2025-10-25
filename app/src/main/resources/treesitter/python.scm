; We capture the whole definition node now (@*.definition) for top-level items.
; The name is still useful (@*.name).

; Class definition (captures at any nesting level initially, Java logic sorts out hierarchy)
(class_definition
  name: (identifier) @class.name) @class.definition

; Function definition at module level (direct child of module)
(module
  (function_definition
    name: (identifier) @function.name) @function.definition)

; Method definition (function_definition directly inside a class's body block)
; This also captures static methods if they are structured as function_definition within class body.
(class_definition
  body: (block
    (function_definition
      name: (identifier) @function.name) @function.definition
  )
)

; Decorated method definition with attribute decorators (e.g., @property, @name.setter)
; Note: Property setters are filtered in PythonAnalyzer.shouldSkipNode() to avoid duplicates
(class_definition
  body: (block
    (decorated_definition
      (decorator
        (attribute))
      definition: (function_definition
        name: (identifier) @function.name
      )
    ) @function.definition
  )
)

; Decorated method definition with simple identifier decorators (e.g., @staticmethod, @classmethod)
; No filtering needed for these since they don't create duplicates.
(class_definition
  body: (block
    (decorated_definition
      (decorator
        (identifier))
      definition: (function_definition
        name: (identifier) @function.name
      )
    ) @function.definition
  )
)

; Top-level variable assignment
(module
  (expression_statement
    (assignment
      left: (identifier) @field.name) @field.definition))
