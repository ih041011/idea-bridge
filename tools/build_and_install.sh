#!/usr/bin/env bash
# Rebuild the plugin, install it into a local IDEA, restart the IDE, and wait for the bridge.
#
# The ORDER here is not cosmetic. A running IDE keeps its loaded plugin jar locked, so copying while
# it is up fails with "Permission denied" and silently leaves the OLD plugin installed -- which looks
# exactly like a build that succeeded but has no effect. Stop IDEA, copy, start; the md5sum
# comparison is the check that the copy actually landed.
#
# Usage:  tools/build_and_install.sh [project-path-to-open]
#
# Env:
#   IDEA_HOME   IDEA installation directory (discovered by tools/idea_env.py when unset)
#   PLUGIN_DIR  plugins directory to install into (derived from the OS config dir when unset)
#   PYTHON      interpreter for the Python helpers (probed when unset)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# git-bash can resolve `python` to the unusable WindowsApps stub, which fails with "Permission
# denied" rather than running. Probe the candidates instead of trusting the name.
PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  for candidate in python3 python py; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "" >/dev/null 2>&1; then
      PY="$candidate"
      break
    fi
  done
fi
if [ -z "$PY" ]; then
  echo "no working python interpreter found; set PYTHON=/path/to/python" >&2
  exit 1
fi

mapfile -t PATHS < <("$PY" tools/idea_env.py --check)
# Python on Windows writes CRLF, and mapfile strips only the LF, so a trailing CR survives and turns
# every path into a filename that does not exist. Strip it first.
IDEA_HOME="${PATHS[0]%$'\r'}"
PLUGINS_ROOT="${PATHS[1]%$'\r'}"
# idea_env.py reports native paths, so on Windows they arrive backslash-separated. MSYS needs forward
# slashes to test and join them; Java and IDEA accept them either way.
IDEA_HOME="${IDEA_HOME//\\//}"
PLUGINS_ROOT="${PLUGINS_ROOT//\\//}"
# IDEA treats every top-level entry of the plugins directory as a plugin: a bare jar, or a
# directory whose lib/ holds the jars. Dropping the jar straight into the root's own lib/ would make
# IDEA look for a plugin directory called "lib" and find nothing inside it.
PLUGIN_NAME="${PLUGIN_NAME:-idea-bridge}"
PLUGIN_HOME="$PLUGINS_ROOT/$PLUGIN_NAME"
echo "python    : $PY"
echo "IDEA_HOME : $IDEA_HOME"
echo "PLUGIN    : $PLUGIN_HOME"

# The bundled JBR never shipped jar.exe, and on Windows the launcher carries the .exe suffix.
JAVAC="$IDEA_HOME/jbr/bin/javac"
if [ -f "$JAVAC.exe" ]; then
  JAVAC="$JAVAC.exe"
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) WINDOWS=1 ;;
  *) WINDOWS=0 ;;
esac

DEFAULT_PROJECT="$ROOT/demo-project"
if command -v cygpath >/dev/null 2>&1; then
  DEFAULT_PROJECT="$(cygpath -w "$DEFAULT_PROJECT")"
fi
PROJECT="${1:-$DEFAULT_PROJECT}"

echo "=== compile ==="
rm -rf build/classes && mkdir -p build/classes
"$JAVAC" -J-Duser.language=en -J-Duser.country=US \
  -encoding UTF-8 --release 17 -Xlint:-options \
  -cp "$IDEA_HOME/lib/*" -d build/classes plugin/src/ideabridge/*.java
echo "classes: $(find build/classes -name '*.class' | wc -l)"

echo "=== package ==="
"$PY" tools/build_plugin.py

if [ "$WINDOWS" = "1" ]; then
  echo "=== stop IDEA (a running IDE locks the installed jar) ==="
  taskkill //IM idea64.exe //T >/dev/null 2>&1 || true
  sleep 6
  taskkill //F //IM idea64.exe //T >/dev/null 2>&1 || true
  sleep 4
else
  echo "=== stop IDEA yourself now (a running IDE locks the installed jar) ==="
  sleep 5
fi

echo "=== install ==="
mkdir -p "$PLUGIN_HOME/lib"
cp dist/idea-bridge.jar "$PLUGIN_HOME/lib/idea-bridge.jar"
md5sum dist/idea-bridge.jar "$PLUGIN_HOME/lib/idea-bridge.jar"

echo "=== start IDEA with: $PROJECT ==="
(
  cd "$IDEA_HOME/bin"
  if [ "$WINDOWS" = "1" ]; then
    ./idea64.exe "$PROJECT" >/tmp/idea-start.log 2>&1 &
  else
    ./idea.sh "$PROJECT" >/tmp/idea-start.log 2>&1 &
  fi
)

echo "waiting for the bridge to answer..."
for i in $(seq 1 40); do
  sleep 3
  if "$PY" "$ROOT/ib.py" ping >/dev/null 2>&1; then
    echo "bridge is up after ~$((i * 3))s"
    "$PY" "$ROOT/ib.py" ping
    exit 0
  fi
done
echo "bridge did not answer within 120s; check ~/.ideabridge/bridge.log" >&2
exit 1