---
name: brokk-guided-issue
description: >-
  Guided end-to-end issue resolution workflow: select a GitHub issue,
  diagnose the codebase, plan changes, implement in a worktree, review
  with specialist agents, and open a pull request.
---

# Guided Issue Resolution

This skill walks you through the complete lifecycle of resolving a GitHub
issue: selecting an issue, diagnosing the codebase, planning changes,
implementing in an isolated worktree, reviewing with specialist agents,
triaging findings, and opening a pull request.

## Step 1 -- Select Issue

### If an issue number is provided as argument (e.g. `/guided-issue 123`)

Skip directly to **Step 2** using that number.

### If no argument is provided

First verify `gh` is available by running `gh --version`. If it is not
installed, tell the user to install it from https://cli.github.com/ and
authenticate with `gh auth login`, then stop.

Present a menu to the user using `AskUserQuestion` with these options:

- **List recent open issues** -- Show the most recent open issues
- **List issues assigned to me** -- Show issues assigned to the current user
- **Search issues by keyword** -- Search for issues matching a query
- **Enter issue number directly** -- Skip browsing and provide a number

Do NOT pick a default. Do NOT proceed until the user has chosen.

### Fetching issues

Based on the user's choice:

- **Recent open issues**:
  ```bash
  gh issue list --state open --limit 20
  ```

- **Assigned to me**:
  ```bash
  gh issue list --state open --assignee @me --limit 20
  ```

- **Search by keyword**: Ask the user for a search query, then:
  ```bash
  gh issue list --search "<query>" --limit 20
  ```

- **Enter number directly**: Ask the user for the issue number.

For list results, present them and let the user pick one. Then display
full issue details:

```bash
gh issue view <number>
```

## Step 2 -- Diagnose

1. Call `activateWorkspace` with the current project path so Brokk tools
   work.

2. Fetch structured issue details:
   ```bash
   gh issue view <number> --json title,body,comments,labels,assignees
   ```

3. Spawn the `issue-diagnostician` agent, passing it the full issue text
   (title, body, comments, labels). The agent will use Brokk MCP tools
   to explore the codebase and produce a structured diagnosis.

4. Present the diagnosis to the user.

5. Ask the user via `AskUserQuestion`:
   - **Yes, proceed to planning** -- Continue to Step 3
   - **No, let me provide more context** -- Ask what's wrong, then
     re-run the diagnostician with the additional context (max 3 loops)
   - **Cancel** -- Stop the workflow

## Step 3 -- Plan Implementation

1. Spawn the `issue-planner` agent, passing it:
   - The diagnosis from Step 2
   - The original issue details (title, body, comments)
   The agent will produce an ordered implementation plan with specific
   files, methods, and changes.

2. Present the plan to the user.

3. Ask the user via `AskUserQuestion`:
   - **Yes, proceed to implementation** -- Continue to Step 4
   - **Suggest changes** -- Ask what should change, then re-run the
     planner with the feedback (max 3 loops)
   - **Cancel** -- Stop the workflow

## Step 4 -- Implement in Worktree

1. Derive a branch name from the issue:
   - Format: `brokk/issue-<number>-<slug>`
   - Slug: lowercase issue title, replace non-alphanumeric with dashes,
     truncate to 40 characters, trim trailing dashes.
   - Example: issue #42 "Fix null pointer in UserService" becomes
     `brokk/issue-42-fix-null-pointer-in-userservice`

2. Use `EnterWorktree` to create an isolated worktree on that branch.

3. Call `activateWorkspace` again with the **worktree path** so Brokk
   tools target the worktree.

4. Execute each step of the plan in order, using Write, Edit, and Bash
   tools to make code changes.

5. After implementation, look for build/test infrastructure and run it:
   - If `gradlew` exists: `./gradlew build`
   - If `package.json` exists: `npm test` or `yarn test`
   - If `Makefile` exists: `make test`
   - If `pyproject.toml` exists: `pytest` or `uv run pytest`
   - If tests fail, fix the issues before proceeding.

