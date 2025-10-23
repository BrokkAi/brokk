; Package declaration
(package_clause
  [
    (package_identifier)
    ] @package.name
  ) @package.declaration

; Class declarations
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

; Method declarations. This will also match secondary constructors which are handled later
(function_definition
  name: (identifier) @method.name
  ) @method.definition

; Primary constructor. This treats a class definition with parameters as a "method".
(class_definition
  name: (identifier) @method.name
  class_parameters: (class_parameters)
  ) @constructor.definition

