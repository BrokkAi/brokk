# Single Unit Save History Implementation Plan

## Overview
Currently, `BufferDiffPanel` generates separate history entries for each file that has diff changes applied during a save operation. This creates multiple undoable entries that must be undone individually. We need to consolidate all file changes from a single save operation into one atomic history entry.

## Current Behavior Analysis

### Current Code Flow
1. `doSave()` calls `generateDiffChangeActivityEntriesAsync()` with all pending changes
2. `generateDiffChangeActivityEntries()` loops through `diffChanges.entrySet()`
3. For each file, `createHistoryEntry()` is called, which creates a separate `TaskResult`
4. Each `TaskResult` is added to history via `contextManager.addToHistory(diffResult, false)`

### Current Data Structures
- `pendingDiffChanges`: Map<String, Integer> - filename -> change count
- `contentBeforeChanges`: Map<String, String> - filename -> original content
- `currentFileDataMap`: Map<String, FileData> - filename -> current state

### Issues with Current Approach
- Creates N separate history entries for N files changed
- User must undo N times to revert a single save operation
- History is cluttered with individual file entries instead of logical save operations
- No atomic rollback of multi-file saves

## Proposed Solution

### Core Changes Required

#### 1. Modify `generateDiffChangeActivityEntries()` Method
**Location**: `BufferDiffPanel.java:1578`

**Current**: Loops through files and creates individual history entries
**New**: Create single consolidated history entry with all file changes

**Key Changes:**
- Collect all file diffs before creating any history entries
- Generate combined action description for all files
- Create single `TaskResult` with all affected files
- Include all unified diffs in one message structure

#### 2. Create New Combined Diff Message Structure
**New Method**: `createCombinedDiffMessage(Map<String, String> fileDiffs, Map<String, Integer> changeCounts)`

**Purpose**: Generate formatted message containing all file diffs in a single structure

**Message Format**:
```
# Diff Operations Applied (N files, M total changes)

## Files Changed:
- file1.java (2 changes)
- file2.py (1 change)
- file3.ts (3 changes)

## Combined Diff:

### file1.java
```diff
[unified diff content]
```

### file2.py
```diff
[unified diff content]
```

### file3.ts
```diff
[unified diff content]
```
```

#### 3. Update Action Description Generation
**Current**: Individual file descriptions like "Applied 2 diff changes to Main.java"
**New**: Combined descriptions like "Applied diff changes to 3 files (6 total changes)"

**New Method**: `createCombinedActionDescription(Map<String, Integer> changeCounts)`

**Examples**:
- Single file: "Applied diff changes to Main.java"
- Multiple files: "Applied diff changes to 3 files (6 total changes)"
- Detailed: "Applied diff changes to Main.java (2), Utils.py (1), Config.ts (3)"

#### 4. Consolidate Affected Files Set
**Current**: Single file per TaskResult
**New**: All affected files in one TaskResult

**Implementation**: Collect all `ProjectFile` objects from `currentFileDataMap` into single Set

#### 5. Handle Error Scenarios Gracefully
**Current**: Individual file errors are logged, others continue
**New**: Collect all successful diffs, log failures, create single entry for successful ones

**Strategy**:
- Attempt to generate diff for each file
- Collect successful diffs and failed files separately
- Create single history entry for successful files
- Log summary of any failures

## Implementation Tasks

### Phase 1: Core Method Refactoring
- [ ] **Task 1.1**: Extract diff generation logic into separate method
  - Create `generateUnifiedDiffForFile(String filename, String originalContent, FileData fileData)` method
  - Returns `Optional<String>` with unified diff or empty if failed
  - Move error handling to this level

- [ ] **Task 1.2**: Create combined message generation method
  - Implement `createCombinedDiffMessage(Map<String, String> fileDiffs, Map<String, Integer> changeCounts)`
  - Format multiple diffs in readable structure with proper markdown
  - Include summary statistics

- [ ] **Task 1.3**: Create combined action description method
  - Implement `createCombinedActionDescription(Map<String, Integer> changeCounts)`
  - Handle single vs multiple file cases
  - Include change count summaries

### Phase 2: Main Logic Restructuring
- [ ] **Task 2.1**: Refactor `generateDiffChangeActivityEntries()` method
  - Remove the `for` loop that creates individual entries
  - Collect all file diffs into `Map<String, String>` structure
  - Track successful vs failed file processing
  - Create single `TaskResult` with all successful files

- [ ] **Task 2.2**: Update `TaskResult` creation
  - Combine all affected `ProjectFile` objects into single Set
  - Use combined action description
  - Use combined diff message
  - Single call to `contextManager.addToHistory()`

- [ ] **Task 2.3**: Improve error handling
  - Log individual file failures with details
  - Continue processing other files if one fails
  - Include failure summary in console output if any files failed

