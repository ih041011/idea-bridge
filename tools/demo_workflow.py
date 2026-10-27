"""End-to-end demonstration of driving IDEA through the bridge.

This doubles as the smoke test for a fresh install. It walks the loop an agent actually uses:

    read  ->  edit the live buffer  ->  ask the IDE's own analyser  ->  save  ->  run  ->  read console

If it prints a new program output, then plugin loading, the HTTP transport, token auth, the VFS,
document-level editing and run configurations are all working together.

It is idempotent: the target file is first reset to the baseline below, and restored to it at the
end, so it can be run repeatedly without inheriting anything from the previous run.

Usage:  python tools/demo_workflow.py
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import ib  # noqa: E402 -- needs the path tweak above

HELLO = (ROOT / "demo-project" / "src" / "Hello.java").as_posix()

# The file is put back to exactly this before and after the run, so the demo is re-runnable.
# The Chinese text and the curly quotes are deliberate: they are what proves UTF-8 survives the
# whole round trip through Java, HTTP and this script.
BASELINE = """/**
 * Throwaway target for verifying the bridge: it has to be openable, analysable, editable and runnable.
 * 中文注释用来验证 UTF-8 往返，字符：你好，世界 “quoted” — em dash.
 */
public class Hello {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("hello from the bridge demo");
        Thread.sleep(200);
        System.out.println("bridge run finished");
    }
}
"""

MARKER = " (edited through idea-bridge)"
LITERAL = '"hello from the bridge demo"'


def cmd(command, **params):
    """One bridge command, called in process.

    Deliberately not a subprocess: the DSH sandbox blocks the pipes that capturing a child's
    output requires, so a child-process version cannot run there at all.
    """
    reply = ib.send({"cmd": command, **params})
    if not reply.get("ok"):
        raise SystemExit(f"{command} failed: {reply.get('error')}")
    return reply["data"]


def step(title):
    print(f"\n--- {title} " + "-" * max(0, 62 - len(title)))


def locate(text, needle):
    """Offsets of the single occurrence of `needle`, which is what editor.edits wants."""
    index = text.find(needle)
    if index < 0:
        raise SystemExit(f"{needle!r} not found; has the file been edited?")
    if text.find(needle, index + 1) >= 0:
        raise SystemExit(f"{needle!r} appears more than once; cannot pick one")
    return index, index + len(needle)


def edit(text, old, new, save):
    """Replace one occurrence of `old`, reporting how the bridge applied it."""
    start, end = locate(text, old)
    result = cmd("editor.edits", path=HELLO, save=save,
                 edits=[{"start": start, "end": end, "text": new}])
    return result


def main():
    step("status")
    status = cmd("status")
    print(f"IDE      : {status.get('ide')}, build {status.get('build')}")
    print(f"bridge   : protocol {status.get('protocol')}, port {status.get('port')}, "
          f"pid {status.get('pid')}")
    projects = status.get("openProjects") or []
    print(f"projects : {[p['name'] for p in projects]}")
    if not any("demo-project" in (p.get("basePath") or "") for p in projects):
        raise SystemExit("open demo-project in IDEA first, then re-run")

    step("reset the target file to the baseline")
    cmd("file.write", path=HELLO, text=BASELINE, save=True)
    original = cmd("file.read", path=HELLO)["text"]
    print(f"baseline matches: {original == BASELINE} "
          f"({len(original)} chars, {original.count(chr(10)) + 1} lines)")

    # Open it up front. The bridge edits the live buffer when there is one and falls back to the
    # file on disk when there is not, so without this the test would quietly exercise the less
    # interesting path -- and the analyser would honestly answer "not analysed" rather than "clean".
    cmd("editor.open", path=HELLO, focus=False)

    step("edit the live buffer, not the file on disk")
    result = edit(original, LITERAL, f'"hello from the bridge demo{MARKER}"', save=False)
    print(f"applied {result['applied']} edit(s); back-to-front: {result['appliedBackToFront']}; "
          f"mode: {result['mode']}")
    if result["mode"] != "document":
        raise SystemExit(f"expected the open file to be edited in place, got mode={result['mode']}")
    print(f"unsaved in the editor: {cmd('file.read', path=HELLO)['unsaved']}"
          f"   <- the change is in IDEA, not yet on disk")

    step("ask the IDE's own analyser about the edit")
    problems = cmd("problems.get", path=HELLO, limit=20)
    print(f"analyzed: {problems['analyzed']}, complete: {problems['complete']}, "
          f"errors: {problems['errors']}, warnings: {problems['warnings']}")
    for problem in problems["problems"]:
        print(f"  {problem['severity']}: {problem['description']} (line {problem['line']})")
    # A partial analysis reports zero problems exactly like a clean file does, so a smoke test that
    # accepted it would pass while proving nothing.
    if not problems["analyzed"] or not problems["complete"]:
        raise SystemExit(f"the analyser never finished for {HELLO}: "
                         f"analyzed={problems['analyzed']} complete={problems['complete']}")

    step("save, then run the project's own Run Configuration")
    cmd("editor.save", path=HELLO)
    print(f"saved; unsaved flag is now {cmd('file.read', path=HELLO)['unsaved']}")
    cmd("run.execute", name="Hello")
    console = cmd("run.console", name="Hello", waitMs=30000, maxChars=2000)
    print(f"exit code: {console['exitCode']}, terminated: {console['terminated']}")
    for line in (console["text"] or "").splitlines():
        print(f"  | {line}")

    step("restore the baseline")
    after_edit = cmd("file.read", path=HELLO)["text"]
    edit(after_edit, f'"hello from the bridge demo{MARKER}"', LITERAL, save=True)
    print(f"restored exactly: {cmd('file.read', path=HELLO)['text'] == BASELINE}")

    step("done")
    print("read, edit, analyse, run and console capture all round-tripped through IDEA.")


if __name__ == "__main__":
    main()