; Package declaration
(package_clause
  [
    (package_identifier)
    ] @package.name
  ) @package.declaration

; Class declarations TODO: Constructors will be in here too
(class_definition
  name: (identifier) @class.name
  ) @class.definition

; Trait declarations
(trait_definition
  name: (identifier) @class.name
  ) @trait.definition
