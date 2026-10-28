# Copilot Instructions: Doppler JetBrains Plugin

A Kotlin plugin for JetBrains IDEs that manages [Doppler](https://www.doppler.com) secrets. It talks to Doppler **only through the `doppler` CLI subprocess** and injects secrets into run configurations at launch.

## Secrets: hard rules

Secrets must never be persisted or leaked. Before suggesting any code, check that it does **not**:

- write a secret value to a file (temp files, `.idea/`, PSI cache)
- log a secret value at any level
- put a secret value in an exception message that reaches a logger
- pass a secret value as a CLI argument (visible in `ps`)
- put a secret value in a notification title or body
- read, write, or transmit a Doppler token in plugin code
- make any outbound network call other than the `doppler` subprocess

If you can't be certain the answer is "no", don't generate the code. Say what the risk is.

## Kotlin Formatting

- All Kotlin source files, tests, and Gradle Kotlin scripts must follow the project's ktlint formatting rules and `$/.editorconfig` configuration.
- After Kotlin or Gradle Kotlin script changes, run `./gradlew ktlintFormat --console=plain -x patchChangelog`, then run `./gradlew ktlintCheck --console=plain -x patchChangelog` in a separate invocation. Keep formatting changes within the task's scope unless a broader cleanup is authorized.
- Fix formatting violations before reporting completion. Do not disable rules, add baselines, or ignore failures merely to make checks pass without explicit approval.
- CI must check formatting and fail on violations, not automatically rewrite source files.

## Build and Unit Tests

- Add or update unit tests for new code and changed behavior, including relevant edge cases. Include a regression test for bug fixes. Follow the existing test conventions under `src/test/kotlin`.
- After code or build-configuration changes, run `./gradlew buildPlugin test --console=plain` locally from the repository root. Run it after the final edits, not only before making changes; do not rely solely on editor diagnostics or CI.
- Use a compatible JDK. On macOS, if no suitable JDK is configured, use the bundled IntelliJ JDK: `JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" ./gradlew buildPlugin test --console=plain`. On Linux or Windows, if no suitable JDK is configured, set JAVA_HOME to a JDK 17 or newer installation and run the same command. If no JDK is available, report that as the blocker.
- Fix failures introduced by the changes and rerun validation before reporting completion. If validation is blocked or fails for an unrelated reason, report the exact command and blocker; never claim an unrun or failed check passed.
- Do not modify `CHANGELOG.md` during implementation or validation. Avoid publishing tasks for local validation, and exclude `patchChangelog` with `-x patchChangelog` if a required task would invoke it.
- In the final response, summarize the tests added or updated and the local build/test results. Documentation-only changes do not require a plugin build.

## Commit Messages and Pull Request Titles

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

## Path References

- Use `$/...` instead of `./...` for repository-relative path references.

## Code

- Every new public function or class must have at least one unit test that exercises its main path and each documented edge case in src/test/kotlin. Do not add coverage tooling unless it is already configured in the project.
- Do not use IntelliJ Platform APIs annotated with @Deprecated or @ApiStatus.ScheduledForRemoval, or APIs whose Javadoc marks them as deprecated. If the only available option is deprecated, state this in the final response and do not add it silently.

## References

- plugin.xml: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html
- Run configurations: https://plugins.jetbrains.com/docs/intellij/run-configurations.html
- Platform Gradle Plugin 2.x: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
- Extension points: https://plugins.jetbrains.com/docs/intellij/extension-point-list.html
- Doppler CLI: https://docs.doppler.com/reference/cli
- IntelliJ Community source: https://github.com/JetBrains/intellij-community