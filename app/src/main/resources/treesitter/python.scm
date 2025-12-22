; We capture the whole definition node now (@*.definition) for top-level items.
; The name is still useful (@*.name).

; Import statements - capture whole statement for backward compatibility
(import_statement) @import.declaration
(import_from_statement) @import.declaration

; Import statement components for structured parsing
; Captures: from <module_name> import <name1>, <name2> as <alias>
(import_from_statement
  module_name: (dotted_name) @import.module
  name: (dotted_name) @import.name)

(import_from_statement
  module_name: (dotted_name) @import.module
  name: (aliased_import
    name: (dotted_name) @import.name
    alias: (identifier) @import.alias))

; Relative imports: from . import X, from ..parent import Y
(import_from_statement
  module_name: (relative_import) @import.relative
  name: (dotted_name) @import.name)

(import_from_statement
  module_name: (relative_import) @import.relative
  name: (aliased_import
    name: (dotted_name) @import.name
    alias: (identifier) @import.alias))

; Wildcard imports: from pkg.module import *
(import_from_statement
  module_name: (dotted_name) @import.module.wildcard
  (wildcard_import) @import.wildcard)

; Wildcard imports with relative paths: from . import *
(import_from_statement
  module_name: (relative_import) @import.relative.wildcard
  (wildcard_import) @import.wildcard)

; Captures: import <module>
(import_statement
  name: (dotted_name) @import.name)

(import_statement
  name: (aliased_import
    name: (dotted_name) @import.name
    alias: (identifier) @import.alias))

; Class definition (captures at any nesting level initially, Java logic sorts out hierarchy)
(class_definition
  name: (identifier) @class.name) @class.definition

; Decorated class definition (any nesting level)
(decorated_definition
  definition: (class_definition
    name: (identifier) @class.name)
) @class.definition

; Type hierarchy: capture superclasses for inheritance resolution
(class_definition
  name: (identifier) @type.name
  superclasses: (argument_list
    (identifier) @type.super)?
) @type.decl

; Function definition at module level (direct child of module)
(module
  (function_definition
    name: (identifier) @function.name) @function.definition)

; Decorated function definition at module level
(module
  (decorated_definition
    definition: (function_definition
      name: (identifier) @function.name)
  ) @function.definition)

; Function definitions inside module-level control flow (conditionals, exception handling, context managers, loops)
(module
  [(if_statement (block (function_definition name: (identifier) @function.name) @function.definition))
   (if_statement (else_clause (block (function_definition name: (identifier) @function.name) @function.definition)))
   (if_statement (elif_clause (block (function_definition name: (identifier) @function.name) @function.definition)))
   (try_statement (block (function_definition name: (identifier) @function.name) @function.definition))
   (try_statement (except_clause (block (function_definition name: (identifier) @function.name) @function.definition)))
   (try_statement (else_clause (block (function_definition name: (identifier) @function.name) @function.definition)))
   (try_statement (finally_clause (block (function_definition name: (identifier) @function.name) @function.definition)))
   (with_statement (block (function_definition name: (identifier) @function.name) @function.definition))
   (for_statement (block (function_definition name: (identifier) @function.name) @function.definition))
   (while_statement (block (function_definition name: (identifier) @function.name) @function.definition))])

; Decorated function definitions inside module-level control flow
(module
  [(if_statement (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition))
   (if_statement (else_clause (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition)))
   (if_statement (elif_clause (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition)))
   (try_statement (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition))
   (try_statement (except_clause (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition)))
   (try_statement (else_clause (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition)))
   (try_statement (finally_clause (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition)))
   (with_statement (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition))
   (for_statement (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition))
   (while_statement (block (decorated_definition
                           definition: (function_definition name: (identifier) @function.name)
                        ) @function.definition))])



; Method definition (function_definition directly inside a class's body block)
; This also captures static methods if they are structured as function_definition within class body.
(class_definition
  body: (block
    (function_definition
      name: (identifier) @function.name) @function.definition
  )
)

; Decorated method definition (function_definition directly inside a class's body block, with decorators)
; The decorated_definition node itself is captured as @function.definition for consistency,
; and its inner function_definition's name is @function.name.
(class_definition
  body: (block
    (decorated_definition
      definition: (function_definition
        name: (identifier) @function.name
      )
    ) @function.definition
  )
)

; Top-level variable assignments:
; - Simple: VAR = x
; - Annotated: VAR: Type = x or VAR: Type
; - Tuple unpacking: A, B = values
; - Multi-target: FOO = BAR = 42 (captures nested assignment targets)
(module
  (expression_statement
    [(assignment left: (identifier) @field.name)
     (assignment left: (pattern_list (identifier) @field.name))
     (assignment right: (assignment left: (identifier) @field.name))] @field.definition))

; Variable assignments inside module-level control flow (conditionals, exception handling, context managers, loops)
; Covers simple, tuple unpacking, and multi-target assignments in one consolidated pattern
(module
  [(if_statement (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition)))
   (if_statement (else_clause (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition))))
   (if_statement (elif_clause (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition))))
   (try_statement (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition)))
   (try_statement (except_clause (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition))))
   (try_statement (else_clause (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition))))
   (try_statement (finally_clause (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition))))
   (with_statement (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition)))
   (for_statement (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition)))
   (while_statement (block (expression_statement [(assignment left: (identifier) @field.name) (assignment left: (pattern_list (identifier) @field.name)) (assignment right: (assignment left: (identifier) @field.name))] @field.definition)))])
