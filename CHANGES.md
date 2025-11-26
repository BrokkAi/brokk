Model resolution API changes
----------------------------

Per-model getters that used to live on IProject (for example: getQuickModelConfig(), getCodeModelConfig(),
getArchitectModelConfig(), getQuickEditModelConfig(), getQuickestModelConfig(), getScanModelConfig())
have been consolidated. Call sites should migrate to the centralized model-resolution helpers.

Recommended replacements
- If you have an IContextManager instance (`cm`): prefer `cm.getCodeModel()` for the code model slot,
  or call `cm.getModelOrDefault(modelConfig, "<slot>")` when you already have a `ModelConfig`.
- If you only have an `IProject` and a `Service` instance: obtain the `ModelConfig` from `MainProject`
  (e.g. `project.getMainProject().getCodeModelConfig()`) then call `service.getModel(config)`.

Migration note
- Replace direct calls to the old IProject per-model getters with one of the two patterns above.
  The ContextManager helpers provide the simplest and most consistent runtime behavior; use the
  MainProject + Service pattern in non-UI or test code where `IContextManager` is not available.
