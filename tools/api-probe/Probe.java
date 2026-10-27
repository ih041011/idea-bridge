import com.intellij.openapi.project.Project;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.Executor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiManager;
import com.sun.net.httpserver.HttpServer;

/** Compile-time probe: proves javac can resolve both platform API and jdk.httpserver. */
public final class Probe {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create();
        System.out.println("httpserver ok: " + server.getClass().getName());
        Class<?>[] apis = {
            Project.class, WriteCommandAction.class, Document.class, VirtualFile.class,
            FileEditorManager.class, RunManager.class, RunnerAndConfigurationSettings.class,
            ProgramRunnerUtil.class, Executor.class, ApplicationManager.class,
            ProjectManager.class, ModuleManager.class, WindowManager.class, PsiManager.class,
        };
        for (Class<?> c : apis) {
            System.out.println("resolved: " + c.getName());
        }
    }
}