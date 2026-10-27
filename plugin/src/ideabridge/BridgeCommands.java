package ideabridge;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Computable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The command table, plus the threading and project-resolution rules every command obeys.
 *
 * Keeping dispatch in one file means the whole capability of the bridge is readable at a
 * glance; the actual platform work lives in the *Svc classes.
 *
 * Threading contract, enforced here so the services cannot get it wrong:
 *   read(...)       platform state, no locks held on entry
 *   writeCommand    document/PSI mutation, goes on the undo stack
 *   writeAction     VFS mutation, not undoable
 *   edt(...)        UI mutation
 * and thread-affine work (UI) is dispatched to the EDT from a plain HTTP worker thread.
 */
public final class BridgeCommands {

    public static final int PROTOCOL_VERSION = 1;

    private BridgeCommands() {
    }

    public static Map<String, Object> dispatch(Map<String, Object> request) {
        String cmd = Json.str(request.get("cmd"));
        long startedAt = System.nanoTime();
        Map<String, Object> response = Json.obj();
        try {
            Object data = route(cmd, request);
            Json.put(response, "ok", Boolean.TRUE);
            Json.put(response, "cmd", cmd);
            Json.put(response, "data", data);
        } catch (Throwable t) {
            Json.put(response, "ok", Boolean.FALSE);
            Json.put(response, "cmd", cmd);
            Json.put(response, "error", t.getMessage() == null ? t.toString() : t.getMessage());
            Json.put(response, "errorType", t.getClass().getName());
            if (!(t instanceof BridgeException)) {
                Json.put(response, "trace", stackTraceOf(t));
            }
            BridgeLog.warn("command '" + cmd + "' failed: " + t);
        }
        Json.put(response, "ms", (System.nanoTime() - startedAt) / 1_000_000L);
        return response;
    }

    private static Object route(String cmd, Map<String, Object> request) {
        if (cmd == null || cmd.isEmpty()) {
            throw new BridgeException("missing 'cmd'; call {\"cmd\":\"help\"}");
        }
        switch (cmd) {
            case "help":
                return help();
            case "status":
                return status();
            case "project.list":
                return ProjectSvc.list();
            case "project.info":
                return ProjectSvc.info(project(request));
            case "file.read":
                return FileSvc.read(projectOrNull(request), requiredPath(request));
            case "file.write":
                return FileSvc.write(projectOrNull(request), request);
            case "file.list":
                return FileSvc.list(request);
            case "file.stat":
                return FileSvc.stat(request);
            case "vfs.refresh":
                return FileSvc.refresh(request);
            case "editor.open":
                return EditorSvc.open(project(request), request);
            case "editor.active":
                return EditorSvc.active(projectOrNull(request));
            case "editor.edits":
                return EditorSvc.edits(project(request), request);
            case "editor.save":
                return EditorSvc.save(projectOrNull(request), request);
            case "editor.close":
                return EditorSvc.close(project(request), request);
            case "editor.context":
                return EditorSvc.context(project(request), request);
            case "run.list":
                return RunSvc.list(project(request));
            case "run.execute":
                return RunSvc.execute(project(request), request);
            case "run.stop":
                return RunSvc.stop(project(request), request);
            case "run.console":
                return RunSvc.console(project(request), request);
            case "problems.get":
                return DiagSvc.get(project(request), request);
            case "log.tail":
                return logTail(request);
            default:
                throw new BridgeException("unknown cmd '" + cmd + "'; call {\"cmd\":\"help\"} for the list");
        }
    }

    // ------------------------------------------------------------------ helpers

    static String requiredPath(Map<String, Object> request) {
        String path = Json.str(request.get("path"));
        if (path == null || path.isEmpty()) {
            throw new BridgeException("missing 'path'");
        }
        return path;
    }

    static Project project(Map<String, Object> request) {
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        String wanted = Json.str(request.get("project"));
        if (wanted != null && !wanted.isEmpty()) {
            for (Project candidate : open) {
                if (wanted.equals(candidate.getName())
                        || wanted.equals(candidate.getBasePath())
                        || wanted.equals(candidate.getLocationHash())) {
                    return candidate;
                }
            }
            throw new BridgeException("no open project matches '" + wanted + "'; open projects: " + describe(open));
        }
        if (open.length == 1) {
            return open[0];
        }
        if (open.length == 0) {
            throw new BridgeException("no project is open in this IDE");
        }
        throw new BridgeException("several projects are open, pass \"project\": " + describe(open));
    }

    /**
     * For file and VFS work a project is only needed to make edits undoable, so having
     * none open (welcome screen, several projects open) is not an error.
     */
    static Project projectOrNull(Map<String, Object> request) {
        String wanted = Json.str(request.get("project"));
        if (wanted != null && !wanted.isEmpty()) {
            return project(request);
        }
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        return open.length == 1 ? open[0] : null;
    }

    private static List<Object> describe(Project[] projects) {
        List<Object> names = new ArrayList<>();
        for (Project project : projects) {
            names.add(project.getName() + " (" + project.getBasePath() + ")");
        }
        return names;
    }

    static <T> T read(Computable<T> computation) {
        return ApplicationManager.getApplication().runReadAction(computation);
    }

    static void writeCommand(Project project, String name, Runnable action) {
        WriteCommandAction.runWriteCommandAction(project, name, null, action);
    }

