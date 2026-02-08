# Files.walk / Recursive-traversal audit (production code)

This note documents all production sites in the repository that perform recursive filesystem traversal
(Files.walk, Files.walkFileTree, directory listing that can touch project trees/dependencies, etc.),
how they behave w.r.t. symbolic links, how exceptions propagate, and how each site can be reached from
the ArchitectAgent/ToolRegistry -> tools path. The goal is to make it straightforward for a follow-up
change to harden vulnerable walkers against symlink cycles (FileSystemLoopException) and to document
which tool flows can trigger them.

If you want code changes to make any of these walkers resilient (e.g., add an explicit NO-FOLLOW_LINKS
policy, limit maxDepth, or catch FileSystemLoopException specifically and handle it gracefully),
point me to which files you want changed (or say "apply safe-default changes to all listed walkers")
and I will produce targeted edits.

---

## Summary table (quick view)
- LocalCacheScanner.listAllJars (LocalCacheScanner.findArtifact / findLatestVersion)
  - Uses Files.walk(root, FileVisitOption.FOLLOW_LINKS) — explicitly follows symlinks.
  - Catches IOException / SecurityException at the call site and logs (returns empty/continues).
  - Can scan user home caches (may encounter arbitrary user directories).
  - Reachable from GUI/CLI (JavaLanguage dependency picker). Indirectly reachable from tools if the toolset exposes dependency selection (not via SearchTools directly). Still relevant because it's a public utility called in interactive flows.

- DependencyUpdater.updateDependencyOnDisk and helpers
  - Uses multiple Files.walk(...) and Files.walkFileTree(...) calls. Most Files.walk usages do NOT pass FileVisitOption.FOLLOW_LINKS (i.e., default -> does NOT follow symlinks).
  - Exceptions: Files.walk calls are inside try-with-resources and callers catch IOException and perform cleanup/logging. autoUpdateDependenciesOnce wraps per-dependency operations in try/catch and continues on exception.
  - These methods explicitly work with dependency directories (e.g., `.brokk/dependencies`) and local paths supplied by users; they may encounter user-provided directories (including node_modules) when local dependencies point into workspace.
  - Reachable from project-level dependency auto-update code, and toggled by IProject.getAutoUpdate... settings. Tools that trigger dependency updates (if any) or background tasks may call these.

- Completions.expandPatternToPaths
  - Uses Files.isDirectory and then `try (var stream = Files.walk(baseDir, maxDepth))` with NO FileVisitOption — default does NOT follow symlinks.
  - Catches IOException and returns empty.
  - Used by SearchTools.getFileSummaries and other tools that expand user-provided globs; therefore reachable from ToolRegistry-registered search tools invoked by ArchitectAgent/SearchAgent flows. Can therefore surface FileSystemLoopException during tool invocations.

- IProject.Dependency.files() (nested record in IProject.java)
  - Implementation in the interface: `try (var pathStream = Files.walk(root.absPath())) { ... }`
  - Uses default Files.walk (no FileVisitOption) -> does NOT follow symlinks.
  - Exceptions: catches IOException, logs error, returns empty set.
  - This method is a primary source of decompiled dependency file enumeration used by project analyzers. Tools that call `project.getAllFiles()` or enumerate `project.getLiveDependencies()` may end up invoking this.

