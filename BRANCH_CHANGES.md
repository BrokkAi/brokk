# Branch Change Summary: `master..HEAD`

This document summarizes the significant changes, refactors, and new features introduced in this branch compared to `master`.

**Comparison Range:** `master..HEAD`  
**Merge Base Commit:** (Calculated from `git merge-base master HEAD`)

---

## 🚀 Key Highlights

- **Rebranding:** Full transition from the previous project identity to **Brokk**.
- **TUI Overhaul:** Significant redesign of the Terminal UI with a focus on slash commands and real-time feedback.
- **Cost Tracking:** Implementation of LLM token and cost tracking across all provider interactions.
- **Executor Launch:** Transitioned to a JBang-based executor for the Java core, simplifying deployment and runtime management.

---

## 🛠 Categorized Changes

### **Branding & Identity**
- Renamed all core modules and packages to `ai.brokk.*`.
- Updated documentation, CLI help text, and visual assets to reflect the Brokk brand.

### **TUI / User Experience (Python)**
- **Slash Commands:** Replaced complex menu interactions with a streamlined slash-command system (`/add`, `/drop`, `/reset`, etc.) in the Textual-based UI.
- **Markdown Rendering:** Improved rendering of streaming responses and tool-call outputs.
- **Context Management:** Enhanced visibility of the current context window and token usage in the status bar.

### **LLM Infrastructure & Cost Tracking**
- Integrated cost calculation logic for OpenAI, Anthropic, and Google Vertex AI providers.
- Added persistent storage for session-based and lifetime cost tracking.
- Implemented `CostRegistry` to provide real-time updates to the UI.

### **Executor & Launch (Java Core)**
- **JBang Integration:** The Java backend is now launched via JBang, removing the need for pre-compiled heavy JARs in the developer workflow.
- **Process Management:** Refined the lifecycle management of the background executor process to ensure clean exits and better error reporting.

### **ACP / Protocol**
- Updated the **Agent Control Protocol (ACP)** to version 2.0, supporting more granular tool execution states.
- Enhanced streaming support for multi-modal responses.

### **Analyzers & Usage Finding**
- Refactored the Java source analyzer to use a more efficient AST-based approach for usage finding.
- Improved "Smart Selection" logic for automatically pulling in related classes and interfaces into the LLM context.

### **IDE Integrations**
- Updated the JetBrains plugin to support the new ACP version.
- Initial support for VS Code extension connectivity via the new JSON-RPC bridge.

### **Tests & Build**
- Switched to `pytest` for Python TUI testing.
- Added integration tests for the JBang executor startup sequence.
- Implemented NullAway and Error Prone checks across all Java modules to enforce null safety.

---

## 📈 Top Touched Areas

1.  `brokk-code/` (Python TUI and Slash Command logic)
2.  `app/src/main/java/ai/brokk/executor/` (JBang launch and process handling)
3.  `app/src/main/java/ai/brokk/protocol/` (ACP 2.0 implementation)
4.  `app/src/main/java/ai/brokk/analyzer/` (Usage finding and context analysis)
5.  `docs/` (Rebranding and API documentation updates)
