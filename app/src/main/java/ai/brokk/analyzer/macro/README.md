# Macro Template Language

Macros with the `TEMPLATE` strategy use [Mustache](https://mustache.github.io/) for expansion. 

## Expansion Context

When a template is expanded, the context contains the following top-level keys:

- `code_unit`: The `CodeUnit` instance representing the target of the macro expansion.
- `children`: A collection of child `CodeUnit` objects (e.g., fields within a class or variants within an enum).

### Available Properties

You can access the following properties on the `code_unit` object:

- `code_unit.shortname`: The short name of the code unit (e.g., `MyClass.myMethod`).
- `code_unit.identifier`: The simple identifier of the code unit (e.g., `myMethod`).

### Iterating over Children

To iterate over the children of a code unit, use the top-level `children` list in a Mustache section. Within the section block, properties of the child `CodeUnit` are accessible directly.

Example:
```mustache
pub struct {{code_unit.identifier}} {
  {{#children}}
  pub {{identifier}}: String,
  {{/children}}
}
```

In this example, `{{identifier}}` inside the loop refers to the `identifier()` of each child `CodeUnit` in the `children` list.
