"""Find which jar holds classes whose package we are unsure about."""
import zipfile
from pathlib import Path

IDEA = Path(r"E:\ideaIU-2024.1.4.win")
WANT = ["RunContentManager", "ConsoleViewImpl", "RunContentDescriptor", "ProgramRunnerUtil"]

jars = sorted(IDEA.rglob("*.jar"))
for w in WANT:
    print(f"=== {w} ===")
    shown = 0
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as z:
                hits = [n for n in z.namelist() if n.endswith(w + ".class")]
        except Exception:
            continue
        for h in hits:
            print(f"  {jar.relative_to(IDEA)} :: {h}")
            shown += 1
            if shown > 8:
                break
        if shown > 8:
            break
    if shown == 0:
        print("  (not found)")
    print()