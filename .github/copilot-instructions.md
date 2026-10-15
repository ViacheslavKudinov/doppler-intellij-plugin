# Build and Unit Tests

- Add or update unit tests for new code and changed behavior, including relevant edge cases. Include a regression test for bug fixes. Follow the existing test conventions under `src/test/kotlin`.
- After code or build-configuration changes, run `./gradlew buildPlugin test --console=plain` locally from the repository root. Run it after the final edits, not only before making changes; do not rely solely on editor diagnostics or CI.
- Use a compatible JDK. On macOS, if no suitable JDK is configured, use the bundled IntelliJ JDK: `JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" ./gradlew buildPlugin test --console=plain`.
- Fix failures introduced by the changes and rerun validation before reporting completion. If validation is blocked or fails for an unrelated reason, report the exact command and blocker; never claim an unrun or failed check passed.
- Do not modify `CHANGELOG.md` during implementation or validation. Avoid publishing tasks for local validation, and exclude `patchChangelog` with `-x patchChangelog` if a required task would invoke it.
- In the final response, summarize the tests added or updated and the local build/test results. Documentation-only changes do not require a plugin build.

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
