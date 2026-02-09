# C++ Duplicate-handling and CodeUnit identity (Tree-sitter pipeline)

This note documents how duplicate detection and handling works for C++ in the Tree-sitter analyzers.
It ties together the relevant pieces in:
- TreeSitterAnalyzer.analyzeFileContent (and helpers addTopLevelCodeUnit/addChildCodeUnit)
- CodeUnit identity/equals/hashCode semantics
- CppAnalyzer overrides (extractSignature, enhanceFqName, buildCppOverloadSuffix,
  buildCppQualifierSuffix, shouldIgnoreDuplicate, prioritizingComparator)
- The C++ Tree-sitter query (treesitter/cpp.scm)

Purpose: help future maintainers understand when and why the analyzer logs
"Unexpected duplicate top-level CodeUnits" and how C++-specific hooks affect that.

---

Summary (high-level)
- The main parsing loop is in TreeSitterAnalyzer.analyzeFileContent.
  It builds a set of local structures per-file:
  - localTopLevelCUs: list of top-level CodeUnit instances for the file (order matters)
  - localChildren: child lists keyed by parent CodeUnit
  - localSignatures/localSourceRanges/localHasBody etc.
- When a capture is processed, a CodeUnit is created by createCodeUnit (language-specific).
  Then:
  - enhanceFqName may change the FQN used (CppAnalyzer overrides to normalize destructor names but
    purposely does NOT append parameter signatures to the FQN).
  - extractSignature may produce a signature string (CppAnalyzer returns a signature for function-like
    entities, e.g. "(int) const").
  - If either the enhanced FQN differs from the CodeUnit.fqName() or extractSignature yields a
    non-null signature, a new CodeUnit instance is created with the enhanced shortName and the signature
    (the CodeUnit constructor with explicit signature).
- Top-level and child insertion is mediated by addTopLevelCodeUnit and addChildCodeUnit, which apply
  multiple language-agnostic hooks:
  - Replacement policy via shouldReplaceOnDuplicate (default: false)
  - Ignoring duplicates via shouldIgnoreDuplicate (default: true)
  - For functions specifically: a hasBody boolean derived from AST is used to prefer definitions (hasBody)
    over forward declarations (no body). This preference is implemented before general duplicate logic.
  - If shouldReplaceOnDuplicate returns true, the candidate replaces existing and descendants are removed.
  - If shouldIgnoreDuplicate returns true, candidate is ignored (existing kept).
  - If neither replaces nor ignores, equality (CodeUnit.equals) is used to detect "true duplicates" and ignore them;
    otherwise candidate is added (useful for overloads).

When the file analysis finishes:
- localTopLevelCUs is checked for "true duplicates" by grouping by CodeUnit instance (the code uses
  localTopLevelCUs.stream().collect(Collectors.groupingBy(cu -> cu, Collectors.counting()))).
- If any group has count > 1, an error is logged:
  "Unexpected duplicate top-level CodeUnits in file {}: [...]"
  The diagnostic prints fqName, kind and count for each duplicated CodeUnit key.

Key consequence:
- That final error is triggered only if the *same* CodeUnit instance (as judged by equals/hashCode)
  appears multiple times in localTopLevelCUs. The preceding duplicate-resolution hooks aim to prevent
  logical duplicates (identical symbols) from reaching that point; if they do, it's considered unexpected.

---

CodeUnit identity: equals/hashCode semantics (summary)
- CodeUnit.equals and hashCode define the notion of "same CodeUnit" used throughout deduplication.
- Equality is not only by fqName; it also incorporates kind, source, and signature (if present).
  (See CodeUnit API summary: CodeUnit has fields source, kind, shortName, packageName, signature, transient fqName)
- Practical effect for functions:
  - Two function CodeUnit instances with the same fqName but different signature strings are NOT equal.
    That lets overloads co-exist as distinct CodeUnit instances and survive addTopLevelCodeUnit/addChildCodeUnit.
  - Two function CodeUnit instances with identical fqName and identical signature (and same source/kind) are equal,
    which is treated as a "true duplicate" and typically ignored (i.e., not added).
  - If the signature field is null for both instances but other identity components match, they are equal.

Note: CodeUnit.fqName is used to find same-named existing duplicates during insertion, but final equality uses equals().

---

CppAnalyzer-specific behavior that affects duplicates and identity

1) Treesitter query: treesitter/cpp.scm
- Produces captures for:
  - namespace/class/struct/union/enum definitions
  - function.definition (includes both definitions and declarations via two query forms)
  - field.definition, method.definition, constructor.definition, typedef, using, and preproc_include (@import.declaration)
- The query intentionally captures function *declarators* to handle many declaration shapes. This means function
  captures may have the same "name" but could represent declaration vs definition forms.

