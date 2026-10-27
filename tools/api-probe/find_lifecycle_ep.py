"""Locate the real startup/lifecycle extension point declarations in IDEA 2024.1.

Brute-forces every jar in the install for the relevant identifiers and prints the
declaring XML with context, so plugin.xml is written against EPs that exist.
"""
import re
import zipfile
from pathlib import Path

IDEA = Path(r"E:\ideaIU-2024.1.4.win")
NEEDLES = [
    "postStartupActivity",
    "AppLifecycleListener",
    "appLifecycleListener",
    "ProjectActivity",
]

jars = sorted(IDEA.rglob("*.jar"))
print(f"scanning {len(jars)} jars\n")

found: dict[str, list] = {n: [] for n in NEEDLES}

for jar in jars:
    try:
        with zipfile.ZipFile(jar) as z:
            for entry in z.namelist():
                if not entry.endswith(".xml"):
                    continue
                try:
                    data = z.read(entry).decode("utf-8", "replace")
                except Exception:
                    continue
                for n in NEEDLES:
                    idx = data.find(n)
                    if idx < 0:
                        continue
                    snip = re.sub(r"\s+", " ", data[max(0, idx - 300): idx + 300])
                    found[n].append((str(jar.relative_to(IDEA)), entry, snip))
    except Exception:
        pass

for n in NEEDLES:
    print(f"================ {n} ================")
    rows = found[n]
    if not rows:
        print("  (no XML in any jar mentions it)")
    seen = set()
    for jar_name, entry, snip in rows:
        key = (jar_name, entry)
        if key in seen:
            continue
        seen.add(key)
        print(f"  -- {jar_name} :: {entry}")
        print(f"     {snip}")
    print()