package ideabridge;

import com.intellij.execution.Executor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run configurations: list them, launch one, stop one, and read back what it printed.
 *
 * Launching goes through {@link ProgramRunnerUtil} rather than a raw process, so the run appears
 * in IDEA's Run tool window exactly as if the user had pressed the green arrow -- same console,
 * same re-run button, same Gradle/Maven integration.
 *
 * Picking the right console is the subtle part. IDEA keeps every run tab it has ever created,
 * including finished ones, and several can share a display name -- so "the first match" hands back
 * the oldest tab and a caller asking what its program just printed silently gets a previous run's
 * output. The rules here are: prefer a console whose process is still alive, and if a launch was
 * requested through this bridge, insist on a descriptor that did not exist at that moment.
 */
final class RunSvc {

    /**
     * The descriptors that already existed when {@code run.execute} was called, keyed by
     * "project|name". Anything absent from that list is the run the caller just started.
     */
    private static final Map<String, List<RunContentDescriptor>> BEFORE_LAUNCH = new ConcurrentHashMap<>();

    private RunSvc() {
    }

    static List<Object> list(Project project) {
        return BridgeCommands.read(() -> {
            List<Object> configurations = new ArrayList<>();
            List<?> settings = RunManager.getInstance(project).getAllSettings();
            for (Object item : settings) {
                configurations.add(describe((RunnerAndConfigurationSettings) item));
            }
            return configurations;
        });
    }

    static Map<String, Object> execute(Project project, Map<String, Object> request) {
        String name = Json.str(request.get("name"));
        if (name == null || name.isEmpty()) {
            throw new BridgeException("missing 'name'; call run.list for the available configurations");
        }
        RunnerAndConfigurationSettings settings = BridgeCommands.read(
                () -> RunManager.getInstance(project).findConfigurationByName(name));
        if (settings == null) {
            throw new BridgeException("no run configuration named '" + name + "' in " + project.getName()
                    + "; call run.list");
        }

        Executor executor = DefaultRunExecutor.getRunExecutorInstance();
        BridgeCommands.edt(() -> {
            // Snapshot first: everything already in the list belongs to an earlier run, and
            // run.console uses this to tell the new console apart from the ones before it.
            BEFORE_LAUNCH.put(launchKey(project, name), allDescriptors(project));
            RunManager.getInstance(project).setSelectedConfiguration(settings);
            ProgramRunnerUtil.executeConfiguration(settings, executor);
        });

        Map<String, Object> result = Json.obj();
        Json.put(result, "name", name);
        Json.put(result, "executor", "DefaultRunExecutor");
        Json.put(result, "started", Boolean.TRUE);
        Json.put(result, "hint", "poll run.console with waitMs to collect the output");
        return result;
    }

    static Map<String, Object> stop(Project project, Map<String, Object> request) {
        String name = Json.str(request.get("name"));
        RunContentDescriptor descriptor = findDescriptor(project, name);

        Map<String, Object> result = Json.obj();
        Json.put(result, "name", name);
        if (descriptor == null) {
            Json.put(result, "stopped", Boolean.FALSE);
            Json.put(result, "reason", "nothing is running under that name");
            return result;
        }
        ProcessHandler handler = descriptor.getProcessHandler();
        if (handler == null) {
            Json.put(result, "stopped", Boolean.FALSE);
            Json.put(result, "reason", "the run has no live process (it may have already finished)");
            return result;
        }
        Json.put(result, "wasTerminated", Boolean.valueOf(handler.isProcessTerminated()));
        BridgeCommands.edt(handler::destroyProcess);
        Json.put(result, "stopped", Boolean.TRUE);
        return result;
    }

