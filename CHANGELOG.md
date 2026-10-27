# Changelog

Notable changes to IDEA Bridge. The plugin descriptor carries the same notes under
`<change-notes>` so the IDE can show them after an update.

## 1.0.0 — 2026-10-27

First release. Targets IntelliJ IDEA 2024.1 (build 241), where the official JetBrains MCP
server plugin is not available.

**Commands**

- Projects: `project.list`, `project.info` (modules, content roots, SDK, content entries)
- Files: `file.read`, `file.write`, `file.list` (globs, recursive), `file.stat`, `vfs.refresh`
- Editor: `editor.open`, `editor.active`, `editor.context`, `editor.edits`, `editor.save`,
  `editor.close`
- Runs: `run.list`, `run.execute`, `run.stop`, `run.console`
- Diagnostics: `problems.get`
- Housekeeping: `status`, `help`, `log.tail`

**Design points worth knowing**

- Document edits go through `Document` under `WriteCommandAction`, so they land in the live
  buffer, participate in undo, and never fight with the editor's own state. A file that is not
  open falls back to a plain disk write followed by a VFS refresh.
- `run.execute` goes through `ProgramRunnerUtil`, so a run started from the bridge is
  indistinguishable from pressing the green arrow: same console, re-run button and tooling.
- `problems.get` waits for the IDE's analyser to finish and reports a `complete` flag. Analysis
  is progressive — a just-created file climbs from 0 to its final problem count over several
  seconds — so returning early would report broken code as clean.
- `run.console` identifies the console of the run you just started rather than the first tab
  whose name matches. The IDE keeps every finished run tab, so a name match alone can silently
  return a previous run's output.
- VFS refreshes run outside any read/write action. The platform silently skips them under a
  lock, which is why a newly written file used to stay invisible to the IDE.