# Overview

Brokk (the [Norse god of the forge](https://en.wikipedia.org/wiki/Brokkr))
is the first code assistant that understands code semantically, not just
as chunks of text.  Brokk is designed to allow LLMs to work effectively
on large codebases that cannot be jammed entirely into working context.

# What Brokk can do

1. Ridiculously good agentic search / code retrieval. Better than Claude Code, better than Sourcegraph,
   better than Augment Code.  Here are
   [Brokk's explanation of "how does bm25 search work?"](https://gist.github.com/jbellis/c2696f58f22a1c1a2aa450fdf45c21f4)
   in the [DataStax Cassandra repo](https://github.com/datastax/cassandra/) (a brand-new feature, not in anyone's training set), starting cold
   with no context, compared to 
   [Claude Code's (probably the second-best code RAG out there)](https://github.com/user-attachments/assets/3f77ea58-9fe3-4eab-8698-ec4e20cf1974).   
1. Automatically determine the most-related classes to your working context and summarize them
1. Parse a stacktrace and add source for all the methods to your context
1. Add source for all the usages of a class, field, or method to your context
1. Parse "anonymous" context pieces from external commands
1. Build/lint your project and ask the LLM to fix errors autonomously

These allow some simple but powerful patterns:
- "Here is the diff for commit X, which introduced a regression.  Here is the stacktrace
  of the error and the full source of the methods involved.  Find the bug."
- "Here are the usages of Foo.bar.  Is parameter zep always loaded from cache?"

# Using Brokk

When you start Brokk, you’ll see four main areas:

![image](https://github.com/user-attachments/assets/32d5a1bd-67b2-4181-8bc8-bfd4d546b959)

1. Output Panel (Left Side): Displays the LLM or shell command output.
1. History Panel (Right Side): Keeps a chronological list of your actions.  
1. Command Input & Buttons (Bottom-Left): Code, Ask, Search, and Run in Shell specify how your input is interpreted.  Stop cancels the in-progress action.
1. Context Panel (Bottom): Lists active code/text fragments in your current context, specifying whether they’re read-only or editable, and has
   buttons to manipulate context.

As you add context, Brokk will automatically include summaries of the most closely-related classes
as determined by a graph analysis of your codebase.  This helps the LLM avoid hallucinations when
reasoning about your code.  You can change the number of classes included in the File menu:
![image](https://github.com/user-attachments/assets/009ab017-1804-4b0c-8845-50395700c1a1)

You can also see `Refresh Code Intelligence` in the above screenshot.  Brokk will automatically
create the code intelligence graph on startup; it will set it to refresh automatically or manually
based on how long that takes.  If it is on manual refresh, this menu item is how you invoke it.

## Primary Actions

- Code: Tells the LLM you want code generation or modification.
- Ask: Ask a question referencing the current context.
- Search: Invokes a specialized agent that looks through your codebase for answers NOT in your current context.
- Run in Shell: Executes any shell command, streaming the output into the Output Panel.
- Stop: Cancels the currently running LLM or shell command.

## Context Panel
- Edit, Read: Decide which files the LLM can modify (editable) or just look at (read-only).
- Summarize: Summarizes the specified classes (declarations and signatures, but no method bodies).
- Drop: Removes snippets you no longer want in context.
- Copy, Paste: Copy snippets to your clipboard or paste external text into Brokk’s context.
  - Stacktraces get special treatment; they will be augmented with the source of referenced methods.
- Symbol Usage: Pick a symbol (class, field, or method) and automatically gather all references into a snippet.

You can doubleclick on any context to preview it.

## General Workflow
- Add relevant code or text to your context (choose Edit for modifiable files, Read for reference-only).
- Type instructions in the command box; use Code, Ask, Search, or Run in Shell as needed.
- Capture or incorporate external context using Run combined witn “Capture Text” or “Edit Files.”
- Use the History Panel to keep track, undo, or redo changes. Forget to commit and the LLM scribbled all over your
  code in the next request? No problem, Undo includes filesystem changes too.

# Examples
Here are a few scenarios illustrating how Brokk helps with real-world tasks.

## Scenario #1: Debugging a Regression with Git Bisect
1. Run `git bisect` to identify the commit that caused a regression.
2. Load the commit and the files changed by that commit as editable context: run `git show [revision]`,
   then `Capture Text` and `Edit References`.  (You can also select the new context fragment in the context table
   and click `Edit Files` from there; `Edit References` is a shortcut.)
4. Paste the stacktrace corresponding to the regression with ctrl-V or the Paste button.
5. Tell the LLM: "This stacktrace is caused by a change in the attached diff. Look at the changes to see what
   could cause the problem, and fix it."

## Scenario #2: Exploring an unfamiliar part of the codebase
1. You want to know how that BM25 search feature your colleague wrote works. Type "how does bm25 search work?" into
   the Instructions area and click Search.
2. The Search output is automatically captured as context; if you want to make changes, select it and click `Edit Files.`

## Scenario #3: AI-powered refactoring
![image](https://github.com/user-attachments/assets/e5756f8d-9cef-4467-b3c3-43872eafe8e1)

1. Invoke Symbol Usage on Project::getAnalyzerWrapper, and click Edit Files on the resulting usage context.
   This will make all files editable that include calls to that method.
2. Add Project itself as editable.  Brokk automatically includes a summary of AnalyzerWrapper in the
   auto-context.
3. Type your instructions into the instructions area and click Code:
   `Replace Project.getAnalyzerWrapper with getAnalyzer() and getAnalyzerNonBlocking() that encapsulate aw.get and aw.getNonBlocking; update the callers appropriately.`

## Working with dependencies
Often you find yourself working with poorly documented dependencies that your LLM doesn't know enough about to use without hallucinating.  Brokk can help!

Check out the source code and open it as a Brokk project. Then click on `Summarize Fields` 
and use ** globbing to select everything. (Usually you will want to target e.g. src/main and not src/ to leave out test code.)
![image](https://github.com/user-attachments/assets/1f70c224-a3de-463f-bea0-4bfb238ea2b4)

Brokk will summarize all the classes; now you can doubleclick on the context to make sure it's
what you wanted, then copy it and either paste it directly as context into Brokk as a one-off,
or save it as a file for re-use.  In this example, I did this twice in the Gumtree library:
once for `core/` and again for `client/`.
![image](https://github.com/user-attachments/assets/69f7e56d-771e-4e51-9342-ef091919c51a)

If you have a more targeted idea of what you need, you can also pick just those classes and
dial up the AutoContext size to get the surrounding infrastructure.  Here I've left the Gumtree
`client` summary and let AutoContext=20 do its thing.  This is 5x smaller than summarizing
all of `core`:
![image](https://github.com/user-attachments/assets/9e025a01-b7ba-4f17-b7d8-cdaab455e7a4)

## A note on o1pro

Brokk is particularly useful when making complex, multi-file edits with o1pro.

After setting up your session, use `copy` to pull all the content, including Brokk's prompts, into your clipboard.
Paste into o1pro and add your request at the bottom in the <goal> section.  Then paste o1pro's response back into
Brokk and have it apply the edits with the Code action.

# Current status

We are currently focused on making Brokk's Java support the best in the world.
Other languages will follow.

### Known issues

- Opening different projects is not yet supported, Brokk always works on the project from cwd
  that it was executed from
- "Stop" button does not work during search.  This is caused by https://github.com/langchain4j/langchain4j/issues/2658
- Joern (the code intelligence engine) needs to run delombok before it can analyze anything.
  Delombok is extremely slow for anything but trivial projects, making Brokk a poor fit for
  large Lombok-using codebases.

# Getting started

1. Install Java 21+.  If you don't already have a preferred way of doing this, [sdkman](https://sdkman.io/) is a good choice.
2. Download the latest Brokk jar from the [releases page](https://github.com/jbellis/brokk/releases).
3. Run Brokk by doubleclicking on the jar or with `java -jar path/to/brokk/brokk-0.X.Y.jar`.
4. Go to `File -> Edit Secret Keys` to configure your preferred LLM.
5. Go to `File -> Open Project` to open your project.

Brokk will attempt to infer a build command and style guide for your project. You can edit these 
in `.brokk/project.properties` and `.brokk/style.md`, respectively.

There is a [Brokk Discord](https://discord.gg/QjhQDK8kAj) for questions and suggestions.

## Finer points on some commands

- Brokk doesn't offer automatic running of tests (too much variance in what you might want it to do).
  Instead, Brokk allows you to run arbitrary shell commands, and import those as context with "Capture Text"
  or "Edit Files."
