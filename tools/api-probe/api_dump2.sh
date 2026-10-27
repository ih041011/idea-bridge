#!/usr/bin/env bash
# Second signature pass: threading, run configurations, console text and VFS helpers.
set -e
IDEA='E:\ideaIU-2024.1.4.win'
JAVA="$IDEA\\jbr\\bin\\java.exe"
CP="$IDEA\\lib\\*"
OUT='F:/DSH Workspace/idea-bridge/build/tools'

"$JAVA" -cp "$OUT;$CP" ReflectDump \
  'com.intellij.openapi.application.Application#runReadAction|runWriteAction|invokeAndWait|invokeLater|executeOnPooledThread|isReadAccessAllowed|isDispatchThread|isHeadlessEnvironment' \
  'com.intellij.openapi.application.ReadAction#compute|run' \
  'com.intellij.openapi.application.ApplicationInfo#getInstance|getBuild|getFullApplicationName' \
  'com.intellij.openapi.application.ApplicationNamesInfo#getInstance|getFullName' \
  'com.intellij.execution.RunnerAndConfigurationSettings#getName|getType|getUniqueID|isTemporary|getConfiguration' \
  'com.intellij.execution.configurations.ConfigurationType#getId|getDisplayName' \
  'com.intellij.execution.configurations.RunConfiguration#getName|getType' \
  'com.intellij.openapi.vfs.VirtualFile#getFileSystem|isValid|getParent|findChild|createChildDirectory|getName|getUrl' \
  'com.intellij.openapi.editor.LogicalPosition' \
  'com.intellij.openapi.fileEditor.FileEditorManager#getSelectedEditor|getEditors|getAllEditors' \
  'com.intellij.execution.ui.RunContentDescriptor#getConsoleView|getProcessHandler|getDisplayName|getExecutionConsole' \
  'com.intellij.execution.impl.ConsoleViewImpl#getText|getIOFile|getFile' \
  'com.intellij.execution.ui.ConsoleView#getText|attachToProcess' \
  'com.intellij.notification.Notification' \
  'com.intellij.notification.NotificationType' \
  'com.intellij.openapi.command.CommandProcessor#executeCommand|getInstance' \
  'com.intellij.openapi.fileEditor.FileDocumentManager#saveAllDocuments|saveDocument'