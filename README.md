# IDEA Bridge

**An HTTP control API for a running IntelliJ IDEA — for agents and scripts.**

[中文说明](README.zh-CN.md) · MIT licensed · IntelliJ IDEA 2024.1 (build 241)

IDEA Bridge is a plugin that starts a loopback HTTP server inside the IDE process and exposes what
you actually need in order to write code in it: open projects, files, **the live editor buffer**,
undoable edits, run configurations together with their console output, and the IDE's own error
analysis.

That last part is the point. Writing files to disk is a solved problem. What this gives you is the
IDE's *state* — the buffer a human is editing, its undo history, its analyser, its Run tool window.
Changes made through IDEA Bridge land in the same places a person's changes land, so syntax
highlighting, code analysis, refactoring and the undo stack all agree with them.

## Why this exists

| Alternative | Why it isn't enough |
| --- | --- |
| JetBrains' official MCP server plugin | Requires IDE 2025.1+; this targets 2024.1 (build 241) |
| `idea64.exe --line N file` | Opens a file. Cannot read or write a buffer, cannot run anything |
| Remote Development Server | Built for a human at a thin client, not for a program to drive |
| Editing files on disk directly | The IDE never learns about it; an open buffer overwrites you |
| **This plugin** | Runs in-process, so it reaches the platform APIs directly |

## Requirements

- **IntelliJ IDEA 2024.1** (build 241). `plugin.xml` pins `since-build="241" until-build="241.*"`.
- **Python 3.8+** for the client and the build scripts.
- No IntelliJ Platform SDK download and **no Gradle** — the build compiles against the IDE already
  installed on your machine, using the JBR that ships with it.

## Install

```bash
git clone https://github.com/ih041011/idea-bridge
cd idea-bridge
tools/build_and_install.sh
```

That compiles the plugin against your IDEA installation, packages the jar, stops the IDE, copies the
plugin in, restarts the IDE and waits for the bridge to come up. It finds your IDE automatically; if
yours lives somewhere unusual, point at it:

```bash
IDEA_HOME="C:/Program Files/JetBrains/IntelliJ IDEA 2024.1" tools/build_and_install.sh
```

**The order in that script is not cosmetic.** A running IDE keeps its loaded plugin jar locked, so
copying while it is up fails with `Permission denied` and silently leaves the *old* plugin installed —
which looks exactly like a build that succeeded but had no effect. The script stops the IDE first and
verifies the copy landed with an md5 comparison.

Afterwards, **start IDEA normally**. No flags, no launcher script: the plugin starts the server on
`appStarted`, so it is already listening while the welcome screen is up, before any project is open.

## Use

```bash
python ib.py help            # every command
python ib.py status          # is it up, and which projects are open
python ib.py editor.active   # what am I looking at, where is the caret
```

A typical editing loop:

```bash
F=F:/work/src/Main.java

python ib.py editor.context "path=$F" line=42 before=6 after=6   # look before you touch
python ib.py editor.edits   "path=$F" edits=@patch.json save=false
python ib.py problems.get   "path=$F"                            # did that break anything?
python ib.py editor.save    "path=$F"
python ib.py run.execute    Main
python ib.py run.console    Main waitMs=30000                    # what did it actually print?
```

## Commands

| Command | Parameters | What it does |
| --- | --- | --- |
| `status` | — | Server state, IDE version and build, open projects |
| `help` | — | The command list, from the plugin itself |
| `project.list` | — | Every open project |
| `project.info` | `project` | Modules, content roots, SDK |
| `file.read` | `path` | Read a file; **prefers the unsaved buffer if the file is open** |
| `file.write` | `path, text, createDirs, save` | Write through the VFS; an open file takes the document path |
| `file.list` | `path, glob, recursive, limit` | List a directory; supports `*`, `**` and `?` |
| `file.stat` | `path` | Exists, size, modified time |
| `vfs.refresh` | `path, recursive` | Drop the IDE's cache and re-read from disk |
| `editor.open` | `path, line, column, focus` | Open and navigate |
| `editor.active` | — | Current file, caret and selection |
| `editor.context` | `path, line, before, after` | Lines around a position — the cheap way to look first |
| `editor.edits` | `path, edits[{start,end,text}], save` | Batched offset edits, **undone by a single undo** |
| `editor.save` | `path` | Save |
| `editor.close` | `path` | Close that file's editor |
| `run.list` | `project` | Every run configuration |
| `run.execute` | `name, project` | Launch — identical to pressing the green arrow |
| `run.stop` | `name, project` | Terminate |
| `run.console` | `name, project, maxChars, waitMs` | Console text and exit code |
| `problems.get` | `path, limit, waitMs` | **The IDE's own** errors and warnings; `complete` says whether analysis finished |
| `log.tail` | `lines` | The plugin's own log |

