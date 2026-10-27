"""Package the compiled plugin classes and its descriptor into an installable jar.

There is no jar.exe in the bundled JBR, so the archive is written with zipfile. The
descriptor has to sit at META-INF/plugin.xml *inside* the archive -- that is the only
place the platform looks for it when the jar lives in <plugin>/lib/.
"""
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLASSES = ROOT / "build" / "classes"
DESCRIPTOR = ROOT / "plugin" / "resources" / "META-INF" / "plugin.xml"
JAR = ROOT / "dist" / "idea-bridge.jar"


def main() -> int:
    if not DESCRIPTOR.is_file():
        print(f"missing descriptor: {DESCRIPTOR}")
        return 1

    classes = sorted(CLASSES.rglob("*.class"))
    if not classes:
        print(f"no compiled classes under {CLASSES}; run the javac step first")
        return 1

    JAR.parent.mkdir(parents=True, exist_ok=True)
    JAR.unlink(missing_ok=True)

    with zipfile.ZipFile(JAR, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(DESCRIPTOR, "META-INF/plugin.xml")
        for path in classes:
            archive.write(path, path.relative_to(CLASSES).as_posix())

    with zipfile.ZipFile(JAR) as archive:
        names = archive.namelist()

    print(f"{JAR}  {JAR.stat().st_size / 1024:.1f} KiB, {len(names)} entries")
    print("descriptor at META-INF/plugin.xml:", "META-INF/plugin.xml" in names)
    for name in names:
        print("   ", name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())