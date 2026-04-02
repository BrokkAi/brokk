# Context Subsystem Guide

## Core Concepts

1. **Context is immutable**: All mutation methods (`addFragments`, `removeFragments`, `withParsedOutput`, etc.) return a new `Context` instance with a fresh UUID. A Context cannot be modified in-place.
1. **Context identity**: Two Contexts are equal iff they share the same UUID (`id`). Use `workspaceContentEquals()` to compare semantic content across different Context instances.
1. **Fragment asynchronicity**: Some Fragments (especially UsageFragment) can be expensive and slow to materialize text() and other ComputedValues. Methods that block for these values to be ready (usually via join()) should be annotated with @org.jetbrains.annotations.Blocking. If you need to wait for all current Fragments to be materialized before doing something that changes state-on-disk, use Context.awaitContentsAreComputed.
1. **Fragment identity (`hasSameSource`)**: Use `hasSameSource()` to check if two fragments represent the same underlying resource (file, symbol, etc.) regardless of content differences. This is distinct from `contentEquals()` which compares actual content, and from `equals()` which is just object identity. Since contentEquals must wait for materialization it is much more expensive than the others, don't use it unless semantically necessary. (It is probably not necessary unless you're editing the Context-diffing code.)
1. **ContextFragment is also immutable**; use refreshCopy() to get a new copy if the backing store may have changed. NB: because of the above, even if nothing has changed equals() will usually return false. (Exception: a few fragment types like PasteFragment that have no external source of truth).

## Interacting with the GUI

- **ComputedSubscription**: Use `ComputedSubscription.bind(fragment, owner, uiUpdate)` to safely subscribe to async fragment completion. Subscriptions auto-dispose when the owning component is removed from the ancestor hierarchy. `bind()` also guards against duplicate subscriptions for the same `ComputedValue` on the same component.

## DiffService vs ContextDelta

- DiffService is not appropriate for asking "did anything change between Contexts X and Y"; instead, DiffService is trying to answer the question of "what changes should we show the user" and it plays a bunch of games around ignoring new files and so forth to do that.
- ContextDelta exists to answer the "did anything change" question and is appropriate for activity/action descriptions.

## Working with Fragments

- **SpecialTextType**: Some StringFragments are "special"; these have their behavior encapsulated in SpecialTextType. You should try to have SpecialTextType in your Workspace when you are working with StringFragments.
- **Files**: you should almost never need to cast to ProjectPathFragment etc to get a ProjectFile reference; just use ContextFragment::files().stream().flatmap().
- **Path normalization**: `DtoMapper` uses `contextManager.toFile(relPath)` with the *current* project root, not the serialized root. This handles ZIP histories created on different OSes.
