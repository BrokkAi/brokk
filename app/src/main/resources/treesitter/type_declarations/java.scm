; Add imports
; Import declarations (optional capture, might be noisy)
; (import_declaration) @import.definition

(class_declaration
  name: (identifier) @class.name
  superclass: (superclass
                (type_identifier) @class.super
                )?
  interfaces: (super_interfaces
                (type_list
                  (type_identifier) @class.interface
                  )
                )?
  )

; Add interfaces

; Add records