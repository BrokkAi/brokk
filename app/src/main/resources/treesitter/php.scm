; Namespace definition
(namespace_definition
  name: (namespace_name) @namespace.name) @namespace.definition

; Class definition
(class_declaration
  name: (name) @class.name) @class.definition

; Interface definition
(interface_declaration
  name: (name) @interface.name) @interface.definition ; Treat as class-like

; Trait definition
(trait_declaration
  name: (name) @trait.name) @trait.definition ; Treat as class-like

; Global function definition
(function_definition
  name: (name) @function.name) @function.definition

; Method definitions (covers methods in classes, interfaces, and traits)
; The TreeSitterAnalyzer will determine the parent class/interface/trait.
(method_declaration
  name: (name) @function.name
) @function.definition


; Class property / field
; @field.definition is the property_declaration node.
; @field.name is the 'name' node inside 'variable_name' which is a field of 'property_element'.
(class_declaration
  body: (declaration_list
    (property_declaration
      (property_element
        name: (variable_name (name) @field.name)) ; Capture the innermost 'name' for the simple name
    ) @field.definition
  )
)

; Class constant
; @field.definition is the const_declaration node within a class.
; @field.name is the 'name' node inside 'const_element'.
(class_declaration
  body: (declaration_list
    (const_declaration
      (const_element (name) @field.name) ; Capture the 'name' child of const_element
    ) @field.definition
  )
)

; Top-level constant
; @field.definition is the const_declaration node.
; @field.name is the 'name' node inside 'const_element'.
(const_declaration
  (const_element (name) @field.name)
) @field.definition ; Correctly associate @field.definition with const_declaration

; Attributes (PHP 8+)
; Captures attribute_group that might precede class, method, or property.
; (attribute_group) @attribute.definition  ; Temporarily commented out to isolate TSQueryErrorNodeType

; TODO: The query logic needs to ensure attributes are associated with the
; correct subsequent declaration. For now, this just captures them.
; The TreeSitterAnalyzer's decorator handling might pick these up if they
; are siblings before the actual definition node.

; TODO: Add captures for use statements if needed for import analysis.
; (use_declaration (use_clause (name_identifier) @import.name))
