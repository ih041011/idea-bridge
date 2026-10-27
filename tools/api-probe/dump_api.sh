#!/usr/bin/env bash
# Dump the exact IntelliJ Platform API signatures we plan to call, so the plugin
# is written against the real 241 API instead of guessed signatures.
IDEA='E:\ideaIU-2024.1.4.win'
JAVAP="$IDEA\\jbr\\bin\\javap.exe"
CP="$IDEA\\lib\\*"

if [ ! -f "$JAVAP" ]; then
  echo "!! javap NOT present in JBR -- cannot dump signatures"
  exit 1
fi
echo "javap: present"
echo

methods() {
  echo "=== $1  [/$2/] ==="
  "$JAVAP" -cp "$CP" "$1" 2>&1 | grep -E "$2" | sed 's/^  //' | head -25
  echo
}

methods 'com.intellij.openapi.command.WriteCommandAction' 'runWriteCommandAction'
methods 'com.intellij.execution.ProgramRunnerUtil' 'executeConfiguration|getRunner'
methods 'com.intellij.execution.executors.DefaultRunExecutor' 'getRunExecutorInstance'
methods 'com.intellij.openapi.fileEditor.FileEditorManager' 'getSelectedTextEditor|openTextEditor|getOpenFiles|closeFile|getInstance'
methods 'com.intellij.openapi.editor.Document' 'public.*(replaceString|setText|getText|getLineNumber|getLineStartOffset|getLineEndOffset|getLineCount|insertString|deleteString)'
methods 'com.intellij.openapi.editor.Editor' 'getCaretModel|getSelectionModel|getDocument'
methods 'com.intellij.openapi.editor.CaretModel' 'getOffset|getLogicalPosition|getCaretCount'
methods 'com.intellij.openapi.editor.SelectionModel' 'getSelectedText|getSelectionStart|getSelectionEnd'
methods 'com.intellij.execution.RunManager' 'getAllSettings|getSelectedConfiguration|setSelectedConfiguration|getInstance|findConfigurationByName'
methods 'com.intellij.execution.RunContentManager' 'getAllDescriptors|getInstance|getRunContentDescriptorByContent'
methods 'com.intellij.execution.ui.RunContentDescriptor' 'getProcessHandler|getConsoleView|getDisplayName'
methods 'com.intellij.execution.ui.ConsoleView' 'getText|print'
methods 'com.intellij.execution.process.ProcessHandler' 'destroyProcess|isProcessTerminated|getExitCode'
methods 'com.intellij.openapi.vfs.LocalFileSystem' 'findFileByPath|refreshAndFindFileByPath|refreshIoFiles|getInstance'
methods 'com.intellij.openapi.vfs.VirtualFile' 'refresh|setBinaryContent|createChildData|getPath|isDirectory|getChildren|getLength|getTimeStamp|getName'
methods 'com.intellij.openapi.fileEditor.FileDocumentManager' 'getCachedDocument|getDocument|saveDocument|getFile|getInstance'
methods 'com.intellij.openapi.application.ApplicationManager' 'runReadAction|runWriteAction|invokeAndWait|invokeLater|getApplication'
methods 'com.intellij.openapi.project.ProjectManager' 'getOpenProjects|getInstance'
methods 'com.intellij.openapi.module.ModuleManager' 'getModules|getInstance'
methods 'com.intellij.openapi.vfs.VfsUtilCore' 'loadText'
methods 'com.intellij.notification.Notifications$Bus' 'notify'
methods 'com.intellij.ide.AppLifecycleListener' 'appStarted|appFrameCreated'
methods 'com.intellij.openapi.startup.ProjectActivity' 'execute'
methods 'com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl' 'getHighlights'
methods 'com.intellij.openapi.util.Computable' 'compute'
methods 'com.intellij.openapi.util.TextRange' 'getStartOffset|getEndOffset'