; Package declaration
(package_declaration
  [
    (identifier)
    (scoped_identifier)
  ] @package.name
) @package.declaration

; Import declarations (optional capture, might be noisy)
; (import_declaration) @import.definition

; Annotation declarations
(annotation_type_declaration
  name: (identifier) @annotation.name) @annotation.definition

; Class declarations
(class_declaration
  name: (identifier) @class.name) @class.definition

; Interface declarations
(interface_declaration
  name: (identifier) @interface.name) @interface.definition

; Record declarations
(record_declaration
  name: (identifier) @record.name) @record.definition

; Method declarations
(method_declaration
  name: (identifier) @method.name) @method.definition

; Constructor declarations
(constructor_declaration
  name: (identifier) @constructor.name) @constructor.definition

; Field declarations
(field_declaration
  (variable_declarator
    name: (identifier) @field.name)
) @field.definition

; Enum declarations
(enum_declaration
  name: (identifier) @enum.name) @enum.definition

; Enum constants
(enum_constant
  name: (identifier) @field.name) @field.definition

; Record components (implicit fields created by record components)
; Primary: Java grammar where record components are formal_parameters on the 'parameters' field
(record_declaration
  parameters: (formal_parameters
    (formal_parameter
      name: (identifier) @field.name) @field.definition
  )
)

; Annotations to strip
(annotation) @annotation
