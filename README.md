# Doppler for IntelliJ IDEA (Unofficial)

[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/ViacheslavKudinov/doppler-intellij-plugin/badge)](https://scorecard.dev/viewer/?uri=github.com/ViacheslavKudinov/doppler-intellij-plugin)
[![License: Apache 2.0](https://img.shields.io/badge/license-apache--2.0-green.svg)](LICENSE)
[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/34961?label=JetBrains&logo=jetbrains)](https://plugins.jetbrains.com/plugin/34961-doppler-secrets-manager-unofficial-)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34961-doppler-secrets-manager-unofficial-.svg)](https://plugins.jetbrains.com/plugin/34961-doppler-secrets-manager-unofficial-)
[![codecov](https://codecov.io/gh/ViacheslavKudinov/doppler-intellij-plugin/graph/badge.svg?token=4EI6QNLEYR)](https://codecov.io/gh/ViacheslavKudinov/doppler-intellij-plugin)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=ViacheslavKudinov_doppler-intellij-plugin&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=ViacheslavKudinov_doppler-intellij-plugin)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=ViacheslavKudinov_doppler-intellij-plugin&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=ViacheslavKudinov_doppler-intellij-plugin)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=ViacheslavKudinov_doppler-intellij-plugin&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=ViacheslavKudinov_doppler-intellij-plugin)
<a href="https://app.aikido.dev/audit-report/external/V4RyMEgJX2I1ghlnmzgpbWY8/request" target="_blank">
    <img src="https://app.aikido.dev/assets/badges/full-light-theme.svg" alt="Aikido Security Audit Report" height="40" />    
</a>

**This is an unofficial, independently developed plugin. It is not affiliated with, endorsed by,
or supported by Doppler.** Doppler's name, trademarks, and copyrighted materials belong to Doppler.
The plugin uses an original icon, not the official Doppler logo. The plugin's source code remains
available under the [Apache License 2.0](LICENSE).

Browse and edit your [Doppler](https://doppler.com) secrets without leaving the IDE.

## 🛡️ Assessing the risk of an unofficial plugin

Installing an IDE plugin means trusting executable code with access to your development
environment. A plugin can potentially read project files and secrets, start processes, and
make network requests with the IDE user's permissions. This plugin handles secret values and
invokes an authenticated Doppler CLI, so a malicious change or compromised dependency could
expose secrets or modify resources accessible to that CLI account. Independent development
also means Doppler does not review, maintain, or support this plugin.

If your organization requires additional assurance, review and build a specific release yourself:

1. **Select and record the source.** Check out a release tag such as `v1.7.0`, record its full
   commit SHA, and compare it with the release evidence or another trusted record. A tag name
   alone does not prove who authored or reviewed its contents. Reassess changes before each update.
2. **Assess the code and build tools before running them.** Review the plugin sources, Gradle
   wrapper, settings, build scripts, dependencies, and release workflows. Gradle configuration
   and tests execute code too; perform the build in a disposable environment without production
   credentials or access to sensitive projects.
3. **Threat model your intended use.** Identify the secrets, source code, and Doppler permissions
   at risk. Trace how secrets enter the editor and launched processes, where data could be
   persisted or logged, which executables are invoked, and which network destinations are used.
   Consider malicious updates, compromised dependencies, and other code running in the same IDE
   or under the same OS user. Use a test Doppler config and the least privileges your workflow needs.
4. **Run security checks and inspect the findings.** Combine manual review with static analysis,
   tests, dependency/SBOM vulnerability scanning, and plugin compatibility verification. Review
   the actual configuration and results behind repository badges. Scanners have coverage limits;
   investigate whether a reported dependency is shipped, used only during builds/tests, or
   provided by the IDE. See the [SBOM scanning example](SUPPLY_CHAIN_SECURITY.md#scan-a-published-sbom-for-vulnerabilities-with-grype).
5. **Build and install the archive you assessed.** After reviewing the selected checkout, build
   its ZIP and SBOM, run the tests, and scan the SBOM. Keep the commit SHA, build environment,
   scan results, and archive checksum with your review record.

For example, start with a separate checkout of version **1.7.0**:

```bash
git clone --branch v1.7.0 --depth 1 \
  https://github.com/ViacheslavKudinov/doppler-intellij-plugin.git doppler-plugin-review
cd doppler-plugin-review
git rev-parse HEAD

# Review the source and build configuration before executing these commands.
# Use a disposable build environment without production credentials.
./gradlew buildPlugin test cyclonedxBom -x patchChangelog --console=plain
grype sbom:build/distributions/doppler-plugin-1.7.0.cdx.json
shasum -a 256 build/distributions/doppler-plugin-1.7.0.zip
```

Install `build/distributions/doppler-plugin-1.7.0.zip` using
**Settings | Plugins | ⚙ | Install Plugin from Disk…**, then restart the IDE when prompted.
[JetBrains documents installation from a local ZIP](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk).
Start in a separate IDE profile or instance with non-sensitive test data. A separate IDE instance
under the same OS user does not isolate filesystem access or credentials; use OS-level isolation
when that boundary is required. Control automatic plugin updates so a later Marketplace update
does not replace the version you reviewed without another assessment.

Building from reviewed source gives you control over the installed archive and its origin.
The source, downloaded dependencies, build environment, and tools still require trust. Successful
tests, clean scans, signatures, and attestations provide evidence with specific limits; they do
not guarantee that a plugin is safe. A local build may differ from the published signed archive
and does not inherit its signature or attestation. For published downloads, follow the
[release verification instructions](#verify-a-github-release-download) and read the
[supply chain security guide](SUPPLY_CHAIN_SECURITY.md).

## ✨ Features

- **Projects tree:** a `Doppler` tool window listing your projects, grouped into environments and configs.
- **Edit secrets:** single-click a config to open its secrets in an editor tab.
- **Two-way sync:** saving the tab (`⌘S` / `Ctrl+S`) pushes your changes to Doppler; click the config
  again to pull the latest values. Saves check for conflicting remote edits and apply replacements
  before deletions, keeping editor changes available for correction if a write is rejected.
- **Setup guidance:** the tool window detects a missing CLI or missing login and links to the fix.
- **Run and Debug:** opt in per IDE project to inject fresh secrets into Java, Maven, Python,
  Node.js/npm, Gradle, Docker image/Dockerfile, and Docker Compose launches without saving injected
  values in run configurations. Explicit run configuration variables take precedence.
- **Secret name mapping and transformers:** control the injected names of secrets from the default
  Doppler project/config selected in **Folder Setup**. Keep original names with `none`, apply a
  built-in transformer such as `camel` (`API_KEY` to `apiKey`) or `lower-kebab`, or choose `custom`
  to rename selected secrets (for example, `API_KEY` to `APP_API_KEY`) while leaving unmapped names
  unchanged. These settings are per IDE project and apply to Run and Debug injection only;
  names and values stored in Doppler are never changed.
- **Runnable examples:** try every supported integration with synthetic values and custom mappings
  using the [injection examples guide](examples/README.md), without printing environment values.

## 🧩 Compatibility

Targets **IntelliJ IDEA 2025.3 (build 253) and later**, Community or Ultimate. The plugin depends on
both `com.intellij.modules.platform` and the **Terminal plugin** (`org.jetbrains.plugins.terminal`),
which must be installed and enabled. Other JetBrains IDEs must satisfy both dependencies and the
minimum platform build requirement; compatibility is not guaranteed solely by using the IntelliJ Platform.

The plugin is compiled against the *oldest* supported release and leaves `untilBuild` unset so newer
IDE builds can load it. This does not guarantee compatibility with every future IDE release:
platform API changes may require a plugin update. Compatibility must still be verified against
the supported IDE versions.

Compatibility is checked with the JetBrains Plugin Verifier:

```bash
./gradlew verifyPlugin
```

To widen or shift the range, edit `sinceBuild` and the verified IDEs in `build.gradle.kts`.

## 📋 Requirements

Requires **IntelliJ Platform 2025.3 (build 253) or later** and an installed, authenticated
[Doppler CLI](https://docs.doppler.com/docs/install-cli).

### Dependencies defined in plugin.xml

| Dependency ID | Required / optional | Plugin |
| --- | --- | --- |
| `org.jetbrains.plugins.terminal` | Required | [Terminal](https://plugins.jetbrains.com/plugin/13123-terminal) |
| `com.intellij.modules.platform` | Required | IntelliJ Platform module |
| `com.intellij.java` | Optional | [Java](https://plugins.jetbrains.com/plugin/27368-java) |
| `com.intellij.gradle` | Optional | [Gradle](https://plugins.jetbrains.com/plugin/13112-gradle) |
| `PythonCore` | Optional | [Python Community Edition](https://plugins.jetbrains.com/plugin/7322-python-community-edition) |
| `JavaScript` | Optional | [JavaScript and TypeScript](https://plugins.jetbrains.com/plugin/22069-javascript-and-typescript) |
| `Docker` | Optional | [Docker](https://plugins.jetbrains.com/plugin/7724-docker) |

For more information, see the
[Plugin Compatibility Guide](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html).

The Terminal plugin must be installed and enabled. Optional dependencies enable the corresponding
Run and Debug integrations; missing language or Docker plugins do not prevent browsing and editing
Doppler secrets. Plugin availability depends on your IDE and edition. Enable the relevant plugins
under **Settings | Plugins**.

Configure language runtimes, Maven, Gradle, Node.js/npm, Docker Engine, and Docker Compose as needed
for your native Run configurations. Doppler does not install these tools or bundle the IDE plugins.

### Doppler CLI

The plugin drives the Doppler CLI, which owns authentication:

```bash
brew install dopplerhq/cli/doppler   # or see the install docs for your platform
doppler login
```

The CLI is auto-detected on `PATH` and in common install locations. If your IDE was launched from the GUI
with a minimal `PATH`, set the binary location explicitly in **Settings | Tools | Doppler**.

Global CLI settings are available even without an open project.

### Default project and config

To associate the current IDE project folder with a Doppler project and config, click
**Folder Setup…** at the top of **Settings | Tools | Doppler**, or open its **Folder Setup**
child page. Select a project and config,
then click **Apply** or **OK**. If you change the CLI login while Settings is open,
click **Reload** before applying folder setup changes. The plugin uses the Doppler CLI's folder configuration,
so this setup also applies to CLI commands run from that folder. **Clear folder setup**
removes this folder's override when applied; a parent folder's defaults may still apply.
Use **Reload** to read changes made with `doppler setup` outside the IDE.
While folder setup or config choices are loading, the fields are disabled and a spinner appears
beside the loading message.
The configured project and config are marked with a bookmark icon in the Doppler tree.
Folder setup is checked in the background every 30 seconds so CLI changes update the bookmark
and any unchanged Settings selections. Setup operations use the existing CLI call logging;
background checks appear as `FOLDER_SETUP_POLL` when **Log CLI calls** is enabled.

## Interface Reference

The plugin's user-facing interfaces are its IDE controls, YAML secret documents, and Run and
Debug environment injection. The following sections describe their accepted inputs, outputs,
and side effects without requiring readers to inspect the implementation.

| Interface | Inputs | Outputs and effects | Reference |
| --- | --- | --- | --- |
| Doppler tool window and folder setup | Project/config selections, config clicks, and refresh actions | A projects/environments/configs tree and secret editor tabs; applying folder setup changes the CLI defaults for the IDE project folder | [Folder setup](#default-project-and-config) |
| YAML secret editor | Secret-name/value mappings, restricted `null` values, empty strings, and removed or cleared keys | Saving creates, updates, or deletes Doppler secrets; invalid YAML is rejected before writes, and failed saves retain local edits | [Editing secrets](#-editing-secrets) |
| Run and Debug injection | Project-local approval, folder defaults, name transformation or custom mappings, and explicit run configuration variables | Fresh secrets in the launched process environment; explicit run configuration variables take precedence, and injected values are not saved in run configurations | [Injection reference](#-run-and-debug-environment-injection) and [runnable examples](examples/README.md) |
| Connection checks and optional CLI tracing | **Test Connection** and the **Log CLI calls** setting | Connection status and sanitized CLI trace entries, without secret values, tokens, or CLI output | [CLI setup](#doppler-cli) and [diagnostic logging](#-building) |

### Settings Reference

Open **Settings | Tools | Doppler** for IDE-wide settings, or its **Folder Setup** and
**Run and Debug** child pages for the current project.

| Setting | Scope | Default | Accepted input and effect |
| --- | --- | --- | --- |
| Doppler CLI path | IDE-wide | Empty (auto-detect) | Path to the Doppler executable; an empty field searches `PATH` and common installation locations |
| Re-read open configs every | IDE-wide | 5 seconds | Integer from 0 to 3600; `0` disables automatic secret-value refresh |
| Log CLI calls | IDE-wide | Off | Enables sanitized invocation metadata in the IDE log; does not log secret values or CLI output |
| Folder Setup project/config | IDE project folder, managed by the CLI | Existing CLI folder defaults, including inherited defaults | Select a Doppler project and config, then apply; clearing removes this folder's override, not inherited parent defaults |
| Automatically inject Doppler secrets into Run and Debug | Current IDE project, with user-local approval | Off | Enables secret injection for supported launches; project files alone cannot grant approval |
| Name transformation | Current IDE project | None | `none`, `camel`, `upper-camel`, `lower-snake`, `tf-var`, `dotnet-env`, `lower-kebab`, or `custom`; changes injected names only |
| Custom name mappings | Current IDE project | No mappings | Pairs of existing and new secret names; used only with Custom, with unmapped names unchanged |

Disabling secret-value refresh does not disable account-validation checks while secret configs
are open. See [Editing secrets](#-editing-secrets) for refresh conditions, save semantics, and
failure recovery, and [How secrets are handled](#-how-secrets-are-handled) for data-handling limits.

## ▶️ Run and Debug environment injection

Choose your default project and config in **Settings | Tools | Doppler | Folder Setup**.
Then click **Run and Debug…** on the main Doppler settings page and enable **Automatically inject Doppler
secrets into Run and Debug**. Injection is automatic on every launch; there is no Inject button.
The setting is local to the current IDE project and starts disabled.
Enabling it also records approval for this project in this IDE's user settings, outside the project
and without settings synchronization. Workspace files cannot grant approval. After upgrading from
a version without this approval check, enable injection once again on this settings page.

**Name transformation** defaults to **None**, which keeps secret names unchanged. Optionally choose
one of [Doppler's name transformers](https://docs.doppler.com/docs/accessing-secrets#name-transformers):
`camel`, `upper-camel`, `lower-snake`, `tf-var`, `dotnet-env`, or `lower-kebab`. The CLI transforms
only the injected secret names; values and saved Doppler secrets are unchanged. For example,
`camel` injects `API_KEY` as `apiKey`. Existing run configuration variables still take precedence
when their names match the transformed names. If Doppler reports conflicting transformed names,
the launch fails rather than injecting a partial environment.

**Custom** renames only the secrets you list. Add pairs of current and new secret names; matching
secrets are injected under the new name, and all other secrets keep their names. The current-name
cell is a dropdown of secrets in the default config; names cannot be typed. Saved names missing
from the config are shown in red but kept until a replacement is selected or the pair is removed.
Pairs are stored per project and kept while another transformation is selected, but used only while
**Custom** is selected.
Invalid mappings or mappings that would overwrite another Doppler secret stop the launch instead
of silently keeping an old name or dropping a variable.
**Every Run or Debug launch fetches fresh secrets**, so changes saved in the plugin, CLI, or
Doppler dashboard apply to the next launch. The injection service does not cache secret values.
Launched processes keep their inherited environment for their lifetime; changes apply on the
next launch rather than updating an already running process.
Every launch checks the current CLI account and folder defaults so an account or config change
does not reuse another config's values. The launch aborts if the account or folder defaults change
while secrets are being loaded.
With **Log CLI calls** enabled on the main Doppler settings page, every injection CLI call is
logged as `RUN_INJECTION`, including the folder lookup, account checks, and secrets download.
Logs contain sanitized command metadata, duration, and exit status; secret values and CLI output
are never logged.

Values are fetched with `doppler secrets download --no-file --no-fallback --format json`, so references
are resolved by Doppler without creating an `.env` file or reading/writing CLI fallback storage.
If Doppler is unavailable, injection fails instead of using stored secrets. Explicit environment
variables already set in the run configuration take precedence. Missing folder defaults leave the launch unchanged;
failure to read a configured environment stops the launch with a message that contains no CLI output.

Supported launch paths (with the corresponding IDE plugins enabled):

| Runtime | Injection point |
| --- | --- |
| Java and Maven | JVM launch parameters, including Run and Debug |
| Python | An execution-only profile passed to Python's native Run/Debug runner, including target-based launches |
| Node.js | Node target launch sessions, including npm-based configurations |
| Gradle | Task execution through the Tooling API, including delegated runs and debugging; project sync is excluded |
| Docker image / Dockerfile | An execution-only copy of the container deployment settings |
| Docker Compose | The Compose process environment, for substitution into the Compose file |

See the [Compose injection guide](examples/README.md#docker-compose) for variable forwarding
and custom name mapping details.

The plugin never saves injected values in run configurations and does not change the IDE's own
process environment. An application or container already running keeps its inherited environment;
the plugin cannot remove variables from that process.
Docker's execution-only command preview is disabled to avoid showing injected values.

### Try the injection examples

See the [injection examples](examples/README.md) for runnable Java, Maven, Python, Node.js/npm,
Gradle, Docker image/Dockerfile, and Docker Compose checks, including custom name mappings.
The guide covers IDE setup, synthetic test values, and cleanup without printing environment values.

## 🔐 How secrets are handled

- Secrets live **in memory only**. The editor tab is backed by a custom in-memory virtual file system, so
  secret values are never written to disk, to the IDE's local history, or to the project directory.
- Secret **values are passed to the CLI over stdin**, never as command-line arguments, so they cannot be
  read from the process table by other local users.
- The plugin **never stores or transmits your Doppler token**. Only non-sensitive preferences are persisted.
- `DOPPLER_PROJECT`, `DOPPLER_CONFIG` and `DOPPLER_ENVIRONMENT` are managed by Doppler and are hidden from
  the editor and excluded from writes.
- YAML syntax errors report only a location, never document excerpts that could expose secret values
  in notifications or IDE diagnostic logs.
- The plugin accepts secret names containing ASCII letters, digits, `_`, `/`, `:`, `.`, and `-`,
  including leading digits. Doppler's workplace naming rules still apply. Names are passed after
  the CLI's `--` separator so they cannot be interpreted as flags; `=` is rejected to prevent
  a name from becoming a `KEY=value` argument.
- Notification text is HTML-escaped, so project, config and CLI-provided text cannot inject markup.
- When the last editor tab for a config closes, its secrets are dropped from memory.

## ✏️ Editing secrets

Single-click a config in the tree to open it. Secrets are shown as YAML:

```yaml
#################################################################
##  This file was generated by Doppler. When you make          ##
##  changes to this file, they will be saved to your           ##
##  config in Doppler as well.                                 ##
##                                                             ##
##  A `null` value indicates that the secret is restricted.    ##
##  Restricted secrets may be overwritten but cannot be read.  ##
#################################################################

DB_URL: psql://app@localhost/appdb
PRIVATE_KEY: |-
  -----BEGIN PRIVATE KEY-----
  EXAMPLE-ONLY-NOT-A-REAL-KEY
  -----END PRIVATE KEY-----
RESTRICTED_ONE: null
STRIPE_KEY: sk_test_EXAMPLE_NOT_A_REAL_KEY
```

Multi-line values are written as `|-` literal blocks so certificates and JSON blobs stay readable.
Every document is re-parsed before it is shown; if a block scalar would not round-trip exactly, the
quoted form is emitted instead, so a value can never be silently corrupted.

A `null` value marks a **restricted** secret, which can be overwritten by replacing the `null` but
left as `null` is a no-op — restricted values are never written back.

Deleting a secret works either way: remove its line, or clear its value so only `MY_SECRET:` remains.
Both issue a `doppler secrets delete`, and both work for restricted secrets too. Note that clearing
(`MY_SECRET:`) is distinct from the literal `MY_SECRET: null`, and from `MY_SECRET: ""` which stores
an empty string.

Selected config tabs are re-read from Doppler every 5 seconds while their IDE window is active,
so dashboard edits show up on their own. Switching to another application or an ordinary editor
tab pauses automatic secret-value refresh. A
refresh is paused while you edit and resumes on the next interval after you undo all unsaved edits
or successfully save to Doppler. Pending or rejected/failed syncs also pause refresh. When all open
configs are paused, account-only validation still runs every 30 seconds while configs are open,
including when value refresh is disabled. This clears cached documents on logout or account change.
Normal value refresh already validates the account and postpones the standalone check, avoiding
an extra request. Standalone checks are logged as `ACCOUNT_VALIDATION`.
A rejected/failed save stays in the editor; correct the document and save it again,
or click the config again to explicitly discard it and reload. Change or
disable the interval in **Settings | Tools | Doppler**.

On save the plugin computes a **minimal diff** against the last known server state and issues only the
required writes — changed and added keys via `doppler secrets set`, removed keys via `doppler secrets delete`.
All replacement writes must succeed before any old names are deleted. A rename at a secret limit
therefore requires temporary headroom: increase the limit or free unused capacity in Doppler, then
retry. A rejected replacement leaves the original names intact and your edits available; the plugin
never retries by deleting originals first. Earlier successful writes may already have been applied.
If the document is not valid YAML the save is rejected and nothing is sent, so a malformed buffer can never
wipe a config. To discard local edits, click the config in the tree again to re-fetch it.
Malformed CLI snapshots are rejected instead of being treated as empty configs.

## 🛠️ Building

To inspect CLI activity, enable **Settings | Tools | Doppler | Log CLI calls** and apply.
Open **Help | Show Log in Finder/Explorer** and search `idea.log` for `Doppler CLI`.
Each invocation has matching `START` and `END` entries with a call ID, operation label,
duration, exit code, and outcome. Count `START` entries to count CLI invocations (the CLI
may make multiple HTTP requests internally). Tree events are labeled `TREE_STARTUP`,
`TREE_PANEL_FOCUS`, `TREE_WINDOW_FOCUS`, `TREE_REFRESH_BUTTON`, `TREE_RETRY`, or `TREE_EXPAND`.
Config fetches caused by a tree reload keep that reload's label. Other labels include
`AUTO_REFRESH` (periodic secret refresh), `CONFIG_OPEN`, `CONFIG_RELOAD`, `SAVE`,
`SAVE_RECOVERY`, `AUTOCOMPLETE`, and `TEST_CONNECTION`.
Each automatic config refresh runs `me`, `secrets`, then `me` to check the account before
and after the read. All three calls include masked project/config/environment context.
Logging is off by default. Project, config, and known environment names show their first 60%
(rounded down) followed by `....`; at least one character is always hidden. Unknown environments
are omitted without extra requests. Secret names and other arguments remain redacted; stdin,
CLI output, and tokens are not included in these trace entries.

The project uses the [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html).

```bash
./gradlew test         # run the test suite
./gradlew buildPlugin  # produce build/distributions/doppler-plugin-<version>.zip
./gradlew cyclonedxBom # produce a full Gradle dependency SBOM in build/distributions
./gradlew verifyPlugin # JetBrains Plugin Verifier across the supported IDE range
./gradlew runIde       # launch a sandbox IDE with the plugin installed
```

Use `./gradlew buildPlugin -Psnapshot` to append `-SNAPSHOT` to the version from
`gradle.properties` without modifying the file. Without the flag, the version stays unchanged.

If no JDK is on your `PATH`, any JetBrains IDE ships one:

```bash
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
```

Install the built archive via **Settings | Plugins | ⚙ | Install Plugin from Disk…**.

Restart the IDE when prompted after installation, updates, or removal. The plugin requires
restart because its in-memory secret editors cannot be reliably unloaded while the IDE is running.

## 🔍 CodeQL analysis

The [CodeQL workflow](.github/workflows/codeql.yaml) builds and analyzes Actions and Kotlin in a
job with read-only repository permissions. It stores only SARIF results as temporary artifacts;
automatic SARIF and database uploads are disabled in that job.

A separate job on a fresh runner uploads the reports with `security-events: write`. It does not
check out the repository or execute project code. Reports come from the same workflow run, and
the upload uses the workflow's commit and ref. The final jobs retain the `Analyze (actions)` and
`Analyze (java-kotlin)` check names and fail if analysis or upload fails.

This boundary isolates upload credentials. SARIF produced by PR-controlled build code remains
untrusted and can still be manipulated; the job split does not guarantee analysis integrity.

## 📦 Releases

New to provenance, SBOMs, or attestations? Read the
[release security guide](SUPPLY_CHAIN_SECURITY.md) for what they mean, why we publish them,
how verification helps, and what it cannot prove.

GitHub releases are [immutable](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases),
so assets can only be attached while a release is a draft.

The [Release workflow](.github/workflows/release-please.yaml) runs on pushes to `main` and uses
Release Please to create or update the release PR. Pull-request builds use `-SNAPSHOT` versions;
release builds use the version committed in `gradle.properties`.

The CI workflow declares `cache-mode: read`, and the release build and signing job declares
`cache-mode: write`. The Publish workflow has no cache-mode override and uses GitHub's
[trigger-based default](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#cache-mode).

1. Merging the release PR makes Release Please create the tag and a **draft** GitHub release.
2. The `release` environment job builds the plugin and CycloneDX SBOM from the released commit,
   signs the plugin, and verifies its signature. Only the signed `doppler-plugin-<version>-signed.zip`
   is released, alongside the public `chain.crt`, SBOM, and `SHA256SUMS`.
3. Build provenance is attested for all four files and every JAR extracted from the signed zip,
   including bundled dependencies. The SBOM is attested against the signed zip. The four release
   files are attached to the draft before the immutable release is published; the extracted JARs
   are attestation subjects only, not separate release assets.
4. The manual Publish workflow downloads the signed zip and certificate from the selected release.
   It verifies both files' GitHub provenance against the Release workflow and validated tag commit,
   verifies the plugin signature, then uploads the exact zip to Marketplace without rebuilding or
   signing again. No signing credentials are needed by the Publish workflow.

To recover a deleted release, dispatch the Release workflow from `main` with the tag whose version
matches both `gradle.properties` and `.release-please-manifest.json`. The workflow creates a draft
for the selected `main` commit and runs the same build, signing, attestation, and publishing jobs.
For example, run `gh workflow run release-please.yaml --ref main -f tag=v1.7.0`. The tag must not
already exist. If the build fails after creating the draft, delete that draft release and its tag
before dispatching the recovery again.

Configure `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD` as secrets in the GitHub
`release` environment, with any desired approval and branch protection rules. Keep `PUBLISH_TOKEN`
in the separate `publish` environment. The public certificate is included in the release so users
can verify signatures; the private key and password remain secrets. The workflow exports the
`CERTIFICATE_CHAIN` value as PEM-encoded `chain.crt`, includes it in `SHA256SUMS`, attests it,
and attaches it to the GitHub release. Bash and `base64 --decode` handle either PEM or
Base64-encoded PEM in the secret; OpenSSL validates the exported certificate. Private-key material
in the certificate file is rejected.
All `upload-artifact` steps retain their temporary Actions artifacts for one day, including the
release handoff, CodeQL SARIF, and Scorecard SARIF. Published GitHub release assets remain available afterward.

To upload a completed release to Marketplace, run the
[Publish workflow](.github/workflows/publish.yaml) manually from `main`, supplying its existing
semantic-version tag, for example:

```bash
gh workflow run publish.yaml --ref main -f tag=v1.7.0
```

The tag must belong to `main` history and have a published release. The `publish` environment's
protection rules apply before the upload job runs. Verification or signature failures stop the
upload; the Publish workflow does not generate a second attestation.

This publishing flow requires a release containing a signed zip and an attested `chain.crt`.
Earlier releases without those assets cannot be published through this workflow.

### Verify a GitHub release download

To verify a release later, install the GitHub CLI and Java, and authenticate with `gh auth login`.
Download the signed zip, public certificate, SBOM, and checksums from the **same release**.
Replace `1.7.0` below with the version you want to verify:

```bash
version=1.7.0
gh release download "v$version" --repo ViacheslavKudinov/doppler-intellij-plugin \
  --pattern '*-signed.zip' --pattern 'chain.crt' --pattern '*.cdx.json' \
  --pattern 'SHA256SUMS' --dir "verify-$version"
cd "verify-$version"
shasum -a 256 -c SHA256SUMS
```

Verify the signed zip and certificate's provenance with the GitHub CLI, and the SBOM attestation:

```bash
gh attestation verify "doppler-plugin-$version-signed.zip" --repo ViacheslavKudinov/doppler-intellij-plugin \
  --signer-workflow ViacheslavKudinov/doppler-intellij-plugin/.github/workflows/release-please.yaml \
  --deny-self-hosted-runners
gh attestation verify "doppler-plugin-$version-signed.zip" --repo ViacheslavKudinov/doppler-intellij-plugin \
  --predicate-type https://cyclonedx.org/bom   # the SBOM attestation
gh attestation verify chain.crt --repo ViacheslavKudinov/doppler-intellij-plugin \
  --signer-workflow ViacheslavKudinov/doppler-intellij-plugin/.github/workflows/release-please.yaml \
  --deny-self-hosted-runners
```

After verifying the certificate's provenance, obtain the
[JetBrains ZIP Signer CLI](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html#plugin-signature-verification)
and save its CLI JAR as `marketplace-zip-signer-cli.jar` in the download directory. Verify the ZIP
signature against the released public certificate:

```bash
java -jar marketplace-zip-signer-cli.jar verify \
  -in "doppler-plugin-$version-signed.zip" -cert chain.crt
```

A successful signature check confirms that the archive matches the signing certificate and has
not been modified since signing. No private key or password is needed for verification.

### Verify a Marketplace download

Marketplace can modify the ZIP container, so its checksum and ZIP provenance may differ from
the GitHub release. Verify the JARs inside the downloaded ZIP against their GitHub build
provenance instead. Marketplace JAR attestation verification is supported starting with
**version 1.7.0**; earlier releases do not have the required JAR attestations.

Install the GitHub CLI and `unzip`, authenticate with `gh auth login`, and download the plugin
ZIP from Marketplace. Run the following in **Bash**, replacing the version and ZIP path with
your download's values:

```bash
(
  set -euo pipefail
  version=1.7.0
  marketplace_zip=/path/to/doppler-plugin-1.7.0.zip
  repo=ViacheslavKudinov/doppler-intellij-plugin
  release_commit="$(gh api "repos/$repo/commits/v$version" --jq .sha)"
  extracted="$(mktemp -d)"
  trap 'rm -rf "$extracted"' EXIT

  # A ZIP containing no JARs makes unzip fail and stops verification.
  unzip -q "$marketplace_zip" '*.jar' -d "$extracted"
  while IFS= read -r -d '' jar; do
    gh attestation verify "$jar" --repo "$repo" \
      --signer-workflow "$repo/.github/workflows/release-please.yaml" \
      --source-digest "$release_commit" \
      --deny-self-hosted-runners
  done < <(find "$extracted" -type f -name '*.jar' -print0)
)
```

Every JAR must pass. The [GitHub CLI verification options](https://cli.github.com/manual/gh_attestation_verify)
restrict provenance to this repository, the Release workflow, the selected release commit,
and GitHub-hosted runners. Attestations are fetched from GitHub automatically; no separate
attestation file or signing certificate is needed for this check.

Success confirms that each extracted JAR matches bytes attested by the release workflow.
It does not verify ZIP metadata, non-JAR files, or whether JARs were removed from the archive.
The SBOM attestation remains bound to the original GitHub release ZIP.

Generate a local dependency SBOM with `./gradlew cyclonedxBom`.

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for bug reports, suggestions, and contribution guidelines.

## 🗂️ Project layout

```
src/main/kotlin/com/vk/doppler/
├── cli/            Doppler CLI process execution, JSON parsing, binary discovery, name transformation
├── completion/     Secret reference completion, name index and loading
├── editor/         Virtual file system, document format, two-way sync, editor tab presentation
├── injection/      Run configuration secret injection (Java, Gradle, Node.js, Python, Docker)
├── notifications/  Balloon notifications
├── service/        Application-level facade and the secret diffing logic
├── settings/       Persisted settings, folder setup, injection consent, and the settings UI
└── toolwindow/     Projects / environments / configs tree and CLI installer options

src/main/resources/
├── icons/          Tool window, editor tab, and tree icons
└── META-INF/       plugin.xml and optional-dependency configs (doppler-java/gradle/node/python/docker.xml)

src/test/kotlin/    Unit tests mirroring the main package layout, plus testutil/
```