### Phase 3: Supporting Changes
- [ ] **Task 3.1**: Update method signatures and documentation
  - Update JavaDoc for `generateDiffChangeActivityEntries()` to reflect single-entry behavior
  - Ensure async wrapper `generateDiffChangeActivityEntriesAsync()` works correctly
  - Update any error logging messages to reflect consolidated approach

- [ ] **Task 3.2**: Add logging improvements
  - Log summary of consolidated save operation
  - Include statistics: "Created single history entry for N files with M total changes"
  - Maintain detailed per-file logging for debugging

- [ ] **Task 3.3**: Consider edge cases
  - Empty diff changes (already handled)
  - All files fail diff generation (should not create history entry)
  - Mix of successful and failed files (create entry for successful ones)

### Phase 4: Testing and Validation
- [ ] **Task 4.1**: Manual testing scenarios
  - Save single file with diff changes → verify single history entry
  - Save multiple files with diff changes → verify single consolidated entry
  - Save with some files failing → verify partial entry created
  - Undo operation → verify all files revert together

- [ ] **Task 4.2**: Integration testing
  - Test with existing undo/redo functionality
  - Verify TaskResult integration with context manager
  - Test async processing maintains single-entry behavior

- [ ] **Task 4.3**: Performance considerations
  - Measure impact of generating multiple diffs before creating history entry
  - Ensure async processing doesn't create race conditions
  - Verify memory usage with large diff operations

## Code Changes Required

### File: BufferDiffPanel.java

#### New Methods to Add:
```java
// Extract diff generation with error handling
private Optional<String> generateUnifiedDiffForFile(String filename, String originalContent, FileData fileData) {
    // Move existing diff generation logic here with try/catch
    // Return Optional.empty() on failure, Optional.of(diff) on success
}

// Create combined action description
private String createCombinedActionDescription(Map<String, Integer> changeCounts) {
    // Handle single vs multiple file cases
    // Include change count summaries
}

// Create combined diff message
private ChatMessage createCombinedDiffMessage(Map<String, String> fileDiffs, Map<String, Integer> changeCounts) {
    // Format all diffs in single markdown message
    // Include file summary and individual diffs
}
```

#### Method to Modify:
```java
// Current method signature remains the same
private void generateDiffChangeActivityEntries(
        Map<String, Integer> diffChanges,
        Map<String, String> contentBefore,
        Map<String, FileData> currentFileDataMap) {

    // NEW IMPLEMENTATION:
    // 1. Collect all successful file diffs into Map<String, String>
    // 2. Track all successful ProjectFiles into Set<ProjectFile>
    // 3. Create single TaskResult with combined message and all files
    // 4. Single call to contextManager.addToHistory()
}
```

## Benefits of This Approach

### User Experience
- **Atomic Undo**: Single undo operation reverts entire save
- **Cleaner History**: One logical entry per save operation instead of per-file entries
- **Better Context**: Combined diff shows full scope of changes made together

### Technical Benefits
- **Consistency**: All files from one save operation are treated as single unit
- **Reduced History Clutter**: Fewer total history entries to manage
- **Logical Grouping**: Related changes are kept together conceptually

### Maintainability
- **Clearer Intent**: Code reflects logical save operation rather than technical file iteration
- **Error Handling**: Centralized error handling for entire save operation
- **Extensibility**: Easier to add save-level features (e.g., save comments, tags)

## Risk Analysis

### Low Risk
- **Backward Compatibility**: No breaking changes to public APIs
- **Error Handling**: Maintains existing error recovery behavior
- **Performance**: Minimal impact, possibly slight improvement from fewer history entries

### Medium Risk
- **Large Diffs**: Single message with many large diffs could be memory intensive
  - **Mitigation**: Consider diff size limits or summary mode for large operations
- **Message Formatting**: Combined diff message needs to be readable
  - **Mitigation**: Careful markdown formatting with clear file separators

### Considerations
- **Undo Granularity**: Users lose ability to undo individual files from multi-file save
  - **Assessment**: This is actually desired behavior - save operations should be atomic
- **History Entry Size**: Larger individual entries vs more numerous smaller entries
  - **Assessment**: Trade-off favors fewer, larger entries for logical grouping

## Timeline Estimate

- **Phase 1**: 2-3 hours (method extraction and creation)
- **Phase 2**: 3-4 hours (main logic restructuring and testing)
- **Phase 3**: 1-2 hours (documentation and supporting changes)
- **Phase 4**: 2-3 hours (testing and validation)

**Total Estimated Time**: 8-12 hours

This plan provides a comprehensive approach to consolidating multi-file diff saves into single, atomic history entries while maintaining all existing functionality and improving user experience.