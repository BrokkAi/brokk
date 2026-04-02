; Package declaration
(package_declaration
  [
    (identifier)
    (scoped_identifier)
  ] @package.name
) @package.definition

; Annotation declarations
(annotation_type_declaration
  name: (identifier) @annotation.name) @annotation.definition

; Class declarations
(class_declaration
  name: (identifier) @class.name) @class.definition

; Interface declarations
(interface_declaration
  name: (identifier) @interface.name) @interface.definition

; Record declarations
(record_declaration
  name: (identifier) @record.name) @record.definition

; Method declarations
(method_declaration
  name: (identifier) @method.name) @method.definition

; Constructor declarations
(constructor_declaration
  name: (identifier) @constructor.name) @constructor.definition

; Field declarations
(field_declaration
  (variable_declarator
    name: (identifier) @field.name)
) @field.definition

; Interface constant declarations
(constant_declaration
  (variable_declarator
    name: (identifier) @field.name)
) @field.definition

; Enum declarations
(enum_declaration
  name: (identifier) @enum.name) @enum.definition

; Enum constants
(enum_constant
  name: (identifier) @field.name) @field.definition

; Record components (implicit fields created by record components)
; Primary: Java grammar where record components are formal_parameters on the 'parameters' field
(record_declaration
  parameters: (formal_parameters
    (formal_parameter
      name: (identifier) @field.name) @field.definition
  )
)

; Lambda expressions
(lambda_expression) @lambda.definition

; Inheritance/type hierarchy captures merged here for single-pass parsing
(class_declaration
  name: (identifier) @type.name
  superclass: (superclass
                [
                  (type_identifier)
                  (scoped_type_identifier)
                ] @type.super
              )?
  interfaces: (super_interfaces
                (type_list
                  [
                    (type_identifier)
                    (scoped_type_identifier)
                  ] @type.super
                )
              )?
) @type.decl

(interface_declaration
  name: (identifier) @type.name
  (extends_interfaces
    (type_list
      [
        (type_identifier)
        (scoped_type_identifier)
        ] @type.super
      )
    )?
) @type.decl

(enum_declaration
  name: (identifier) @type.name
  interfaces: (super_interfaces
                (type_list
                  [
                    (type_identifier)
                    (scoped_type_identifier)
                  ] @type.super
                )
              )?
) @type.decl

(record_declaration
  name: (identifier) @type.name
  interfaces: (super_interfaces
                (type_list
                  [
                    (type_identifier)
                    (scoped_type_identifier)
                  ] @type.super
                )
              )?
) @type.decl

; Annotations to strip
(annotation) @annotation

; Test markers for JUnit/TestNG detection
; Tree-sitter-java represents "@Test" (no args) as marker_annotation, and "@Test(...)" as annotation.
; Filtering for specific test annotation names is performed in the analyzer.
[
  (marker_annotation
    name: [
      (identifier) @test_marker
      (scoped_identifier name: (identifier) @test_marker)
    ]
  )

  (annotation
    name: [
      (identifier) @test_marker
      (scoped_identifier name: (identifier) @test_marker)
    ]
  )
]

; Support for JUnit 3 / legacy TestCase inheritance detection
(superclass
  [
    (type_identifier) @test_marker
    (scoped_type_identifier (type_identifier) @test_marker)
  ]
)
