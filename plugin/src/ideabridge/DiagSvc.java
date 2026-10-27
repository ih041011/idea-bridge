package ideabridge;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The IDE's own error and warning analysis, so the agent can check its work with the same engine
 * the user sees in the editor instead of guessing from a compiler's exit code.
 *
 * Analysis only exists for files the IDE has open, and it arrives progressively: right after an
 * edit the highlight list is still filling in, so an empty list means "not analysed yet" far more
 * often than it means "clean". Answering from a partial pass would let a broken file be reported
 * healthy -- the one failure this command must not have -- so it waits for the daemon first and
 * tells the caller whether that wait actually succeeded.
 */
final class DiagSvc {

    /**
     * Analysis normally settles well inside a second for a file the IDE has already seen, but a
     * file that has just been created and opened can take several seconds -- measured at roughly
     * eight on this machine. The budget is set past that, because returning too early is not a
     * slow answer but a wrong one: it reports a broken file as clean.
     */
    private static final int DEFAULT_WAIT_MS = 15000;

    private DiagSvc() {
    }

    static Map<String, Object> get(Project project, Map<String, Object> request) {
        String target = BridgeCommands.requiredPath(request);
        int limit = Json.intOr(request, "limit", 200);
        long waitMs = Math.max(0L, Json.intOr(request, "waitMs", DEFAULT_WAIT_MS));

        VirtualFile file = FileSvc.refreshAndFind(target);
        if (file == null) {
            throw new BridgeException("no such file: " + target);
        }
        if (file.isDirectory()) {
            throw new BridgeException(target + " is a directory");
        }

        Document document = BridgeCommands.read(
                () -> FileDocumentManager.getInstance().getCachedDocument(file));

        Map<String, Object> result = Json.obj();
        Json.put(result, "path", file.getPath());
        if (document == null) {
            Json.put(result, "analyzed", Boolean.FALSE);
            Json.put(result, "reason", "the file is not open in an editor, so the IDE has no analysis"
                    + " for it; call editor.open first");
            Json.put(result, "count", 0);
            Json.put(result, "errors", 0);
            Json.put(result, "warnings", 0);
            Json.put(result, "problems", new ArrayList<>());
            return result;
        }

        boolean complete = waitForAnalysis(project, file, waitMs);

        return BridgeCommands.read(() -> {
            // Passing WARNING asks for warnings and anything more severe, which is errors too.
            List<?> highlights = DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.WARNING, project);

            List<Object> problems = new ArrayList<>();
            int errors = 0;
            int warnings = 0;
            int other = 0;
            boolean truncated = false;

            for (Object item : highlights) {
                HighlightInfo info = (HighlightInfo) item;
                HighlightSeverity severity = info.getSeverity();
                String severityName = severity == null ? "" : String.valueOf(severity.getName());
                if ("ERROR".equals(severityName)) {
                    errors++;
                } else if ("WARNING".equals(severityName)) {
                    warnings++;
                } else {
                    other++;
                }

                if (problems.size() >= limit) {
                    truncated = true;
                    continue;
                }

                int start = info.getStartOffset();
                int end = info.getEndOffset();
                Map<String, Object> problem = Json.obj();
                Json.put(problem, "severity", severityName);
                Json.put(problem, "description", info.getDescription());
                Json.put(problem, "line", document.getLineNumber(start) + 1);
                Json.put(problem, "start", start);
                Json.put(problem, "end", end);
                Json.put(problem, "text", info.getText());
                problems.add(problem);
            }

            Json.put(result, "analyzed", Boolean.TRUE);
            // Without this a caller cannot tell "no problems found" from "the daemon had not got
            // there yet"; they are the same three numbers otherwise.
            Json.put(result, "complete", Boolean.valueOf(complete));
            Json.put(result, "completeReason", complete ? null
                    : "the IDE was still analysing this file after " + waitMs
                      + "ms, so these results may be incomplete; retry with a larger waitMs");
            Json.put(result, "count", problems.size());
            Json.put(result, "totalReported", highlights.size());
            Json.put(result, "errors", errors);
            Json.put(result, "warnings", warnings);
            Json.put(result, "other", other);
            Json.put(result, "truncated", Boolean.valueOf(truncated));
            Json.put(result, "problems", problems);
            return result;
        });
    }

    /**
     * Waits for the daemon to finish analysing this file, returning whether it did inside the
     * budget. Failing to finish is reported rather than hidden: a partial highlight list and a
     * clean file are indistinguishable once the list has been turned into a count.
     */
    private static boolean waitForAnalysis(Project project, VirtualFile file, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            if (analysisFinished(project, file)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static boolean analysisFinished(Project project, VirtualFile file) {
        boolean[] finished = new boolean[1];
        BridgeCommands.read(() -> {
            PsiFile psi = PsiManager.getInstance(project).findFile(file);
            // No PSI means there is nothing to analyse, so nothing is still pending for it.
            finished[0] = psi == null
                    || DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(psi);
            return null;
        });
        return finished[0];
    }
}