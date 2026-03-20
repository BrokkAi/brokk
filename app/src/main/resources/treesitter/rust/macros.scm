; Capture macro invocations like println!(...) or vec![...]
(macro_invocation
  macro: [
    (identifier) @macro.name
    (scoped_identifier) @macro.name
  ]
) @macro.invocation

; Capture derive macro arguments like #[derive(Debug, Is)]
; This must come BEFORE the generic attribute pattern to take precedence
(attribute_item
  (attribute
    (identifier) @_derive
    (#eq? @_derive "derive")
    (token_tree
      [
        (identifier) @macro.derive.name
        (type_identifier) @macro.derive.name
      ]
    )
  )
) @macro.derive

; Capture other attribute macros like #[tokio::main] but NOT #[derive(...)]
; The predicate excludes "derive" to avoid double-matching
(attribute_item
  (attribute
    [
      ((identifier) @macro.attribute.name
        (#not-eq? @macro.attribute.name "derive"))
      (scoped_identifier) @macro.attribute.name
    ]
  )
) @macro.attribute
