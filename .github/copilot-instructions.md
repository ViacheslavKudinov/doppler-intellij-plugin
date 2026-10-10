# Commit Messages and Pull Request Titles

- Use Conventional Commits for all generated commit messages and pull request titles: `<type>[optional scope][!]: <description>`.
- Choose a type that reflects the change: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, or `revert`.
- If a change spans multiple unrelated concerns, choose the type that reflects the primary change and note the secondary changes in the commit body or PR description. If no single type fits, suggest splitting the change into separate commits or pull requests.
- Use an optional concise scope when useful, such as `cli`, `editor`, `deps`, or `workflows`.
- Write a short, imperative description with a lowercase initial and no trailing period.
- Mark breaking changes with `!` before the colon and explain them in a `BREAKING CHANGE:` footer in the commit body or PR description.
- Make the PR title describe the overall change and suitable for use as a squash-merge commit message.
- Do not create commits or pull requests unless explicitly requested.

Examples:

- `feat(editor): add secret editing`
- `fix(cli): handle stdin write failures`
- `ci: increase Kotlin compiler heap for CodeQL`
- `build(deps): upgrade Kotlin Gradle plugin`
