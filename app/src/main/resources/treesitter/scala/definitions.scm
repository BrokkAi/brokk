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
  name: (identifier) @object.name
  ) @object.definition

; Trait declarations
(trait_definition
  name: (identifier) @trait.name
  ) @trait.definition

; Enum declarations
(enum_definition
  name: (identifier) @enum.name
  ) @enum.definition

; Method declarations. This will also match secondary constructors which are handled later
(function_definition
  name: (identifier) @method.name
  ) @method.definition

; Primary constructor. This treats a class definition with parameters as a "method".
(class_definition
  name: (identifier) @constructor.name
  class_parameters: (class_parameters)
  ) @constructor.definition

; Field definitions
;
; IMPORTANT: TreeSitterAnalyzer.collectDefinitions stores captures per match via putIfAbsent,
; so multi-name patterns must produce one match per identifier. For Scala multi-declarators
; like "var x, y: Int = 1", the grammar provides:
;   (var_definition pattern: (identifiers (identifier) (identifier)) type: ... value: ...)
; These patterns match a single (identifier) @field.name within the identifiers list,
; and Tree-sitter will emit a separate match per identifier.
(class_definition
  body: (template_body
          [
            (val_definition
              pattern: (identifier) @field.name
              ) @field.definition

            (val_definition
              pattern: (identifiers (identifier) @field.name)
              ) @field.definition

            (var_definition
              pattern: (identifier) @field.name
              ) @field.definition

            (var_definition
              pattern: (identifiers (identifier) @field.name)
              ) @field.definition
            ]
          )
  )

(trait_definition
  body: (template_body
          [
            (val_definition
              pattern: (identifier) @field.name
              ) @field.definition

            (val_definition
              pattern: (identifiers (identifier) @field.name)
              ) @field.definition

            (var_definition
              pattern: (identifier) @field.name
              ) @field.definition

            (var_definition
              pattern: (identifiers (identifier) @field.name)
              ) @field.definition
            ]
          )
  )

(object_definition
  body: (template_body
          [
            (val_definition
              pattern: (identifier) @field.name
              ) @field.definition

            (val_definition
              pattern: (identifiers (identifier) @field.name)
              ) @field.definition

            (var_definition
              pattern: (identifier) @field.name
              ) @field.definition

            (var_definition
              pattern: (identifiers (identifier) @field.name)
              ) @field.definition
            ]
          )
  )

; Top-level variables as field definitions
(compilation_unit
  [
    (val_definition
      pattern: (identifier) @field.name
      ) @field.definition

    (val_definition
      pattern: (identifiers (identifier) @field.name)
      ) @field.definition

    (var_definition
      pattern: (identifier) @field.name
      ) @field.definition

    (var_definition
      pattern: (identifiers (identifier) @field.name)
      ) @field.definition
    ]
  )

; Enum cases as "fields"
(enum_definition
  body: (enum_body
          (enum_case_definitions
            (simple_enum_case name: (identifier) @field.name) @field.definition
            )
          )
  )

; Test markers for JUnit/ScalaTest detection
; Filtering (e.g. for specific annotation names or keywords) is performed in the analyzer
(annotation
  name: (type_identifier) @test.annotation
  )

; ScalaTest FunSuite: test("description") { ... }
(call_expression
  function: (identifier) @test.call
  )

; ScalaTest FlatSpec: "test" should "work" in { ... }
(infix_expression
  operator: (identifier) @test.infix
  )