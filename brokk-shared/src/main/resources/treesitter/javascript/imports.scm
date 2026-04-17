; Capture import statements
(import_statement) @import.declaration

; CommonJS require statements
(call_expression
  function: (identifier) @_require_func
  arguments: (arguments (string) @_require_path)
) @module.require_call
