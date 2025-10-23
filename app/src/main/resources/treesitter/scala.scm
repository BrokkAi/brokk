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

(object_definition
  name: (identifier) @class.name
  ) @object.definition

; Trait declarations
(trait_definition
  name: (identifier) @class.name
  ) @trait.definition

; Enum declarations
(enum_definition
  name: (identifier) @class.name
  ) @enum.definition

; Method declarations
(function_definition
  name: (identifier) @method.name) @method.definition
