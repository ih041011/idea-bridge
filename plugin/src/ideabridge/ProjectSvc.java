package ideabridge;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only views of the open projects: what is open, and how each one is laid out on disk.
 *
 * {@code project.info} is what the agent reads before touching anything, so it deliberately
 * returns the full picture -- SDK, content roots, source roots -- rather than making the
 * caller issue a second round trip per module.
 */
final class ProjectSvc {

    private ProjectSvc() {
    }

    static List<Object> list() {
        return BridgeCommands.read(() -> {
            List<Object> projects = new ArrayList<>();
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                projects.add(summary(project));
            }
            return projects;
        });
    }

    static Map<String, Object> info(Project project) {
        return BridgeCommands.read(() -> {
            Map<String, Object> result = summary(project);
            ProjectRootManager rootManager = ProjectRootManager.getInstance(project);
            Json.put(result, "projectSdkName", rootManager.getProjectSdkName());
            Json.put(result, "projectSdkType", rootManager.getProjectSdkTypeName());
            Json.put(result, "projectSdk", describeSdk(rootManager.getProjectSdk(), false));

            List<Object> contentRoots = new ArrayList<>();
            for (VirtualFile root : rootManager.getContentRoots()) {
                contentRoots.add(root.getPath());
            }
            Json.put(result, "contentRoots", contentRoots);

            List<Object> modules = new ArrayList<>();
            for (Module module : ModuleManager.getInstance(project).getModules()) {
                modules.add(describeModule(module));
            }
            Json.put(result, "moduleDetails", modules);
            return result;
        });
    }

    private static Map<String, Object> summary(Project project) {
        Map<String, Object> entry = Json.obj();
        Json.put(entry, "name", project.getName());
        Json.put(entry, "basePath", project.getBasePath());
        Json.put(entry, "locationHash", project.getLocationHash());

        List<Object> names = new ArrayList<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            names.add(module.getName());
        }
        Json.put(entry, "modules", names);
        return entry;
    }

    private static Map<String, Object> describeModule(Module module) {
        Map<String, Object> entry = Json.obj();
        Json.put(entry, "name", module.getName());
        Json.put(entry, "loaded", Boolean.valueOf(module.isLoaded()));

        ModuleRootManager roots = ModuleRootManager.getInstance(module);
        Json.put(entry, "sdkInherited", Boolean.valueOf(roots.isSdkInherited()));
        Json.put(entry, "sdk", describeSdk(roots.getSdk(), roots.isSdkInherited()));

        List<Object> contentRoots = new ArrayList<>();
        for (VirtualFile root : roots.getContentRoots()) {
            contentRoots.add(root.getPath());
        }
        Json.put(entry, "contentRoots", contentRoots);

        List<Object> sourceRoots = new ArrayList<>();
        for (VirtualFile root : roots.getSourceRoots()) {
            sourceRoots.add(root.getPath());
        }
        Json.put(entry, "sourceRoots", sourceRoots);

        List<Object> entries = new ArrayList<>();
        for (ContentEntry contentEntry : roots.getContentEntries()) {
            entries.add(describeContentEntry(contentEntry));
        }
        Json.put(entry, "contentEntries", entries);
        return entry;
    }

    private static Map<String, Object> describeContentEntry(ContentEntry contentEntry) {
        Map<String, Object> entry = Json.obj();
        Json.put(entry, "url", contentEntry.getUrl());
        VirtualFile file = contentEntry.getFile();
        Json.put(entry, "path", file == null ? null : file.getPath());

        List<Object> sources = new ArrayList<>();
        for (VirtualFile source : contentEntry.getSourceFolderFiles()) {
            sources.add(source.getPath());
        }
        Json.put(entry, "sourceFolders", sources);

        List<Object> excludes = new ArrayList<>();
        for (VirtualFile exclude : contentEntry.getExcludeFolderFiles()) {
            excludes.add(exclude.getPath());
        }
        Json.put(entry, "excludeFolders", excludes);
        return entry;
    }

    /**
     * {@code Sdk.getVersionString()} can throw for SDKs whose home directory has moved or been
     * deleted, and a project that inherits its SDK reports the project-level one here. Neither
     * case should fail the whole command, so both are reported as data instead.
     */
    private static Object describeSdk(Sdk sdk, boolean inherited) {
        if (sdk == null) {
            return null;
        }
        Map<String, Object> entry = Json.obj();
        Json.put(entry, "name", sdk.getName());
        Json.put(entry, "inherited", Boolean.valueOf(inherited));
        try {
            Json.put(entry, "homePath", sdk.getHomePath());
        } catch (Throwable t) {
            Json.put(entry, "homePath", null);
        }
        try {
            Json.put(entry, "version", sdk.getVersionString());
        } catch (Throwable t) {
            Json.put(entry, "version", "unavailable: " + t);
        }
        return entry;
    }
}