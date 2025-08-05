; Package declaration
(package_declaration) @package.definition

; Import declarations (optional capture, might be noisy)
; (import_declaration) @import.definition

; Annotation declarations
(annotation_type_declaration
  body: (_)) @annotation.definition

; Class declarations
(class_declaration
  body: (_)) @class.definition

; Interface declarations
(interface_declaration
  body: (_)) @interface.definition

; Method declarations
(method_declaration
  body: (_)?) @method.definition

; Field declarations
(field_declaration) @field.definition

; Enum declarations
(enum_declaration
  body: (enum_body
    (enum_constant)*)) @enum.definition

; Annotations to strip
(annotation) @annotation
