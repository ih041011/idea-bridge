"""Locate the IntelliJ Platform API classes we need for the idea-bridge plugin.

Scans every jar in the local IDEA install and reports which jar provides each
class, so we can build a compile-time classpath without downloading a Gradle SDK.
"""
import sys
import zipfile
from pathlib import Path

IDEA = Path(r"E:\ideaIU-2024.1.4.win")

TARGETS = [
    # application / lifecycle
    "com/intellij/openapi/application/ApplicationManager.class",
    "com/intellij/openapi/application/Application.class",
    "com/intellij/openapi/application/WriteAction.class",
    "com/intellij/openapi/application/TransactionGuard.class",
    "com/intellij/openapi/startup/StartupActivity.class",
    "com/intellij/openapi/components/ProjectComponent.class",
    "com/intellij/diagnostic/Log.class",
    # project / module
    "com/intellij/openapi/project/Project.class",
    "com/intellij/openapi/project/ProjectManager.class",
    "com/intellij/openapi/module/ModuleManager.class",
    "com/intellij/openapi/module/Module.class",
    "com/intellij/openapi/Disposable.class",
    # command / document / editor
    "com/intellij/openapi/command/WriteCommandAction.class",
    "com/intellij/openapi/command/CommandProcessor.class",
    "com/intellij/openapi/editor/Document.class",
    "com/intellij/openapi/editor/Editor.class",
    "com/intellij/openapi/editor/EditorFactory.class",
    "com/intellij/openapi/fileEditor/FileEditorManager.class",
    "com/intellij/openapi/fileEditor/OpenFileDescriptor.class",
    "com/intellij/openapi/fileEditor/FileEditor.class",
    # vfs / psi / files
    "com/intellij/openapi/vfs/VirtualFile.class",
    "com/intellij/openapi/vfs/VirtualFileManager.class",
    "com/intellij/openapi/vfs/LocalFileSystem.class",
    "com/intellij/openapi/vfs/impl/local/LocalFileSystemImpl.class",
    "com/intellij/psi/PsiFile.class",
    "com/intellij/psi/PsiManager.class",
    "com/intellij/openapi/fileTypes/FileTypeManager.class",
    # run configurations
    "com/intellij/execution/RunManager.class",
    "com/intellij/execution/RunnerAndConfigurationSettings.class",
    "com/intellij/execution/ProgramRunnerUtil.class",
    "com/intellij/execution/Executor.class",
    "com/intellij/execution/runners/RunProfileState.class",
    # diagnostics
    "com/intellij/codeInsight/daemon/DaemonCodeAnalyzer.class",
    "com/intellij/codeInsight/daemon/HighlightingDaemon.class",
    "com/intellij/openapi/wm/WindowManager.class",
    "com/intellij/openapi/ui/Messages.class",
]

# Also prove the JDK's own built-in HTTP server is available (zero deps).
JDK_TARGETS = ["com/sun/net/httpserver/HttpServer.class"]

BUNDLED_RUNTIME = [
    "com/google/gson/Gson.class",
    "com/google/gson/JsonObject.class",
]


def scan(targets, label):
    print(f"=== {label} ===")
    found = {}
    for jar in sorted(IDEA.rglob("*.jar")):
        try:
            with zipfile.ZipFile(jar) as z:
                names = set(z.namelist())
        except Exception:
            continue
        for t in targets:
            if t in names:
                found.setdefault(t, []).append(jar.relative_to(IDEA))
    for t in targets:
        hits = found.get(t, [])
        if hits:
            print(f"  OK   {t}")
            for h in hits[:3]:
                print(f"         <- {h}")
        else:
            print(f"  MISS {t}")
    return found


def scan_jdk():
    jrt = Path(r"E:\ideaIU-2024.1.4.win\jbr\lib")
    print("=== JBR (JDK) ===")
    for jar in sorted(jrt.glob("*.jar")):
        print(f"  jbr jar: {jar.name}")


if __name__ == "__main__":
    all_jars = sorted(IDEA.rglob("*.jar"))
    print(f"total jars under {IDEA}: {len(all_jars)}\n")
    scan(TARGETS, "IntelliJ Platform API")
    print()
    scan(BUNDLED_RUNTIME, "Bundled third-party (gson etc.)")
    print()
    scan_jdk()