#!/usr/bin/env bash
# Compile ReflectDump against the platform jars and dump the exact 241 signatures
# for every API the bridge plugin will call.
set -e
IDEA='E:\ideaIU-2024.1.4.win'
JAVAC="$IDEA\\jbr\\bin\\javac.exe"
JAVA="$IDEA\\jbr\\bin\\java.exe"
CP="$IDEA\\lib\\*"
OUT='F:/DSH Workspace/idea-bridge/build/tools'
SRC='F:/DSH Workspace/idea-bridge/tools'

mkdir -p "$OUT"
"$JAVAC" -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" "$SRC/ReflectDump.java"

"$JAVA" -cp "$OUT;$CP" ReflectDump \
  'com.intellij.openapi.command.WriteCommandAction#runWriteCommandAction' \
  'com.intellij.execution.ProgramRunnerUtil#executeConfiguration|getRunner' \
  'com.intellij.execution.executors.DefaultRunExecutor#getRunExecutorInstance' \
  'com.intellij.openapi.fileEditor.FileEditorManager#getSelectedTextEditor|openTextEditor|getOpenFiles|closeFile|getInstance' \
  'com.intellij.openapi.editor.Document#replaceString|setText|getText|getLineNumber|getLineStartOffset|getLineEndOffset|getLineCount|insertString|deleteString' \
  'com.intellij.openapi.editor.Editor#getCaretModel|getSelectionModel|getDocument' \
  'com.intellij.openapi.editor.CaretModel#getOffset|getLogicalPosition' \
  'com.intellij.openapi.editor.SelectionModel#getSelectedText|getSelectionStart|getSelectionEnd' \
  'com.intellij.execution.RunManager#getAllSettings|getSelectedConfiguration|setSelectedConfiguration|getInstance|findConfigurationByName' \
  'com.intellij.execution.RunContentManager#getAllDescriptors|getInstance' \
  'com.intellij.execution.ui.RunContentDescriptor#getProcessHandler|getConsoleView|getDisplayName' \
  'com.intellij.execution.ui.ConsoleView#getText|print' \
  'com.intellij.execution.process.ProcessHandler#destroyProcess|isProcessTerminated|getExitCode' \
  'com.intellij.openapi.vfs.LocalFileSystem#findFileByPath|refreshAndFindFileByPath|refreshIoFiles|getInstance' \
  'com.intellij.openapi.vfs.VirtualFile#refresh|setBinaryContent|createChildData|getPath|isDirectory|getChildren|getLength|getTimeStamp' \
  'com.intellij.openapi.fileEditor.FileDocumentManager#getCachedDocument|getDocument|saveDocument|getFile|getInstance' \
  'com.intellij.openapi.application.ApplicationManager#runReadAction|runWriteAction|invokeAndWait|invokeLater|getApplication' \
  'com.intellij.openapi.project.ProjectManager#getOpenProjects|getInstance' \
  'com.intellij.openapi.module.ModuleManager#getModules|getInstance' \
  'com.intellij.openapi.vfs.VfsUtilCore#loadText' \
  'com.intellij.notification.Notifications$Bus#notify' \
  'com.intellij.ide.AppLifecycleListener#appStarted|appFrameCreated' \
  'com.intellij.openapi.startup.ProjectActivity#execute' \
  'com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl#getHighlights' \
  'com.intellij.openapi.fileEditor.OpenFileDescriptor' \
  'com.intellij.openapi.project.Project#getBasePath|getName|isDisposed|getLocationHash' \
  'com.intellij.openapi.module.Module#getName'