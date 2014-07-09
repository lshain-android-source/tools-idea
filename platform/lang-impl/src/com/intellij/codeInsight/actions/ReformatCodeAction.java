/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.actions;

import com.intellij.application.options.editor.EditorOptions;
import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.LanguageFormatting;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.arrangement.engine.ArrangementEngine;
import com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.util.ArrayList;
import java.util.Collections;

public class ReformatCodeAction extends AnAction implements DumbAware {
  private static final @NonNls String HELP_ID = "editing.codeReformatting";

  @Override
  public void actionPerformed(AnActionEvent event) {
    DataContext dataContext = event.getDataContext();
    final Project project = PlatformDataKeys.PROJECT.getData(dataContext);
    if (project == null) {
      return;
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments();
    final Editor editor = PlatformDataKeys.EDITOR.getData(dataContext);
    final VirtualFile[] files = PlatformDataKeys.VIRTUAL_FILE_ARRAY.getData(dataContext);
    if (files == null) {
      return;
    }

    PsiFile file = null;
    final PsiDirectory dir;
    boolean hasSelection = false;

    if (editor != null){
      file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
      if (file == null) return;
      dir = file.getContainingDirectory();
      hasSelection = editor.getSelectionModel().hasSelection();
    }
    else if (areFiles(files)) {
      final ReadonlyStatusHandler.OperationStatus operationStatus = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files);
      if (!operationStatus.hasReadonlyFiles()) {
        final ReformatFilesDialog reformatFilesDialog = new ReformatFilesDialog(project, files);
        reformatFilesDialog.show();
        if (!reformatFilesDialog.isOK()) return;
        if (reformatFilesDialog.optimizeImports() && !DumbService.getInstance(project).isDumb()) {
          new ReformatAndOptimizeImportsProcessor(
            project, convertToPsiFiles(files, project), reformatFilesDialog.isProcessOnlyChangedText()
          ).run();
        }
        else {
          new ReformatCodeProcessor(project, convertToPsiFiles(files, project), null, reformatFilesDialog.isProcessOnlyChangedText()).run();
        }
      }

      return;
    }
    else {
      Project projectContext = PlatformDataKeys.PROJECT_CONTEXT.getData(dataContext);
      Module moduleContext = LangDataKeys.MODULE_CONTEXT.getData(dataContext);

      if (projectContext != null || moduleContext != null) {
        final String text;
        if (moduleContext != null) {
          text = CodeInsightBundle.message("process.scope.module", moduleContext.getModuleFilePath());
        }
        else {
          text = CodeInsightBundle.message("process.scope.project", project.getPresentableUrl());
        }

        LayoutProjectCodeDialog dialog
          = new LayoutProjectCodeDialog(project, moduleContext, CodeInsightBundle.message("process.reformat.code"), text, true);
        dialog.show();
        if (!dialog.isOK()) return;
        if (dialog.isOptimizeImports() && !DumbService.getInstance(project).isDumb()) {
          if (moduleContext != null) {
            new ReformatAndOptimizeImportsProcessor(project, moduleContext, dialog.isProcessOnlyChangedText()).run();
          }
          else {
            new ReformatAndOptimizeImportsProcessor(project, dialog.isProcessOnlyChangedText()).run();
          }
        }
        else {
          if (moduleContext != null) {
            new ReformatCodeProcessor(project, moduleContext, dialog.isProcessOnlyChangedText()).run();
          }
          else {
            new ReformatCodeProcessor(project, dialog.isProcessOnlyChangedText()).run();
          }
        }
        return;
      }

      PsiElement element = LangDataKeys.PSI_ELEMENT.getData(dataContext);
      if (element == null) return;
      if (element instanceof PsiDirectoryContainer) {
        dir = ((PsiDirectoryContainer)element).getDirectories()[0];
      }
      else if (element instanceof PsiDirectory) {
        dir = (PsiDirectory)element;
      }
      else {
        file = element.getContainingFile();
        if (file == null) return;
        dir = file.getContainingDirectory();
      }
    }

    boolean optimizeImports = ReformatFilesDialog.isOptmizeImportsOptionOn();
    boolean processWholeFile = false;
    boolean processChangedTextOnly = PropertiesComponent.getInstance().getBoolean(LayoutCodeConstants.PROCESS_CHANGED_TEXT_KEY, false);
    boolean rearrangeEntries = PropertiesComponent.getInstance().getBoolean(LayoutCodeConstants.REARRANGE_ENTRIES_KEY, false);
    final boolean showDialog = EditorSettingsExternalizable.getInstance().getOptions().SHOW_REFORMAT_DIALOG;
    if (showDialog || (file == null && dir != null)) {
      final LayoutCodeDialog dialog = new LayoutCodeDialog(project, CodeInsightBundle.message("process.reformat.code"), file, dir,
                                                           hasSelection ? Boolean.TRUE : Boolean.FALSE, HELP_ID);
      dialog.show();
      if (!dialog.isOK()) return;
      final boolean showDialogAtFuture = !dialog.isDoNotAskMe();
      EditorSettingsExternalizable.getInstance().getOptions().SHOW_REFORMAT_DIALOG = showDialogAtFuture;
      updateShowDialogSetting(dialog, "\"Reformat Code\" dialog disabled");
      optimizeImports = dialog.isOptimizeImports();
      rearrangeEntries = dialog.isRearrangeEntries();
      processWholeFile = dialog.isProcessWholeFile();
      processChangedTextOnly = dialog.isProcessOnlyChangedText();
      
      if (dialog.isProcessDirectory()){
        if (optimizeImports) {
          new ReformatAndOptimizeImportsProcessor(project, dir, dialog.isIncludeSubdirectories(), processChangedTextOnly).run();
        }
        else {
          new ReformatCodeProcessor(project, dir, dialog.isIncludeSubdirectories(), processChangedTextOnly).run();
        }
        return;
      }
    }
    
    final TextRange range;
    if (!processWholeFile && editor != null && editor.getSelectionModel().hasSelection()){
      range = TextRange.create(editor.getSelectionModel().getSelectionStart(), editor.getSelectionModel().getSelectionEnd());
    }
    else{
      range = null;
    }

    if (optimizeImports && range == null) {
      if (file != null || dir == null) {
        new ReformatAndOptimizeImportsProcessor(project, file, processChangedTextOnly).run();
      }
      else {
        new ReformatAndOptimizeImportsProcessor(project, dir, true, processChangedTextOnly).run();
      }
    }
    else {
      new ReformatCodeProcessor(project, file, range, processChangedTextOnly).run();
    }

    if (rearrangeEntries && file != null && editor != null) {
      final ArrangementEngine engine = ServiceManager.getService(project, ArrangementEngine.class);
      try {
        final PsiFile finalFile = file;
        SelectionModel selectionModel = editor.getSelectionModel();
        final TextRange rangeToUse = selectionModel.hasSelection()
                                     ? TextRange.create(selectionModel.getSelectionStart(), selectionModel.getSelectionEnd())
                                     : TextRange.create(0, editor.getDocument().getTextLength());
        CommandProcessor.getInstance().executeCommand(project, new Runnable() {
          @Override
          public void run() {
            engine.arrange(finalFile, Collections.singleton(rangeToUse));
          }
        }, getTemplatePresentation().getText(), null);
      }
      finally {
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
      }
    }
  }

