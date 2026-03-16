; Capture macro invocations like println!(...) or vec![...]
(macro_invocation
  macro: [
    (identifier) @macro.name
    (scoped_identifier) @macro.name
  ]
) @macro.invocation

; Capture attribute macros and derives like #[derive(Debug)] or #[tokio::main]
(attribute_item
  (attribute
    [
      (identifier) @macro.attribute.name
      (scoped_identifier) @macro.attribute.name
    ]
  )
) @macro.attribute
