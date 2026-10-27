# API probe scripts

These are not needed to build or use the plugin. They are kept because of *how* this plugin was
written: there is no Gradle build and no downloaded IntelliJ Platform SDK, so every API call in
`plugin/src/ideabridge/` was checked against the API actually installed on the machine before it was
written. Anyone porting this to another build needs exactly these tools.

They are listed roughly in the order they were useful.

| Script | What it does |
| --- | --- |
| `ReflectDump.java` | Prints the public constructors and methods of any class, loaded by reflection from an IDEA installation. Reflection cannot see varargs or non-public members. |
| `api_dump.sh` | Compiles and runs `ReflectDump` against `$IDEA_HOME/lib/*`. Edit the class list inside to ask about different types. |
| `api_dump2.sh` | The second pass, with the class list extended. Superseded by editing `api_dump.sh`. |
| `find_lifecycle_ep.py` | Walks every jar in an installation and prints the declarations of the startup/lifecycle extension points, so `plugin.xml` cannot be written against an extension point that does not exist. |
| `probe_jars.py` | Finds which jar a class lives in. |
| `find_classes.py` | An earlier, narrower version of `probe_jars.py`, with the class list hard-coded; it ignores its arguments. |
| `Probe.java` | A minimal compile-time check that the platform API resolves from `$IDEA_HOME/lib/*` at all. |
| `check_extpoints.py` | **Superseded, kept as a warning.** It scanned only `lib/*.jar` and `plugins/*/lib/*.jar`, found nothing, and would have led to the wrong conclusion. The extension points are declared in `lib/util-8.jar` and `lib/app-client.jar`. Use `find_lifecycle_ep.py`, which walks every jar. |
| `dump_api.sh` | **Dead end.** The bundled JBR ships `javac`, `java` and `keytool` but no `javap`, so this approach cannot work. `ReflectDump.java` exists because of this. |

Two lessons that cost real time, recorded here so they do not have to be learned again:

1. **A narrow jar scan produces confident false negatives.** `check_extpoints.py` reporting "no such
   extension point" was a bug in the script, not a fact about the IDE. Scanning everything is cheap.
2. **`javac` reports errors in batches.** Fixing the first one only reveals the next; the compile has
   to be re-run after each fix rather than read once.