  public static void updateShowDialogSetting(LayoutCodeDialog dialog, String title) {
    if (dialog.isDoNotAskMe()) {
      Notifications.Bus.notify(new Notification("Reformat Code", title,
                                                "<html>You can re-enable the dialog on the <a href=''>IDE Settings -> Editor</a> pane</html>",
                                                NotificationType.INFORMATION, new NotificationListener() {
          @Override
          public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent e) {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
              final ShowSettingsUtil util = ShowSettingsUtil.getInstance();
              IdeFrame ideFrame = WindowManagerEx.getInstanceEx().findFrameFor(null);
              util.editConfigurable((JFrame)ideFrame, new EditorOptions());
            }
          }
        }));
    }
  }

  public static PsiFile[] convertToPsiFiles(final VirtualFile[] files,Project project) {
    final PsiManager manager = PsiManager.getInstance(project);
    final ArrayList<PsiFile> result = new ArrayList<PsiFile>();
    for (VirtualFile virtualFile : files) {
      final PsiFile psiFile = manager.findFile(virtualFile);
      if (psiFile != null) result.add(psiFile);
    }
    return PsiUtilCore.toPsiFileArray(result);
  }

  @Override
  public void update(AnActionEvent event){
    Presentation presentation = event.getPresentation();
    DataContext dataContext = event.getDataContext();
    Project project = PlatformDataKeys.PROJECT.getData(dataContext);
    if (project == null){
      presentation.setEnabled(false);
      return;
    }

    Editor editor = PlatformDataKeys.EDITOR.getData(dataContext);

    final VirtualFile[] files = PlatformDataKeys.VIRTUAL_FILE_ARRAY.getData(dataContext);

    if (editor != null){
      PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
      if (file == null || file.getVirtualFile() == null) {
        presentation.setEnabled(false);
        return;
      }

      if (LanguageFormatting.INSTANCE.forContext(file)  != null) {
        presentation.setEnabled(true);
        return;
      }
    }
    else if (files!= null && areFiles(files)) {
      boolean anyFormatters = false;
      for (VirtualFile virtualFile : files) {
        if (virtualFile.isDirectory()) {
          presentation.setEnabled(false);
          return;
        }
        final PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
        if (psiFile == null) {
          presentation.setEnabled(false);
          return;
        }
        final FormattingModelBuilder builder = LanguageFormatting.INSTANCE.forContext(psiFile);
        if (builder != null) {
          anyFormatters = true;
        }
      }
      if (!anyFormatters) {
        presentation.setEnabled(false);
        return;
      }
    }
    else if (files != null && files.length == 1) {
      // skip. Both directories and single files are supported.
    }
    else if (LangDataKeys.MODULE_CONTEXT.getData(dataContext) == null &&
             PlatformDataKeys.PROJECT_CONTEXT.getData(dataContext) == null) {
      PsiElement element = LangDataKeys.PSI_ELEMENT.getData(dataContext);
      if (element == null) {
        presentation.setEnabled(false);
        return;
      }
      if (!(element instanceof PsiDirectory)) {
        PsiFile file = element.getContainingFile();
        if (file == null || LanguageFormatting.INSTANCE.forContext(file) == null) {
          presentation.setEnabled(false);
          return;
        }
      }
    }
    presentation.setEnabled(true);
  }

  public static boolean areFiles(final VirtualFile[] files) {
    if (files == null) return false;
    if (files.length < 2) return false;
    for (VirtualFile virtualFile : files) {
      if (virtualFile.isDirectory()) return false;
    }
    return true;
  }
}
