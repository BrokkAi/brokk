; We capture the whole definition node now (@*.definition) for top-level items.
; The name is still useful (@*.name).

; Class definitions:
; - Module-level classes
; - Classes nested directly inside class bodies
; - Decorated classes at module level
; - Decorated classes nested inside class bodies
; Note: We intentionally DO NOT capture classes inside functions.

; Module-level class definition
(module
  (class_definition
    name: (identifier) @class.name) @class.definition)

; Nested class definition (class inside a class body block)
(class_definition
  body: (block
    (class_definition
      name: (identifier) @class.name) @class.definition
  )
)

; Decorated class definition at module level
(module
  (decorated_definition
    definition: (class_definition
      name: (identifier) @class.name
    )
  ) @class.definition
)

; Decorated nested class definition (inside a class body block)
(class_definition
  body: (block
    (decorated_definition
      definition: (class_definition
        name: (identifier) @class.name
      )
    ) @class.definition
  )
)

; Function definition at module level (direct child of module)
(module
  (function_definition
    name: (identifier) @function.name) @function.definition)

; Method definitions (restricted to module/class scopes; exclude classes inside functions)

; Module-level class methods
(module
  (class_definition
    body: (block
      (function_definition
        name: (identifier) @function.name) @function.definition
    )
  )
)

; Decorated module-level class methods
(module
  (class_definition
    body: (block
      (decorated_definition
        definition: (function_definition
          name: (identifier) @function.name
        )
      ) @function.definition
    )
  )
)

; Nested class methods (class inside a class body), both under module scope
(module
  (class_definition
    body: (block
      (class_definition
        body: (block
          (function_definition
            name: (identifier) @function.name) @function.definition
        )
      )
    )
  )
)

; Decorated nested class methods (class inside a class body), under module scope
(module
  (class_definition
    body: (block
      (class_definition
        body: (block
          (decorated_definition
            definition: (function_definition
              name: (identifier) @function.name
            )
          ) @function.definition
        )
      )
    )
  )
)

; Top-level variable assignment
(module
  (expression_statement
    (assignment
      left: (identifier) @field.name) @field.definition))
