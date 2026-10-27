package ideabridge;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reading and writing files, always preferring the live buffer over the copy on disk.
 *
 * Two rules that matter here.
 *
 * First: when a file is open in an editor, IDEA's buffer is the truth and the disk copy is
 * potentially stale. Writing straight to disk in that situation makes the editor reload or
 * silently discard the change, so every mutation first asks whether a document exists and takes
 * the document path when it does.
 *
 * Second: refreshing the VFS is only legal while the caller holds no lock at all -- neither a read
 * nor a write action. A refresh attempted under a lock is skipped silently rather than failing
 * loudly, so every refresh in this class is deliberately called outside the action wrappers.
 */
final class FileSvc {

    private FileSvc() {
    }

    static Map<String, Object> read(Project project, String target) {
        // Resolved before entering the read action, because a refresh under a read lock does nothing.
        VirtualFile found = refreshAndFind(target);
        return BridgeCommands.read(() -> {
            VirtualFile file = found;
            Map<String, Object> result = Json.obj();
            Json.put(result, "path", target);
            if (file == null) {
                Json.put(result, "exists", Boolean.FALSE);
                Json.put(result, "onDisk", Boolean.valueOf(Files.exists(Paths.get(target))));
                return result;
            }
            if (file.isDirectory()) {
                throw new BridgeException(target + " is a directory; use file.list");
            }

            FileDocumentManager documents = FileDocumentManager.getInstance();
            Document document = documents.getCachedDocument(file);
            String text = textOf(file);

            Json.put(result, "exists", Boolean.TRUE);
            Json.put(result, "liveBuffer", Boolean.valueOf(document != null));
            Json.put(result, "unsaved", Boolean.valueOf(document != null && documents.isDocumentUnsaved(document)));
            Json.put(result, "charset", file.getCharset() == null ? null : file.getCharset().name());
            Json.put(result, "length", text.length());
            Json.put(result, "lines", text.isEmpty() ? 0 : EditorSvc.splitLines(text).size());
            Json.put(result, "lineSeparator", documents.getLineSeparator(file, project));
            Json.put(result, "text", text);
            return result;
        });
    }

