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

import com.intellij.CommonBundle;
import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.LanguageImportStatements;
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable;
import com.intellij.openapi.help.HelpManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.arrangement.Rearranger;
import com.intellij.util.ui.OptionsDialog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;

public class LayoutCodeDialog extends DialogWrapper {
  private final           PsiFile      myFile;
  @Nullable private final PsiDirectory myDirectory;
  private final           Boolean      myTextSelected;

  private JRadioButton myRbFile;
  private JRadioButton myRbSelectedText;
  private JRadioButton myRbDirectory;
  private JCheckBox    myCbIncludeSubdirs;
  private JCheckBox    myCbOptimizeImports;
  private JCheckBox    myCbArrangeEntries;
  private JCheckBox    myCbOnlyVcsChangedRegions;
  private JCheckBox    myDoNotAskMeCheckBox;

  private final String myHelpId;

  public LayoutCodeDialog(@NotNull Project project,
                          @NotNull String title,
                          @Nullable PsiFile file,
                          @Nullable PsiDirectory directory,
                          Boolean isTextSelected,
                          final String helpId) {
    super(project, true);
    myFile = file;
    myDirectory = directory;
    myTextSelected = isTextSelected;

    setOKButtonText(CodeInsightBundle.message("reformat.code.accept.button.text"));
    setTitle(title);
    init();
    myHelpId = helpId;
  }

