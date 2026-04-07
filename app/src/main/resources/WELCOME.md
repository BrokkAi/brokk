# Welcome to Brokk!

Brokk is code intelligence for AI.

Check out the README at https://github.com/jbellis/brokk for more details.

## Getting Started
- Type in the Instructions area; right-click on the suggested files to add them to the Workspace
- Use Deep Scan to get higher-quality recommendations of what to add to the Workspace to accomplish your task
- You can manually add files or summaries to your Workspace from the Workspace menu
- Reference material should usually be added with `Context -> Summarize Files` to keep your context streamlined.

Completion Suggestions: Press Ctrl+Space in any dialog to get completions.

## Using the Workspace

- Right click on any context entry in the Workspace to view, modify, or drop it from the Workspace.
- Right click on the Activity log to reset the context to an early point in time, with or without undoing.
  (The difference is that undo affects file contents, as well.)

## More Ways to add Context
- You can add diffs or older versions of files to the Workspace from the Git commit or file logs.
- Paste text (including stacktraces), and Brokk will automatically parse them for references to
  code sources.
- Use the `Capture` button below this textarea to capture the current output as context.

## Working with external LLMs
`Edit -> Copy`, or `Copy All` in the context panel right click menu, gathers your entire context and relevant instructions for use with external editors like o1pro.