## Step 5 -- Review Changes

1. Detect the base branch:
   ```bash
   DEFAULT_BRANCH=$(git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | sed 's@^refs/remotes/origin/@@')
   if [ -z "$DEFAULT_BRANCH" ]; then
     DEFAULT_BRANCH=$(git remote show origin 2>/dev/null | grep 'HEAD branch' | sed 's/.*: //')
   fi
   ```

2. Compute the diff:
   ```bash
   git diff "$DEFAULT_BRANCH"...HEAD
   ```

3. Parse the diff to build a list of changed files, grouped into source,
   test, infrastructure/config, and documentation. Note total lines
   added/removed.

4. Spawn all 5 reviewer agents in a **single response** using parallel
   `Agent` tool calls. Each reviewer prompt must include the diff text,
   changed-file list, and the original issue title for context.

   | Agent Name | Focus |
   |------------|-------|
   | `security-reviewer` | Injection, auth bypass, data leaks, backdoors, CVEs |
   | `dry-reviewer` | Code duplication, reimplemented functionality |
   | `senior-dev-reviewer` | Intent verification, smuggled changes, missing tests |
   | `devops-reviewer` | Infrastructure, CI/CD, operational concerns |
   | `architect-reviewer` | Coupling, cohesion, SOLID, design patterns |

5. Consolidate findings:
   - Collect all findings from all agents.
   - Deduplicate -- if multiple agents flagged the same issue, merge
     them and note which agents identified it.
   - Sort by severity: CRITICAL, HIGH, MEDIUM, LOW.
   - Omit empty severity sections.

6. Present the review report.

### Report Format

```
# Review: <issue-title>

**Issue**: #<number> | **Branch**: <branch> | **Files Changed**: <count>

## Findings

### CRITICAL
| # | Finding | File(s) | Agent(s) | Details |
|---|---------|---------|----------|---------|

### HIGH
| # | Finding | File(s) | Agent(s) | Details |
|---|---------|---------|----------|---------|

### MEDIUM
| # | Finding | File(s) | Agent(s) | Details |
|---|---------|---------|----------|---------|

### LOW
| # | Finding | File(s) | Agent(s) | Details |
|---|---------|---------|----------|---------|

## Summary
<2-3 sentence assessment>
```

## Step 6 -- Triage Findings

If there are no CRITICAL or HIGH findings, skip to Step 7.

For each CRITICAL or HIGH finding, ask the user via `AskUserQuestion`:

- **Fix it now** -- Make the code change to address the finding
- **Create a new issue** -- File a GitHub issue for it:
  ```bash
  gh issue create --title "<finding summary>" --body "<finding details>\n\nFound during review of #<number>"
  ```
- **Dismiss** -- Skip this finding

After addressing CRITICAL/HIGH findings, if there are MEDIUM or LOW
findings, present a summary and ask via `AskUserQuestion`:

- **Address selected findings** -- Let the user pick which to fix
- **Skip remaining findings** -- Proceed to Step 7

Implement any chosen fixes.

## Step 7 -- Commit and PR

1. Stage all changes:
   ```bash
   git add -A
   ```

2. Commit with a message referencing the issue:
   ```bash
   git commit -m "Fixes #<number>: <issue-title>"
   ```

3. Push the branch:
   ```bash
   git push -u origin <branch-name>
   ```

4. Create a pull request:
   ```bash
   gh pr create --title "Fixes #<number>: <short-title>" --body "$(cat <<'EOF'
   ## Summary

   Fixes #<number>

   <2-3 bullet points summarizing the changes>

   ## Changes

   <list of files changed and what was done>

   ## Test Plan

   <how the changes were tested>
   EOF
   )"
   ```

5. Use `ExitWorktree` to return to the original working directory.

6. Display the PR URL to the user.