### Passing arguments

`key=value`, where the value is decoded as JSON when it parses and kept as a string otherwise — so
numbers, booleans, `null` and nested arrays all work without quoting games.

An argument is treated as `key=value` **only when the text before the first `=` is a plain
identifier**. Without that rule a pasted code snippet, which almost always contains `=`, gets split
into a nonsense key and dropped, and you get a confusing `missing 'text'` instead of a working write.

Source text is passed **by indirection**, because real code does not survive a command line — it
contains `=` and newlines:

```bash
python ib.py file.write    path=F:/x/New.java text=@snippet.java   # read the value from a file
python ib.py editor.edits  path=F:/x/A.java   edits=-              # read the value from stdin
```

A bare argument is taken as the path (or the name, for `run.*`) when the command has an obvious one:

```bash
python ib.py file.read      F:/x/A.java
python ib.py run.execute    MyConfig
python ib.py problems.get   F:/x/A.java limit=20
python ib.py file.list      F:/x 'glob=**/*.java' recursive=true
```

### Environment variables

| Variable | Effect |
| --- | --- |
| `IDEABRIDGE_HOST`, `IDEABRIDGE_PORT` | Override the endpoint (defaults come from `~/.ideabridge/endpoint.json`) |
| `IDEABRIDGE_TOKEN`, `IDEA_BRIDGE` | Override the token |
| `IDEABRIDGE_PORT` (plugin side) | Change the listening port, default `64343` |
| `IDEABRIDGE_TIMEOUT` | Client request timeout in seconds, default `900` |

## Design decisions

Each of these was found the hard way, and each is the reason some ordinary-looking code is shaped
the way it is.

1. **Ask whether an open `Document` exists before touching a file.** When a file is open in an
   editor, the copy on disk can be stale, and writing it directly makes the IDE either reload or
   silently discard the change. An open file goes through `Document.setText` / `replaceString`, which
   keeps the undo stack, the PSI, highlighting and analysis in step; only a file that isn't open is
   written to disk.
2. **`editor.edits` applies edits in descending offset order.** Applying them forwards lets an early
   edit move the offsets of a later one. Two edits at the same offset are stable: `[{0,0,"// first"},
   {0,0,"// second"}]` reliably yields `// second` then `// first`.
3. **A bare `Application.runWriteAction` must be on the EDT** — 241 asserts the thread — while
   `WriteCommandAction.runWriteCommandAction` switches threads for you. The request thread is
   `ideabridge-http`, so `BridgeCommands.writeAction` calls `edt(...)` before entering the write
   action.
4. **A VFS refresh is illegal under any lock** — not just a read action, a write action too — and it
   is *silently skipped* rather than reported. An early version wrapped `refreshAndFindFileByPath` in
   a read action, so a newly created file came back `vfsVisible:false` and every later command
   believed the file did not exist. All refreshes now run unlocked.
5. **Refresh the parent directory before looking for a new file**, or the new file stays invisible to
   the IDE.
6. **Console text only comes from `ConsoleViewImpl.getText()`.** `RunContentDescriptor` has no
   `getConsoleView()`, and the `ConsoleView` interface has no `getText()`.
7. **Logs do not go into the IDE's own log** (its path moves with the installation). They go to
   `~/.ideabridge/bridge.log`, rotated at 2 MB — and **any logging failure is swallowed**, because a
   log must never be the reason a command fails.