    static Map<String, Object> console(Project project, Map<String, Object> request) {
        String name = Json.str(request.get("name"));
        int maxChars = Json.intOr(request, "maxChars", 20000);
        long waitMs = Math.max(0L, Json.intOr(request, "waitMs", 0));

        List<RunContentDescriptor> before = snapshotFor(project, name);
        long deadline = System.currentTimeMillis() + waitMs;

        // A launch is asynchronous, so a caller that just ran something can ask the bridge to wait
        // instead of polling from the outside. The first loop waits for the console to show up at
        // all; returning the moment a stale tab matched would defeat the point. A descriptor that
        // did not exist at launch time counts as arrived even if the process has already finished,
        // which is what keeps a fast program from stalling here until the deadline.
        RunContentDescriptor descriptor = null;
        while (true) {
            descriptor = findDescriptor(project, name);
            if (isLive(descriptor) || isFresh(before, descriptor)
                    || System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Then, if it is still alive, let it finish within the remaining budget. Stopping at the
        // appearance of the tab would hand back a console holding only the command line.
        while (isLive(descriptor) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            RunContentDescriptor refreshed = findDescriptor(project, name);
            if (refreshed != null) {
                descriptor = refreshed;
            }
        }

        Map<String, Object> result = Json.obj();
        Json.put(result, "name", name);
        if (descriptor == null) {
            Json.put(result, "found", Boolean.FALSE);
            Json.put(result, "text", "");
            Json.put(result, "reason", name == null
                    ? "nothing has been run in this project"
                    : "no run console named '" + name + "'");
            return result;
        }

        Json.put(result, "found", Boolean.TRUE);
        Json.put(result, "displayName", descriptor.getDisplayName());
        ProcessHandler handler = descriptor.getProcessHandler();
        if (handler != null) {
            Json.put(result, "exitCode", handler.getExitCode());
            Json.put(result, "terminated", Boolean.valueOf(handler.isProcessTerminated()));
        }

        RunContentDescriptor resolved = descriptor;
        String[] captured = new String[1];
        BridgeCommands.edt(() -> captured[0] = BridgeCommands.read(() -> consoleText(resolved)));
        String text = captured[0] == null ? "" : captured[0];

        boolean truncated = text.length() > maxChars;
        if (truncated) {
            // The tail is what matters: a failure is reported at the end of the output.
            text = text.substring(text.length() - maxChars);
        }
        Json.put(result, "truncated", Boolean.valueOf(truncated));
        Json.put(result, "length", text.length());
        Json.put(result, "text", text);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> describe(RunnerAndConfigurationSettings settings) {
        RunConfiguration configuration = settings.getConfiguration();
        ConfigurationType type = settings.getType();

        Map<String, Object> entry = Json.obj();
        Json.put(entry, "name", settings.getName());
        Json.put(entry, "temporary", Boolean.valueOf(settings.isTemporary()));
        Json.put(entry, "uniqueId", settings.getUniqueID());
        Json.put(entry, "typeId", type == null ? null : type.getId());
        Json.put(entry, "typeName", type == null ? null : type.getDisplayName());
        Json.put(entry, "configurationClass", configuration == null ? null : configuration.getClass().getName());
        return entry;
    }

    /**
     * The console to report for a name: a live one if there is one, otherwise the most recently
     * created match. Content-manager state is mutated on the EDT, so it is read there too.
     */
    private static RunContentDescriptor findDescriptor(Project project, String name) {
        RunContentDescriptor[] found = new RunContentDescriptor[1];
        BridgeCommands.edt(() -> {
            RunContentDescriptor newest = null;
            for (RunContentDescriptor descriptor : allDescriptors(project)) {
                if (!matches(descriptor, name)) {
                    continue;
                }
                // getAllDescriptors preserves creation order, so the last match is the newest.
                newest = descriptor;
                ProcessHandler handler = descriptor.getProcessHandler();
                if (handler != null && !handler.isProcessTerminated()) {
                    found[0] = descriptor;
                    return;
                }
            }
            found[0] = newest;
        });
        return found[0];
    }

    /** Must run on the EDT. */
    private static List<RunContentDescriptor> allDescriptors(Project project) {
        List<RunContentDescriptor> out = new ArrayList<>();
        for (Object item : RunContentManager.getInstance(project).getAllDescriptors()) {
            out.add((RunContentDescriptor) item);
        }
        return out;
    }

    private static List<RunContentDescriptor> snapshotFor(Project project, String name) {
        List<RunContentDescriptor>[] holder = new List[1];
        BridgeCommands.edt(() -> holder[0] = BEFORE_LAUNCH.get(launchKey(project, name)));
        return holder[0];
    }

    private static boolean isFresh(List<RunContentDescriptor> before, RunContentDescriptor candidate) {
        if (before == null || candidate == null) {
            return false;
        }
        for (RunContentDescriptor prior : before) {
            if (prior == candidate) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLive(RunContentDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        ProcessHandler handler = descriptor.getProcessHandler();
        return handler != null && !handler.isProcessTerminated();
    }

    private static boolean matches(RunContentDescriptor descriptor, String name) {
        return name == null || name.isEmpty() || name.equals(descriptor.getDisplayName());
    }

    private static String launchKey(Project project, String name) {
        return project.getLocationHash() + "|" + name;
    }

    private static String consoleText(RunContentDescriptor descriptor) {
        ExecutionConsole console = descriptor.getExecutionConsole();
        if (console instanceof ConsoleViewImpl impl) {
            return impl.getText();
        }
        // ConsoleView exposes no text accessor, so a console that is not ConsoleViewImpl simply
        // has nothing readable; reporting empty is better than guessing at a private field.
        return "";
    }
}