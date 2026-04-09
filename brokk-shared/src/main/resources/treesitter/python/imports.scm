; Import statement components for structured parsing
; Each pattern captures @import.declaration for the whole statement plus component captures
; Captures: from <module_name> import <name1>, <name2> as <alias>
(import_from_statement
  module_name: (dotted_name) @import.module
  name: (dotted_name) @import.name) @import.declaration

(import_from_statement
  module_name: (dotted_name) @import.module
  name: (aliased_import
    name: (dotted_name) @import.name
    alias: (identifier) @import.alias)) @import.declaration

; Relative imports: from . import X, from ..parent import Y
(import_from_statement
  module_name: (relative_import) @import.relative
  name: (dotted_name) @import.name) @import.declaration

(import_from_statement
  module_name: (relative_import) @import.relative
  name: (aliased_import
    name: (dotted_name) @import.name
    alias: (identifier) @import.alias)) @import.declaration

; Wildcard imports: from pkg.module import *
(import_from_statement
  module_name: (dotted_name) @import.module.wildcard
  (wildcard_import) @import.wildcard) @import.declaration

; Wildcard imports with relative paths: from . import *
(import_from_statement
  module_name: (relative_import) @import.relative.wildcard
  (wildcard_import) @import.wildcard) @import.declaration

; Captures: import <module>
(import_statement
  name: (dotted_name) @import.name) @import.declaration

(import_statement
  name: (aliased_import
    name: (dotted_name) @import.name
    alias: (identifier) @import.alias)) @import.declaration

; Fallback for simple import statements without named components.
; These must remain after the specific patterns above so that we don't skip structured captures.
(import_statement) @import.declaration
(import_from_statement) @import.declaration