    /**
     * A bare write action, for VFS and document mutations that are not user-level commands.
     *
     * This has to hop to the EDT first. In 241 a plain Application.runWriteAction asserts that it
     * runs on the event thread, while WriteCommandAction does that marshalling itself -- so calling
     * straight through from a listener thread aborts with an IllegalStateException instead of
     * writing. Requests always arrive on listener threads, so the hop is not optional.
     */
    static void writeAction(Runnable action) {
        edt(() -> ApplicationManager.getApplication().runWriteAction(action));
    }

    static void edt(Runnable action) {
        Application application = ApplicationManager.getApplication();
        if (application.isDispatchThread()) {
            action.run();
        } else {
            application.invokeAndWait(action);
        }
    }

    static String stackTraceOf(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    // ------------------------------------------------------------------ outputs

    private static Map<String, Object> help() {
        String[][] commands = {
                {"status", "bridge liveness, IDE build, open projects", ""},
                {"project.list", "every open project with its base path and modules", ""},
                {"project.info", "modules, content roots and SDK of one project", "project"},
                {"file.read", "read a file; prefers the live buffer when the file is open", "path"},
                {"file.write", "write a file through the VFS, creating parents if asked", "path, text, createDirs, save"},
                {"file.list", "list a directory, optionally filtered by glob", "path, glob, recursive, limit"},
                {"file.stat", "existence, size, mtime and kind for a path", "path"},
                {"vfs.refresh", "drop the IDE's cache for a path and re-read it from disk", "path, recursive"},
                {"editor.open", "open a file in the editor, optionally at a line/column", "path, line, column, focus"},
                {"editor.active", "the file, caret and selection of the active editor", ""},
                {"editor.edits", "apply a batch of offset edits as one undoable command", "path, edits[{start,end,text}], save"},
                {"editor.save", "save a file's buffer to disk", "path"},
                {"editor.close", "close a file's editors", "path"},
                {"editor.context", "the lines around a caret position, for orientation", "path, line, before, after"},
                {"run.list", "every run configuration in the project", "project"},
                {"run.execute", "launch a run configuration by name", "name, project"},
                {"run.stop", "terminate a running configuration", "name, project"},
                {"run.console", "the console text of a running or finished configuration", "name, project, maxChars"},
                {"problems.get", "errors and warnings for one file from the IDE's analysis", "path, limit"},
                {"log.tail", "the tail of the bridge's own log file", "lines"},
        };
        List<Object> result = new ArrayList<>();
        for (String[] command : commands) {
            Map<String, Object> entry = Json.obj();
            Json.put(entry, "cmd", command[0]);
            Json.put(entry, "summary", command[1]);
            Json.put(entry, "params", command[2]);
            result.add(entry);
        }
        Map<String, Object> envelope = Json.obj();
        Json.put(envelope, "protocol", PROTOCOL_VERSION);
        Json.put(envelope, "transport", "POST /cmd with header X-Bridge-Token, body {\"cmd\":..., ...}");
        Json.put(envelope, "commands", result);
        return envelope;
    }

    private static Map<String, Object> status() {
        Map<String, Object> result = Json.obj();
        Json.put(result, "running", Boolean.valueOf(BridgeServer.isRunning()));
        Json.put(result, "port", BridgeServer.port());
        Json.put(result, "protocol", PROTOCOL_VERSION);
        String failure = BridgeServer.startupFailure();
        Json.put(result, "startupFailure", failure);

        // The IDE identity earns its place more than usual here. This bridge leans on platform APIs
        // that are not stable across builds, so a caller should be able to see which build it is
        // actually driving instead of assuming the plugin only loaded where it was meant to.
        com.intellij.openapi.application.ApplicationInfo info =
                com.intellij.openapi.application.ApplicationInfo.getInstance();
        Json.put(result, "ide", info.getFullApplicationName());
        Json.put(result, "build", String.valueOf(info.getBuild()));
        Json.put(result, "pid", Long.valueOf(ProcessHandle.current().pid()));

        Project[] open = ProjectManager.getInstance().getOpenProjects();
        List<Object> names = new ArrayList<>();
        for (Project project : open) {
            Map<String, Object> entry = Json.obj();
            Json.put(entry, "name", project.getName());
            Json.put(entry, "basePath", project.getBasePath());
            names.add(entry);
        }
        Json.put(result, "openProjects", names);
        return result;
    }

    private static Map<String, Object> logTail(Map<String, Object> request) {
        Path log = Paths.get(System.getProperty("user.home", "."), ".ideabridge", "bridge.log");
        int lines = Json.intOr(request, "lines", 200);
        Map<String, Object> result = Json.obj();
        Json.put(result, "path", log.toString());
        if (!Files.isReadable(log)) {
            Json.put(result, "exists", Boolean.FALSE);
            Json.put(result, "text", "");
            return result;
        }
        try {
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - Math.max(1, lines));
            Json.put(result, "exists", Boolean.TRUE);
            Json.put(result, "totalLines", all.size());
            Json.put(result, "text", String.join("\n", all.subList(from, all.size())));
        } catch (IOException e) {
            throw new BridgeException("cannot read " + log + ": " + e.getMessage());
        }
        return result;
    }
}