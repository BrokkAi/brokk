; Parse package
(package_declaration
  [
    (scoped_identifier)
    (identifier)
    ] @package.path
  )

; Parse imports
(import_declaration
  [
    (scoped_identifier)
    (identifier)
    ] @import.path
  )

(class_declaration
  name: (identifier) @type.name
  superclass: (superclass
                (type_identifier) @type.super
                )?
  interfaces: (super_interfaces
                (type_list
                  (type_identifier) @type.super
                  )
                )?
  ) @type.decl

(interface_declaration
  name: (identifier) @type.name
  interfaces: (super_interfaces
                (type_list
                  (type_identifier) @type.super
                  )
                )?
  ) @type.decl

(enum_declaration
  name: (identifier) @type.name
  interfaces: (super_interfaces
                (type_list
                  (type_identifier) @type.super
                  )
                )?
  ) @type.decl

(record_declaration
  name: (identifier) @type.name
  interfaces: (super_interfaces
                (type_list
                  (type_identifier) @type.super
                  )
                )?
  ) @type.decl