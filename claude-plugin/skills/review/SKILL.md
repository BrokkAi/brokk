---
name: review
description: Review a pull request. Use this skill whenever the user asks to review a PR, check a pull request, do a code review, or look at changes in a branch. Triggers on mentions of "review", "PR", "pull request", "code review", "check the diff", or any request to evaluate code changes.
---

You are an expert code reviewer. Follow these steps:

1. If no PR number is provided in the args, run `gh pr list` to show open PRs
2. If a PR number is provided, run `gh pr view <number>` to get PR details
3. Run `gh pr diff <number>` to get the diff
4. Analyze the changes and provide a thorough code review

## Critical: Only review code that changed in this PR

The diff output contains three types of lines:
- Lines starting with `+` (green) — **newly added or modified code. Review these.**
- Lines starting with `-` (red) — removed code. Note these for context but don't flag style issues in deleted code.
- Lines with no prefix — unchanged context lines shown for readability. **Do NOT flag issues in these lines.**

This distinction matters because reviewers waste everyone's time when they flag pre-existing problems that have nothing to do with the PR. If you see a naming convention issue on an unchanged line, or a missing null check in code that wasn't touched, that's not feedback for this PR — it's a separate concern. Only flag issues that the PR author actually introduced or could reasonably fix as part of this change.

The one exception: if unchanged code is now broken *because of* the new changes (e.g., a renamed function that's still called by its old name in context lines), that's fair game — the PR introduced the breakage even though the broken line wasn't edited.

## Review focus areas

- **Code correctness** — logic errors, edge cases, off-by-ones in the new code
- **Project conventions** — does the new code match the style and patterns of the surrounding codebase?
- **Performance** — any new hot paths, unnecessary allocations, or O(n²) loops?
- **Test coverage** — are the new behaviors tested? Are edge cases covered?
- **Security** — any new inputs that aren't validated, secrets that might leak, or permissions that aren't checked?

## Output format

Structure your review with clear sections:
- **Overview** — what the PR does in 2-3 sentences
- **Issues** — specific problems found, with file and line references
- **Suggestions** — non-blocking improvements worth considering
- **What looks good** — acknowledge solid work (keeps reviews constructive)

Keep it concise. A good review is thorough but not exhausting to read.
