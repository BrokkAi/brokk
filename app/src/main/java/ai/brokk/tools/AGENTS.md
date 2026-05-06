# Tools Package Guidance

- Do not hard-code analyzer language extensions in tool-level capability checks. The active analyzer owns supported-file filtering and should signal unsupported files by returning empty results, `Optional.empty()`, or the existing unavailable path as appropriate.