2) createCodeUnit and enhanceFqName (in CppAnalyzer)
- createCodeUnit builds an initial fqName (possibly using '$' delimiter for class/member separation vs '.').
- CppAnalyzer.enhanceFqName:
  - For function-like skeletons: it normalizes destructor names to include a leading '~' (if not present).
  - Intentionally returns the clean fqName for functions WITHOUT appending parameters/qualifiers — signature is kept
    separate (in CodeUnit.signature) instead of being folded into fqName.
  - For non-functions, returns unchanged.

3) extractSignature (in CppAnalyzer)
- For function-like entities, returns a stable signature string:
  - Builds a parameter-type CSV using buildCppOverloadSuffix (AST-driven, avoids naive comma-splitting).
  - Builds a qualifier suffix using buildCppQualifierSuffix (const/volatile/ref/noexcept parsing).
  - Returns strings like "(int,std::function<void(int)>) const noexcept(true)" or "()" for empty parameter lists.
- The signature is stored in CodeUnit.signature when constructing the final CodeUnit instance.

4) buildCppOverloadSuffix / buildCppQualifierSuffix
- buildCppOverloadSuffix:
  - Traverses the function_declarator (or finds it recursively) and iterates named parameter children.
  - For each parameter, strips default values and attempts to remove parameter names (AST-based when possible).
  - Normalizes pointers/references spacing and returns a CSV of parameter types (no surrounding parens).
  - Returns "" if no usable declarator/parameters.
- buildCppQualifierSuffix:
  - Finds text between parameter end and either the body start or node end.
  - Detects const/volatile, reference qualifiers (&/&&) and full noexcept clauses (including parenthesized expr).
  - Returns a concatenation like "const && noexcept(true)" or empty string.

5) shouldIgnoreDuplicate (in CppAnalyzer)
- Overrides TreeSitterAnalyzer.shouldIgnoreDuplicate:
  - For functions: returns false -> do NOT ignore duplicates. Rationale: functions may be overloads/definition+declaration.
    This permits multiple function CodeUnit instances with same base fqName but different signatures to be added.
  - For classes, fields, modules: returns true -> ignore duplicates (typical header/re-declaration noise).
  - For other kinds: delegates to super.shouldIgnoreDuplicate.

6) prioritizingComparator (in CppAnalyzer)
- Provides a preference ordering among CodeUnits (affects sort order returned by IAnalyzer.priorityComparator):
  - For functions: prefer HasBody definitions, and prefer those defined in source files (.c/.cpp/.cc/.cxx) over headers.
  - This comparator doesn't directly change duplicate resolution during addTopLevelCodeUnit, but it affects which
    definition callers see first (useful for "pick first" semantics when multiple CodeUnits share the same symbol).

---

End-to-end duplicate handling for C++ (concrete pipeline)
1. Tree-sitter query matches a function or method (declaration or definition).
2. TreeSitterAnalyzer.extractSimpleName + createCodeUnit produce a provisional CodeUnit with:
   - source (ProjectFile), kind (FUNCTION), packageName, fqName (name or class-qualified).
3. CppAnalyzer.enhanceFqName may normalize (e.g., destructor '~') but does NOT append signature.
4. CppAnalyzer.extractSignature produces a signature string for function-like entities (non-null, at least "()" for no params).
5. If enhancedFqName != cu.fqName() OR signature != null, the code constructs a new CodeUnit with that signature:
   new CodeUnit(source, kind, packageName, enhancedShortName, codeUnitSignature)
   -> signature is stored separately in the CodeUnit object.
6. The localHasBody map records whether the AST node had a non-empty body (definition) or not.
7. Insertion into localTopLevelCUs:
   - addTopLevelCodeUnit checks for an existingDuplicate by comparing fqName equality (string) among localTopLevelCUs:
     existingDuplicate = first existing where existing.fqName().equals(cu.fqName())
   - For functions:
     - If existingHasBody && !candidateHasBody: candidate is ignored (definition exists).
     - If candidateHasBody && !existingHasBody: candidate replaces existing (definition preferred).
     - If both have same hasBody value:
       - If both have signatures and signatures equal -> ignore candidate as duplicate.
       - Otherwise fall through to general logic.
   - For non-functions:
     - If shouldReplaceOnDuplicate(...) true -> replace existing and remove descendants
     - else if shouldIgnoreDuplicate(existing, candidate) true -> ignore candidate
     - else:
       - If existing.equals(candidate) -> ignore (true duplicate)
       - Else -> add (distinct overload or distinct entity)
8. Because CppAnalyzer.shouldIgnoreDuplicate returns false for functions, multiple functions with the same fqName
   (but different signatures) are allowed and added to localTopLevelCUs as separate CodeUnit instances.
