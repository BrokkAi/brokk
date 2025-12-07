; Java locals query: captures local variable declarations, enhanced-for loop variables,
; try-with-resources resources, catch parameters, and formal parameters.
; Each capture exposes the variable name (@local.name).

; Local variable declaration - matches "int x = 5;" or "List<String> items = new ArrayList<>();"
(local_variable_declaration
  (variable_declarator
    name: (identifier) @local.name)) @local.declaration

; Formal parameter - matches "(String param)" in method signatures
(formal_parameter
  name: (identifier) @local.name) @local.param

; Enhanced-for loop variable - matches "for (String s : items)"
(enhanced_for_statement
  name: (identifier) @local.name) @local.enhanced_for

; Resource in try-with-resources - matches "try (Resource r = ...)"  
(resource
  name: (identifier) @local.name) @local.resource

; Catch parameter - matches "catch (Exception e)"
(catch_formal_parameter
  name: (identifier) @local.name) @local.catch
