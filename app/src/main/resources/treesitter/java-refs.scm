; Java reference query: captures method/field uses, qualified Type.member, and constructors.

; Method invocations - unqualified: foo(...)
(method_invocation
  name: (identifier) @ref.name
  arguments: (argument_list)) @ref.call

; Field accesses: receiver.field or Type.FIELD
(field_access
  field: (identifier) @ref.name) @ref.field

; Constructor calls: new Type(...)
(object_creation_expression
  type: (type_identifier) @ref.ctor.type
  arguments: (argument_list)) @ref.ctor
