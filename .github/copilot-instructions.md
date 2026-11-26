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

If you can't be certain the answer is "no", don't generate the code. Reply with a short section titled "Secret handling risk" that names the risky rule, the code path involved, and a safer alternative.

## Kotlin Formatting

- All Kotlin source files, tests, and Gradle Kotlin scripts must follow the project's ktlint formatting rules and `$/.editorconfig` configuration.
- After Kotlin or Gradle Kotlin script changes, run `./gradlew ktlintFormat --console=plain -x patchChangelog`, then run `./gradlew ktlintCheck --console=plain -x patchChangelog` in a separate invocation. Limit formatting changes to the files and lines touched by the task. Only reformat other files if the user explicitly asks for a broader cleanup in the current request.
- Fix formatting violations before reporting completion. Do not disable rules, add baselines, or ignore failures merely to make checks pass without explicit approval.
- If `./gradlew ktlintFormat` or `./gradlew ktlintCheck` fails for a non-formatting reason, report the exact command, an error output summary, and the blocker instead of changing unrelated files.
- CI must check formatting and fail on violations, not automatically rewrite source files.

## Build and Unit Tests

- Add or update unit tests for new code and changed behavior, including applicable boundary values (such as empty, null-equivalent, and maximum-length inputs) and each documented error path. Include a regression test for bug fixes. Follow the existing test conventions under `src/test/kotlin`.
- After code or build-configuration changes, run `./gradlew buildPlugin test --console=plain` locally from the repository root. Run it after the final edits, not only before making changes; do not rely solely on editor diagnostics or CI.
- Select a JDK for local validation:
  1. Check whether a JDK 17 or newer compatible with the project's Gradle toolchain requirement is configured. If so, run the build/test command above.
  2. If not, on macOS, check the bundled IntelliJ JDK for compatibility and, if compatible, run `JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" ./gradlew buildPlugin test --console=plain`.
  3. If no compatible JDK is configured or available at that macOS path, set `JAVA_HOME` to another compatible JDK 17 or newer and run the build/test command above.
  4. If no compatible JDK is available, report it as the blocker.
- Fix failures introduced by the changes and rerun validation before reporting completion. If validation is blocked or fails for an unrelated reason, report the exact command and blocker; never claim an unrun or failed check passed.
- Do not modify `CHANGELOG.md` during implementation or validation. Do not run tasks that upload or publish artifacts, such as `publishPlugin` or any task whose name starts with `publish`, during local validation. Exclude `patchChangelog` with `-x patchChangelog` if a required task would invoke it.
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

- Use `$/...` instead of `./...` for repository-relative path references, except in executable commands, where the actual shell path must be preserved.

## Code

- Every new public function or class must have at least one unit test that exercises its main path and each documented edge case in src/test/kotlin. Do not add coverage tooling unless it is already configured in the project.
- Do not use IntelliJ Platform APIs annotated with @Deprecated or @ApiStatus.ScheduledForRemoval, or APIs whose Javadoc marks them as deprecated. If the only available option is such an API, do not add it; report the blocker in the final response.

## References

- plugin.xml: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html
- Run configurations: https://plugins.jetbrains.com/docs/intellij/run-configurations.html
- Platform Gradle Plugin 2.x: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
- Extension points: https://plugins.jetbrains.com/docs/intellij/extension-point-list.html
- Doppler CLI: https://docs.doppler.com/reference/cli
- IntelliJ Community source: https://github.com/JetBrains/intellij-community