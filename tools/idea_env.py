"""Locate the IntelliJ IDEA installation and the plugins directory to install into.

Both are discovered rather than hard-coded, because these paths differ per machine and a baked-in
one is the first thing that breaks for anyone who clones this repo. Either can be overridden with
the IDEA_HOME / PLUGIN_DIR environment variables.

    python tools/idea_env.py           prints IDEA_HOME, then PLUGIN_DIR, one per line
    python tools/idea_env.py --check   the same, but explains what to set when it finds nothing

The plugins directory is derived from the installation rather than guessed: build.txt names the
product and version, and IDEA keys its per-user config directory off exactly those. That is what
makes this work for a Toolbox install, a standalone install, or a second copy of the IDE, without
anyone editing a path.
"""
import os
import sys
from pathlib import Path

# The prefix of build.txt mapped to the config-directory name IDEA uses for that product.
PRODUCTS = {
    "IU": "IntelliJIdea",
    "IC": "IdeaIC",
    "PY": "PyCharm",
    "PC": "PyCharmCE",
    "PS": "PhpStorm",
    "GO": "GoLand",
    "CL": "CLion",
    "RD": "Rider",
    "WS": "WebStorm",
    "DB": "DataGrip",
}


def _search_roots():
    """Directories worth walking for bin/idea64.exe, most likely first."""
    roots = []
    local = os.environ.get("LOCALAPPDATA")
    if local:
        roots.append(Path(local) / "Programs")
        roots.append(Path(local) / "JetBrains" / "Toolbox" / "apps")
    if sys.platform == "win32":
        roots.append(Path(r"C:\Program Files\JetBrains"))
        roots.append(Path(r"C:\Program Files (x86)\JetBrains"))
    elif sys.platform == "darwin":
        roots.append(Path("/Applications"))
    else:
        roots.append(Path.home() / ".local/share/JetBrains/Toolbox/apps")
        roots.append(Path("/opt"))
    return [root for root in roots if root.is_dir()]


def _launcher_name():
    return "idea64.exe" if sys.platform == "win32" else "idea.sh"


def _is_install(path: Path) -> bool:
    return (path / "build.txt").is_file() and (path / "lib").is_dir() \
        and (path / "bin" / _launcher_name()).is_file()


def _walk(roots, max_depth=4):
    """Breadth-first, depth-limited: Toolbox nests an install several levels under its own id."""
    queue = [(root, 0) for root in roots]
    found = []
    while queue:
        current, depth = queue.pop(0)
        if _is_install(current):
            found.append(current)
            continue
        if depth >= max_depth:
            continue
        try:
            children = sorted(child for child in current.iterdir() if child.is_dir())
        except OSError:
            continue
        queue.extend((child, depth + 1) for child in children if not child.name.startswith("."))
    return found


def _build_field(install: Path) -> str:
    """The contents of build.txt, e.g. 'IU-241.18034.62'."""
    try:
        return (install / "build.txt").read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def _build_code(install: Path) -> str:
    """'IU-241.18034.62' -> 'IU'."""
    field = _build_field(install)
    return field.split("-", 1)[0] if "-" in field else ""


def _build_number(install: Path) -> str:
    """'IU-241.18034.62' -> '241.18034.62'."""
    return _build_field(install).split("-", 1)[-1]


def _rank(install: Path):
    """Ultimate before Community, then newest build first."""
    numbers = tuple(-int(part) for part in _build_number(install).split(".") if part.isdigit())
    return (0 if _build_code(install) == "IU" else 1, numbers)


def find_idea_home():
    override = os.environ.get("IDEA_HOME")
    if override:
        path = Path(override)
        if _is_install(path):
            return path
        print(f"IDEA_HOME={override} does not look like an IDEA installation "
              f"(expected bin/{_launcher_name()}, build.txt and lib/)", file=sys.stderr)
        return None
    installs = _walk(_search_roots())
    if not installs:
        return None
    return sorted(installs, key=_rank)[0]


def config_root() -> Path:
    """Where IDEA keeps its per-user configuration on this platform."""
    if sys.platform == "win32":
        base = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
        return Path(base) / "JetBrains"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "JetBrains"
    return Path.home() / ".config" / "JetBrains"


def _config_directory_name(install: Path) -> str:
    """'IU-241.18034.62' -> 'IntelliJIdea2024.1'.

    IDEA version codes pack the year into the first two digits and the release into the third:
    231 is 2023.1, 241 is 2024.1, 243 is 2024.3.
    """
    product = PRODUCTS.get(_build_code(install))
    code = _build_number(install).split(".")[0]
    if not product or len(code) != 3 or not code.isdigit():
        return ""
    return f"{product}{2000 + int(code[:2])}.{int(code[2])}"


def find_plugin_dir(install: Path) -> Path:
    override = os.environ.get("PLUGIN_DIR")
    if override:
        return Path(override)

    root = config_root()
    name = _config_directory_name(install)
    exact = root / name if name else None
    if exact is not None and exact.is_dir():
        return exact / "plugins"

    # No config directory yet, or the name did not resolve: fall back to a sibling for the same
    # product. Installing into a directory IDEA has not created yet is fine -- it creates it on the
    # next start -- but the name has to be right, so an existing one is always preferred.
    if name:
        product = "".join(ch for ch in name if not ch.isdigit() and ch != ".")
        siblings = sorted((path for path in root.glob(f"{product}*") if path.is_dir()), reverse=True)
        if siblings:
            return siblings[0] / "plugins"

    if exact is not None:
        return exact / "plugins"
    # build.txt did not name a product we recognise, so there is nothing to derive it from.
    print(f"could not derive the config directory from {install}; set PLUGIN_DIR", file=sys.stderr)
    return root / "plugins"


def main(argv) -> int:
    check = "--check" in argv
    install = find_idea_home()
    if install is None:
        print("Could not find an IntelliJ IDEA installation.", file=sys.stderr)
        print("Set IDEA_HOME to the directory holding bin/idea64.exe (or bin/idea.sh),", file=sys.stderr)
        print("build.txt and lib/ -- for example on Windows:", file=sys.stderr)
        print('  IDEA_HOME="C:/Program Files/JetBrains/IntelliJ IDEA 2024.1" tools/build_and_install.sh',
              file=sys.stderr)
        print("Searched: " + ", ".join(str(root) for root in _search_roots()), file=sys.stderr)
        return 1

    plugin_dir = find_plugin_dir(install)
    if not plugin_dir.parent.is_dir():
        print(f"note: {plugin_dir.parent} does not exist yet; IDEA will create it on first start",
              file=sys.stderr)

    print(install)
    print(plugin_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))