8. **`problems.get` waits for the analyser to finish.** The IDE provides highlighting
   *progressively*: a just-created file measured `count` 0 at ~2s, 1 at ~4s, 3 at ~6s and settled at 5
   by ~8s. Not waiting reports "not analysed yet" as "no problems", which is the one mistake a
   diagnostics command must not make. It polls
   `DaemonCodeAnalyzerEx.isErrorAnalyzingFinished(PsiFile)` and reports the outcome truthfully as
   `complete`.
9. **The console must be the one from *this* run.** IDEA keeps every finished run tab, so matching by
   name returns the **oldest** one, and "what did the program I just started print?" silently reads a
   previous run's output. A live process is preferred; on `run.execute` the set of existing tabs is
   recorded, and `run.console` insists on a tab that was not there at that moment — a tab that has
   already finished still counts as arrived, otherwise a fast program would idle until the timeout.

## Security

- Binds `127.0.0.1` only. Nothing is reachable from another machine.
- `/cmd` requires a token, as an `X-Bridge-Token` header or `?token=`, compared in constant time.
  `/ping` is unauthenticated purely so a caller can tell whether the IDE is up.
- The token lives in `~/.ideabridge/token`; on first run a 64-character hex token is generated and
  the file is restricted to its owner.
- Request bodies are capped at 32 MB.
- `BridgeException` separates "the caller asked for something impossible" from a genuine fault and
  deliberately omits the stack trace.
- If the server fails to start, the plugin **logs it and lets the IDE start anyway**. It must never be
  the reason IDEA will not open.

## Known limits

- **IntelliJ IDEA 2024.1 only.** `idea-version` is pinned to `241`. On 2025.1+ use JetBrains' own MCP
  server plugin instead — this exists precisely because that one is unavailable here.
- No HTTP authentication beyond the bearer token, and no TLS: it assumes a single-user machine.
- There is no `file.delete` or rename command. Delete with the shell and call `vfs.refresh`
  (verified working).
- The plugin is developed and tested on Windows. The Java is platform-neutral; only the build and
  install script branches on the OS.

## Development

```bash
tools/build_and_install.sh          # compile, package, install, restart, wait
tools/build_and_install.sh "F:\IDEA\SomeProject"   # ...and open a project
```

Compile without installing:

```bash
"$IDEA_HOME/jbr/bin/javac" -J-Duser.language=en -encoding UTF-8 \
  --release 17 -Xlint:-options -cp "$IDEA_HOME/lib/*" \
  -d build/classes plugin/src/ideabridge/*.java
```

`-J-Duser.language=en` makes `javac` report in English; otherwise its errors are unreadable in a shell
that is not using the matching code page.

There is no `jar.exe` in the bundled JBR, so `tools/build_plugin.py` writes the archive with
`zipfile`. The descriptor must sit at `META-INF/plugin.xml` *inside* the jar — the only place the
platform looks when the jar is in `<plugin>/lib/`.

### Verification

`demo-project/` is a throwaway Java 21 project with a `Hello` run configuration. One command exercises
the whole loop and asserts its own preconditions:

```bash
python tools/demo_workflow.py
```

It resets the target file, edits the live buffer, asks the analyser, saves, runs the configuration and
reads the console back. It fails loudly if the edit silently degenerates to a disk write or if the
analysis never completed — a smoke test that accepted either would pass while proving nothing.

### Layout

```
plugin/src/ideabridge/     the plugin (14 classes, no third-party dependencies)
plugin/resources/META-INF/ plugin.xml
ib.py                      command-line client
tools/                     build, install, path detection, verification
tools/api-probe/           the scripts used to discover the 241 API; see its README
demo-project/              the verification fixture
```

### API probe scripts

Every API call in the plugin was checked against the API actually installed on the machine before it
was written, because there is no downloaded SDK to consult.
[`tools/api-probe/`](tools/api-probe/README.md) holds the reflection dumper and asset scanners that
made that possible, including two approaches that turned out to be dead ends and why. They are worth
keeping if you port this to another build.

## License

[MIT](LICENSE) © 2026 ih041011

## How this was built

Written with [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness), driving the same
IDEA instance the plugin controls — the API signatures, the extension point names and every fix
recorded under "Design decisions" were verified against build 241 as it was being written.

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)