; Namespace declarations
(namespace_definition
  name: (_) @namespace.name) @namespace.definition

; Class declarations
(template_declaration
  (class_specifier
    name: (type_identifier) @class.name)) @class.definition
(class_specifier
  name: (type_identifier) @class.name) @class.definition

; Struct declarations
(template_declaration
  (struct_specifier
    name: (type_identifier) @struct.name)) @struct.definition
(struct_specifier
  name: (type_identifier) @struct.name) @struct.definition

; Union declarations
(union_specifier
  name: (type_identifier) @union.name) @union.definition

; Enum declarations
(enum_specifier
  name: (type_identifier) @enum.name) @enum.definition

; Enumerators (enum members)
(enumerator
  name: (identifier) @enumerator.name) @field.definition

; Global function definitions (non-method functions)
(function_definition
  declarator: (function_declarator
    declarator: (_) @function.name) @function.declarator) @function.definition

; Global function declarations - specific function declarator patterns (prototypes)
(translation_unit
  (declaration
    declarator: (function_declarator
      declarator: (_) @function.name)) @function.definition)

; Global variable declarations - handle both plain and extern declarations
(translation_unit
  (declaration
    declarator: (identifier) @variable.name) @variable.definition)

; Global variable declarations with init_declarator
(translation_unit
  (declaration
    declarator: (init_declarator
      declarator: (identifier) @variable.name)) @variable.definition)

; Global variable declarations with storage class specifiers (extern, static, etc.)
(translation_unit
  (declaration
    type: (_)
    declarator: (identifier) @variable.name) @variable.definition)

; Field declarations in classes and structs
(field_declaration
  declarator: [
                (pointer_declarator (field_identifier) @field.name)
                (field_identifier) @field.name
                ]) @field.definition

; Method declarations within classes - field_declaration with function_declarator
(field_declaration
  declarator: (function_declarator
    declarator: (field_identifier) @method.name)) @method.definition

; Inline method definitions within classes (methods with bodies)
(function_definition
  declarator: (function_declarator
    declarator: (field_identifier) @method.name)) @method.definition

; Constructor declarations within class bodies - capture the declaration node directly
(field_declaration_list
  (declaration
    declarator: (function_declarator
      declarator: (identifier) @constructor.name)) @constructor.definition)

; Typedef declarations
(type_definition
  declarator: (_) @typedef.name) @typedef.definition

; Using declarations (type aliases)
(alias_declaration
  name: (type_identifier) @using.name) @using.definition

; Access specifiers
(access_specifier) @access.specifier

; Test markers (GoogleTest/Catch2 style test macro invocations)
(function_definition
  declarator: (function_declarator
    declarator: [
                  (identifier) @test.marker
                  (field_identifier) @test.marker
                  ])
  (#match? @test.marker "^(TEST|TEST_F|TEST_P|TYPED_TEST|TYPED_TEST_P|TEST_CASE|SCENARIO|BOOST_AUTO_TEST_CASE|BOOST_FIXTURE_TEST_CASE|BOOST_DATA_TEST_CASE|TEST_CLASS|TEST_METHOD)$"))

(call_expression
  function: (identifier) @test.marker
  (#match? @test.marker "^(TEST|TEST_F|TEST_P|TYPED_TEST|TYPED_TEST_P|TEST_CASE|SCENARIO|BOOST_AUTO_TEST_CASE|BOOST_FIXTURE_TEST_CASE|BOOST_DATA_TEST_CASE|TEST_CLASS|TEST_METHOD)$"))

; Fallback capture: require invocation structure, not a bare identifier.
; Some parser shapes expose macro names as bare identifiers. Structure is validated
; in CppAnalyzer before accepting a marker (must map to invocation/body context).
((identifier) @test.marker
  (#match? @test.marker "^(TEST|TEST_F|TEST_P|TYPED_TEST|TYPED_TEST_P|TEST_CASE|SCENARIO|BOOST_AUTO_TEST_CASE|BOOST_FIXTURE_TEST_CASE|BOOST_DATA_TEST_CASE|TEST_CLASS|TEST_METHOD)$"))