  @Override
  protected void init() {
    super.init();

    if (myTextSelected == Boolean.TRUE) {
      myRbSelectedText.setSelected(true);
    }
    else {
      if (myFile != null) {
        myRbFile.setSelected(true);
      }
      else {
        myRbDirectory.setSelected(true);
      }
    }

    myCbIncludeSubdirs.setSelected(true);
    //Loading previous state
    myCbOptimizeImports.setSelected(PropertiesComponent.getInstance().getBoolean(LayoutCodeConstants.OPTIMIZE_IMPORTS_KEY, false));
    myCbArrangeEntries.setSelected(PropertiesComponent.getInstance().getBoolean(LayoutCodeConstants.REARRANGE_ENTRIES_KEY, false));
    myCbOnlyVcsChangedRegions.setSelected(PropertiesComponent.getInstance().getBoolean(LayoutCodeConstants.PROCESS_CHANGED_TEXT_KEY, false));

    ItemListener listener = new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent e) {
        updateState();
      }
    };
    myRbFile.addItemListener(listener);
    myRbSelectedText.addItemListener(listener);
    myRbDirectory.addItemListener(listener);
    myCbIncludeSubdirs.addItemListener(listener);

    updateState();
  }

  private void updateState() {
    myCbIncludeSubdirs.setEnabled(myRbDirectory.isSelected());
    myCbOptimizeImports.setEnabled(
      !myRbSelectedText.isSelected()
      && !(myFile != null && LanguageImportStatements.INSTANCE.forFile(myFile).isEmpty() && myRbFile.isSelected())
    );
    myCbArrangeEntries.setEnabled(myFile != null
                                  && Rearranger.EXTENSION.forLanguage(myFile.getLanguage()) != null
    );

    myCbOnlyVcsChangedRegions.setEnabled(canTargetVcsRegions());
    myDoNotAskMeCheckBox.setEnabled(!myRbDirectory.isSelected());
    myRbDirectory.setEnabled(!myDoNotAskMeCheckBox.isSelected());
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 0));
    GridBagConstraints gbConstraints = new GridBagConstraints();
    gbConstraints.gridy = 0;
    gbConstraints.gridx = 0;
    gbConstraints.gridwidth = 3;
    gbConstraints.gridheight = 1;
    gbConstraints.weightx = 1;

    gbConstraints.fill = GridBagConstraints.BOTH;
    gbConstraints.insets = new Insets(0, 0, 0, 0);

    myRbFile = new JRadioButton(CodeInsightBundle.message("process.scope.file",
                                                          (myFile != null ? "'" + myFile.getVirtualFile().getPresentableUrl() + "'" : "")));
    panel.add(myRbFile, gbConstraints);

    myRbSelectedText = new JRadioButton(CodeInsightBundle.message("reformat.option.selected.text"));
    if (myTextSelected != null) {
      gbConstraints.gridy++;
      gbConstraints.insets = new Insets(0, 0, 0, 0);
      panel.add(myRbSelectedText, gbConstraints);
    }

    myRbDirectory = new JRadioButton();
    myCbIncludeSubdirs = new JCheckBox(CodeInsightBundle.message("reformat.option.include.subdirectories"));
    if (myDirectory != null) {
      myRbDirectory.setText(CodeInsightBundle.message("reformat.option.all.files.in.directory",
                                                      myDirectory.getVirtualFile().getPresentableUrl()));
      gbConstraints.gridy++;
      gbConstraints.insets = new Insets(0, 0, 0, 0);
      panel.add(myRbDirectory, gbConstraints);

      if (myDirectory.getSubdirectories().length > 0) {
        gbConstraints.gridy++;
        gbConstraints.insets = new Insets(0, 20, 0, 0);
        panel.add(myCbIncludeSubdirs, gbConstraints);
      }
    }

    myCbOptimizeImports = new JCheckBox(CodeInsightBundle.message("reformat.option.optimize.imports"));
    if (myTextSelected != null && LanguageImportStatements.INSTANCE.hasAnyExtensions()) {
      gbConstraints.gridy++;
      gbConstraints.insets = new Insets(0, 0, 0, 0);
      panel.add(myCbOptimizeImports, gbConstraints);
    }

    myCbArrangeEntries = new JCheckBox(CodeInsightBundle.message("reformat.option.rearrange.entries"));
    if (myFile != null && Rearranger.EXTENSION.forLanguage(myFile.getLanguage()) != null) {
      gbConstraints.gridy++;
      gbConstraints.insets = new Insets(0, 0, 0, 0);
      panel.add(myCbArrangeEntries, gbConstraints);
    }

    myCbOnlyVcsChangedRegions = new JCheckBox(CodeInsightBundle.message("reformat.option.vcs.changed.region"));
    gbConstraints.gridy++;
    panel.add(myCbOnlyVcsChangedRegions, gbConstraints);

    ButtonGroup buttonGroup = new ButtonGroup();
    buttonGroup.add(myRbFile);
    buttonGroup.add(myRbSelectedText);
    buttonGroup.add(myRbDirectory);

    myRbFile.setEnabled(myFile != null);
    myRbSelectedText.setEnabled(myTextSelected == Boolean.TRUE);

    return panel;
  }

  @Override
  protected JComponent createSouthPanel() {
    JComponent southPanel = super.createSouthPanel();
    myDoNotAskMeCheckBox = new JCheckBox(CommonBundle.message("dialog.options.do.not.show"));
    myDoNotAskMeCheckBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        updateState();
      }
    });
    return OptionsDialog.addDoNotShowCheckBox(southPanel, myDoNotAskMeCheckBox);
  }

  @NotNull
  @Override
  protected Action[] createActions() {
    return new Action[]{getOKAction(), getCancelAction(), getHelpAction()};
  }

  @Override
  protected void doHelpAction() {
    HelpManager.getInstance().invokeHelp(myHelpId);
  }

  public boolean isProcessSelectedText() {
    return myRbSelectedText.isSelected();
  }

  public boolean isProcessWholeFile() {
    return myRbFile.isSelected();
  }

  public boolean isProcessDirectory() {
    return myRbDirectory.isSelected();
  }

  public boolean isIncludeSubdirectories() {
    return myCbIncludeSubdirs.isSelected();
  }

  public boolean isOptimizeImports() {
    return myCbOptimizeImports.isSelected();
  }

  public boolean isRearrangeEntries() {
    return myCbArrangeEntries.isSelected();
  }
  
  public boolean isProcessOnlyChangedText() {
    return myCbOnlyVcsChangedRegions.isEnabled() && myCbOnlyVcsChangedRegions.isSelected();
  }

  public boolean isDoNotAskMe() {
    if (myDoNotAskMeCheckBox.isEnabled()) {
      return myDoNotAskMeCheckBox.isSelected();
    }
    else {
      return !EditorSettingsExternalizable.getInstance().getOptions().SHOW_REFORMAT_DIALOG;
    }
  }

  @Override
  protected void doOKAction() {
    super.doOKAction();
    //Saving checkboxes state
    PropertiesComponent.getInstance().setValue(LayoutCodeConstants.OPTIMIZE_IMPORTS_KEY, Boolean.toString(myCbOptimizeImports.isSelected()));
    PropertiesComponent.getInstance().setValue(LayoutCodeConstants.REARRANGE_ENTRIES_KEY, Boolean.toString(myCbArrangeEntries.isSelected()));
    PropertiesComponent.getInstance().setValue(LayoutCodeConstants.PROCESS_CHANGED_TEXT_KEY, Boolean.toString(myCbOnlyVcsChangedRegions.isSelected()));
  }

  private boolean canTargetVcsRegions() {
    if (isProcessSelectedText()) {
      return false;
    }

    if (isProcessWholeFile()) {
      return FormatChangedTextUtil.hasChanges(myFile);
    }

    if (isProcessDirectory()) {
      if (myDirectory == null) {
        return false;
      }
      return FormatChangedTextUtil.hasChanges(myDirectory);
    }
    return false;
  }
}
