# Overview

Brokk (the [Norse god of the forge](https://en.wikipedia.org/wiki/Brokkr))
is the first code assistant that understands code semantically, not just
as chunks of text.  Brokk is designed to allow LLMs to work effectively
on large codebases that cannot be jammed entirely into working context.

There is a [Brokk Discord](https://discord.gg/QjhQDK8kAj) for questions and suggestions.

# Getting started

Run using jbang (recommended):

1. Install jbang
   - Linux / Mac: `curl -Ls https://sh.jbang.dev | bash -s - app setup`
   - Windows (Powershell): `iex "& { $(iwr https://ps.jbang.dev) } app setup"` 
   - Others: see https://www.jbang.dev/download/
2. Run: `jbang run brokk@jbellis/brokk`

# What Brokk can do

1. Ridiculously good agentic search / code retrieval. Better than Claude Code, better than Sourcegraph,
   better than Augment Code.  Here are
   [Brokk's explanation of "how does bm25 search work?"](https://gist.github.com/jbellis/c2696f58f22a1c1a2aa450fdf45c21f4)
   in the [DataStax Cassandra repo](https://github.com/datastax/cassandra/) (a brand-new feature, not in anyone's training set), starting cold
   with no context, compared to 
   [Claude Code's (probably the second-best code RAG out there)](https://github.com/user-attachments/assets/3f77ea58-9fe3-4eab-8698-ec4e20cf1974).   
1. Automatically determine the most-related classes to your working context and summarize them, preserving the signatures without unnecessary implementation details
1. Parse a stacktrace and add the source for each referenced method to your Workspace
1. Add source for all the usages of a class, field, or method to your Workspace, or add an arbitrarily deep call graph
1. Manage all these tools automatically in Architect mode to solve large, multi-step tasks

These allow some simple but powerful patterns:
- "Here is the diff for commit X, which introduced a regression.  Here is the stacktrace
  of the error and the full source of the methods involved.  Find the bug."
- "Here are the usages of Foo.bar.  Is parameter zep always loaded from cache?"

# Using Brokk

When you start Brokk, you’ll see five main areas:

![image](https://github.com/user-attachments/assets/fdeb80c6-bec9-411b-bba6-a4152361df46)

1. Instructions: Code, Ask, Search, and Run in Shell specify how your input is interpreted.  Stop cancels the in-progress action.
   Deep Scan recommends relevant source files to add to the Workspace to accomplish the given task.
1. Output: Displays the LLM (or shell command) output.
1. History: A chronological list of your actions.  Can undo changes to the Workspace as well as to your code.
1. Workspace: Lists active files and code fragments. Manipulated through the right-click menu or the top-level Workspace menu.
1. Git: Log tab allows viewing diffs or adding them to context; Commit tab allows committing or stashing your changes

As you edit your instructions and add context, Brokk will automatically suggest related files that you
may wish to add to the Workspace. This helps the LLM avoid hallucinations when
reasoning about your code.  This is the row of blue filenames you see below the Instructions.
[To get more precise recommendations, use Deep Scan](https://brokk.ai/blog/lean-context-lightning-development).

## Primary Actions

- Architect: Manipulate the Workspace and call Code and Search agents to solve multi-step tasks.
- Code: Tells the LLM you want code generation or modification.
- Ask: Ask a question referencing the current context.
- Search: Invokes a specialized agent that looks through your codebase for answers NOT in your current context.
- Run in Shell: Executes any shell command, streaming the output into the Output Panel.
- Stop: Cancels the currently running LLM or shell command.

## Workspace actions (context menu)
- Edit, Read: Decide which files the LLM can modify (editable) or just look at (read-only).
- Summarize: Summarizes the specified classes (declarations and signatures, but no method bodies).
- Drop: Removes snippets you no longer want in context.
- Copy, Paste: Copy snippets to your clipboard or paste external text into Brokk’s context.
  - Stacktraces get special treatment; they will be augmented with the source of referenced methods.
  - URLs also get special treatment; their text will be retrieved and ingested
- Symbol Usage: Pick a symbol (class, field, or method) and automatically gather all references into a snippet.
- Call Graph To / Call Graph From: expands the call graph to or from the given function to the specified depth.

You can doubleclick on any context in the Workspace to preview it.

## General Workflow
- Dictate or type instructions in the command box; use Code, Ask, Search, or Run in Shell as needed.
- Add relevant code or text to your context (choose Edit for modifiable files, Read for reference-only).
- Capture or incorporate external context using Run combined witn “Capture Text” or “Edit Files.”
- Use the History Panel to keep track, undo, or redo changes. Forget to commit and the LLM scribbled all over your
  code in the next request? No problem, Undo includes filesystem changes too.

# Examples
Here are a few scenarios illustrating how Brokk helps with real-world tasks.

## Scenario #1: Debugging a Regression with Git Bisect
1. Run `git bisect` to identify the commit that caused a regression.
2. Load the changes into the Workspace using the Git Log tab by right-clicking on the commit and selecting "Capture Diff."
3. Load all affected files into the Workspace by right clicking on the new diff context entry and selecting "Edit References."
4. Paste the stacktrace corresponding to the regression with ctrl-V, or right-click + Paste.
5. Write your instructions: "This stacktrace is caused by a change in the attached diff. Look at the changes to see what
   could cause the problem, and fix it."
6. Click on Code.


## Scenario #2: Exploring an unfamiliar part of the codebase
1. You want to know how that BM25 search feature your colleague wrote works. Type "how does bm25 search work?" into
   the Instructions area.
2. Click on Search.
3. Optionally click on Capture to pull the explanation into the Workspace.

## Scenario #3: AI-powered refactoring

1. Type your instructions into the Instructions area:
   > Replace Project.getAnalyzerWrapper with getAnalyzer() and getAnalyzerNonBlocking() that encapsulate aw.get and aw.getNonBlocking; update the callers appropriately.
1. Invoke Symbol Usage from the Workspace menu on Project::getAnalyzerWrapper.  In this case, our refactor is simple enough that the source for each
   caller is all we need; if our refactor were more complex, we could
   additionally right-click on the new context entry and select "Edit References."
3. Add Project itself as editable; it will be auto-suggested under the Instructions so all you need to do is right-click on it.
4. Click on Code.

## Working with dependencies
Often you find yourself working with poorly documented dependencies that your LLM doesn't know enough about to use without hallucinating.  Brokk can help!

Use File -> Decompile Dependency... and select the appropriate jar.
![image](https://github.com/user-attachments/assets/d9b6911c-91dc-44f8-97bd-652ee4a97e29)

Brokk will decompile the jar; after reopening the project, you can read, search, summarize,
and otherwise treat them like your own project files. (Except you can't edit them.)
(The decompiled source is 99% as useful to an LLM as the original source would be, and it's guaranteed to be the right version.)

# Code Intelligence Status

| Language    | Summarization | Symbolic Search Tools | Type-Aware Usage | Call Graphs |
| ----------- | ------------- |----------------------| ---------------- | ----------- |
| Java        | ✅             | ✅                    | ✅                | ✅           |
| C++, C      | ✅             | ✅                    | ✅                | ✅           |
| Python      | ✅             | ✅                    |                  |             |
| Javascript, JSX | ✅          | ✅                    |                  |             |
| C#          | ✅             | ✅                    |                  |             |
| Go          | ✅             | ✅                    |                  |             |
| Rust        | ✅             | ✅                    |                  |             |
| PHP         | ✅             | ✅                    |                  |             |

When Brokk doesn’t (yet) have full AST-level intelligence for a language, it falls back to file-level retrieval and Architect-mode orchestration—but those fallbacks are still stronger than the “full” support most tools provide. Brokk's Deep Scan, Search, Architect, and Workspace integrations make Brokk remains the fastest path to understanding and fixing code in any language.

### Known issues

- Joern (the code intelligence engine) needs to run delombok before it can analyze anything.
  Delombok is extremely slow for anything but trivial projects, making Brokk a poor fit for
  large Lombok-using codebases.
- Litellm does not pass 429 (over quota) or 503 (unavailable) errors from model vendors to Brokk. This will result
  in Brokk aborting its task with `BadRequestError (no further information, the response was null; check litellm logs)`.
  We are fixing this in Litellm.

## Finer points on some commands

- Brokk doesn't offer automatic running of tests (too much variance in what you might want it to do).
  Instead, Brokk allows you to run arbitrary shell commands, and import those as context with "Capture Text"
  or "Edit Files."  You can easily run your tests this way and have Brokk work on the results. If you really
  want Brokk to always run a test suite after making edits, you can change `buildCommand` in `.brokk/project.properties`
  accordingly.
- There is some overlap between `Symbol Usage` and `Call Graph to Function`; besides the former being just a single level
  deep in the call graph, Symbol Uage includes the entire source of each calling method while Call Graph to Function
  only includes one line per call.

# Contributing

Brokk uses sbt (Scala Build Tool) since it has a Scala component. To build Brokk,
1. Install sbt (e.g. with sdkman)
2. Run the sbt repl: `sbt` (with JDK 21 or newer)
3. In the sbt repl, run individual commands: `run`, `clean`, `test`, `assembly`, etc.

If you have `sbtn` installed it can be used to run commands such as `sbtn run` with a faster startup time.
(You can also run a single command without the repl with e.g. `sbt run` but sbt has a very high
startup overhead so using the repl or `sbtn` is recommended.)

If you change the build definition, run `reload` to start using the changes.

## Icon Browser

To explore available Look and Feel icons for UI development:
- GUI browser: `sbt "runMain io.github.jbellis.brokk.gui.SwingIconUtil icons"`
- Console list: `sbt "runMain io.github.jbellis.brokk.gui.SwingIconUtil"`

Use `SwingUtil.uiIcon("IconName")` to safely load icons with automatic fallbacks.