- Project-wide enumeration: IProject.getAllFiles()
  - Concrete implementation: AbstractProject.getAllFiles() (present in this Workspace) implements the project-wide enumeration and does not itself invoke Files.walk.
    * For the main project tree it delegates to the underlying repo via repo.getFilesForAnalysis(). That repo-level call may be implemented by a Git-backed or filesystem-backed repo; the exact traversal behavior (whether it uses git internals or Files.walk) is an implementation detail of the IGitRepo in use (e.g., GitRepo or LocalFileRepo) and is not audited here.
    * After obtaining the repo-provided file set, AbstractProject.getAllFilesRaw() merges in files from enabled (live) dependencies by iterating over getLiveDependencies() and adding each IProject.Dependency.files() result into the aggregate set.
  - Dependency enumeration: IProject.Dependency.files() (the Dependency record implementation in IProject) does perform a recursive filesystem traversal: it calls Files.walk(root.absPath()) without passing FileVisitOption.FOLLOW_LINKS (i.e., default => does NOT follow symbolic links) and it catches IOException, logs an error, and returns an empty set on failure. Thus dependency enumeration is a direct Files.walk site and is covered by the try/catch behavior described.
  - Tool reachability: SearchTools methods such as searchSubstrings, searchFilenames, listFiles, and skimDirectory call project.getAllFiles() (or otherwise rely on the project's file set). Because AbstractProject.getAllFiles() is the project-facing aggregation, any Files.walk invocations that happen in repo.getFilesForAnalysis() or in IProject.Dependency.files() are on the ArchitectAgent/ToolRegistry -> tool method path: a tool-invoked search or summary request can therefore trigger the underlying walkers (whether in the repo implementation or in dependency enumeration).
  - Implication: since AbstractProject.getAllFiles() itself does not call Files.walk, hardening work should focus on the repo implementations (repo.getFilesForAnalysis()) and on IProject.Dependency.files() (which already uses NO-FOLLOW_LINKS and catches IOException). SearchTools callers that depend on getAllFiles() will inherit whatever traversal semantics those underlying implementations expose.

---

## Detailed findings

Below are the relevant code sites, with excerpts (descriptive) and behavior notes.

### 1) app/src/main/java/ai/brokk/util/LocalCacheScanner.java

Relevant methods / lines:
- findLatestVersion: uses `Files.list(artifactDir)` and later:
  - For other caches: `try (Stream<Path> walk = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) { ... }`
- findArtifact: in the "Scan other caches" loop:
  - `try (Stream<Path> walk = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) { ... }`
- listAllJars: for each root:
  - `try (Stream<Path> s = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) { return s.filter(...).toList().stream(); } catch (IOException ...) { ... }`

Behavior:
- Explicit usage of `FileVisitOption.FOLLOW_LINKS` — these walkers will follow filesystem symbolic links and therefore can traverse symlink loops.
- Exception handling: Files.walk is invoked inside try/catch that catches `IOException` and `SecurityException` (and sometimes general Exception). On catch the code logs a warning (logger.warn/logger.debug) and proceeds, returning an empty Stream for that root (so the root is effectively skipped).
- FileSystemLoopException is a subclass of IOException. When FileSystemLoopException is thrown during the walk, it will be caught by the catch blocks surrounding the Files.walk call, causing the code to log and skip that root. However, note that in some patterns the code returns a Stream's toList() inside the try, creating an intermediate collection before continuing; worst-case the exception is thrown while iterating inside toList() and will be surfaced to the enclosing try/catch and handled there.
- Because the code explicitly follows links, it is the most obvious place to trigger `FileSystemLoopException` when scanning user caches that contain symlink cycles (e.g., if some cache or package manager stored a symlink back to a parent directory).

Reachability from Agent -> ToolRegistry -> tools:
- LocalCacheScanner is typically used by GUI components (JavaLanguage) or the CLI dependency tools. It's not directly referenced by SearchTools; still, an ArchitectAgent or SearchAgent might indirectly cause UI flows that call into LocalCacheScanner (e.g., dependency pickers). If tools exposed to the LLM call into code that uses LocalCacheScanner, a tool-invoked operation could surface the exception. (SearchTools in this codebase does not call LocalCacheScanner directly.)

Conclusion & recommendation:
- Because FOLLOW_LINKS is explicitly enabled, consider changing to NOT follow links for public scans or add robust cycle handling. At minimum, log and skip loops (already the code logs and returns empty on IOException). Prefer defensively avoiding FOLLOW_LINKS unless necessary.

---

### 2) app/src/main/java/ai/brokk/util/DependencyUpdater.java

Relevant methods:
- updateDependencyOnDisk(...) — performs multiple scans:
  - Scans existing target directory:
    try (var pathStream = Files.walk(targetPath)) { ... }  // default: NO FOLLOW_LINKS
  - Scans tempDir:
    try (var pathStream = Files.walk(tempDir)) { ... } // default: NO FOLLOW_LINKS
- getNewestFileTimestamp(Path dir) uses `try (var stream = Files.walk(dir))` // default: NO FOLLOW_LINKS
- updateGitDependencyOnDisk uses GitRepoFactory.cloneRepo then `FileUtil.deleteRecursively(gitInternalDir)` etc.
- updateLocalPathDependencyOnDisk uses `Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() { ... })` — walkFileTree defaults to NOT following links unless FileVisitOption.FOLLOW_LINKS provided. The code does not pass FOLLOW_LINKS.

Behavior:
- Default Files.walk and Files.walkFileTree invocations in this file do NOT pass FOLLOW_LINKS => they do not follow symlinks (so symlink cycles will not be followed) — this reduces risk of FileSystemLoopException originating from these walkers.
- Exception handling:
  - Most Files.walk calls are inside try-with-resources and the surrounding callers handle IOException: updateDependencyOnDisk catches IOException and attempts cleanup, then rethrows. autoUpdateDependenciesOnce wraps each per-dep update in try/catch (IOException) and logs warn, continuing to the next dependency. Thus when a FileSystemLoopException occurs inside updateDependencyOnDisk it will be caught either locally and turned into an IOException (often logged + cleanup) or will bubble up and be caught in autoUpdateDependenciesOnce and logged without aborting the whole pass.
- Special note: updateLocalPathDependencyOnDisk copies files from an arbitrary local sourcePath. The code validates that the source is not inside the dependencies root and that it is not the same as target, but it does not explicitly guard against symlink cycles in the source. Since it uses Files.walkFileTree without FOLLOW_LINKS, it should not traverse a symlinked directory target (walkFileTree will by default not follow symlinked directories). However files inside the source may be symlinks to elsewhere; because follow-links is not enabled, visiting a symlink entry will call visitFile on the link itself (depending on BasicFileAttributes), but it will not traverse into the link target.
- getNewestFileTimestamp uses Files.walk(dir) without FOLLOW_LINKS -> no follow.

Reachability:
- These methods are used by autoUpdateDependenciesOnce, which may be invoked by project code or background tasks. The Search/Architect tooling may indirectly trigger dependency updates if the project is configured to auto-update dependencies, or during project syncs. The stacktrace described (walk into node_modules/@zanafleet/contracts/contracts) suggests a tool walked into project node_modules; DependencyUpdater's guards (for local dependency update) check normalizedSource against dependenciesRoot, but if dependencies come from other sources or if the project root's own enumeration is used, DependencyUpdater may not be the origin. However DependencyUpdater can still be involved when importing or auto-updating local dependencies pointing into node_modules.

Conclusion & recommendation:
- The DependencyUpdater uses NO-FOLLOW_LINKS by default in Files.walk and FileVisitor, which is safer.
- Its exception handling logs and continues at higher levels; FileSystemLoopException would be caught as an IOException in most call sites and be logged, preventing a hard crash.
- If more granular handling is desired, catch FileSystemLoopException specifically to log a clearer message and skip the problematic subtree.

---

### 3) app/src/main/java/ai/brokk/Completions.java

Relevant method:
- expandPatternToPaths(IProject project, String pattern)

Key points in implementation:
- Uses Files.isDirectory(baseDir)
- Decides maxDepth (Integer.MAX_VALUE if recursive via `**`, otherwise 1 + remainingSeparators)
- Builds PathMatcher with glob relative to baseDir
- Uses `try (var stream = Files.walk(baseDir, maxDepth)) { return stream.filter(Files::isRegularFile).filter(p -> matcher.matches(baseDir.relativize(p))).map(Path::toAbsolutePath).toList(); } catch (IOException e) { return List.of(); }`

Behavior:
- Files.walk(...) is invoked WITHOUT FileVisitOption.FOLLOW_LINKS -> default behavior is to NOT follow symbolic links. Therefore symlink cycles should not be traversed by this walker. (Note: Files.walk still may throw FileSystemLoopException if the filesystem has weird entries? But per JDK docs, FileSystemLoopException is thrown when a cycle is detected and FOLLOW_LINKS is used; without FOLLOW_LINKS cycles should not be followed and thus not throw loop exceptions. Nevertheless, a FileSystemLoopException can be thrown in some pathological filesystem states even without FOLLOW_LINKS.)
- Exception handling: catches IOException and returns empty list. So tools invoking this will see "no matches" when IO errors occur.

Reachability:
- Completions.expandPatternToPaths is used by:
  - Completions.expandPath -> used by SearchTools.getFileSummaries (and others) in SearchTools.java
  - SearchTools.getFileSummaries calls `Completions.expandPath(project, pattern)` to turn user-supplied globs into ProjectFile/ExternalFile sets.
- Those SearchTools methods are registered with ToolRegistry (they are annotated with @Tool) and therefore reachable from ArchitectAgent and SearchAgent flows. That maps directly to the reported stacktrace: a ToolRegistry-invoked tool (e.g., getFileSummaries or any tool that expands globs) can call `expandPatternToPaths` -> Files.walk(baseDir, ...) and propagate exceptions back through ToolRegistry.executeTool -> ArchitectAgent/SearchAgent.

Recommendation:
- expandPatternToPaths already does NOT follow links and catches IOException, returning empty -> robust in the sense it swallows IO errors. If you need clearer behavior (e.g., special-case FileSystemLoopException) you can catch that subclass and log a message pointing to the problematic base dir (helps debugging).

---

### 4) app/src/main/java/ai/brokk/project/IProject.java — nested Dependency.files()

Relevant code:
- `public Set<ProjectFile> files() { try (var pathStream = Files.walk(root.absPath())) { var masterRoot = root.getRoot(); return pathStream.filter(Files::isRegularFile).map(path -> new ProjectFile(masterRoot, masterRoot.relativize(path))).collect(Collectors.toSet()); } catch (IOException e) { logger.error("Error loading dependency files from {}: {}", root.absPath(), e.getMessage()); return Set.of(); } }`

Behavior:
- Files.walk(root.absPath()) invoked with default options -> does NOT follow symlinks.
- Catch: catches IOException, logs error and returns empty set. So a FileSystemLoopException (subclass of IOException) thrown here would be caught, logged as error, and the dependency would appear empty.
- This method enumerates dependency content from a dependency root's absolute path — the dependency root may be a directory under project `.brokk/dependencies` or a user-supplied local dependency root. If the dependency tree contains symlink cycles, default NO-FOLLOW_LINKS reduces risk; however cycles could still cause issues in unusual filesystems.

Reachability:
- Tools that iterate project dependencies or analyzers that include dependency files will transitively call this method. SearchTools or other analysis code that calls `IProject.getAllFiles()` might merge results from dependencies into the workspace.

Recommendation:
- Current behavior is conservative: default NO-FOLLOW_LINKS and logs IOException. If you prefer more targeted handling, catch FileSystemLoopException specifically and log the problematic dependency root + suggested fix (e.g., remove symlink cycle).

---

### 5) SearchTools and other tool-facing code that triggers enumeration

Key tool methods that can cause recursive traversal:
- SearchTools.searchSubstrings: calls `contextManager.getProject().getAllFiles()` => the concrete project implementation of getAllFiles() determines how enumeration is performed. searchSubstrings then reads file.read() per file — these file reads can be expensive but don't recursively walk.
- SearchTools.searchFilenames: also calls `contextManager.getProject().getAllFiles()` and filters by filename.
- SearchTools.listFiles: calls `contextManager.getProject().getAllFiles()` and formats files in a directory.
- SearchTools.skimDirectory: computes `Path absTargetDir = project.getRoot().resolve(targetDir); File[] fsItems = absTargetDir.toFile().listFiles();` -> this is not recursive by itself, but later for each child it calls analyzer.summarizeSymbols(file) which reads file contents.
- SearchTools.getFileSummaries: uses `Completions.expandPath(...)` which calls Files.walk(baseDir,...)
- SearchTools.getFileContents: uses `contextManager.toFile(filename)` which resolves to a BrokkFile and then calls file.read(). Not recursive.

Behavior:
- Where the tool calls `getAllFiles()` we must inspect the concrete `IProject.getAllFiles()` implementation. The interface default is empty; concrete classes in project implementation (not present here) will determine whether symlinks are followed and whether Files.walk is used.
- Tools that call Completions.expandPath are directly backing FileTree walking using Files.walk (NO FOLLOW_LINKS).
- Tools that call directly into directory scanning via `File` API (listFiles) do not follow symlinks recursively by themselves, but could be affected by symlinked directory entries.

Plausible end-to-end path mapping (example showing how an ArchitectAgent-invoked tool can produce a walk into node_modules):

- ArchitectAgent receives an instruction that triggers a tool call (e.g., search for filenames or request file summaries).
- ArchitectAgent builds a ToolExecutionRequest and passes it to ToolRegistry.executeTool.
- ToolRegistry.locates the registered method (one of SearchTools' @Tool methods) and invokes it.
- Example: `getFileSummaries` is invoked by the tool infrastructure with an argument containing a glob like "node_modules/**/contracts/**".
- getFileSummaries calls Completions.expandPath(project, pattern).
- expandPatternToPaths resolves baseDir and calls Files.walk(baseDir, maxDepth) to expand the glob.
- If the project's node_modules contains a symlink cycle (e.g., package A -> symlink to package B -> symlink back to A), and if the Files.walk call is executed with FOLLOW_LINKS then a FileSystemLoopException may be thrown by the JDK's file traversal code (FileTreeIterator).
- In our code: expandPatternToPaths uses NO FOLLOW_LINKS -> less likely to hit FileSystemLoopException; however LocalCacheScanner explicitly uses FOLLOW_LINKS, and some other code (not present here) might use FOLLOW_LINKS. If a FOLLOW_LINKS walk is reached from a tool invocation (e.g., a tool that scans caches or external directories), a FileSystemLoopException will be thrown and bubble up to the enclosing try/catch: depending on the site it will either be logged and turned into an empty result (safe), or rethrown and get wrapped by ToolRegistry.executeTool into a ToolExecutionResult.internalError (ToolRegistry.executeTool catches Exception, handles with GlobalExceptionHandler, and returns ToolExecutionResult.internalError), which will then be presented by ArchitectAgent or SearchAgent as a tool failure. The stacktrace referenced in the issue (FileTreeIterator walking /home/lenovo/projects/zanafleet/node_modules/@zanafleet/contracts/contracts) suggests a tool walked into node_modules. The most direct candidate in this repo for tool-driven walking is Completions.expandPatternToPaths (used by getFileSummaries) and the unknown concrete `IProject.getAllFiles()` implementation (which may itself use Files.walk and could have used FOLLOW_LINKS there).

---

## Concrete places to harden (recommended priorities)

1. LocalCacheScanner: remove `FileVisitOption.FOLLOW_LINKS` unless there is a compelling reason to follow symlinks in caches. If following links is required, add explicit cycle detection or catch FileSystemLoopException and skip problem roots. This is highest-risk because FOLLOW_LINKS is explicit.

2. Completions.expandPatternToPaths: currently uses NO-FOLLOW_LINKS and catches IOException. Consider catching FileSystemLoopException separately with a log message that includes baseDir and the original pattern (to help debug user reports). Also consider bounding maxDepth even for recursive globs (e.g., cap at a large but finite depth) to reduce DoS-style behavior on pathological trees.

3. IProject implementations (concrete MainProject/AbstractProject): we need to inspect these to determine whether getAllFiles uses Files.walk and whether it uses FOLLOW_LINKS. If it uses Files.walk with FOLLOW_LINKS or otherwise does recursive traversal across user workspaces (including node_modules), add NO-FOLLOW_LINKS or cycle detection.

4. DependencyUpdater: currently safe (NO-FOLLOW_LINKS), but it copies content from user-specified directories (local dependencies). If a user imports a dependency that contains symlink cycles inside sourcePath, the current walkFileTree without FOLLOW_LINKS will not follow them; this is acceptable. Keep current approach but explicitly document behavior and add a guard log if unexpected loops are detected.

5. Any other helper utilities that use Files.walk with FOLLOW_LINKS should be audited and changed to be conservative.

---

## Exception propagation summary

- Most Files.walk usages are wrapped in try/catch(IOException) and/or try-with-resources; `FileSystemLoopException` is an `IOException` subclass and will be caught by these catches. Typical behavior in these files is to:
  - Log the exception (warn or error)
  - Return an empty collection (avoid crashing)
  - In DependencyUpdater.updateDependencyOnDisk a thrown IOException causes the higher-level auto-update to catch, log, and continue with the next dependency.

- ToolRegistry.executeTool wraps invocation in a try/catch(Exception) and will convert unexpected exceptions into ToolExecutionResult.internalError (and also call GlobalExceptionHandler.handle). Thus if a FileSystemLoopException escapes a tool method invocation it will be caught by ToolRegistry and converted into an internal error result; the ArchitectAgent/SearchAgent will then observe tool failure.

- The stacktrace observed previously that shows the JDK internals walking node_modules likely indicates a Files.walk invocation that used FOLLOW_LINKS or a concrete project implementation that followed links. The code sites that explicitly use FOLLOW_LINKS are LocalCacheScanner.* (scanning home caches) — these are plausible culprits. Completions.expandPatternToPaths and IProject.Dependency.files use NO-FOLLOW_LINKS in this source snapshot and are less likely to produce FileSystemLoopException; however the concrete `IProject.getAllFiles()` implementation (not present here) is the most likely origin of a project-root walk into node_modules.

---

## Next steps (what I can do for you)

1. If you want me to harden specific walkers now, say which of the following you want changed and I will produce edits:
   - LocalCacheScanner: remove FOLLOW_LINKS or wrap Files.walk calls to explicitly catch FileSystemLoopException and skip problematic paths.
   - Completions.expandPatternToPaths: add explicit catch for FileSystemLoopException with better logging, and cap maxDepth to a safe value.
   - IProject.Dependency.files (interface): add an explicit catch for FileSystemLoopException and a helpful log message.
   - DependencyUpdater: add specific FileSystemLoopException handling in getNewestFileTimestamp and updateDependencyOnDisk to log and skip the dependency instead of failing.

2. To complete a full audit of all end-to-end paths from tools -> file traversal, please add the concrete project implementation classes that provide `IProject.getAllFiles()`. The likely filenames I need are:
   - app/src/main/java/ai/brokk/project/AbstractProject.java
   - app/src/main/java/ai/brokk/project/MainProject.java
   - any other class that implements IProject and is used at runtime for real projects (WorktreeProject etc.)
   Add those files to the Workspace and I'll scan them for Files.walk / recursion usages and extend this document with exact findings and suggested code edits.

---

## Short code-comment snippets you can add (optional)

If you want quick inline guidance in the code near the main scanning methods, add comments like the following above problematic walkers (these are suggestions; I can add them with real edits if you instruct me to):

- Above LocalCacheScanner.walk:
  // NOTE: This Files.walk is using FOLLOW_LINKS; that makes us vulnerable to symlink cycles (FileSystemLoopException).
  // Consider removing FOLLOW_LINKS or adding explicit cycle handling/catch for FileSystemLoopException.

- Above Completions.expandPatternToPaths Files.walk:
  // NOTE: Uses NO-FOLLOW_LINKS by default; catches IOException and returns empty list on I/O errors.
  // If callers need to distinguish "no matches" from "I/O error" refactor to throw a specialized exception.

---

If you want me to implement specific hardening edits now (e.g., catch FileSystemLoopException specifically in these files and add better logging), tell me which files to change and I will produce precise SEARCH/REPLACE edits. If you prefer I first inspect concrete `IProject.getAllFiles()` implementations, please add those files to the Workspace and I will continue.
