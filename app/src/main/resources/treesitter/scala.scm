; Package declaration
(package_clause
  [
    (package_identifier)
    ] @package.name
  ) @package.declaration

; Import declarations
(import_declaration) @import.declaration

; Class declarations
(class_definition
  name: (identifier) @class.name
  ) @class.definition

(object_definition
  name: (identifier) @object.name
  ) @object.definition

; Trait declarations
(trait_definition
  name: (identifier) @trait.name
  ) @trait.definition

; Enum declarations
(enum_definition
  name: (identifier) @enum.name
  ) @enum.definition

; Method declarations. This will also match secondary constructors which are handled later
(function_definition
  name: (identifier) @method.name
  ) @method.definition

; Primary constructor. This treats a class definition with parameters as a "method".
(class_definition
  name: (identifier) @constructor.name
  class_parameters: (class_parameters)
  ) @constructor.definition

; Field definitions
(class_definition
  body: (template_body
          [
            (val_definition pattern: (identifier) @field.name) @field.definition
            (var_definition pattern: (identifier) @field.name) @field.definition
            ]
          )
  )

(trait_definition
  body: (template_body
          [
            (val_definition pattern: (identifier) @field.name) @field.definition
            (var_definition pattern: (identifier) @field.name) @field.definition
            ]
          )
  )

(object_definition
  body: (template_body
          [
            (val_definition pattern: (identifier) @field.name) @field.definition
            (var_definition pattern: (identifier) @field.name) @field.definition
            ]
          )
  )

; Top-level variables as field definitions
(compilation_unit
  [
    (val_definition pattern: (identifier) @field.name) @field.definition
    (var_definition pattern: (identifier) @field.name) @field.definition
    ]
  )

; Enum cases as "fields"
(enum_definition
  body: (enum_body
          (enum_case_definitions
            (simple_enum_case name: (identifier) @field.name) @field.definition
            )
          )
  )