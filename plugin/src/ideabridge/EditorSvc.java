package ideabridge;

import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Everything that touches an editor: opening, orienting, editing, saving, closing.
 *
 * Edits are applied through the {@link Document} inside a write command rather than written to
 * disk, because that is what makes them undoable with Ctrl+Z and keeps the PSI, the caret and
 * every open view valid. Offsets are applied back-to-front so an edit can never shift the
 * offsets of the edits that follow it -- the single most common way a batch edit corrupts a file.
 */
final class EditorSvc {

    /** A single offset replacement: replace [start, end) with text. */
    private record Edit(int start, int end, String text) {
    }

    private EditorSvc() {
    }

    static Map<String, Object> open(Project project, Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        Integer line = Json.intOr(request, "line", null);
        int column = Json.intOr(request, "column", 0);
        boolean focus = Json.boolOr(request, "focus", true);

        VirtualFile file = require(target);
        OpenFileDescriptor descriptor = line != null && line >= 1
                ? new OpenFileDescriptor(project, file, line - 1, Math.max(0, column))
                : new OpenFileDescriptor(project, file);

        Map<String, Object> result = Json.obj();
        Json.put(result, "path", file.getPath());
        Json.put(result, "url", file.getUrl());
        Json.put(result, "requestedLine", line);
        BridgeCommands.edt(() -> {
            Editor editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, focus);
            Json.put(result, "opened", Boolean.valueOf(editor != null));
            if (editor != null) {
                describeEditor(result, editor);
            }
        });
        return result;
    }

    static Map<String, Object> active(Project project) {
        Map<String, Object> result = Json.obj();
        if (project == null) {
            Json.put(result, "hasActiveEditor", Boolean.FALSE);
            Json.put(result, "reason", "no single project is open; pass \"project\"");
            return result;
        }
        BridgeCommands.edt(() -> {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            Json.put(result, "hasActiveEditor", Boolean.valueOf(editor != null));
            if (editor == null) {
                return;
            }
            VirtualFile file = BridgeCommands.read(
                    () -> FileDocumentManager.getInstance().getFile(editor.getDocument()));
            Json.put(result, "path", file == null ? null : file.getPath());
            describeEditor(result, editor);
        });
        return result;
    }

    static Map<String, Object> edits(Project project, Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        List<Object> raw = Json.listOr(request, "edits");
        if (raw.isEmpty()) {
            throw new BridgeException("missing 'edits': a non-empty array of {start, end, text}");
        }
        boolean save = Json.boolOr(request, "save", true);
        boolean returnText = Json.boolOr(request, "returnText", false);

        List<Edit> edits = new ArrayList<>();
        for (Object entry : raw) {
            Map<String, Object> spec = Json.asObject(entry);
            if (spec == null) {
                throw new BridgeException("every edit must be an object with start/end/text");
            }
            Integer start = Json.intOr(spec, "start", null);
            if (start == null) {
                throw new BridgeException("an edit is missing 'start'");
            }
            Integer end = Json.intOr(spec, "end", null);
            String text = Json.str(spec.get("text"));
            edits.add(new Edit(start, end == null ? start : end, text == null ? "" : text));
        }
        edits.sort(Comparator.comparingInt(Edit::start).reversed());

        VirtualFile file = require(target);
        Document live = BridgeCommands.read(() -> FileDocumentManager.getInstance().getCachedDocument(file));

        Map<String, Object> result = Json.obj();
        Json.put(result, "path", file.getPath());
        Json.put(result, "applied", edits.size());
        Json.put(result, "appliedBackToFront", Boolean.TRUE);

        if (live != null) {
            BridgeCommands.writeCommand(project, "idea-bridge: edit " + target, () -> {
                int length = live.getText().length();
                for (Edit edit : edits) {
                    int start = clamp(edit.start(), 0, length);
                    int end = clamp(edit.end(), start, length);
                    live.replaceString(start, end, edit.text());
                }
            });
            if (save) {
                BridgeCommands.writeAction(() -> FileDocumentManager.getInstance().saveDocument(live));
            }
            Json.put(result, "mode", "document");
            Json.put(result, "saved", Boolean.valueOf(save));
            Json.put(result, "lineCount", BridgeCommands.read(live::getLineCount));
            Json.put(result, "length", BridgeCommands.read(() -> live.getText().length()));
            if (returnText) {
                Json.put(result, "text", BridgeCommands.read(live::getText));
            }
            return result;
        }

        // Not open in any editor: edit a copy and put it on disk, then let the VFS notice.
        String original = BridgeCommands.read(() -> FileSvc.textOf(file));
        String updated = applyToString(original, edits);
        FileSvc.writeToDisk(target, updated, false);
        FileSvc.refreshAndFind(target);
        Json.put(result, "mode", "disk");
        Json.put(result, "saved", Boolean.TRUE);
        Json.put(result, "lineCount", splitLines(updated).size());
        Json.put(result, "length", updated.length());
        if (returnText) {
            Json.put(result, "text", updated);
        }
        return result;
    }

    static Map<String, Object> save(Project project, Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        VirtualFile file = require(target);
        Document live = BridgeCommands.read(() -> FileDocumentManager.getInstance().getCachedDocument(file));

        Map<String, Object> result = Json.obj();
        Json.put(result, "path", file.getPath());
        if (live == null) {
            Json.put(result, "saved", Boolean.FALSE);
            Json.put(result, "reason", "no open buffer for this file; the copy on disk is already current");
            return result;
        }
        BridgeCommands.writeAction(() -> FileDocumentManager.getInstance().saveDocument(live));
        Json.put(result, "saved", Boolean.TRUE);
        return result;
    }

    static Map<String, Object> close(Project project, Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        VirtualFile file = require(target);

        Map<String, Object> result = Json.obj();
        Json.put(result, "path", file.getPath());
        boolean[] wasOpen = new boolean[1];
        BridgeCommands.edt(() -> {
            FileEditorManager manager = FileEditorManager.getInstance(project);
            FileEditor[] editors = manager.getEditors(file);
            wasOpen[0] = editors.length > 0;
            if (wasOpen[0]) {
                manager.closeFile(file);
            }
        });
        Json.put(result, "wasOpen", Boolean.valueOf(wasOpen[0]));
        Json.put(result, "closed", Boolean.valueOf(wasOpen[0]));
        return result;
    }

    static Map<String, Object> context(Project project, Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        Integer line = Json.intOr(request, "line", null);
        int before = Json.intOr(request, "before", 20);
        int after = Json.intOr(request, "after", 20);

        VirtualFile file = require(target);
        return BridgeCommands.read(() -> {
            String text = FileSvc.textOf(file);

            List<String> lines = splitLines(text);
            int total = lines.size();
            int focus = line != null && line >= 1 ? Math.min(line, Math.max(1, total)) : 1;
            int from = Math.max(1, focus - Math.max(0, before));
            int to = Math.min(total, focus + Math.max(0, after));

            List<Object> window = new ArrayList<>();
            for (int i = from; i <= to; i++) {
                Map<String, Object> entry = Json.obj();
                Json.put(entry, "line", i);
                Json.put(entry, "text", lines.get(i - 1));
                Json.put(entry, "focus", Boolean.valueOf(i == focus));
                window.add(entry);
            }

            Map<String, Object> result = Json.obj();
            Json.put(result, "path", file.getPath());
            Json.put(result, "lineCount", total);
            Json.put(result, "focusLine", focus);
            Json.put(result, "from", from);
            Json.put(result, "to", to);
            Json.put(result, "liveBuffer",
                    Boolean.valueOf(FileDocumentManager.getInstance().getCachedDocument(file) != null));
            Json.put(result, "lines", window);
            return result;
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void describeEditor(Map<String, Object> result, Editor editor) {
        Document document = editor.getDocument();
        CaretModel caret = editor.getCaretModel();
        SelectionModel selection = editor.getSelectionModel();
        LogicalPosition position = caret.getLogicalPosition();

        Json.put(result, "line", position.line + 1);
        Json.put(result, "column", position.column);
        Json.put(result, "caretOffset", caret.getOffset());
        Json.put(result, "lineCount", document.getLineCount());
        Json.put(result, "selectionStart", selection.getSelectionStart());
        Json.put(result, "selectionEnd", selection.getSelectionEnd());
        Json.put(result, "selectedText", selection.getSelectedText());
    }

    private static VirtualFile require(String target) {
        VirtualFile file = FileSvc.refreshAndFind(target);
        if (file == null) {
            throw new BridgeException("no such file: " + target);
        }
        if (file.isDirectory()) {
            throw new BridgeException(target + " is a directory; use file.list");
        }
        return file;
    }

    private static String applyToString(String text, List<Edit> edits) {
        StringBuilder builder = new StringBuilder(text);
        for (Edit edit : edits) {
            int start = clamp(edit.start(), 0, builder.length());
            int end = clamp(edit.end(), start, builder.length());
            builder.replace(start, end, edit.text());
        }
        return builder.toString();
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    /** Splits on '\n' and drops a trailing '\r', so CRLF files do not report a phantom column. */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                lines.add(text.substring(start, end));
                start = i + 1;
            }
        }
        lines.add(text.substring(start));
        return lines;
    }
}