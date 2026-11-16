# Contributing

To contribute, open an [issue](https://github.com/ViacheslavKudinov/doppler-intellij-plugin/issues)
to report a bug, suggest an improvement, or discuss a proposed change.

You can also fork the repository and make changes in your fork. Share a link to the branch or
commit in an issue, with a description of the change and how you tested it. The maintainer may
consider applying those changes as a patch; acceptance is not guaranteed.

For security vulnerabilities, follow the [security policy](SECURITY.md).

## Tests

Add or update automated tests under [src/test/kotlin](src/test/kotlin) for all new or changed
behavior. Cover the main path and relevant edge cases, and follow the existing test conventions.
Every bug fix must include a regression test.

## Kotlin Formatting

Kotlin source files, tests, and Gradle Kotlin scripts must follow the project's shared
ktlint configuration. Run the same check locally that CI uses:

```bash
./gradlew ktlintCheck
```

To apply automatic formatting, run `./gradlew ktlintFormat`, then run `./gradlew ktlintCheck`
in a separate invocation. Fix any remaining violations manually. The Gradle `check` task also
includes ktlint checks. CI fails on formatting violations and does not rewrite files.

## Agentic workflows

Edit agentic workflow sources under `$/.github/workflows/*.md`, then regenerate
their compiled workflows with the pinned GitHub CLI extension:

```bash
gh extension install github/gh-aw --pin v1.2.2
gh aw compile --strict
```

Commit each source file with its corresponding `.lock.yml` file. Do not edit
compiled workflows manually.

The **Compile agentic workflows** Action runs for same-repository pull requests
targeting the default branch when agentic workflow Markdown changes. It uses the
trusted workflow definition from the base branch, checks out the PR head only as
compiler input, and never executes code or actions from that checkout. After
successful compilation, it commits changed `.lock.yml` files directly to the PR
branch; unchanged output produces no commit. Compilation errors, branch protection
restrictions, and concurrent branch updates fail the job instead of overwriting
newer work. The Action never updates the default branch or executes the generated
workflows.

Automatic commits require a GitHub App installed on this repository with
**Contents: read and write** and **Workflows: read and write** permissions.
Configure its App ID as the `GH_AW_APP_ID` repository variable. Store the App
private key in Doppler and sync it to Actions as `GH_AW_APP_PRIVATE_KEY`.
The default Actions token is insufficient to update files under
`$/.github/workflows/`. The App token is scoped to this repository, created only
after compilation, and revoked when the job ends. Missing App configuration
fails token creation; use local compilation until it is configured. Branches
without a pull request are not updated automatically.
