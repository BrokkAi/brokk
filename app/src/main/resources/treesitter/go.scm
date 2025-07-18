(function_declaration
  name: (identifier) @function.name) @function.definition;

; Matches type declarations for structs and interfaces
(type_declaration
  (type_spec
    name: (type_identifier) @type.name ; Capture the type_identifier as the name
    type: (_) @type.kind ; Capture the specific type (struct_type, interface_type)
  )
) @type.definition

(var_declaration
  (var_spec
    name: (identifier) @variable.name
    ; type: (_) @variable.type ; optional for future use
    ; value: (_) @variable.value ; optional for future use
  ) @variable.definition ; @variable.definition now points to var_spec
)

(const_declaration
  (const_spec
    name: (identifier) @constant.name
    ; type: (_) @constant.type ; optional for future use
    ; value: (_) @constant.value ; optional for future use
  ) @constant.definition ; @constant.definition now points to const_spec
)

(method_declaration
  receiver: (parameter_list
    (parameter_declaration
      type: [ (type_identifier) @method.receiver.type (pointer_type (type_identifier) @method.receiver.type) ]
    )
  )
  name: (field_identifier) @method.name
) @method.definition

; Captures field declarations within a struct
(struct_type
  (field_declaration_list
    (field_declaration
      name: (field_identifier) @struct.field.name
      ; type: (_) @struct.field.type ; Optional: useful for future type analysis
    ) @struct.field.definition
  )
)

; Captures method specifications within an interface type
(interface_type
  (method_elem
    name: (field_identifier) @interface.method.name
    parameters: (parameter_list) @interface.method.parameters ; parameter_list is the type of the node for the 'parameters' field
    ; result: (_) @interface.method.result ; Result is optional, removing from query to ensure match
  ) @interface.method.definition
)