    /**
     * The live buffer when the file is open in an editor, otherwise the text on disk.
     *
     * Callers must already hold a read lock. Going through the document first is what makes an
     * unsaved edit visible to every reader of this bridge, and it is also why the IOException
     * from the disk path is turned into a BridgeException here rather than escaping: Computable
     * declares no checked exceptions, so callers inside a read action cannot catch it themselves.
     */
    static String textOf(VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        if (document != null) {
            return document.getText();
        }
        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException e) {
            throw new BridgeException("cannot read " + file.getPath() + ": " + e.getMessage());
        }
    }

    static Map<String, Object> write(Project project, Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        String text = Json.str(request.get("text"));
        if (text == null) {
            throw new BridgeException("missing 'text'");
        }
        boolean createDirs = Json.boolOr(request, "createDirs", false);
        boolean save = Json.boolOr(request, "save", true);

        VirtualFile existing = refreshAndFind(target);
        Document live = existing == null
                ? null
                : BridgeCommands.read(() -> FileDocumentManager.getInstance().getCachedDocument(existing));

        Map<String, Object> result = Json.obj();
        Json.put(result, "path", target);
        Json.put(result, "bytes", text.getBytes(StandardCharsets.UTF_8).length);

        if (live != null) {
            BridgeCommands.writeCommand(project, "idea-bridge: write " + target, () -> live.setText(text));
            if (save) {
                BridgeCommands.writeAction(() -> FileDocumentManager.getInstance().saveDocument(live));
            }
            Json.put(result, "mode", "document");
            Json.put(result, "saved", Boolean.valueOf(save));
            return result;
        }

        writeToDisk(target, text, createDirs);
        // Re-read the parent directory before looking for the child. A brand-new file only becomes
        // visible to the IDE once its directory has been refreshed, and resolving the file alone is
        // not always enough to trigger that -- this used to report vfsVisible=false and leave every
        // later command convinced the file did not exist.
        Path parent = Paths.get(target).getParent();
        if (parent != null) {
            refreshAndFind(parent.toString());
        }
        VirtualFile refreshed = refreshAndFind(target);
        Json.put(result, "mode", "disk");
        Json.put(result, "saved", Boolean.TRUE);
        Json.put(result, "vfsVisible", Boolean.valueOf(refreshed != null));
        return result;
    }

    /** Writes UTF-8 to disk. The caller is responsible for refreshing the VFS afterwards. */
    static void writeToDisk(String target, String text, boolean createDirs) {
        Path path = Paths.get(target);
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                if (!createDirs) {
                    throw new BridgeException("directory " + parent + " does not exist; pass createDirs=true");
                }
                Files.createDirectories(parent);
            }
            Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BridgeException("cannot write " + target + ": " + e.getMessage());
        }
    }

    static Map<String, Object> list(Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        String glob = Json.str(request.get("glob"));
        boolean recursive = Json.boolOr(request, "recursive", false);
        int limit = Json.intOr(request, "limit", 500);
        Pattern pattern = glob == null || glob.isEmpty() ? null : globToPattern(glob);

        VirtualFile found = refreshAndFind(target);
        return BridgeCommands.read(() -> {
            VirtualFile root = found;
            if (root == null) {
                throw new BridgeException("no such directory: " + target);
            }
            if (!root.isDirectory()) {
                throw new BridgeException(target + " is not a directory");
            }
            List<Object> entries = new ArrayList<>();
            collect(root, root, recursive, pattern, limit, entries);

            Map<String, Object> result = Json.obj();
            Json.put(result, "path", target);
            Json.put(result, "glob", glob);
            Json.put(result, "recursive", Boolean.valueOf(recursive));
            Json.put(result, "count", entries.size());
            Json.put(result, "truncated", Boolean.valueOf(entries.size() >= limit));
            Json.put(result, "entries", entries);
            return result;
        });
    }

    private static void collect(VirtualFile root, VirtualFile directory, boolean recursive, Pattern pattern,
                                int limit, List<Object> out) {
        VirtualFile[] children = directory.getChildren();
        if (children == null) {
            return;
        }
        // Sorted so repeated calls return a stable order; the VFS makes no ordering promise.
        VirtualFile[] sorted = children.clone();
        Arrays.sort(sorted, Comparator.comparing(VirtualFile::getName, String.CASE_INSENSITIVE_ORDER));

        for (VirtualFile child : sorted) {
            if (out.size() >= limit) {
                return;
            }
            if (child.isDirectory()) {
                if (pattern == null) {
                    out.add(describeEntry(root, child));
                }
                if (recursive) {
                    collect(root, child, true, pattern, limit, out);
                }
            } else if (pattern == null || pattern.matcher(relativePath(root, child)).matches()) {
                out.add(describeEntry(root, child));
            }
        }
    }

    static Map<String, Object> stat(Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        VirtualFile found = refreshAndFind(target);
        return BridgeCommands.read(() -> {
            VirtualFile file = found;
            Map<String, Object> result = Json.obj();
            Json.put(result, "path", target);
            if (file == null) {
                Json.put(result, "exists", Boolean.FALSE);
                Json.put(result, "onDisk", Boolean.valueOf(Files.exists(Paths.get(target))));
                return result;
            }
            Json.put(result, "exists", Boolean.TRUE);
            Json.put(result, "directory", Boolean.valueOf(file.isDirectory()));
            Json.put(result, "writable", Boolean.valueOf(file.isWritable()));
            Json.put(result, "size", file.getLength());
            Json.put(result, "modified", file.getTimeStamp());
            Json.put(result, "url", file.getUrl());
            return result;
        });
    }

    static Map<String, Object> refresh(Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        boolean recursive = Json.boolOr(request, "recursive", true);

        VirtualFile file = refreshAndFind(target);
        if (file != null) {
            // Deliberately not wrapped in a write action. refresh() is illegal under any lock, and
            // the write-action wrapper this used to carry is why it reported success while doing
            // nothing at all.
            file.refresh(false, recursive);
        }

        Map<String, Object> result = Json.obj();
        Json.put(result, "path", target);
        Json.put(result, "found", Boolean.valueOf(file != null));
        if (file != null) {
            Json.put(result, "directory", Boolean.valueOf(file.isDirectory()));
            Json.put(result, "children", file.isDirectory() ? file.getChildren().length : 0);
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolves a path to a VirtualFile, re-reading it from disk first.
     *
     * Runs with no lock held on purpose, which is why it must stay outside the read/write action
     * wrappers. VFS refresh is only legal in the unlocked state, so the read-action wrapper this
     * used to have turned every refresh into a silent no-op -- a file created a moment earlier
     * stayed invisible to the whole IDE until something else happened to refresh its parent.
     */
    static VirtualFile refreshAndFind(String target) {
        LocalFileSystem filesystem = LocalFileSystem.getInstance();
        VirtualFile file = filesystem.refreshAndFindFileByPath(target);
        if (file == null) {
            file = filesystem.findFileByPath(target);
        }
        return file != null && file.isValid() ? file : null;
    }

    private static Map<String, Object> describeEntry(VirtualFile root, VirtualFile file) {
        Map<String, Object> entry = Json.obj();
        Json.put(entry, "name", file.getName());
        Json.put(entry, "path", file.getPath());
        Json.put(entry, "relativePath", relativePath(root, file));
        Json.put(entry, "directory", Boolean.valueOf(file.isDirectory()));
        if (!file.isDirectory()) {
            Json.put(entry, "size", file.getLength());
        }
        Json.put(entry, "modified", file.getTimeStamp());
        return entry;
    }

    private static String relativePath(VirtualFile root, VirtualFile child) {
        String rootPath = root.getPath();
        String childPath = child.getPath();
        if (childPath.length() > rootPath.length() && childPath.startsWith(rootPath)) {
            String rest = childPath.substring(rootPath.length());
            return rest.startsWith("/") ? rest.substring(1) : rest;
        }
        return childPath;
    }

    /**
     * Globs are matched against the path relative to the listed directory, so {@code *.java}
     * only sees the top level and {@code **}{@code /*.java} recurses. Bracket expressions are
     * escaped rather than honoured -- a bridge filter does not need them, and leaving them
     * unescaped would turn a stray '[' into a PatternSyntaxException.
     */
    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                case '{':
                case '}':
                case '[':
                case ']':
                case '\\':
                    regex.append('\\').append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}