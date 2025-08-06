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
  body: (_)) @annotation.definition

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
    declarator: (variable_declarator name: (identifier) @field.name
    )
) @field.definition

; Enum declarations
(enum_declaration
  name: (identifier) @enum.name
  body: (enum_body
    (enum_constant
      name: (identifier) @enum.constant
    )
  )
) @enum.definition

; Annotations to strip
(annotation) @annotation
