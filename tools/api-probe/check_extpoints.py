"""Find how IntelliJ 2024.1 declares the startup/lifecycle extension points.

We must register the bridge boot hook through an extension point that really
exists in build 241 -- guessing wrong makes the plugin fail to load. This scans
every plugin XML inside the installed IDE and prints the context around the
declaration so we can read the exact EP name, interface and beanClass.
"""
import re
import zipfile
from pathlib import Path

IDEA = Path(r"E:\ideaIU-2024.1.4.win")
WANT = [
    "postStartupActivity",
    "appLifecycleListener",
    "startupActivity",
    "applicationInitializer",
    "applicationService",
]

jars = sorted(IDEA.glob("lib/*.jar")) + sorted(IDEA.glob("plugins/*/lib/*.jar"))
print(f"scanning {len(jars)} jars for extension point declarations\n")

hits: dict[str, set] = {}

for jar in jars:
    try:
        with zipfile.ZipFile(jar) as z:
            names = z.namelist()
    except Exception:
        continue
    for n in names:
        if not n.endswith(".xml") or not n.startswith("META-INF/"):
            continue
        try:
            data = z.read(n).decode("utf-8", "replace")
        except Exception:
            continue
        for w in WANT:
            for m in re.finditer(re.escape(f'name="{w}"'), data):
                snip = data[max(0, m.start() - 260): m.start() + 420]
                snip = re.sub(r"\s+", " ", snip)
                hits.setdefault(w, set()).add((jar.name, n, snip))

for w in WANT:
    print(f"================ {w} ================")
    entries = sorted(hits.get(w, []))
    if not entries:
        print("  (NOT declared in lib/ or plugins/*/lib/)")
    for jar_name, xml_name, snip in entries[:6]:
        print(f"  -- {jar_name} :: {xml_name}")
        print(f"     {snip}")
    print()