9. At the end of file analysis, analyzeFileContent groups localTopLevelCUs by CodeUnit identity (groupingBy(cu -> cu, counting())).
   - This uses CodeUnit.equals/hashCode to key groups.
   - The "Unexpected duplicate top-level CodeUnits" error is logged when the SAME CodeUnit instance (as judged by equals/hashCode)
     appears more than once in localTopLevelCUs.
   - Since addTopLevelCodeUnit/addChildCodeUnit attempt to avoid adding exact-equal duplicates, this final error indicates
     an unexpected situation where identical CodeUnit objects (equal per equals/hashCode) were added multiple times to the
     top-level list despite the duplicate-handling logic.

Practical examples (when duplicates do and don't happen)
- Allowed and normal (no error):
  - Two function CodeUnits with fqName "ns.Foo" but signatures "(int)" and "(double)" -> both added (overloads). equals() is false.
  - A header forward declaration (no body) and a source definition (hasBody true) -> definition will replace declaration (no duplication).
  - Two class CodeUnits coming from different parse paths but representing the same class: shouldIgnoreDuplicate returns true
    -> new one ignored -> no duplication.
- Triggers "Unexpected duplicate top-level CodeUnits" (rare / error case):
  - The same CodeUnit (same fqName, same signature (or both signature null), same kind, same source identity fields) somehow
    gets added twice to localTopLevelCUs. This can happen if addTopLevelCodeUnit's pre-checks mis-identify the existing entry
    (e.g., due to mutated equality contract), or the same CodeUnit instance is inserted twice via two different code paths
    without the presence-check (edge cases).
  - Because CppAnalyzer permits multiple functions with the same bare fqName, duplicates printed in the final diagnostic will
    be grouped by equals/hashCode; so only true-equals duplicates (likely programmer error or query duplication) will show up.

Recommendations for maintainers (short)
- If you see the "Unexpected duplicate top-level CodeUnits" log for C++ files:
  - Inspect the CodeUnit groups printed in diagnostics for fqName/kind/count. If fqName shows identical and kind is FUNCTION:
    - Check whether the signature field differs; if signatures are different, the diagnostic is surprising (equals should differ).
    - If signatures are identical or null, check whether addTopLevelCodeUnit correctly detected/replaced/ignored duplicates
      (body preference and shouldIgnoreDuplicate behavior).
  - Consider adding additional trace logs that print CodeUnit.signature() and CodeUnit.source().toString() in the grouping diagnostic
    to see why equals() regards them as identical.
- When changing CodeUnit.equals/hashCode semantics, be mindful that:
  - These methods are used both for deduplication and as Map keys in analysis output. Changing equality may alter which symbols
    are considered overloads vs duplicates. Keep function signatures in equality if you want overloads to be distinct.
- If adding a language-specific policy that treats different signatures as the same (e.g., collapsing template instantiations),
  do so by overriding shouldIgnoreDuplicate or by normalizing signatures in enhanceFqName/extractSignature before constructing the CodeUnit.

---

Exact condition for the final error
- TreeSitterAnalyzer.analyzeFileContent computes:
  var duplicatesByCodeUnit = localTopLevelCUs.stream().collect(Collectors.groupingBy(cu -> cu, Collectors.counting()));
  var duplicatedCUs = duplicatesByCodeUnit.entrySet().stream().filter(e -> e.getValue() > 1).toList();
- If duplicatedCUs is not empty, it logs:
  log.error("Unexpected duplicate top-level CodeUnits in file {}: [{}]", file, diagnostics);
  where diagnostics = each duplicate entry formatted as "fqName=%s, kind=%s, count=%d".
- Note: groupingBy uses CodeUnit.equals and hashCode for grouping keys. So the error is logged only when equals/hashCode say
  there are multiple identical CodeUnit keys present more than once in localTopLevelCUs.

---

Files referenced
- app/src/main/java/ai/brokk/analyzer/TreeSitterAnalyzer.java
  - analyzeFileContent, addTopLevelCodeUnit, addChildCodeUnit (see comments above)
- app/src/main/java/ai/brokk/analyzer/CppAnalyzer.java
  - extractSignature, enhanceFqName, buildCppOverloadSuffix, buildCppQualifierSuffix, shouldIgnoreDuplicate, prioritizingComparator
- app/src/main/java/ai/brokk/analyzer/CodeUnit.java
  - equals/hashCode govern identity (signature included in equality for functions)
- app/src/main/resources/treesitter/cpp.scm
  - what AST nodes are captured (functions, methods, declarations, includes, etc.)

---

If you want, I can:
- Add temporary debug instrumentation to the duplicate-detection site to log signature/source for each duplicate group (helpful for debugging).
- Create a unit test that constructs the failing scenario (if you can provide a minimal reproducer).
- Suggest or implement changes to make the duplicate diagnostic more descriptive (include signature and source file in the logged diagnostic).

No production behavior was changed by this note.
