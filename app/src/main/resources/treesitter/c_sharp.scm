; Class: Capture node and its name field
(class_declaration
  name: (identifier) @class.name) @class.definition

; Interface: Capture node and its name field. Uses class.* captures for consistency.
(interface_declaration
  name: (identifier) @class.name) @class.definition

; Struct: Capture node and its name field. Uses class.* captures for consistency.
(struct_declaration
  name: (identifier) @class.name) @class.definition

; Record types (record_declaration, record_struct_declaration) if captured, should also use @class.name / @class.definition.

; Function (Method): Capture node and its name field
(method_declaration
  name: (identifier) @function.name) @function.definition

; Field: Capture the field.declaration node and the identifier within variable_declarator's name field
(field_declaration
  (variable_declaration
    (variable_declarator
       name: (identifier) @field.name))) @field.definition

; Field (Property): Capture node and its name field
(property_declaration
  name: (identifier) @field.name) @field.definition

; Constructor: Capture the whole node (name extraction handled in Java)
(constructor_declaration
  name: (identifier) @constructor.name) @constructor.definition

; Attributes/Annotations to ignore in skeleton map
(attribute_list) @annotation
