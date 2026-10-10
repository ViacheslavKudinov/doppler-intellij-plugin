<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Doppler-plugin Changelog

## [Unreleased]

### Fixed

- Remove secret-bearing YAML excerpts from error notifications and diagnostic logs.
- Preserve local edits after rejected or failed syncs instead of overwriting them during auto-refresh.
- Reject malformed CLI secrets snapshots rather than opening an empty or incomplete config.
- Keep the remote snapshot accurate when a readable secret is left as a `null` placeholder.
- Re-read the server state after partially failed syncs without discarding local edits.
- Prevent explicit reloads from replacing editor contents while a sync is still in progress.
- Reject secret names that could be interpreted as CLI flags or `KEY=value` arguments.
- Escape notification content to prevent HTML injection from server- or CLI-provided text.
- Fail the sync instead of continuing when a secret value cannot be written to the CLI's stdin.
- Release secrets from memory when the last editor tab for a config is closed.
