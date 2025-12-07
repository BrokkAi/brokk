; Java reference query: captures method/field uses, qualified Type.member, and constructors.

; -------------
; Method invocations
; -------------

; Unqualified method call: foo(...)
(method_invocation
  name: (identifier) @ref.name
  arguments: (argument_list)) @ref.call

; Qualified method call via expression: receiver.foo(...)
(method_invocation
  object: (identifier) @qual.expr
  name: (identifier) @ref.name
  arguments: (argument_list)) @ref.call

; Qualified method call via chained field access: a.b().c(...)
(method_invocation
  object: (field_access) @qual.expr
  name: (identifier) @ref.name
  arguments: (argument_list)) @ref.call

; Qualified method call via static type: Type.foo(...)
(method_invocation
  object: (type_identifier) @qual.type
  name: (identifier) @ref.name
  arguments: (argument_list)) @ref.call

; this.foo(...)
(method_invocation
  object: (this) @qual.this
  name: (identifier) @ref.name
  arguments: (argument_list)) @ref.call

; super.foo(...)
(method_invocation
  object: (super) @qual.super
  name: (identifier) @ref.name
  arguments: (argument_list)) @ref.call

; -------------
; Field accesses
; -------------

; Instance field: receiver.field
(field_access
  object: (identifier) @qual.expr
  field: (identifier) @ref.name) @ref.field

; Chained receiver: a.b.c
(field_access
  object: (field_access) @qual.expr
  field: (identifier) @ref.name) @ref.field

; Static field: Type.FIELD
(field_access
  object: (type_identifier) @qual.type
  field: (identifier) @ref.name) @ref.field

; this.field
(field_access
  object: (this) @qual.this
  field: (identifier) @ref.name) @ref.field

; super.field
(field_access
  object: (super) @qual.super
  field: (identifier) @ref.name) @ref.field

; -------------
; Constructor calls
; -------------

; new Type(...)
(object_creation_expression
  type: (type_identifier) @ref.ctor.type
  arguments: (argument_list)) @ref.ctor
