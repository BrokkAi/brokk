# Macro Template Language

Macros with the `TEMPLATE` strategy use [Mustache](https://mustache.github.io/) for expansion. 

## Expansion Context

When a template is expanded, the context contains a `code_unit` key. This key points to the `CodeUnit` instance representing the target of the macro expansion.

### Available Properties

You can access the following properties on the `code_unit` object:

- `code_unit.shortname`: The short name of the code unit (e.g., `MyClass.myMethod`).
- `code_unit.identifier`: The simple identifier of the code unit (e.g., `myMethod`).
- `code_unit.children`: A collection of child `CodeUnit` objects (e.g., fields within a class or variants within an enum).

### Iterating over Children

To iterate over the children of a code unit, use a Mustache section. Within the section block, properties of the child `CodeUnit` are accessible directly.

Example:
```mustache
pub struct {{code_unit.identifier}} {
  {{#code_unit.children}}
  pub {{identifier}}: String,
  {{/code_unit.children}}
}
```

In this example, `{{identifier}}` inside the loop refers to the `identifier()` of each child `CodeUnit` in the `children` list.
