#!/usr/bin/env python
"""Drive the DSH IDEA Bridge from the command line.

The plugin writes its port and token to ~/.ideabridge/endpoint.json, so they never have to be
passed by hand. This posts one command to the running IDE and prints the reply as JSON.

    ib.py ping                          is the IDE up and is the listener alive?
    ib.py status
    ib.py project.list
    ib.py file.read path=F:/x/Main.java
    ib.py file.read F:/x/Main.java      a bare argument is taken as the path (or name)
    ib.py editor.edits path=F:/x/Main.java edits=[{"start":0,"end":0,"text":"// hi\n"}]
    ib.py run.execute MyConfig
    ib.py raw '{"cmd":"status"}'

Arguments of the form key=value are decoded as JSON when they parse and kept as strings
otherwise, so numbers, booleans, null and nested arrays/objects all work without quoting
games -- but note that a value like true or 42 is taken as a boolean or a number.

An argument counts as key=value only when the text before the first '=' is a plain
identifier, so a pasted snippet containing '=' cannot silently become a nonsense key.

Source text is passed by indirection, because real code does not survive a command line:

    ib.py file.write path=F:/x/New.java text=@snippet.java     read the value from a file
    ib.py file.write path=F:/x/New.java text=-                 read the value from stdin

Exit code is 0 only when the command itself succeeded; a transport failure or an ok=false
reply both exit non-zero, so this composes with shell conditionals.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

# A Windows console defaults to the legacy code page, which mangles every non-ASCII character a
# Java source file contains. Forcing UTF-8 on the way out is what makes reading a file with
# Chinese text in it come back intact instead of as replacement characters.
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass

HOME = Path.home() / ".ideabridge"
ENDPOINT_FILE = HOME / "endpoint.json"
DEFAULT_PORT = 64343

# A run.console call may legitimately block while the run configuration produces output, so the
# default is generous rather than tight.
TIMEOUT = float(os.environ.get("IDEABRIDGE_TIMEOUT", "900"))

PATH_COMMANDS = ("file.", "editor.", "problems.", "vfs.")
NAME_COMMANDS = ("run.",)

# An argument is key=value only when the key looks like this; see split_args for why.
KEY_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def load_endpoint():
    """Host, port and token, with the environment taking precedence over the file."""
    host = os.environ.get("IDEABRIDGE_HOST", "127.0.0.1")
    port = os.environ.get("IDEABRIDGE_PORT")
    token = os.environ.get("IDEABRIDGE_TOKEN") or os.environ.get("IDEA_BRIDGE")

    if ENDPOINT_FILE.is_file():
        try:
            data = json.loads(ENDPOINT_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        if isinstance(data, dict):
            port = port or data.get("port")
            token = token or data.get("token")
            host = os.environ.get("IDEABRIDGE_HOST") or data.get("host") or host

    return str(host), int(port or DEFAULT_PORT), token or ""


def call(host, port, path, body, token, timeout=TIMEOUT):
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        f"http://{host}:{port}{path}", data=payload, method="POST" if payload else "GET"
    )
    if payload:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("X-Bridge-Token", token)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def decode(raw):
    """JSON when it parses, the raw string otherwise, so unquoted paths work."""
    try:
        return json.loads(raw)
    except ValueError:
        return raw


def send(body, timeout=TIMEOUT):
    """Post one already-built command body and return the decoded reply.

    Exposed for importing rather than shelling out to this script. That matters under the DSH
    sandbox, which forbids creating the pipes that capturing a child process needs -- there an
    in-process call is the only way, and it also avoids paying for a fresh interpreter per command.
    """
    host, port, token = load_endpoint()
    return call(host, port, "/cmd", body, token, timeout)


def read_value(raw):
    """Decode one key=value payload, pulling from outside the command line when asked.

    Source text cannot travel as a shell argument: it usually contains '=', which key=value
    splitting would destroy, and newlines do not survive a Windows command line. Reading it from
    a file or stdin is what makes file.write and editor.edits usable for real code.
    """
    if raw == "-":
        return sys.stdin.read()
    if raw.startswith("@"):
        return Path(raw[1:]).read_text(encoding="utf-8")
    return decode(raw)


def split_args(argv):
    """Separate bare arguments from key=value pairs.

    Only an identifier before the first '=' makes an argument a pair. Otherwise a pasted Java
    snippet -- which almost always contains '=' -- is split into a nonsense key and dropped, so
    the caller gets a confusing "missing 'text'" instead of a working write.
    """
    positional, body = [], {}
    for arg in argv:
        key, sep, raw = arg.partition("=")
        if sep and not arg.startswith("{") and KEY_PATTERN.fullmatch(key):
            body[key] = read_value(raw)
        else:
            positional.append(arg)
    return positional, body


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0

    host, port, token = load_endpoint()

    if argv[0] == "endpoint":
        print(json.dumps(
            {"host": host, "port": port, "token": token, "endpointFile": str(ENDPOINT_FILE)},
            indent=2, ensure_ascii=False))
        return 0

    if argv[0] == "ping":
        try:
            print(json.dumps(call(host, port, "/ping", None, "", timeout=10),
                             indent=2, ensure_ascii=False))
            return 0
        except Exception as exc:
            print(f"bridge not reachable on {host}:{port}: {exc}", file=sys.stderr)
            return 1

    if argv[0] == "raw":
        if len(argv) < 2:
            print("raw needs a JSON body, e.g. raw '{\"cmd\":\"status\"}'", file=sys.stderr)
            return 2
        body = json.loads(argv[1])
    else:
        positional, body = split_args(argv)
        if not positional:
            print("no command given; run with --help for the list", file=sys.stderr)
            return 2
        command = positional[0]
        body["cmd"] = command

        extra = positional[1:]
        if extra:
            if len(extra) > 1:
                print(f"too many bare arguments: {extra}", file=sys.stderr)
                return 2
            if command.startswith(PATH_COMMANDS) and "path" not in body:
                body["path"] = extra[0]
            elif command.startswith(NAME_COMMANDS) and "name" not in body:
                body["name"] = extra[0]
            else:
                print(f"unexpected bare argument for {command}: {extra[0]}", file=sys.stderr)
                return 2

    try:
        reply = call(host, port, "/cmd", body, token)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        print(f"HTTP {exc.code} from the bridge: {detail}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"bridge not reachable on {host}:{port}: {exc}", file=sys.stderr)
        print("is IDEA running with the DSH IDEA Bridge plugin enabled?", file=sys.stderr)
        return 1

    print(json.dumps(reply, indent=2, ensure_ascii=False))
    return 0 if reply.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))